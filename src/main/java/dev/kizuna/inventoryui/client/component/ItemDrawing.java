package dev.kizuna.inventoryui.client.component;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.theme.ThemeTokens;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 网格、槽位和拖动浮层共用的图标绘制；尺寸单位为逻辑像素。 */
public final class ItemDrawing {
    private static final int ICON_PADDING = 3;
    private static final int LABEL_INSET = 4;
    private static final int COUNT_BOTTOM_INSET = 12;
    private static final int VANILLA_ITEM_SIZE = 16;
    private static final float QUARTER_TURN_DEGREES = 90f;

    private ItemDrawing() {}

    public static void draw(
            GuiGraphics context,
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
            GuiGraphics context,
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
        var icon = ResourceLocation.tryParse(item.iconId());
        int size = Math.max(1, Math.min(width, height) - 2 * ICON_PADDING);
        int color = colors.applyAsInt(ThemeTokens.INVENTORY_ITEM_TEXT);
        if (icon != null && client.getResourceManager().getResource(icon).isPresent()) {
            // 图标随库存方向旋转，数量文字保留水平，混合状态在退出前还原。
            context.flush();
            boolean blending = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_BLEND);
            RenderSystem.enableBlend();
            var matrices = context.pose();
            matrices.pushPose();
            try {
                matrices.translate(x + width / 2f, y + height / 2f, 0);
                if (rotated) {
                    matrices.mulPose(
                            com.mojang.math.Axis.ZP.rotationDegrees(
                                    QUARTER_TURN_DEGREES));
                }
                context.blit(icon, -size / 2, -size / 2, size, size, 0, 0, 1, 1, 1, 1);
                context.flush();
            } finally {
                matrices.popPose();
                if (!blending) {
                    RenderSystem.disableBlend();
                }
            }
        } else {
            context.drawString(
                    client.font,
                    client.font.plainSubstrByWidth(
                            item.name(), Math.max(1, width - 2 * LABEL_INSET)),
                    x + LABEL_INSET,
                    y + LABEL_INSET,
                    color);
        }
        if (count > 1) {
            context.drawString(
                    client.font,
                    Integer.toString(count),
                    x + width - LABEL_INSET - client.font.width(Integer.toString(count)),
                    y + height - COUNT_BOTTOM_INSET,
                    color);
        }
    }

    public static void drawStack(
            GuiGraphics context,
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
        matrices.pushPose();
        try {
            matrices.translate(x + (width - size) / 2, y + (height - size) / 2, 0);
            matrices.scale(size / VANILLA_ITEM_SIZE, size / VANILLA_ITEM_SIZE, 1);
            context.renderItem(stack, 0, 0);
            context.renderItemDecorations(Minecraft.getInstance().font, stack, 0, 0);
        } finally {
            matrices.popPose();
        }
    }
}
