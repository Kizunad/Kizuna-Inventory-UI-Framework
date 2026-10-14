package dev.kizuna.inventoryui.server;

import com.google.protobuf.Message;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Envelope;

/** 框架完成路由校验后的单次请求；请求身份与模块业务幂等键相互独立。 */
public final class ServerRequest<C> {
    private final ServerConnection<C> connection;
    private final Envelope envelope;
    private final String payloadType;

    ServerRequest(ServerConnection<C> connection, Envelope envelope, String payloadType) {
        this.connection = connection;
        this.envelope = envelope;
        this.payloadType = payloadType;
    }

    public ServerConnection<C> connection() {
        return connection;
    }

    public String message() {
        return envelope.getMessage();
    }

    public String requestId() {
        return envelope.getRequest();
    }

    public <T extends Message> T payload(T defaultInstance) {
        // 解码类型必须等于协商的描述符，不能把另一种可解析的消息误当成本请求。
        if (!payloadType.equals(defaultInstance.getDescriptorForType().getFullName())) {
            throw new IllegalArgumentException("请求解码类型与契约不一致");
        }
        @SuppressWarnings("unchecked")
        T value = (T) ServerProtocol.parse(defaultInstance.getParserForType(), envelope.getData());
        return value;
    }
}
