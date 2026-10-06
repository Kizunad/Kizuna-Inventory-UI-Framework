package dev.kizuna.inventoryui.client.component;

import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.inventory.InventoryDragSession;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** 单个动态槽位；装备、快捷引用和投料槽都通过同一内容与落点契约接入。 */
public final class ItemSlotComponent extends BaseComponent implements ItemDropTarget {
    private static final int TEXT_INSET = 4;
    private final Supplier<ClientBindings.SlotContent> content;
    private final InventoryDragSession drag;
    private final ToIntFunction<String> colors;
    private Runnable beginDrag;

    public ItemSlotComponent(
            int size,
            Supplier<ClientBindings.SlotContent> content,
            InventoryDragSession drag,
            ToIntFunction<String> colors) {
        // 槽位身份和数量由所属模块声明，这里不预置装备部位或栏位枚举。
        if (size <= 0) {
            throw new IllegalArgumentException("slot size must be positive");
        }
        this.content = Objects.requireNonNull(content);
        this.drag = Objects.requireNonNull(drag);
        this.colors = Objects.requireNonNull(colors);
        sizing(Sizing.fixed(size), Sizing.fixed(size));
    }

    public ItemSlotComponent dragSource(Runnable beginDrag) {
        // 来源适配器负责从自己的权威状态调用 drag.begin，不能凭显示图标虚构库存实例。
        this.beginDrag = Objects.requireNonNull(beginDrag);
        return this;
    }

    @Override
    public void draw(
            OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        var value = content.get();
        context.fill(
                x, y, x + width, y + height, colors.applyAsInt(ThemeTokens.INVENTORY_CELL_EVEN));
        if (value.item() != null) {
            ItemDrawing.draw(
                    context, value.item(), value.item().count(), x, y, width, height, colors);
        } else {
            var text = MinecraftClient.getInstance().textRenderer;
            context.drawTextWithShadow(
                    text,
                    text.trimToWidth(value.label(), Math.max(1, width - 2 * TEXT_INSET)),
                    x + TEXT_INSET,
                    y + (height - text.fontHeight) / 2,
                    colors.applyAsInt(ThemeTokens.TEXT));
        }
        var payload = drag.current();
        if (payload != null && isInBoundingBox(mouseX, mouseY)) {
            // 本地过滤只负责候选高亮，真正的执行端仍需再次检查权限。
            context.fill(
                    x,
                    y,
                    x + width,
                    y + height,
                    colors.applyAsInt(
                            value.accepts().test(payload)
                                    ? ThemeTokens.INVENTORY_DROP_VALID
                                    : ThemeTokens.INVENTORY_DROP_INVALID));
        }
        tooltip(List.of(Text.literal(value.label())));
    }

    @Override
    public boolean dropItem(double screenX, double screenY) {
        var value = content.get();
        var payload = drag.current();
        if (payload == null || !value.accepts().test(payload)) {
            drag.cancel();
            return false;
        }
        // 消费一次通用载荷，绑定或装备的后续状态只由模块的权威更新提供。
        drag.consume(value.drop());
        return true;
    }

    @Override
    public boolean onMouseDown(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            content.get().clear().run();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // 可拖拽物优先开始来源拖动，纯动作槽则直接执行注册的激活动作。
            if (beginDrag != null && content.get().item() != null) {
                beginDrag.run();
            } else {
                content.get().activate().run();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean canFocus(FocusSource source) {
        return true;
    }

    @Override
    public void onFocusLost() {
        // 拖动来源的 owner 传此组件时，失焦只撤销本槽发起的会话。
        drag.cancel(this);
        super.onFocusLost();
    }
}
