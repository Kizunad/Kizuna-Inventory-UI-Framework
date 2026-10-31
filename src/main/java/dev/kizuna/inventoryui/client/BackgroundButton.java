package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 背景卡片沿用 56 px 行高与底部标题遮罩，选中项显示强调边框。 */
final class BackgroundButton extends Button {
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
                Component.literal(label),
                ignored -> action.run(),
                DEFAULT_NARRATION);
        this.runtime = runtime;
        this.id = id;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
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
        var text = Minecraft.getInstance().font;
        context.text(
                text,
                text.plainSubstrByWidth(getMessage().getString(), getWidth() - 2 * LABEL_X),
                getX() + LABEL_X,
                getY() + LABEL_Y,
                runtime.color(HUD_TEXT),
                false);
        context.outline(
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
