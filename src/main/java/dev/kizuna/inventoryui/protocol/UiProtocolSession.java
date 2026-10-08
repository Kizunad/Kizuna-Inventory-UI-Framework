package dev.kizuna.inventoryui.protocol;

import com.google.protobuf.ByteString;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 客户端线程上的协商、统一消息入口和请求生命周期；传输层可以是 Fabric 或宿主适配器。 */
public final class UiProtocolSession {
    public static final long REQUEST_TIMEOUT_MS = 10_000L;
    public static final long HANDSHAKE_TIMEOUT_MS = 10_000L;
    private static final int MAX_PENDING_REQUESTS = 128;
    private final FrameworkCatalog catalog;
    private final Map<String, UiWire.Contract<?>> contracts = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Consumer<UiWire.Packet> outbound;
    private final Consumer<String> diagnostics;
    private Map<String, UiWire.Capability> negotiated = Map.of();
    private long generation;
    private long handshakeDeadline;
    private State state = State.DISCONNECTED;

    public UiProtocolSession(
            FrameworkCatalog catalog,
            List<UiWire.Contract<?>> contracts,
            Consumer<UiWire.Packet> outbound,
            Consumer<String> diagnostics) {
        // 复用逻辑路由器的模块归属校验，传输接口自身没有业务消息名单。
        this.catalog = Objects.requireNonNull(catalog);
        this.outbound = Objects.requireNonNull(outbound);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        List<UiMessageRouter.Contract<?>> routes = new ArrayList<>();
        contracts.forEach(value -> routes.add(value.route()));
        new UiMessageRouter(catalog, routes, Runnable::run, packet -> {});
        contracts.forEach(value -> this.contracts.put(value.route().id(), value));
    }

    public long connect(long nowMs) {
        // 新连接撤销所有旧请求；服务器必须明确 ACCEPT，不能把无响应当成兼容。
        disconnect();
        state = State.NEGOTIATING;
        handshakeDeadline = Math.addExact(nowMs, HANDSHAKE_TIMEOUT_MS);
        try {
            outbound.accept(UiWire.Packet.negotiation(UiWire.Kind.HELLO, capabilities()));
        } catch (RuntimeException failure) {
            state = State.FAILED;
            diagnostics.accept("TRANSPORT_FAILURE: " + failure.getMessage());
        }
        return generation;
    }

    public Map<String, UiWire.Capability> capabilities() {
        // 从每个已注册 codec 读取生成类型全名，不能由协商层另写一份载荷清单。
        var result = new LinkedHashMap<String, UiWire.Capability>();
        contracts.forEach(
                (id, value) ->
                        result.put(
                                id,
                                new UiWire.Capability(
                                        value.route().moduleId(),
                                        value.route().version(),
                                        value.route().direction(),
                                        value.required(),
                                        value.codec().payloadType())));
        return Map.copyOf(result);
    }

    public void receive(long sourceGeneration, UiWire.Packet packet) {
        // 排队之后再次检查连接代数；旧连接包不会完成新连接请求或调用子模块。
        if (sourceGeneration != generation || state == State.DISCONNECTED) {
            return;
        }
        if (packet.kind() == UiWire.Kind.ACCEPT) {
            accept(packet);
            return;
        }
        if (packet.kind() == UiWire.Kind.ERROR) {
            if (state == State.NEGOTIATING && packet.request().isEmpty()) {
                // 服务端明确拒绝协商时立即失败，无需再等本地握手计时器。
                state = State.FAILED;
            }
            diagnostics.accept(packet.status() + ": " + packet.module() + "/" + packet.message());
            if (!packet.request().isEmpty()) {
                complete(packet, false);
            }
            return;
        }
        if (state != State.READY) {
            reject(packet, "NOT_READY");
            return;
        }
        if (packet.kind() == UiWire.Kind.RESULT) {
            if (!packet.status().equals("ACCEPTED") && !packet.status().equals("REJECTED")) {
                reject(packet, "INVALID_RESULT");
            } else {
                complete(packet, packet.status().equals("ACCEPTED"));
            }
            return;
        }
        if (packet.kind() != UiWire.Kind.EVENT) {
            reject(packet, "INVALID_DIRECTION");
            return;
        }
        if (!catalog.modules().containsKey(packet.module())) {
            reject(packet, "MODULE_MISSING");
            return;
        }
        var contract = contracts.get(packet.message());
        if (contract == null || !contract.route().moduleId().equals(packet.module())) {
            reject(packet, "UNKNOWN_MESSAGE");
            return;
        }
        if (!negotiated.containsKey(packet.message())
                || packet.version() != contract.route().version()
                || contract.route().direction() != UiMessageRouter.Direction.SERVER_TO_CLIENT) {
            reject(packet, "PROTOCOL_MISMATCH");
            return;
        }
        deliver(contract, packet);
    }

    private void accept(UiWire.Packet packet) {
        // 可省略可选能力；必需能力必须存在，方向、版本、模块和生成类型必须一致。
        if (state != State.NEGOTIATING) {
            reject(packet, "UNEXPECTED_HANDSHAKE");
            return;
        }
        var local = capabilities();
        String error = null;
        for (var entry : local.entrySet()) {
            if (entry.getValue().required() && !packet.capabilities().containsKey(entry.getKey())) {
                error = "REQUIRED_CAPABILITY_MISSING";
            }
        }
        for (var entry : packet.capabilities().entrySet()) {
            var ours = local.get(entry.getKey());
            var theirs = entry.getValue();
            if (ours == null
                    || !ours.module().equals(theirs.module())
                    || ours.version() != theirs.version()
                    || ours.direction() != theirs.direction()
                    || !ours.payloadType().equals(theirs.payloadType())) {
                error = "PROTOCOL_MISMATCH";
            }
        }
        if (error != null) {
            state = State.FAILED;
            reject(packet, error);
            return;
        }
        negotiated = packet.capabilities();
        state = State.READY;
    }

