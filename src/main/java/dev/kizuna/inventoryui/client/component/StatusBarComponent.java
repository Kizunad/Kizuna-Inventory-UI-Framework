package dev.kizuna.inventoryui.client.component;

import dev.kizuna.inventoryui.theme.ThemeTokens;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** 通用数值条；单位、最大值和含义由模块提供，不预置任何角色属性。 */
public final class StatusBarComponent extends BaseComponent {
    private final Supplier<Value> source;
    private final ToIntFunction<String> colors;

    public StatusBarComponent(
            int width, int height, Supplier<Value> source, ToIntFunction<String> colors) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bar dimensions must be positive");
        }
        this.source = Objects.requireNonNull(source);
        this.colors = Objects.requireNonNull(colors);
        sizing(Sizing.fixed(width), Sizing.fixed(height));
    }

    @Override
    public void draw(
            OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 越界值只在显示时裁切；供应方的权威数据不被 UI 改写。
        var value = source.get();
        double fraction = Math.max(0, Math.min(1, value.current() / value.maximum()));
        context.fill(x, y, x + width, y + height, colors.applyAsInt(ThemeTokens.PROGRESS_TRACK));
        context.fill(
                x,
                y,
                x + (int) Math.round(width * fraction),
                y + height,
                colors.applyAsInt(value.fillToken()));
        var text = MinecraftClient.getInstance().textRenderer;
        var label = text.trimToWidth(value.label(), width);
        context.drawTextWithShadow(
                text,
                label,
                x + (width - text.getWidth(label)) / 2,
                y + (height - text.fontHeight) / 2,
                colors.applyAsInt(ThemeTokens.TEXT));
        tooltip(value.tooltip());
    }

    public record Value(
            String label, double current, double maximum, String fillToken, List<Text> tooltip) {
        public Value {
            // 非有限数不能进入像素计算；无容量的业务状态应由模块显示成文本。
            Objects.requireNonNull(label);
            Objects.requireNonNull(fillToken);
            tooltip = List.copyOf(tooltip);
            if (!Double.isFinite(current) || !Double.isFinite(maximum) || maximum <= 0) {
                throw new IllegalArgumentException(
                        "status values must be finite; maximum must be positive");
            }
        }
    }
}
