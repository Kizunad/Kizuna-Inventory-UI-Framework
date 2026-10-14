package dev.kizuna.inventoryui.server;

import com.google.protobuf.Message;

import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 单模块的连接视图；发送权限限于本模块已协商的 S2C 消息。 */
public final class ServerConnection<C> {
    private final String id;
    private final Supplier<C> context;
    private final BiConsumer<String, Message> outbound;
    private final Predicate<String> supported;

    ServerConnection(
            String id,
            Supplier<C> context,
            BiConsumer<String, Message> outbound,
            Predicate<String> supported) {
        this.id = id;
        this.context = context;
        this.outbound = outbound;
        this.supported = supported;
    }

    /** 本条连接的 UUID；不是玩家身份，重连后必定变化。 */
    public String id() {
        return id;
    }

    /** 每次向宿主读取当前上下文，避免死亡重生后继续持有旧玩家实体。 */
    public C context() {
        return context.get();
    }

    public boolean supports(String message) {
        return supported.test(message);
    }

    /** 仅在宿主线程和 READY 连接上发送；断线及越模块发送会明确拒绝。 */
    public void send(String message, Message value) {
        outbound.accept(message, value);
    }
}
