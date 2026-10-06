package dev.kizuna.inventoryui.client.component;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.inventoryui.theme.ThemeTokens;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** 动态状态图标条；标签、层数、时长文案和操作全部由模块投影。 */
public final class EffectStripComponent extends BaseComponent {
    private static final int CELL_SIZE = 24;
    private static final int GAP = 2;
    private static final int TEXT_INSET = 2;
    private final Supplier<List<Effect>> source;
    private final ToIntFunction<String> colors;
    private List<Effect> visible = List.of();
    private int offset;

    public EffectStripComponent(
            int width, Supplier<List<Effect>> source, ToIntFunction<String> colors) {
        if (width < CELL_SIZE) {
            throw new IllegalArgumentException("effect strip must fit one cell");
        }
        this.source = Objects.requireNonNull(source);
        this.colors = Objects.requireNonNull(colors);
        sizing(Sizing.fixed(width), Sizing.fixed(CELL_SIZE));
    }

    @Override
    public void update(float delta, int mouseX, int mouseY) {
        // 空列表自动收起，父布局无需知道子模块当前是否有状态效果。
        visible = List.copyOf(source.get());
        int desiredHeight = visible.isEmpty() ? 0 : CELL_SIZE;
        if (verticalSizing().get().value != desiredHeight) {
            verticalSizing(Sizing.fixed(desiredHeight));
        }
        super.update(delta, mouseX, mouseY);
    }

    @Override
    public void draw(
            OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 可见区域内按容量分页，效果数量动态变化也不会画到所属窗口外。
        int capacity = Math.max(1, width / (CELL_SIZE + GAP));
        offset = Math.max(0, Math.min(offset, Math.max(0, visible.size() - capacity)));
        tooltip(List.<Text>of());
        var client = MinecraftClient.getInstance();
        for (int i = offset; i < Math.min(visible.size(), offset + capacity); i++) {
            var effect = visible.get(i);
            int left = x + (i - offset) * (CELL_SIZE + GAP);
            context.fill(
                    left,
                    y,
                    left + CELL_SIZE,
                    y + CELL_SIZE,
                    colors.applyAsInt(ThemeTokens.HEADER));
            var icon = Identifier.tryParse(effect.iconId());
            if (icon != null && client.getResourceManager().getResource(icon).isPresent()) {
                RenderSystem.enableBlend();
                context.drawTexture(icon, left, y, CELL_SIZE, CELL_SIZE, 0, 0, 1, 1, 1, 1);
            }
            context.drawTextWithShadow(
                    client.textRenderer,
                    client.textRenderer.trimToWidth(effect.badge(), CELL_SIZE),
                    left + TEXT_INSET,
                    y + CELL_SIZE - client.textRenderer.fontHeight,
                    colors.applyAsInt(ThemeTokens.TEXT));
            if (mouseX >= left
                    && mouseX < left + CELL_SIZE
                    && mouseY >= y
                    && mouseY < y + CELL_SIZE) {
                tooltip(effect.tooltip());
            }
        }
    }

    @Override
    public boolean onMouseScroll(double mouseX, double mouseY, double amount) {
        offset = Math.max(0, offset - (int) amount);
        return true;
    }

    public record Effect(String id, String iconId, String badge, List<Text> tooltip) {
        public Effect {
            // 身份与文案分离；框架不解释效果种类，也不根据剩余时间自行移除权威效果。
            Objects.requireNonNull(id);
            Objects.requireNonNull(iconId);
            Objects.requireNonNull(badge);
            tooltip = List.copyOf(tooltip);
        }
    }
}
