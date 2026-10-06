package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** 背景卡片沿用 56 px 行高与底部标题遮罩，选中项显示强调边框。 */
final class BackgroundButton extends ButtonWidget {
    static final int HEIGHT = 56;
    private static final int LABEL_TOP = 38;
    private static final int LABEL_X = 6;
    private static final int LABEL_Y = 43;
    private final ClientRuntime runtime;
    private final String id;

    BackgroundButton(
            ClientRuntime runtime,
            String id,
            String label,
            int x,
            int y,
            int width,
            Runnable action) {
        super(
                x,
                y,
                width,
                HEIGHT,
                Text.literal(label),
                ignored -> action.run(),
                DEFAULT_NARRATION_SUPPLIER);
        this.runtime = runtime;
        this.id = id;
    }

    @Override
    public void renderButton(DrawContext context, int mouseX, int mouseY, float delta) {
        // 缺失缩略图保留主题底色，选择结果仅在资源成功载入后更新。
        context.fill(
                getX(), getY(), getX() + getWidth(), getY() + getHeight(), runtime.color(CONTROL));
        if (id.startsWith("local:")) {
            runtime.localBackgrounds()
                    .thumbnail(context, id, getX(), getY(), getWidth(), getHeight());
        } else {
            WorkspaceBackground.thumbnail(
                    context,
                    runtime.catalog().backgrounds().get(id),
                    getX(),
                    getY(),
                    getWidth(),
                    getHeight());
        }
        context.fill(
                getX(),
                getY() + LABEL_TOP,
                getX() + getWidth(),
                getY() + getHeight(),
                runtime.color(HUD_BACKGROUND));
        var text = MinecraftClient.getInstance().textRenderer;
        context.drawText(
                text,
                text.trimToWidth(getMessage().getString(), getWidth() - 2 * LABEL_X),
                getX() + LABEL_X,
                getY() + LABEL_Y,
                runtime.color(HUD_TEXT),
                false);
        context.drawBorder(
                getX(),
                getY(),
                getWidth(),
                getHeight(),
                runtime.color(
                        id.equals(runtime.background()) || isHovered() || isFocused()
                                ? ACCENT
                                : BORDER));
    }
}
