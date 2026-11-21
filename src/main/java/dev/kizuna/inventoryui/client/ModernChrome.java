package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.ModernMetrics.*;
import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import dev.kizuna.inventoryui.window.UiWindowManager;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** 展开窗的外框和窗口控制按钮；按钮绘制和点击都读取相同的动作矩形。 */
final class ModernChrome {
    private static final int SHADOW_SPREAD = 7;
    private static final int SHADOW_OFFSET_Y = 3;
    private static final int DROP_SHADOW_HEIGHT = 3;
    private static final int CONTROL_ICON_SIZE = 7;
    private static final int CONTROL_TEXTURE_SIZE = 48;

    enum Action {
        RESTORE("maximize-2"),
        MINIMIZE("minus"),
        PIN("lock-keyhole-open"),
        CLOSE("x");
        final Identifier icon;

        Action(String icon) {
            this.icon =
                    Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "textures/gui/window/" + icon + ".png");
        }
    }

    private static final Identifier PINNED =
            Identifier.fromNamespaceAndPath("kizuna_inventory_ui", "textures/gui/window/lock-keyhole.png");

    private static final List<Action> WINDOW_ACTIONS =
            List.of(Action.MINIMIZE, Action.PIN, Action.CLOSE);
    private static final List<Action> MINIMIZED_ACTIONS = List.of(Action.RESTORE, Action.PIN, Action.CLOSE);

    private static List<Action> actions(ClientRuntime runtime, UiWindowManager.WindowState state) {
        // 登记了摘要的窗口才有收小和锁；小窗把收小换成展开，其余共用颜色、图标及命中尺寸。
        if (!runtime.modern().canCompact(state)) {
            return List.of(Action.CLOSE);
        }
        return state.minimized() ? MINIMIZED_ACTIONS : WINDOW_ACTIONS;
    }

    static UiWindowManager.Rect actionBounds(
            ClientRuntime runtime, UiWindowManager.WindowState state, UiWindowManager.Rect bounds, Action action) {
        // 工位和系统窗口不显示无法执行的固定按钮；小窗没有标题栏，按钮贴右上角并按小窗尺寸收窄。
        var actions = actions(runtime, state);
        int width = WINDOW_ACTION_WIDTH;
        int height = WINDOW_HEADER_HEIGHT;
        int right = bounds.x() + bounds.width() - WINDOW_ACTION_INSET;
        if (state.minimized()) {
            width = Math.min(CONTROL_ICON_SIZE, Math.max(1, bounds.width() / actions.size()));
            height = Math.min(CONTROL_ICON_SIZE, bounds.height());
            right = bounds.x() + bounds.width();
        }
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action current = actions.get(i);
            if (current == Action.PIN && !state.canPin()) {
                continue;
            }
            right -= width;
            if (current == action) {
                return new UiWindowManager.Rect(right, bounds.y(), width, height);
            }
        }
        return null;
    }

    static Action hit(
            ClientRuntime runtime, UiWindowManager.WindowState state, UiWindowManager.Rect bounds, double x, double y) {
        // 与绘制共用尺寸，窗口缩小时不会出现图标与实际点击位置错开。
        for (Action action : actions(runtime, state)) {
            var area = actionBounds(runtime, state, bounds, action);
            if (area != null && area.contains(x, y)) {
                return action;
            }
        }
        return null;
    }

    static void render(
            GuiGraphicsExtractor context,
            ClientRuntime runtime,
            UiWindowManager.WindowState state,
            UiWindowManager.Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY) {
        // 所有颜色由主题令牌提供：渐变、细边、底边压暗与阴影各有令牌，主题把哪一层设成全透明就没有那一层。
        int x = bounds.x(), y = bounds.y(), w = bounds.width(), h = bounds.height();
        for (int spread = SHADOW_SPREAD; spread >= 1; spread--) {
            context.fill(
                    x - spread,
                    y - spread + SHADOW_OFFSET_Y,
                    x + w + spread,
                    y + h + spread + SHADOW_OFFSET_Y,
                    runtime.color(SHADOW));
        }
        context.fill(x, y + h, x + w, y + h + DROP_SHADOW_HEIGHT, runtime.color(SHADOW_DROP));
        context.fillGradient(
                x, y, x + w, y + h, runtime.color(EXPANDED_PANEL_TOP), runtime.color(EXPANDED_PANEL_BOTTOM));
        context.outline(x, y, w, h, runtime.color(BORDER));
        context.fill(x + 1, y + 1, x + w - 1, y + 2, runtime.color(EDGE_LIGHT));
        context.fill(x + 1, y + 2, x + 2, y + h - 1, runtime.color(EDGE_SIDE));
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, runtime.color(EDGE_DARK));
        int header = Math.min(WINDOW_HEADER_HEIGHT, h);
        context.fillGradient(
                x + 2,
                y + 2,
                x + w - 2,
                y + header,
                runtime.color(HEADER),
                runtime.color(HEADER_BOTTOM));
        context.fill(x + 2, y + header - 1, x + w - 2, y + header, runtime.color(HEADER_RULE));
        int titleEnd = x + w;
        if (controls) {
            titleEnd = actionBounds(runtime, state, bounds, actions(runtime, state).get(0)).x();
        }
        var font = Minecraft.getInstance().font;
        int titleLeft = x + WINDOW_TITLE_PADDING_X;
        String title = font.plainSubstrByWidth(runtime.title(state), Math.max(0, titleEnd - titleLeft));
        context.text(font, title, titleLeft, y + WINDOW_TITLE_PADDING_Y, runtime.color(TEXT), false);
        // 强调短线压在标题下的分隔线上，和标题文字等宽。
        context.fill(
                titleLeft, y + header - 1, titleLeft + font.width(title), y + header, runtime.color(HEADER_ACCENT));
        if (!controls) {
            return;
        }
        controls(context, runtime, state, bounds, mouseX, mouseY);
    }

    static void controls(GuiGraphicsExtractor context, ClientRuntime runtime, UiWindowManager.WindowState state,
            UiWindowManager.Rect bounds, int mouseX, int mouseY) {
        // 图标随文字色染色，浅底主题也能分辨窗口控制；整张贴图缩放到统一图标尺寸。
        for (Action action : actions(runtime, state)) {
            var area = actionBounds(runtime, state, bounds, action);
            if (area == null) {
                continue;
            }
            if (area.contains(mouseX, mouseY)) {
                context.fill(
                        area.x() + 1,
                        area.y() + 1,
                        area.x() + area.width() - 1,
                        area.y() + area.height() - 1,
                        runtime.color(
                                action == Action.CLOSE ? CONTROL_CLOSE_HOVER : CONTROL_HOVER));
            }
            var icon = action == Action.PIN && state.pinned() ? PINNED : action.icon;
            int iconSize = Math.min(CONTROL_ICON_SIZE, Math.min(area.width(), area.height()));
            context.blit(
                    RenderPipelines.GUI_TEXTURED,
                    icon,
                    area.x() + (area.width() - iconSize) / 2,
                    area.y() + (area.height() - iconSize) / 2,
                    0,
                    0,
                    iconSize,
                    iconSize,
                    CONTROL_TEXTURE_SIZE,
                    CONTROL_TEXTURE_SIZE,
                    CONTROL_TEXTURE_SIZE,
                    CONTROL_TEXTURE_SIZE,
                    runtime.color(TEXT));
        }
    }
}
