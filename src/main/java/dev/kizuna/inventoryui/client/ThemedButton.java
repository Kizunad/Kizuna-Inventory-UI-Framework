package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 菜单按钮使用当前主题，不再混入原版石纹按钮。 */
final class ThemedButton extends ButtonWidget {
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
                Text.literal(text),
                ignored -> action.run(),
                DEFAULT_NARRATION_SUPPLIER);
        this.runtime = runtime;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        // 键盘焦点与鼠标悬停保持一致提示，主题切换无需重建按钮。
        boolean highlighted = isHovered() || isFocused();
        context.fill(
                getX(),
                getY(),
                getX() + getWidth(),
                getY() + getHeight(),
                runtime.color(highlighted ? CONTROL_HOVER : CONTROL));
        context.drawBorder(
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                runtime.color(highlighted ? ACCENT : BORDER));
        var text = MinecraftClient.getInstance().textRenderer;
        String label =
                text.trimToWidth(
                        getMessage().getString(), Math.max(0, getWidth() - 2 * TEXT_INSET));
        context.drawText(
                text,
                label,
                getX() + (getWidth() - text.getWidth(label)) / 2,
                getY() + (getHeight() - text.fontHeight) / 2,
                runtime.color(active ? TEXT : MUTED_TEXT),
                false);
    }
}
