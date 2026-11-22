package dev.kizuna.inventoryui.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kizuna.inventoryui.workspace.IconStyle;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** 客户端命令 /kiui icons item|glass_line|ink：不开界面也能换新版界面的图标风格，三个名字都能补全。 */
final class IconStyleCommand {
    private IconStyleCommand() { }

    static void register(ClientRuntime runtime) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> icons = ClientCommands.literal("icons");
            for (var style : IconStyle.values()) {
                icons.then(ClientCommands.literal(style.id()).executes(command -> {
                    runtime.iconStyle(style);
                    var name = Component.translatable(style.nameKey());
                    var message = Component.translatable("kizuna_inventory_ui.icons.switched", name);
                    command.getSource().sendFeedback(message);
                    return 1;
                }));
            }
            dispatcher.register(ClientCommands.literal("kiui").then(icons));
        });
    }
}
