package dev.kizuna.inventoryui.server;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 每条连接一个路由器；统一协商后只向目标模块投递，不拥有业务库存或事务。 */
public final class ServerRouter<C> implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final String connectionId = UUID.randomUUID().toString();
    private final Map<String, ServerModuleRegistry.Module<C>> modules;
    private final Map<String, Capability> capabilities;
    private final Map<String, ServerModuleSession<C>> sessions = new LinkedHashMap<>();
    private final Map<String, ServerConnection<C>> connections = new LinkedHashMap<>();
    private final Supplier<C> context;
    private final Consumer<Envelope> outbound;
    private final BiConsumer<String, RuntimeException> diagnostics;
    private Map<String, Capability> negotiated = Map.of();
    private boolean ready;
    private boolean closed;
    private boolean attempted;

    public ServerRouter(
            ServerModuleRegistry<C> registry,
            Supplier<C> context,
            Consumer<Envelope> outbound,
            BiConsumer<String, RuntimeException> diagnostics) {
        // 注册目录在服务器启动时冻结；所有连接共用契约，各自创建模块业务会话。
        modules = registry.modules();
        capabilities = registry.capabilities();
        this.context = Objects.requireNonNull(context);
        this.outbound = Objects.requireNonNull(outbound);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    public void receive(byte[] bytes) {
        // 旧连接不再分发；损坏的外层信封没有可信请求身份，直接丢弃。
        checkThread();
        if (closed || bytes.length > ServerProtocol.MAX_REQUEST_BYTES) {
            return;
        }
        Envelope packet;
        try {
            packet = ServerProtocol.parse(Envelope.parser(), ByteString.copyFrom(bytes));
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (packet.getKind() == Kind.ERROR) {
            return;
        }
        if (packet.getProtocol() != ServerProtocol.VERSION) {
            error(packet, "PROTOCOL_MISMATCH");
            return;
        }
        if (packet.getKind() == Kind.HELLO) {
            negotiate(packet);
            return;
        }
        if (!ready) {
            error(packet, "NOT_READY");
            return;
        }
        if (packet.getKind() != Kind.REQUEST) {
            error(packet, "INVALID_DIRECTION");
            return;
        }
        dispatch(packet);
    }

    private void negotiate(Envelope packet) {
        // 一条连接只协商一次；失败不能留下部分就绪的模块，也不能用第二次 HELLO 重置业务。
        if (attempted) {
            error(packet, "UNEXPECTED_HANDSHAKE");
            return;
        }
        attempted = true;
        if (!packet.getModule().isEmpty()
                || !packet.getMessage().isEmpty()
                || !packet.getRequest().isEmpty()
                || !packet.getStatus().isEmpty()
                || !packet.getData().isEmpty()
                || packet.getVersion() != 0) {
            error(packet, "INVALID_PAYLOAD");
            return;
        }
        var offered = packet.getCapabilitiesMap();
        try {
            offered.forEach(ServerProtocol::validate);
        } catch (IllegalArgumentException invalid) {
            error(packet, "INVALID_PAYLOAD");
            return;
        }
        for (var entry : capabilities.entrySet()) {
            if (entry.getValue().getRequired()
                    && !ServerProtocol.matches(entry.getValue(), offered.get(entry.getKey()))) {
                negotiationError(entry.getKey(), entry.getValue(), "REQUIRED_CAPABILITY_MISSING");
                return;
            }
        }
        var accepted = new LinkedHashMap<String, Capability>();
        for (var entry : offered.entrySet()) {
            var local = capabilities.get(entry.getKey());
            if (local != null && ServerProtocol.matches(local, entry.getValue())) {
                accepted.put(entry.getKey(), local);
            } else if (entry.getValue().getRequired()) {
                String code;
                if (!modules.containsKey(entry.getValue().getModule())) {
                    code = "MODULE_MISSING";
                } else if (local == null) {
                    code = "UNKNOWN_MESSAGE";
                } else {
                    code = "PROTOCOL_MISMATCH";
                }
                negotiationError(entry.getKey(), entry.getValue(), code);
                return;
            }
        }
        negotiated = Map.copyOf(accepted);
        try {
            for (String module : accepted.values().stream().map(Capability::getModule).distinct().toList()) {
                var connection = new ServerConnection<C>(
                        connectionId,
                        () -> {
                            requireReady();
                            return context.get();
                        },
                        (message, value) -> event(module, message, value),
                        message -> supports(module, message));
                connections.put(module, connection);
                sessions.put(module, Objects.requireNonNull(modules.get(module).factory().apply(connection)));
            }
        } catch (RuntimeException failure) {
            diagnostics.accept("初始化模块会话失败", failure);
            closeSessions();
            negotiated = Map.of();
            error(packet, "HANDLER_FAILURE");
            return;
        }
        ready = true;
        send(base(Kind.ACCEPT).putAllCapabilities(negotiated).build());
    }

    private void dispatch(Envelope packet) {
        // 契约归属、方向、版本和请求身份都由公共入口检查，模块不能抢占其他模块的消息。
        if (!modules.containsKey(packet.getModule())) {
            error(packet, "MODULE_MISSING");
            return;
        }
        var contract = capabilities.get(packet.getMessage());
        if (contract == null || !contract.getModule().equals(packet.getModule())) {
            error(packet, "UNKNOWN_MESSAGE");
            return;
        }
        if (!negotiated.containsKey(packet.getMessage())
                || packet.getVersion() != contract.getVersion()) {
            error(packet, "PROTOCOL_MISMATCH");
            return;
        }
        if (contract.getDirection() != Direction.CLIENT_TO_SERVER) {
            error(packet, "INVALID_DIRECTION");
            return;
        }
        if (!isUuid(packet.getRequest()) || !packet.getStatus().isEmpty()
                || packet.getCapabilitiesCount() != 0) {
            error(packet, "INVALID_PAYLOAD");
            return;
        }
        try {
            boolean accepted = sessions.get(packet.getModule()).handle(new ServerRequest<>(
                    connections.get(packet.getModule()), packet, contract.getPayloadType()));
            send(reply(packet, Kind.RESULT).setStatus(accepted ? "ACCEPTED" : "REJECTED").build());
        } catch (IllegalArgumentException invalid) {
            error(packet, "INVALID_PAYLOAD");
        } catch (RuntimeException failure) {
            diagnostics.accept("模块处理失败：" + packet.getModule(), failure);
            error(packet, "HANDLER_FAILURE");
        }
    }

    private boolean supports(String module, String message) {
        // 可选能力可能未协商，发送前允许模块主动查询；关闭后的视图不能继续工作。
        checkThread();
        var contract = negotiated.get(message);
        return ready && !closed && contract != null && contract.getModule().equals(module);
    }

    private void event(String module, String message, Message value) {
        // 子模块只能发送自身的已协商事件，描述符和整个信封大小在出口再次校验。
        requireReady();
        var contract = negotiated.get(message);
        if (contract == null || !contract.getModule().equals(module)
                || contract.getDirection() != Direction.SERVER_TO_CLIENT
                || !contract.getPayloadType().equals(value.getDescriptorForType().getFullName())) {
            throw new IllegalStateException("未协商、越模块或类型不匹配的事件：" + message);
        }
        send(base(Kind.EVENT).setModule(module).setMessage(message)
                .setVersion(contract.getVersion()).setData(value.toByteString()).build());
    }

    private void negotiationError(String message, Capability contract, String code) {
        // 协商错误带上失败模块和消息，让用户能定位缺少的子 JAR；没有请求身份。
        error(base(Kind.HELLO).setModule(contract.getModule()).setMessage(message)
                .setVersion(contract.getVersion()).build(), code);
    }

    private void error(Envelope packet, String code) {
        send(reply(packet, Kind.ERROR).setStatus(code).build());
    }

    private void send(Envelope packet) {
        // 上限覆盖信封开销，不能让单个业务模块发出客户端无法接收的大包。
        if (packet.getSerializedSize() > ServerProtocol.MAX_PACKET_BYTES) {
            throw new IllegalStateException("服务端信封超过大小上限");
        }
        outbound.accept(packet);
    }

    private static Envelope.Builder reply(Envelope packet, Kind kind) {
        return base(kind).setModule(packet.getModule()).setMessage(packet.getMessage())
                .setVersion(packet.getVersion()).setRequest(packet.getRequest());
    }

    private static Envelope.Builder base(Kind kind) {
        return Envelope.newBuilder().setProtocol(ServerProtocol.VERSION).setKind(kind);
    }

    private static boolean isUuid(String value) {
        // UUID.fromString 会接受部分缩写形式；协议仅接受完整的规范字符串。
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void requireReady() {
        // 推送也遵守宿主线程约束，后台任务必须切回服务器线程并检查连接生命周期。
        checkThread();
        if (!ready || closed) {
            throw new IllegalStateException("连接未就绪或已关闭");
        }
    }

    private void checkThread() {
        // 核心路由不依赖 Minecraft；宿主负责把网络线程的数据转交给创建路由的线程。
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("路由只能在宿主线程使用");
        }
    }

    @Override
    public void close() {
        // 先使所有模块连接视图失效，再释放资源；某个模块关闭失败不影响其他模块。
        checkThread();
        if (closed) {
            return;
        }
        closed = true;
        ready = false;
        negotiated = Map.of();
        closeSessions();
    }

    private void closeSessions() {
        // 初始化部分失败与正常断线共用清理路径，每个成功创建的会话只关闭一次。
        var previous = Map.copyOf(sessions);
        sessions.clear();
        connections.clear();
        previous.forEach((module, session) -> {
            try {
                session.close();
            } catch (RuntimeException failure) {
                diagnostics.accept("关闭模块会话失败：" + module, failure);
            }
        });
    }
}
