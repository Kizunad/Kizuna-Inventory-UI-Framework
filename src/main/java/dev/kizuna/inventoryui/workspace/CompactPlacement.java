package dev.kizuna.inventoryui.workspace;

import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import java.util.Objects;

/** 功能的小窗偏好。偏距以缩放后的外框到相邻边的 GUI 像素表示。 */
public record CompactPlacement(
        boolean pinned, Position position, Corner corner, int dx, int dy, float scale, float backdropAlpha,
        String variantId) {
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /** FLOW 由框架按各小窗声明的列和顺序排开；PLACED 使用玩家拖放留下的角和偏距。 */
    public enum Position { FLOW, PLACED }

    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2f;
    public static final float DEFAULT_SCALE = 1f;
    private static final float DEFAULT_BACKDROP_ALPHA = 0f;

    public CompactPlacement {
        // 没有记录排位方式的条目按自动排开读入。
        position = Objects.requireNonNullElse(position, Position.FLOW);
        Objects.requireNonNull(corner);
        Objects.requireNonNull(variantId);
        if (dx < 0 || dy < 0 || !(scale >= MIN_SCALE && scale <= MAX_SCALE)
                || !(backdropAlpha >= 0 && backdropAlpha <= 1)) {
            throw new IllegalArgumentException("invalid compact placement");
        }
    }

    public static CompactPlacement initial(String variant) {
        return new CompactPlacement(false /* pinned */, Position.FLOW, Corner.TOP_LEFT, 0, 0, DEFAULT_SCALE,
                DEFAULT_BACKDROP_ALPHA, variant);
    }

    public CompactPlacement pin() {
        return new CompactPlacement(true /* pinned */, position, corner, dx, dy, scale, backdropAlpha, variantId);
    }

    public CompactPlacement unpin() {
        return new CompactPlacement(false /* pinned */, position, corner, dx, dy, scale, backdropAlpha, variantId);
    }

    public CompactPlacement style(String variant, float zoom, float alpha) {
        return new CompactPlacement(pinned, position, corner, dx, dy, zoom, alpha, variant);
    }

    /** 交还给框架自动排开，留下的角和偏距不再使用。 */
    public CompactPlacement flow() {
        return new CompactPlacement(pinned, Position.FLOW, Corner.TOP_LEFT, 0, 0, scale, backdropAlpha, variantId);
    }

    public CompactPlacement anchor(Rect outer, Rect frame) {
        // 中心落在哪个象限就贴哪两条边；拖出可用区时边距为零。
        int left = outer.x() - frame.x();
        int top = outer.y() - frame.y();
        int right = frame.width() - left - outer.width();
        int bottom = frame.height() - top - outer.height();
        Corner nearest;
        if (top <= bottom) {
            nearest = left <= right ? Corner.TOP_LEFT : Corner.TOP_RIGHT;
        } else {
            nearest = left <= right ? Corner.BOTTOM_LEFT : Corner.BOTTOM_RIGHT;
        }
        return new CompactPlacement(pinned, Position.PLACED, nearest, Math.max(0, Math.min(left, right)),
                Math.max(0, Math.min(top, bottom)), scale, backdropAlpha, variantId);
    }

    public Rect resolve(Rect frame, int width, int height) {
        // 小屏的暂时夹取只影响本次结果，回到大屏仍使用原来的偏距。
        int w = Math.clamp(Math.round(width * scale), 1, frame.width());
        int h = Math.clamp(Math.round(height * scale), 1, frame.height());
        int maxX = frame.width() - w;
        int maxY = frame.height() - h;
        int x = switch (corner) {
            case TOP_LEFT, BOTTOM_LEFT -> dx;
            default -> maxX - dx;
        };
        int y = switch (corner) {
            case TOP_LEFT, TOP_RIGHT -> dy;
            default -> maxY - dy;
        };
        return new Rect(frame.x() + Math.clamp(x, 0, maxX), frame.y() + Math.clamp(y, 0, maxY), w, h);
    }
}
