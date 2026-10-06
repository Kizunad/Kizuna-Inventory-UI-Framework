package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.WorkspaceMetrics.*;

import dev.kizuna.inventoryui.client.component.ItemDropTarget;
import dev.kizuna.inventoryui.window.UiWindowManager;

import io.wispforest.owo.ui.container.Containers;
import io.wispforest.owo.ui.container.FlowLayout;
import io.wispforest.owo.ui.core.Component;
import io.wispforest.owo.ui.core.OwoUIAdapter;

import net.minecraft.client.gui.DrawContext;

/** 独立 owo 组件树，业务订阅由窗口 scope 管理，渲染资源随视图释放。 */
final class OwoWindowView implements AutoCloseable {
    final OwoUIAdapter<FlowLayout> adapter;
    private UiWindowManager.Rect previous;

    OwoWindowView(
            ClientRuntime runtime,
            UiWindowManager.WindowState state,
            ClientBindings.WindowFactory factory) {
        // 标题栏由工作台绘制，owo 子树只占用窗口内容区并采用局部坐标。
        var bounds = state.bounds();
        adapter =
                OwoUIAdapter.createWithoutScreen(
                        bounds.x() + WINDOW_BORDER,
                        bounds.y() + WINDOW_HEADER_HEIGHT,
                        Math.max(1, bounds.width() - 2 * WINDOW_BORDER),
                        Math.max(1, bounds.height() - WINDOW_HEADER_HEIGHT - WINDOW_BORDER),
                        Containers::verticalFlow);
        try {
            adapter.rootComponent.allowOverflow(false);
            adapter.rootComponent.child(factory.create(runtime, state));
            adapter.inflateAndMount();
        } catch (RuntimeException | Error failure) {
            // 部分构建的组件树也可能持有资源，挂载失败时先释放再传播异常。
            adapter.dispose();
            throw failure;
        }
        previous = bounds;
    }

    void render(
            DrawContext context, UiWindowManager.Rect bounds, int mouseX, int mouseY, float delta) {
        // 外框位置或尺寸变化时同步适配器，保证绘制与输入使用同一内容区域。
        if (!bounds.equals(previous)) {
            adapter.moveAndResize(
                    bounds.x() + WINDOW_BORDER,
                    bounds.y() + WINDOW_HEADER_HEIGHT,
                    Math.max(1, bounds.width() - 2 * WINDOW_BORDER),
                    Math.max(1, bounds.height() - WINDOW_HEADER_HEIGHT - WINDOW_BORDER));
            previous = bounds;
        }
        adapter.render(context, mouseX, mouseY, delta);
    }

    void cancelInput() {
        // 清除组件焦点会触发库存组件的失焦处理，从而取消组件内部拖动。
        var focus = adapter.rootComponent.focusHandler();
        if (focus != null) {
            focus.focus(null, Component.FocusSource.MOUSE_CLICK);
        }
    }

    boolean dropItem(double x, double y) {
        // childAt 返回实际最上层叶子；向父级寻找落点，支持容器包装后的自定义组件。
        if (x < adapter.x()
                || y < adapter.y()
                || x >= adapter.x() + adapter.width()
                || y >= adapter.y() + adapter.height()) {
            return false;
        }
        var component = adapter.rootComponent.childAt((int) x, (int) y);
        while (component != null) {
            if (component instanceof ItemDropTarget target) {
                return target.dropItem(x, y);
            }
            component = component.parent();
        }
        return false;
    }

    @Override
    public void close() {
        // 先终止交互，再释放组件树，避免释放后的组件仍被当作输入目标。
        cancelInput();
        adapter.dispose();
    }
}
