package dev.kizuna.inventoryui.server;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Capability;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Direction;

import java.io.IOException;

/** 服务端 wire v2 的公共传输约束；业务容量和事务限制由模块另外校验。 */
public final class ServerProtocol {
    public static final int VERSION = 2;
    /** 完整 C2S 信封上限，单位字节，低于原版 Play C2S 包限制。 */
    public static final int MAX_REQUEST_BYTES = 16_384;
    /** 完整 S2C 信封上限，单位字节，与客户端 UiWire 相同。 */
    public static final int MAX_PACKET_BYTES = 262_144;
    private static final int MAX_MESSAGE_DEPTH = 32;

    private ServerProtocol() {}

    static <T extends MessageLite> T parse(Parser<T> parser, ByteString bytes) {
        // 信封与内层业务消息分别限制大小、递归和尾部输入，不能仅依赖流 sizeLimit。
        if (bytes.size() > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException("请求超过大小上限");
        }
        var input = bytes.newCodedInput();
        input.setRecursionLimit(MAX_MESSAGE_DEPTH);
        try {
            T value = parser.parseFrom(input);
            input.checkLastTagWas(0);
            if (!input.isAtEnd()) {
                throw new IllegalArgumentException("请求包含未消费的输入");
            }
            return value;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("无效 Protobuf 请求", invalid);
        }
    }

    static void validate(String message, Capability capability) {
        // 模块身份、命名空间和类型声明在注册与握手时使用相同校验。
        requireId(message);
        requireId(capability.getModule());
        if (!message.substring(0, message.indexOf(':'))
                        .equals(capability.getModule().split(":", 2)[0])
                || capability.getVersion() <= 0
                || (capability.getDirection() != Direction.CLIENT_TO_SERVER
                        && capability.getDirection() != Direction.SERVER_TO_CLIENT)
                || capability.getPayloadType().isBlank()) {
            throw new IllegalArgumentException("无效消息契约：" + message);
        }
    }

    static void requireId(String id) {
        // 展示名称不能代替协议身份；命名空间用于隔离第三方模块。
        if (id == null || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("无效协议 ID：" + id);
        }
    }

    static boolean matches(Capability local, Capability peer) {
        // required 是两端各自的需求，不属于载荷类型；其他字段必须精确匹配。
        return peer != null
                && local.getModule().equals(peer.getModule())
                && local.getVersion() == peer.getVersion()
                && local.getDirection() == peer.getDirection()
                && local.getPayloadType().equals(peer.getPayloadType());
    }
}
