package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.inventory.ItemAction;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;

/** 最上层公共操作菜单；动作列表和可用性由模块提供，库存更新使旧菜单失效。 */
public final class ItemContextMenu {
    private static final int WIDTH = 180;
    private static final int ROW_HEIGHT = 20;
    private static final int PADDING = 5;
    private List<ItemAction> actions = List.of();
    private BooleanSupplier valid = () -> false;
    private int x;
    private int y;
    private int selected;
    private int offset;
    private int visibleRows = 1;

    public void open(int x, int y, List<ItemAction> actions, BooleanSupplier valid) {
        // 保存坐标后在绘制时按实际视口裁切，菜单可以从任意窗口或槽位唤起。
        this.x = x;
        this.y = y;
        this.actions = List.copyOf(actions);
        this.valid = Objects.requireNonNull(valid);
        selected = 0;
        offset = 0;
    }

    public boolean isOpen() {
        // 有关联的权威状态变更后立即撤销，禁止执行针对旧物品生成的回调。
        if (!actions.isEmpty() && !valid.getAsBoolean()) {
            close();
        }
        return !actions.isEmpty();
    }

    public void render(
            DrawContext context,
            int width,
            int height,
            int mouseX,
            int mouseY,
            ToIntFunction<String> colors) {
        // 菜单按可用高度滚动，避免大量扩展操作落到屏幕外而无法选择。
        if (!isOpen()) {
            return;
        }
        int w = Math.min(WIDTH, width);
        visibleRows = Math.max(1, Math.min(actions.size(), height / ROW_HEIGHT));
        x = Math.max(0, Math.min(x, width - w));
        y = Math.max(0, Math.min(y, height - visibleRows * ROW_HEIGHT));
        var text = MinecraftClient.getInstance().textRenderer;
        context.fill(
                x, y, x + w, y + visibleRows * ROW_HEIGHT, colors.applyAsInt(ThemeTokens.PANEL));
        for (int row = 0; row < visibleRows; row++) {
            int index = row + offset;
            int top = y + row * ROW_HEIGHT;
            var action = actions.get(index);
            boolean hover =
                    mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + ROW_HEIGHT;
            if (hover || selected == index) {
                context.fill(
                        x, top, x + w, top + ROW_HEIGHT, colors.applyAsInt(ThemeTokens.HEADER));
            }
            context.drawTextWithShadow(
                    text,
                    text.trimToWidth(action.label(), w - 2 * PADDING),
                    x + PADDING,
                    top + PADDING,
                    colors.applyAsInt(
                            action.enabled() ? ThemeTokens.TEXT : ThemeTokens.MUTED_TEXT));
            if (hover && !action.enabled() && !action.disabledReason().isBlank()) {
                context.drawTooltip(text, Text.literal(action.disabledReason()), mouseX, mouseY);
            }
        }
    }

    public boolean click(double mouseX, double mouseY, int button) {
        // 菜单外点击只关闭且消费本次事件，不把同一次点击穿透到库存。
        if (!isOpen()) {
            return false;
        }
        int index = (int) ((mouseY - y) / ROW_HEIGHT) + offset;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && mouseX >= x
                && mouseX < x + WIDTH
                && mouseY >= y
                && mouseY < y + visibleRows * ROW_HEIGHT
                && index < actions.size()) {
            invoke(index);
        } else {
            close();
        }
        return true;
    }

    public boolean key(int key) {
        // 键盘选择和鼠标共用同一执行入口，禁用操作也不会被 Enter 绕过。
        if (!isOpen()) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
        } else if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            selected = Math.floorMod(selected + (key == GLFW.GLFW_KEY_UP ? -1 : 1), actions.size());
            offset = Math.max(0, Math.min(selected, Math.max(offset, selected - visibleRows + 1)));
        } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            invoke(selected);
        }
        return true;
    }

    public void scroll(double amount) {
        // 滚动只改变可见范围，不触发动作或改变业务选择。
        offset = Math.max(0, Math.min(actions.size() - visibleRows, offset - (int) amount));
    }

    private void invoke(int index) {
        // 在回调之前撤销菜单，避免回调重入时再次执行同一个操作。
        var action = actions.get(index);
        if (action.enabled()) {
            close();
            action.execute().run();
        }
    }

    public void close() {
        actions = List.of();
        valid = () -> false;
    }
}
