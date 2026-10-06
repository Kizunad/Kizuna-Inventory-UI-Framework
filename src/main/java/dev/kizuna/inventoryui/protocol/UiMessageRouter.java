package dev.kizuna.inventoryui.protocol;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 逻辑消息的类型、版本和模块归属边界；编解码由宿主网络适配器完成。 */
public final class UiMessageRouter {
    private static final Logger LOGGER = Logger.getLogger(UiMessageRouter.class.getName());
    private final Map<String, Contract<?>> contracts;
    private final FrameworkCatalog catalog;
    private final Executor clientExecutor;
    private final Consumer<Outbound> outbound;
    private final Consumer<Failure> diagnostics;
    private long generation;
    private boolean connected;

    public UiMessageRouter(
            FrameworkCatalog catalog,
            List<Contract<?>> contracts,
            Executor clientExecutor,
            Consumer<Outbound> outbound) {
        this(
                catalog,
                contracts,
                clientExecutor,
                outbound,
                failure ->
                        LOGGER.log(
                                Level.SEVERE,
                                failure.code()
                                        + " "
                                        + failure.moduleId()
                                        + " "
                                        + failure.messageId(),
                                failure.cause()));
    }

    public UiMessageRouter(
            FrameworkCatalog catalog,
            List<Contract<?>> contracts,
            Executor clientExecutor,
            Consumer<Outbound> outbound,
            Consumer<Failure> diagnostics) {
        // 初始化时确认每条消息归属已安装模块；全部验证成功后才固定消息表。
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.clientExecutor =
                Objects.requireNonNull(clientExecutor, "clientExecutor must not be null");
        this.outbound = Objects.requireNonNull(outbound, "outbound must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        Map<String, Contract<?>> checked = new LinkedHashMap<>();
        for (Contract<?> contract :
                Objects.requireNonNull(contracts, "contracts must not be null")) {
            Objects.requireNonNull(contract, "contract must not be null");
            if (!catalog.modules().containsKey(contract.moduleId())) {
                throw new IllegalArgumentException(
                        "contract module missing: " + contract.moduleId());
            }
            if (!namespace(contract.id()).equals(namespace(contract.moduleId()))) {
                throw new IllegalArgumentException(
                        "contract outside module namespace: " + contract.id());
            }
            if (checked.putIfAbsent(contract.id(), contract) != null) {
                throw new IllegalArgumentException("duplicate message contract: " + contract.id());
            }
        }
        this.contracts = Map.copyOf(checked);
    }

    public synchronized long connect() {
        // 每次连接获得新代数，之前排队的消息即使晚到也不能写入新连接。
        generation = Math.incrementExact(generation);
        connected = true;
        return generation;
    }

    public synchronized void disconnect() {
        // 断线当场撤销旧代数，无需等到下一次 connect 才让旧消息失效。
        generation = Math.incrementExact(generation);
        connected = false;
    }

    public synchronized long generation() {
        return generation;
    }

    public Result receive(
            long sourceGeneration, String moduleId, String messageId, int version, Object payload) {
        // 接收入口先检查模块、版本、方向和 Java 类型；字段级 schema 由宿主解码器检查。
        Contract<?> contract;
        synchronized (this) {
            Result validation =
                    validate(
                            sourceGeneration,
                            moduleId,
                            messageId,
                            version,
                            Direction.SERVER_TO_CLIENT,
                            payload);
            if (!validation.ok()) {
                return validation;
            }
            contract = contracts.get(messageId);
        }
        try {
            clientExecutor.execute(
                    () -> {
                        synchronized (this) {
                            // 排队期间可能已经断线，执行前必须再次检查代数。
                            if (!connected || generation != sourceGeneration) {
                                return;
                            }
                            try {
                                deliver(contract, payload);
                            } catch (RuntimeException failure) {
                                diagnostics.accept(
                                        new Failure(
                                                Code.HANDLER_FAILURE,
                                                moduleId,
                                                messageId,
                                                failure));
                            }
                        }
                    });
        } catch (RuntimeException failure) {
            diagnostics.accept(new Failure(Code.EXECUTOR_FAILURE, moduleId, messageId, failure));
            return Result.error(Code.EXECUTOR_FAILURE);
        }
        return Result.accepted();
    }

    public Result send(
            long sourceGeneration,
            String moduleId,
            String messageId,
            int version,
            Object payload,
            String requestId) {
        // 与断线互斥地校验并交给传输层；本地 ACCEPTED 不能当作服务端已完成业务操作。
        synchronized (this) {
            Result validation =
                    validate(
                            sourceGeneration,
                            moduleId,
                            messageId,
                            version,
                            Direction.CLIENT_TO_SERVER,
                            payload);
            if (!validation.ok()) {
                return validation;
            }
            if (requestId == null || requestId.isBlank()) {
                return Result.error(Code.INVALID_REQUEST_ID);
            }
            try {
                outbound.accept(new Outbound(moduleId, messageId, version, payload, requestId));
            } catch (RuntimeException failure) {
                diagnostics.accept(
                        new Failure(Code.TRANSPORT_FAILURE, moduleId, messageId, failure));
                return Result.error(Code.TRANSPORT_FAILURE);
            }
        }
        return Result.accepted();
    }

    private Result validate(
            long sourceGeneration,
            String moduleId,
            String messageId,
            int version,
            Direction direction,
            Object payload) {
        // 先隔离旧连接，再区分模块缺失、消息未声明和契约不兼容，供接入方定位错误。
        if (!connected || sourceGeneration != generation) {
            return Result.error(Code.STALE_CONNECTION);
        }
        if (!catalog.modules().containsKey(moduleId)) {
            return Result.error(Code.MODULE_MISSING);
        }
        Contract<?> contract = contracts.get(messageId);
        if (contract == null || !contract.moduleId().equals(moduleId)) {
            return Result.error(Code.UNKNOWN_MESSAGE);
        }
        if (contract.version() != version || contract.direction() != direction) {
            return Result.error(Code.PROTOCOL_MISMATCH);
        }
        if (!contract.payloadType().isInstance(payload)) {
            return Result.error(Code.INVALID_PAYLOAD);
        }
        return Result.accepted();
    }

    private static <T> void deliver(Contract<T> contract, Object payload) {
        // 入口已按同一 Class 校验过载荷，此处恢复类型参数后交给唯一所属模块。
        contract.handler().accept(contract.payloadType().cast(payload));
    }

    private static String namespace(String id) {
        // 这里只提取消息归属；完整资源 ID 的字符规范不属于逻辑消息路由器。
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) {
            throw new IllegalArgumentException("invalid message id: " + id);
        }
        return id.substring(0, separator);
    }

