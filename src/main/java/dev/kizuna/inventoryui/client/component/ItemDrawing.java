package dev.kizuna.inventoryui.client.component;

import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/** 网格、槽位和拖动浮层共用的图标绘制；尺寸单位为逻辑像素。 */
public final class ItemDrawing {
    private static final int ICON_PADDING = 3;
    private static final int LABEL_INSET = 4;
    private static final int COUNT_BOTTOM_INSET = 12;
    private static final int VANILLA_ITEM_SIZE = 16;
    private static final float QUARTER_TURN_DEGREES = 90f;

    private ItemDrawing() {}

    public static void draw(
            GuiGraphicsExtractor context,
            InventorySnapshot.Item item,
            int count,
            int x,
            int y,
            int width,
            int height,
            ToIntFunction<String> colors) {
        draw(context, item, count, false, x, y, width, height, colors);
    }

    public static void draw(
            GuiGraphicsExtractor context,
            InventorySnapshot.Item item,
            int count,
            boolean rotated,
            int x,
            int y,
            int width,
            int height,
            ToIntFunction<String> colors) {
        // 资源缺失时显示名称，避免把调试用缺失纹理作为最终物品表现。
        var client = Minecraft.getInstance();
        var icon = Identifier.tryParse(item.iconId());
        int size = Math.max(1, Math.min(width, height) - 2 * ICON_PADDING);
        int color = colors.applyAsInt(ThemeTokens.INVENTORY_ITEM_TEXT);
        if (icon != null && client.getResourceManager().getResource(icon).isPresent()) {
            // 图标随库存方向旋转，数量文字保留水平；整张贴图走半透明 GUI 纹理管线。
            var matrices = context.pose();
            matrices.pushMatrix();
            try {
                matrices.translate(x + width / 2f, y + height / 2f);
                if (rotated) {
                    matrices.rotate(QUARTER_TURN_DEGREES * Mth.DEG_TO_RAD);
                }
                context.blit(
                        RenderPipelines.GUI_TEXTURED,
                        icon,
                        -size / 2,
                        -size / 2,
                        0,
                        0,
                        size,
                        size,
                        1,
                        1,
                        1,
                        1);
            } finally {
                matrices.popMatrix();
            }
        } else {
            context.text(
                    client.font,
                    client.font.plainSubstrByWidth(
                            item.name(), Math.max(1, width - 2 * LABEL_INSET)),
                    x + LABEL_INSET,
                    y + LABEL_INSET,
                    color);
        }
        if (count > 1) {
            context.text(
                    client.font,
                    Integer.toString(count),
                    x + width - LABEL_INSET - client.font.width(Integer.toString(count)),
                    y + height - COUNT_BOTTOM_INSET,
                    color);
        }
    }

    public static void drawStack(
            GuiGraphicsExtractor context,
            net.minecraft.world.item.ItemStack stack,
            int x,
            int y,
            int width,
            int height) {
        // 复用原版的模型、附魔光泽与叠加信息；供应方负责把领域物品映射为展示栈。
        if (stack.isEmpty()) {
            return;
        }
        float size = Math.max(1, Math.min(width, height) - 2 * ICON_PADDING);
        var matrices = context.pose();
        matrices.pushMatrix();
        try {
            matrices.translate(x + (width - size) / 2, y + (height - size) / 2);
            matrices.scale(size / VANILLA_ITEM_SIZE, size / VANILLA_ITEM_SIZE);
            context.item(stack, 0, 0);
            context.itemDecorations(Minecraft.getInstance().font, stack, 0, 0);
        } finally {
            matrices.popMatrix();
        }
    }
}
