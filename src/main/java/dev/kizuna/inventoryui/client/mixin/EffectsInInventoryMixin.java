package dev.kizuna.inventoryui.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 背包右侧那一列原版状态长条不再绘制；状态图标统一由本框架排在不压界面的位置。 */
@Mixin(EffectsInInventory.class)
abstract class EffectsInInventoryMixin {
    @Inject(method = "canSeeEffects", at = @At("HEAD"), cancellable = true)
    private void inventoryUi$noSideList(CallbackInfoReturnable<Boolean> callback) {
        // 原版据此决定游戏画面那一层还画不画图标；这里固定回答「背包没有接管」。
        callback.setReturnValue(false);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void inventoryUi$skipSideList(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo callback) {
        // 整段跳过，原版列表的背景、文字和悬停都不出现。
        callback.cancel();
    }
}