    public enum Direction {
        SERVER_TO_CLIENT,
        CLIENT_TO_SERVER
    }

    public enum Code {
        ACCEPTED,
        MODULE_MISSING,
        UNKNOWN_MESSAGE,
        PROTOCOL_MISMATCH,
        INVALID_PAYLOAD,
        STALE_CONNECTION,
        INVALID_REQUEST_ID,
        HANDLER_FAILURE,
        EXECUTOR_FAILURE,
        TRANSPORT_FAILURE
    }

    /** 本地校验或提交结果，不表示服务端业务请求的接受、拒绝或完成。 */
    public record Result(Code code) {
        public Result {
            Objects.requireNonNull(code, "code must not be null");
        }

        public boolean ok() {
            return code == Code.ACCEPTED;
        }

        public static Result accepted() {
            return new Result(Code.ACCEPTED);
        }

        public static Result error(Code code) {
            return new Result(code);
        }
    }

    public record Outbound(
            String moduleId, String messageId, int version, Object payload, String requestId) {}

    public record Failure(Code code, String moduleId, String messageId, RuntimeException cause) {}

    public record Contract<T>(
            String moduleId,
            String id,
            int version,
            Direction direction,
            Class<T> payloadType,
            Consumer<T> handler) {
        public Contract {
            // 入站消息必须有本地消费者；出站只交给宿主传输，不能误调用本地处理器。
            namespace(moduleId);
            namespace(id);
            if (version <= 0) {
                throw new IllegalArgumentException("version must be positive");
            }
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(payloadType, "payloadType must not be null");
            if (direction == Direction.SERVER_TO_CLIENT) {
                Objects.requireNonNull(handler, "server message handler must not be null");
            } else if (handler != null) {
                throw new IllegalArgumentException(
                        "outbound contract must not have a local handler");
            }
        }
    }
}
