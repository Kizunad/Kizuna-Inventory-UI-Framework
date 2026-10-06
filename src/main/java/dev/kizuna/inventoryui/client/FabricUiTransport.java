package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.protocol.UiWire;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.util.Identifier;

/** 单一可选 CustomPayload 通道；消息体为 UiWire UTF-8 JSON，不附加字符串长度前缀。 */
final class FabricUiTransport {
    static final Identifier CHANNEL = new Identifier("kizuna_inventory_ui", "message");

    private FabricUiTransport() {}

    static void install(ClientRuntime runtime) {
        runtime.transport(
                packet -> {
                    // 仅向声明支持此通道的服务器发包；普通服务器仍能使用纯本地模块。
                    if (!ClientPlayNetworking.canSend(CHANNEL)) {
                        throw new IllegalStateException("server does not advertise " + CHANNEL);
                    }
                    var buffer = PacketByteBufs.create();
                    buffer.writeBytes(UiWire.encode(packet));
                    ClientPlayNetworking.send(CHANNEL, buffer);
                });
        ClientPlayNetworking.registerGlobalReceiver(
                CHANNEL,
                (client, handler, buffer, sender) -> {
                    // 网络线程只复制有上限的数据；解析和子模块回调在客户端线程执行。
                    if (buffer.readableBytes() > UiWire.MAX_PACKET_BYTES) {
                        System.getLogger(FabricUiTransport.class.getName())
                                .log(System.Logger.Level.WARNING, "UI packet exceeds size limit");
                        return;
                    }
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    client.execute(
                            () -> {
                                if (client.getNetworkHandler() != handler) {
                                    return;
                                }
                                try {
                                    runtime.receive(
                                            runtime.protocol().generation(), UiWire.decode(bytes));
                                } catch (RuntimeException failure) {
                                    System.getLogger(FabricUiTransport.class.getName())
                                            .log(
                                                    System.Logger.Level.WARNING,
                                                    "Invalid UI packet",
                                                    failure);
                                }
                            });
                });
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> {
                    if (ClientPlayNetworking.canSend(CHANNEL)) {
                        runtime.protocol().connect(System.currentTimeMillis());
                    }
                });
    }
}
