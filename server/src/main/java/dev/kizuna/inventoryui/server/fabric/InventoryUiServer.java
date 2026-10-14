package dev.kizuna.inventoryui.server.fabric;

import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.ServerProtocol;
import dev.kizuna.inventoryui.server.ServerRouter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/** Fabric 逻辑服务器唯一通道所有者；单机和专用服务器共用模块路由。 */
public final class InventoryUiServer implements ModInitializer {
    public static final Identifier CHANNEL = new Identifier("kizuna_inventory_ui", "message");
    private static final Logger LOGGER = LoggerFactory.getLogger("kizuna_inventory_ui_server");
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_PACKETS_PER_WINDOW = 20;
    private final Map<ServerPlayNetworkHandler, Connection> connections = new HashMap<>();
    private ServerModuleRegistry<ServerPlayerEntity> registry;

    @Override
    public void onInitialize() {
        // 所有模组的物品初始化完成后再注册业务；每次服务器启动使用全新服务与目录。
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            var next = new ServerModuleRegistry<ServerPlayerEntity>();
            FabricLoader.getInstance().getEntrypoints("kizuna_inventory_ui_server", FabricServerModule.class)
                    .forEach(module -> module.register(server, next));
            next.freeze();
            registry = next;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // 停服先关闭连接，再丢弃服务目录，模块不能通过旧连接继续推送。
            connections.values().forEach(connection -> connection.router.close());
            connections.clear();
            registry = null;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Fabric 的断线回调可能先在网络线程触发；清理也必须切回路由所属线程。
            server.execute(() -> {
                var connection = connections.remove(handler);
                if (connection != null) {
                    connection.router.close();
                }
            });
        });
        boolean registered = ServerPlayNetworking.registerGlobalReceiver(CHANNEL,
                (server, player, handler, buffer, sender) -> {
                    // 网络线程仅复制有界数据；身份、限流、路由及所有业务都回到主线程。
                    if (buffer.readableBytes() > ServerProtocol.MAX_REQUEST_BYTES) {
                        return;
                    }
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    server.execute(() -> {
                        if (registry == null || !handler.isConnectionOpen()
                                || handler.player.networkHandler != handler) {
                            return;
                        }
                        var connection = connections.computeIfAbsent(handler, key -> new Connection(
                                new ServerRouter<>(registry, () -> handler.player, envelope -> {
                                    var packet = PacketByteBufs.create();
                                    packet.writeBytes(envelope.toByteArray());
                                    ServerPlayNetworking.send(handler.player, CHANNEL, packet);
                                }, (message, failure) -> LOGGER.error(message, failure))));
                        if (connection.allow()) {
                            connection.router.receive(bytes);
                        }
                    });
                });
        if (!registered) {
            // 旧业务模组仍独占通道时启动失败，避免用户遇到静默丢包或错误处理器。
            throw new IllegalStateException("统一通道已被其他模组注册；请迁移到 kizuna_inventory_ui_server 入口");
        }
    }

    private static final class Connection {
        private final ServerRouter<ServerPlayerEntity> router;
        private long windowStart = System.nanoTime();
        private int packets;

        private Connection(ServerRouter<ServerPlayerEntity> router) {
            this.router = router;
        }

        private boolean allow() {
            // 同一玩家所有模块共享每秒包预算；超限不执行，客户端通过超时与重同步收敛。
            long now = System.nanoTime();
            if (now - windowStart >= RATE_WINDOW_NANOS) {
                windowStart = now;
                packets = 0;
            }
            return ++packets <= MAX_PACKETS_PER_WINDOW;
        }
    }
}
