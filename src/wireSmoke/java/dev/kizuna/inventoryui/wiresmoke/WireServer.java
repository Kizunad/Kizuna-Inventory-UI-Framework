package dev.kizuna.inventoryui.wiresmoke;

import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.wiresmoke.pb.SmokeProtocol;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** 仅测试源集加载的本地 Fabric 服务端，验证真正的 Play CustomPayload 往返。 */
public final class WireServer implements DedicatedServerModInitializer {
    private static final Identifier CHANNEL = new Identifier("kizuna_inventory_ui", "message");

    @Override
    public void onInitializeServer() {
        // PID 只用于验收脚本异常清理本次子进程，正常路径在客户端断开后退出。
        try {
            Files.writeString(Path.of("fixture.pid"), Long.toString(ProcessHandle.current().pid()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> server.stop(false));
        ServerPlayNetworking.registerGlobalReceiver(
                CHANNEL,
                (server, player, handler, buffer, sender) -> {
                    // 网络线程只读取有界字节；协议解析与发送回包串行到真实服务器线程。
                    if (buffer.readableBytes() > UiWire.MAX_PACKET_BYTES) {
                        throw new IllegalArgumentException("oversized fixture packet");
                    }
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    server.execute(() -> receive(player, UiWire.decode(bytes)));
                });
    }

    private void receive(ServerPlayerEntity player, UiWire.Packet packet) {
        // 夹具只提供验收所需的协议对端，生产框架不包含这个模拟业务。
        if (packet.kind() == UiWire.Kind.HELLO) {
            send(player, UiWire.Packet.negotiation(UiWire.Kind.ACCEPT, packet.capabilities()));
            var data = SmokeProtocol.Value.newBuilder().setValue(7).build().toByteString();
            send(
                    player,
                    new UiWire.Packet(
                            UiWire.VERSION,
                            UiWire.Kind.EVENT,
                            "wire:fixture",
                            "wire:state",
                            1,
                            "",
                            "",
                            data,
                            Map.of()));
            send(
                    player,
                    new UiWire.Packet(
                            UiWire.VERSION,
                            UiWire.Kind.EVENT,
                            "missing:module",
                            "missing:state",
                            1,
                            "missing-probe",
                            "",
                            data,
                            Map.of()));
        } else if (packet.kind() == UiWire.Kind.REQUEST) {
            boolean accepted =
                    UiWire.Codec.protobuf(SmokeProtocol.Value.getDefaultInstance())
                                    .decode(packet.data())
                                    .getValue()
                            > 0;
            send(
                    player,
                    new UiWire.Packet(
                            UiWire.VERSION,
                            UiWire.Kind.RESULT,
                            packet.module(),
                            packet.message(),
                            packet.version(),
                            packet.request(),
                            accepted ? "ACCEPTED" : "REJECTED",
                            null,
                            Map.of()));
        } else if (packet.kind() == UiWire.Kind.ERROR && packet.status().equals("MODULE_MISSING")) {
            // 写证据前核验原请求身份，避免把不相关错误当成缺模块链路成功。
            if (!packet.request().equals("missing-probe")) {
                throw new IllegalStateException("missing-module response lost correlation");
            }
            try {
                Files.writeString(
                        Path.of("wire-server-ok.txt"),
                        "Protobuf wire v2: HELLO/ACCEPT, EVENT, REQUEST/RESULT, MODULE_MISSING\n");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    private static void send(ServerPlayerEntity player, UiWire.Packet packet) {
        // 与客户端使用同一份 proto 生成的二进制信封，不额外添加长度前缀。
        var buffer = PacketByteBufs.create();
        buffer.writeBytes(UiWire.encode(packet));
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }
}
