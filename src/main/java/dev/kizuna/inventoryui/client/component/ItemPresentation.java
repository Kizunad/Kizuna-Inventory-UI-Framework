package dev.kizuna.inventoryui.client.component;

import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.inventory.ItemAction;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.text.Text;

import java.util.List;

/** 模块按自己的模型提供物品外观和操作；默认只显示库存快照的公共字段。 */
public interface ItemPresentation {
    ItemPresentation DEFAULT = new ItemPresentation() {};

    default void draw(
            net.minecraft.client.gui.DrawContext context,
            InventorySnapshot.Item item,
            int count,
            boolean rotated,
            int x,
            int y,
            int width,
            int height,
            java.util.function.ToIntFunction<String> colors) {
        // 默认绘制资源图标；模块可覆盖以使用原版 ItemStack 或自己的模型，不修改库存模型。
        ItemDrawing.draw(context, item, count, rotated, x, y, width, height, colors);
    }

    default List<Text> tooltip(InventorySnapshot.Item item) {
        return List.of(Text.literal(item.name()), Text.literal("数量：" + item.count()));
    }

    default String borderToken(InventorySnapshot.Item item) {
        return ThemeTokens.INVENTORY_BORDER;
    }

    default List<ItemAction> actions(InventorySnapshot.Item item) {
        return List.of();
    }

    default void inspect(InventorySnapshot.Item item) {
        // 详情属于扩展模块；未注册时不自动打开一个假业务窗口。
    }

    default void quickMove(InventorySnapshot.Item item) {
        // 快速转移的目标和装备规则由模块决定，框架不选择业务容器。
    }
}
