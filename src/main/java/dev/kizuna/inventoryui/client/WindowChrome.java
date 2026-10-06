package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.WorkspaceMetrics.*;
import static dev.kizuna.inventoryui.theme.ThemeTokens.*;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.inventoryui.window.UiWindowManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import org.lwjgl.opengl.GL11;

/** 工作台与固定 HUD 共用外框；按钮绘制和点击都读取相同的动作矩形。 */
final class WindowChrome {
    private static final int SHADOW_SPREAD = 7;
    private static final int SHADOW_OFFSET_Y = 3;
    private static final int CONTROL_ICON_SIZE = 14;
    private static final int CONTROL_TEXTURE_SIZE = 48;
    private static final int COMPACT_WIDTH = 180;
    private static final int COMPACT_ACTION_WIDTH = 18;
    private static final int CLOSE_WIDTH = 20;
    private static final int ACCENT_START = 7;
    private static final int ACCENT_END = 32;

    enum Action {
        SIZE("maximize-2"),
        MINIMIZE("minus"),
        PIN("lock-keyhole-open"),
        CLOSE("x");
        final Identifier icon;

        Action(String icon) {
            this.icon =
                    new Identifier("kizuna_inventory_ui", "textures/gui/window/" + icon + ".png");
        }
    }

    private static final Identifier PINNED =
            new Identifier("kizuna_inventory_ui", "textures/gui/window/lock-keyhole.png");

    static UiWindowManager.Rect actionBounds(
            UiWindowManager.WindowState state, UiWindowManager.Rect bounds, Action action) {
        // 工位和系统窗口不显示无法执行的固定按钮，紧凑宽度仍保留全部有效管理入口。
        int right = bounds.x() + bounds.width();
        for (int i = Action.values().length - 1; i >= 0; i--) {
            Action current = Action.values()[i];
            if (current == Action.PIN && !state.canPin()) {
                continue;
            }
            int width =
                    bounds.width() < COMPACT_WIDTH
                            ? COMPACT_ACTION_WIDTH
                            : current == Action.CLOSE ? CLOSE_WIDTH : WINDOW_ACTION_WIDTH;
            right -= width;
            if (current == action) {
                return new UiWindowManager.Rect(right, bounds.y(), width, WINDOW_HEADER_HEIGHT);
            }
        }
        return null;
    }

    static Action hit(
            UiWindowManager.WindowState state, UiWindowManager.Rect bounds, double x, double y) {
        // 与绘制共用尺寸，窗口缩小时不会出现图标与实际点击位置错开。
        for (Action action : Action.values()) {
            var area = actionBounds(state, bounds, action);
            if (area != null && area.contains(x, y)) {
                return action;
            }
        }
        return null;
    }

    static void render(
            DrawContext context,
            ClientRuntime runtime,
            UiWindowManager.WindowState state,
            UiWindowManager.Rect bounds,
            boolean controls,
            int mouseX,
            int mouseY) {
        // 所有颜色由主题令牌提供，渐变、细边、底边压暗与阴影共同构成外框层次。
        int x = bounds.x(), y = bounds.y(), w = bounds.width(), h = bounds.height();
        for (int spread = SHADOW_SPREAD; spread >= 1; spread--) {
            context.fill(
                    x - spread,
                    y - spread + SHADOW_OFFSET_Y,
                    x + w + spread,
                    y + h + spread + SHADOW_OFFSET_Y,
                    runtime.color(SHADOW));
        }
        context.fillGradient(x, y, x + w, y + h, runtime.color(PANEL), runtime.color(PANEL_BOTTOM));
        context.drawBorder(x, y, w, h, runtime.color(BORDER));
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
        context.fill(
                x + ACCENT_START,
                y + header - 2,
                x + Math.min(ACCENT_END, w),
                y + header - 1,
                runtime.color(HEADER_ACCENT));
        int titleEnd = controls ? actionBounds(state, bounds, Action.SIZE).x() : x + w;
        var text = MinecraftClient.getInstance().textRenderer;
        context.drawText(
                text,
                text.trimToWidth(
                        runtime.title(state),
                        Math.max(0, titleEnd - x - 2 * WINDOW_TITLE_PADDING_X)),
                x + WINDOW_TITLE_PADDING_X,
                y + WINDOW_TITLE_PADDING_Y,
                runtime.color(TEXT),
                false);
        if (!controls) {
            return;
        }
        context.draw();
        boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
        RenderSystem.enableBlend();
        try {
            for (Action action : Action.values()) {
                var area = actionBounds(state, bounds, action);
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
                context.drawTexture(
                        icon,
                        area.x() + (area.width() - CONTROL_ICON_SIZE) / 2,
                        area.y() + (area.height() - CONTROL_ICON_SIZE) / 2,
                        CONTROL_ICON_SIZE,
                        CONTROL_ICON_SIZE,
                        0,
                        0,
                        CONTROL_TEXTURE_SIZE,
                        CONTROL_TEXTURE_SIZE,
                        CONTROL_TEXTURE_SIZE,
                        CONTROL_TEXTURE_SIZE);
            }
            context.draw();
        } finally {
            if (!blending) {
                RenderSystem.disableBlend();
            }
        }
    }
}
