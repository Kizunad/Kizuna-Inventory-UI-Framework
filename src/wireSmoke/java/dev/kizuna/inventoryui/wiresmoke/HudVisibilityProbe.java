package dev.kizuna.inventoryui.wiresmoke;

import dev.kizuna.inventoryui.client.InventoryUiClient;
import dev.kizuna.inventoryui.client.VanillaHudHidden;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;

/**
 * 用真实游戏世界验证整个原版 HUD 被跳过，防止只隐藏准星而遗漏物品栏。
 *
 * <p>GUI 先收集渲染状态、帧末统一绘制，帧中途无法截取帧缓冲；因此比较 HUD 提交前后渲染状态里的元素总数，
 * 覆盖所有原版 HUD 元素，与 GUI 缩放无关。
 */
final class HudVisibilityProbe extends Screen implements VanillaHudHidden {
    private boolean hiddenVerified;
    private boolean verified;

    HudVisibilityProbe() {
        super(Component.literal("HUD visibility probe"));
    }

    boolean hiddenVerified() {
        return hiddenVerified;
    }

    boolean verified() {
        return verified;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 先填满同一画布，再直接调用真实 Hud；被标记屏幕下不得新增任何渲染元素。
        if (hiddenVerified) {
            return;
        }
        if (minecraft.gui.hud.isHidden()) {
            throw new IllegalStateException("HUD probe requires F1 to be off");
        }
        context.fill(0, 0, width, height, InventoryUiClient.runtime().color(ThemeTokens.PANEL));
        int before = elementCount(context);
        minecraft.gui.hud.extractRenderState(context, minecraft.getDeltaTracker());
        if (elementCount(context) != before) {
            throw new IllegalStateException("vanilla HUD rendered through inventory screen");
        }
        hiddenVerified = true;
    }

    void observeHud(GuiGraphicsExtractor context) {
        // 由测试模块挂在 HUD 最后的元素调用：探针关闭后原版 HUD 必须先提交自己的元素，F1 设置保持不变。
        if (!hiddenVerified || verified || minecraft.gui.screen() != null) {
            return;
        }
        if (elementCount(context) == 0) {
            throw new IllegalStateException("vanilla HUD did not return after leaving screen");
        }
        if (minecraft.gui.hud.isHidden()) {
            throw new IllegalStateException("inventory screen changed the F1 setting");
        }
        verified = true;
    }

    private static int elementCount(GuiGraphicsExtractor context) {
        // 色块、贴图、文字、物品和画中画分别存放，全部计入才能发现任一类 HUD 内容。
        var state = context.guiRenderState;
        int[] count = {0};
        state.forEachElement(element -> count[0]++, GuiRenderState.TraverseRange.ALL);
        state.forEachText(text -> count[0]++);
        state.forEachItem(item -> count[0]++);
        state.forEachPictureInPicture(picture -> count[0]++);
        return count[0];
    }
}
