package dev.kizuna.inventoryui.server.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 统一通道的 Play CustomPayload：载荷就是一条 Protobuf Envelope 的原始字节，不附加长度前缀。 */
public record UiPayload(byte[] bytes) implements CustomPacketPayload {
    public static final Type<UiPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "message"));
    // 编码直接写字节，解码读完包内剩余字节；单包大小先受原版自定义载荷上限约束，业务上限由收端检查。
    public static final StreamCodec<FriendlyByteBuf, UiPayload> CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeBytes(payload.bytes()),
                    buffer -> {
                        byte[] bytes = new byte[buffer.readableBytes()];
                        buffer.readBytes(bytes);
                        return new UiPayload(bytes);
                    });

    static void register() {
        // 两个方向用同一编解码；公共入口在任何连接建立前执行，客户端与服务端各注册一次。
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Override
    public Type<UiPayload> type() {
        return TYPE;
    }
}
