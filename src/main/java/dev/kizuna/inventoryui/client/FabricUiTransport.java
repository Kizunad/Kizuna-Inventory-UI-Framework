package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.server.fabric.UiPayload;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** 单一可选 CustomPayload 通道；消息体为 Protobuf Envelope，不附加长度前缀。 */
final class FabricUiTransport {
    private FabricUiTransport() {}

    static void install(ClientRuntime runtime) {
        runtime.transport(
                packet -> {
                    // 仅向声明支持此通道的服务器发包；普通服务器仍能使用纯本地模块。
                    if (!ClientPlayNetworking.canSend(UiPayload.TYPE)) {
                        throw new IllegalStateException("server does not advertise " + UiPayload.TYPE.id());
                    }
                    ClientPlayNetworking.send(new UiPayload(UiWire.encode(packet)));
                });
        ClientPlayNetworking.registerGlobalReceiver(
                UiPayload.TYPE,
                (payload, context) -> {
                    // Fabric 在客户端线程回调；超限包只记日志丢弃，解析失败不影响连接。
                    if (payload.bytes().length > UiWire.MAX_PACKET_BYTES) {
                        System.getLogger(FabricUiTransport.class.getName())
                                .log(System.Logger.Level.WARNING, "UI packet exceeds size limit");
                        return;
                    }
                    try {
                        runtime.receive(runtime.protocol().generation(), UiWire.decode(payload.bytes()));
                    } catch (RuntimeException failure) {
                        System.getLogger(FabricUiTransport.class.getName())
                                .log(System.Logger.Level.WARNING, "Invalid UI packet", failure);
                    }
                });
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    if (ClientPlayNetworking.canSend(UiPayload.TYPE)) {
                        runtime.protocol().connect(System.currentTimeMillis());
                    }
                });
    }
}
