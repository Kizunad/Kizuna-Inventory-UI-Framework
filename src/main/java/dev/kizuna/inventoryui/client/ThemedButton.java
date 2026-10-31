package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 菜单按钮使用当前主题，不再混入原版石纹按钮。 */
final class ThemedButton extends Button {
    private static final int TEXT_INSET = 6;
    private final ClientRuntime runtime;

    ThemedButton(
            ClientRuntime runtime,
            String text,
            int x,
            int y,
            int width,
            int height,
            Runnable action) {
        super(
                x,
                y,
                width,
                height,
                Component.literal(text),
                ignored -> action.run(),
                DEFAULT_NARRATION);
        this.runtime = runtime;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 键盘焦点与鼠标悬停保持一致提示，主题切换无需重建按钮。
        boolean highlighted = isHovered() || isFocused();
        context.fill(
                getX(),
                getY(),
                getX() + getWidth(),
                getY() + getHeight(),
                runtime.color(highlighted ? CONTROL_HOVER : CONTROL));
        context.outline(
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                runtime.color(highlighted ? ACCENT : BORDER));
        var text = Minecraft.getInstance().font;
        String label =
                text.plainSubstrByWidth(
                        getMessage().getString(), Math.max(0, getWidth() - 2 * TEXT_INSET));
        context.text(
                text,
                label,
                getX() + (getWidth() - text.width(label)) / 2,
                getY() + (getHeight() - text.lineHeight) / 2,
                runtime.color(active ? TEXT : MUTED_TEXT),
                false);
    }
}
