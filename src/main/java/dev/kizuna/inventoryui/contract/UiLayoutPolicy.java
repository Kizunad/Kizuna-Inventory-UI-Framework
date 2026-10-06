package dev.kizuna.inventoryui.contract;

import java.util.Objects;

/** 纯响应式布局策略；适配器只消费计算出的几何结果。 */
public final class UiLayoutPolicy {
    private UiLayoutPolicy() {}

    public static LayoutSnapshot centered(UiViewport viewport, Request request) {
        // 视觉区域和扩大后的点击区域都受安全区约束，尺寸不足通过结果标记反馈给适配器。
        Objects.requireNonNull(viewport, "viewport must not be null");
        Objects.requireNonNull(request, "request must not be null");
        UiViewport.Rect safe = viewport.safeRect(request.marginX(), request.marginY());
        double width = Math.min(request.preferredWidth(), safe.width());
        double height = Math.min(request.preferredHeight(), safe.height());
        UiViewport.Rect bounds =
                new UiViewport.Rect(
                        safe.x() + (safe.width() - width) / 2.0d,
                        safe.y() + (safe.height() - height) / 2.0d,
                        width,
                        height);
        UiViewport.Rect expandedHitRegion = bounds.expand(request.hitPadding());
        UiViewport.Rect hitRegion = expandedHitRegion.intersection(safe);
        return new LayoutSnapshot(
                safe,
                bounds,
                hitRegion,
                viewport.mode(),
                viewport.belowMinimum(),
                request.textWidth() > bounds.width(),
                !expandedHitRegion.equals(hitRegion));
    }

    public record Request(
            double preferredWidth,
            double preferredHeight,
            double textWidth,
            double hitPadding,
            double marginX,
            double marginY) {
        public Request {
            // 尺寸、边距和点击扩展可以为零，但不能让 NaN 或负值进入布局计算。
            requireNonNegative(preferredWidth, "preferredWidth");
            requireNonNegative(preferredHeight, "preferredHeight");
            requireNonNegative(textWidth, "textWidth");
            requireNonNegative(hitPadding, "hitPadding");
            requireNonNegative(marginX, "marginX");
            requireNonNegative(marginY, "marginY");
        }

        private static void requireNonNegative(double value, String name) {
            // 共用有限非负校验，确保所有布局输入采用一致的数值边界。
            if (!Double.isFinite(value) || value < 0.0d) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
        }
    }

    public record LayoutSnapshot(
            UiViewport.Rect safeRect,
            UiViewport.Rect bounds,
            UiViewport.Rect hitRegion,
            UiViewport.Mode mode,
            boolean belowMinimum,
            boolean textOverflow,
            boolean hitRegionClipped) {
        public LayoutSnapshot {
            // 快照携带可直接消费的几何结果，构造时保证视觉区域和命中区均未越出安全区。
            Objects.requireNonNull(safeRect, "safeRect must not be null");
            Objects.requireNonNull(bounds, "bounds must not be null");
            Objects.requireNonNull(hitRegion, "hitRegion must not be null");
            Objects.requireNonNull(mode, "mode must not be null");
            if (!safeRect.intersection(bounds).equals(bounds)) {
                throw new IllegalArgumentException("bounds must remain inside safeRect");
            }
            if (!safeRect.intersection(hitRegion).equals(hitRegion)) {
                throw new IllegalArgumentException("hitRegion must remain inside safeRect");
            }
        }
    }
}
