package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import dev.kizuna.inventoryui.theme.BuiltinThemes;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 主题行沿用当前主题的按钮外观，右侧色块取自目标主题，选中项显示强调边框。 */
final class ThemeButton extends Button {
    private static final int TEXT_INSET = 6;
    /** 色块边长与间距，单位为 GUI 逻辑像素。 */
    private static final int SWATCH_SIZE = 10;
    private static final int SWATCH_GAP = 2;
    /** 面板、标题、文字、强调与标题短线足以区分整套配色。 */
    private static final List<String> SWATCH_TOKENS =
            List.of(PANEL, HEADER, TEXT, ACCENT, HEADER_ACCENT);
    private final ClientRuntime runtime;
    private final String id;

    ThemeButton(
            ClientRuntime runtime,
            String id,
            String label,
            Rect bounds,
            Runnable action) {
        super(
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                Component.literal(label),
                ignored -> action.run(),
                DEFAULT_NARRATION);
        // 布局传入同一矩形，绘制与按钮命中保持一致。
        this.runtime = runtime;
        this.id = id;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 按钮底色和文字随当前主题，色块固定显示目标主题，切换后无需重建也能对照。
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
                runtime.color(highlighted || id.equals(runtime.theme()) ? ACCENT : BORDER));
        int swatchesWidth = SWATCH_TOKENS.size() * (SWATCH_SIZE + SWATCH_GAP);
        drawSwatches(context, swatchesWidth);
        drawLabel(context, swatchesWidth);
    }

    private void drawSwatches(GuiGraphicsExtractor context, int swatchesWidth) {
        // 色块固定表示目标主题，浅色背景上仍以当前边线分隔。
        var theme = runtime.catalog().themes().get(id);
        int swatchY = getY() + (getHeight() - SWATCH_SIZE) / 2;
        int swatchX = getX() + getWidth() - TEXT_INSET - swatchesWidth;
        for (String token : SWATCH_TOKENS) {
            // 色块描当前主题边线，浅色主题的色块放在浅色按钮上也有轮廓。
            context.fill(
                    swatchX,
                    swatchY,
                    swatchX + SWATCH_SIZE,
                    swatchY + SWATCH_SIZE,
                    BuiltinThemes.color(theme, token));
            context.outline(swatchX, swatchY, SWATCH_SIZE, SWATCH_SIZE, runtime.color(BORDER));
            swatchX += SWATCH_SIZE + SWATCH_GAP;
        }
    }

    private void drawLabel(GuiGraphicsExtractor context, int swatchesWidth) {
        // 标题给右侧预览保留宽度，文字随当前主题染色。
        var text = Minecraft.getInstance().font;
        int labelWidth = Math.max(0, getWidth() - TEXT_INSET * 2 - SWATCH_GAP - swatchesWidth);
        context.text(
                text,
                text.plainSubstrByWidth(getMessage().getString(), labelWidth),
                getX() + TEXT_INSET,
                getY() + (getHeight() - text.lineHeight) / 2,
                runtime.color(TEXT),
                false /* shadow */);
    }
}
