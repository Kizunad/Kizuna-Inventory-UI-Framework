package dev.kizuna.inventoryui.client.mixin;

import dev.kizuna.inventoryui.client.VanillaHudHidden;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版 HUD 开始绘制之前按当前屏幕决定是否跳过，避免透明界面下面残留物品栏。 */
@Mixin(Hud.class)
abstract class InGameHudMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void hideForInventoryScreen(
            GuiGraphicsExtractor context, DeltaTracker deltaTracker, CallbackInfo callback) {
        // 每帧读取屏幕标记，无需接管 hudHidden；切屏、断线和退出都会自然恢复。
        if (Minecraft.getInstance().gui.screen() instanceof VanillaHudHidden) {
            callback.cancel();
        }
    }
}
