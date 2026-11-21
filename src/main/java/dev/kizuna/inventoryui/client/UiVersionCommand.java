package dev.kizuna.inventoryui.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kizuna.inventoryui.workspace.UiVersion;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** 客户端命令 /kiui version classic|modern：不开界面也能切换界面版本，两个版本名都能补全。 */
final class UiVersionCommand {
    private UiVersionCommand() { }

    static void register(ClientRuntime runtime) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> version = ClientCommands.literal("version");
            for (var target : UiVersion.values()) {
                version.then(ClientCommands.literal(target.id()).executes(command -> {
                    runtime.uiVersion(target);
                    var message = Component.translatable("kizuna_inventory_ui.version." + target.id());
                    command.getSource().sendFeedback(message);
                    return 1;
                }));
            }
            dispatcher.register(ClientCommands.literal("kiui").then(version));
        });
    }
}
