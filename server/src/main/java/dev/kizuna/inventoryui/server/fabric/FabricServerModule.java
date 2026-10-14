package dev.kizuna.inventoryui.server.fabric;

import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** kizuna_inventory_ui_server 入口：在 SERVER_STARTING 时注册本次服务器的模块。 */
@FunctionalInterface
public interface FabricServerModule {
    void register(MinecraftServer server, ServerModuleRegistry<ServerPlayerEntity> registry);
}