    private <T> void deliver(UiWire.Contract<T> contract, UiWire.Packet packet) {
        T value;
        try {
            // 编解码错误与模块处理异常分开报告，便于服务端定位契约和业务故障。
            value = contract.route().payloadType().cast(contract.codec().decode(packet.data()));
            Objects.requireNonNull(value);
        } catch (RuntimeException failure) {
            reject(packet, "INVALID_PAYLOAD");
            return;
        }
        try {
            contract.route().handler().accept(value);
        } catch (RuntimeException failure) {
            diagnostics.accept("HANDLER_FAILURE: " + failure.getMessage());
            reject(packet, "HANDLER_FAILURE");
        }
    }

    public <T> CompletableFuture<Outcome> request(String message, T payload, long nowMs) {
        // 请求身份由框架分配，成功结果只结算请求，库存仍等待独立权威状态更新。
        var contract = contracts.get(message);
        if (state != State.READY || !negotiated.containsKey(message)) {
            return CompletableFuture.completedFuture(new Outcome(false, "NOT_READY", ""));
        }
        if (contract == null
                || contract.route().direction() != UiMessageRouter.Direction.CLIENT_TO_SERVER
                || !contract.route().payloadType().isInstance(payload)) {
            return CompletableFuture.completedFuture(new Outcome(false, "INVALID_REQUEST", ""));
        }
        if (pending.size() >= MAX_PENDING_REQUESTS) {
            return CompletableFuture.completedFuture(new Outcome(false, "TOO_MANY_REQUESTS", ""));
        }
        String id = UUID.randomUUID().toString();
        var future = new CompletableFuture<Outcome>();
        pending.put(
                id,
                new Pending(
                        contract.route().moduleId(),
                        message,
                        contract.route().version(),
                        Math.addExact(nowMs, REQUEST_TIMEOUT_MS),
                        future));
        try {
            send(contract, payload, id);
        } catch (RuntimeException failure) {
            pending.remove(id);
            future.complete(new Outcome(false, "TRANSPORT_FAILURE", id));
            diagnostics.accept("TRANSPORT_FAILURE: " + failure.getMessage());
        }
        return future;
    }

    private <T> void send(UiWire.Contract<T> contract, Object value, String id) {
        outbound.accept(
                new UiWire.Packet(
                        UiWire.VERSION,
                        UiWire.Kind.REQUEST,
                        contract.route().moduleId(),
                        contract.route().id(),
                        contract.route().version(),
                        id,
                        "",
                        contract.codec().encode(contract.route().payloadType().cast(value)),
                        Map.of()));
    }

    private void complete(UiWire.Packet packet, boolean accepted) {
        // 除 request ID 外还对拍模块、消息和版本，其他模块不能伪结算此请求。
        var request = pending.get(packet.request());
        if (request == null) {
            diagnostics.accept("UNKNOWN_REQUEST: " + packet.request());
            return;
        }
        if (!request.module().equals(packet.module())
                || !request.message().equals(packet.message())
                || request.version() != packet.version()) {
            diagnostics.accept("RESULT_IDENTITY_MISMATCH: " + packet.request());
            return;
        }
        pending.remove(packet.request());
        request.future().complete(new Outcome(accepted, packet.status(), packet.request()));
    }

    private void reject(UiWire.Packet packet, String code) {
        // 对端收到结构化错误；ERROR 不再回复 ERROR，避免两个端点无限回声。
        diagnostics.accept(code + ": " + packet.module() + "/" + packet.message());
        try {
            outbound.accept(
                    new UiWire.Packet(
                            UiWire.VERSION,
                            UiWire.Kind.ERROR,
                            packet.module(),
                            packet.message(),
                            packet.version(),
                            packet.request(),
                            code,
                            ByteString.EMPTY,
                            Map.of()));
        } catch (RuntimeException failure) {
            diagnostics.accept("TRANSPORT_FAILURE: " + failure.getMessage());
        }
    }

    public void tick(long nowMs) {
        // 超时只结束等待，不自动重试有副作用的操作；模块可按结果重新同步状态。
        if (state == State.NEGOTIATING && nowMs >= handshakeDeadline) {
            state = State.FAILED;
            diagnostics.accept("HANDSHAKE_TIMEOUT");
        }
        var expired =
                pending.entrySet().stream()
                        .filter(entry -> nowMs >= entry.getValue().deadline())
                        .toList();
        for (var entry : expired) {
            pending.remove(entry.getKey());
            entry.getValue().future().complete(new Outcome(false, "TIMEOUT", entry.getKey()));
        }
    }

    public void disconnect() {
        // 先清表再完成 future，回调重入也不能持有旧请求或把新请求塞回旧连接。
        generation = Math.incrementExact(generation);
        state = State.DISCONNECTED;
        negotiated = Map.of();
        var previous = new LinkedHashMap<>(pending);
        pending.clear();
        previous.forEach(
                (id, value) -> value.future().complete(new Outcome(false, "DISCONNECTED", id)));
    }

    public State state() {
        return state;
    }

    public long generation() {
        return generation;
    }

    public enum State {
        DISCONNECTED,
        NEGOTIATING,
        READY,
        FAILED
    }

    public record Outcome(boolean accepted, String code, String requestId) {}

    private record Pending(
            String module,
            String message,
            int version,
            long deadline,
            CompletableFuture<Outcome> future) {}
}
