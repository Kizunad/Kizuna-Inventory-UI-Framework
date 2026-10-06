package dev.kizuna.inventoryui.contract;

import java.util.Objects;

/** 所有适配器共享的纯逻辑/物理视口元数据。 */
public record UiViewport(int logicalWidth, int logicalHeight, int guiScale, double windowScale) {
    public static final int MIN_SUPPORTED_WIDTH = 320;
    public static final int MIN_SUPPORTED_HEIGHT = 240;

    /** 响应式模式的逻辑像素断点；任一方向不足时优先使用紧凑布局。 */
    private static final int REGULAR_MIN_WIDTH = 640;

    private static final int REGULAR_MIN_HEIGHT = 360;
    private static final int WIDE_MIN_WIDTH = 1280;
    private static final int WIDE_MIN_HEIGHT = 720;

    public UiViewport {
        // 尺寸和缩放必须有限且为正，保证后续坐标换算不会产生无穷值或除零。
        if (logicalWidth <= 0 || logicalHeight <= 0) {
            throw new IllegalArgumentException("logical viewport dimensions must be positive");
        }
        if (guiScale <= 0) {
            throw new IllegalArgumentException("guiScale must be positive");
        }
        if (!Double.isFinite(windowScale) || windowScale <= 0.0d) {
            throw new IllegalArgumentException("windowScale must be finite and positive");
        }
    }

    public boolean belowMinimum() {
        return logicalWidth < MIN_SUPPORTED_WIDTH || logicalHeight < MIN_SUPPORTED_HEIGHT;
    }

    public Mode mode() {
        // 优先照顾任一方向不足的小视口，再区分宽屏；模式只依赖逻辑尺寸。
        if (logicalWidth < REGULAR_MIN_WIDTH || logicalHeight < REGULAR_MIN_HEIGHT) {
            return Mode.COMPACT;
        }
        if (logicalWidth >= WIDE_MIN_WIDTH || logicalHeight >= WIDE_MIN_HEIGHT) {
            return Mode.WIDE;
        }
        return Mode.REGULAR;
    }

    public Rect safeRect(double horizontalMargin, double verticalMargin) {
        // 边距最多占据对应轴的一半，极小视口允许空安全区而不产生负尺寸。
        requireMargin(horizontalMargin, "horizontalMargin");
        requireMargin(verticalMargin, "verticalMargin");
        double x = Math.min(horizontalMargin, logicalWidth / 2.0d);
        double y = Math.min(verticalMargin, logicalHeight / 2.0d);
        return new Rect(x, y, logicalWidth - x * 2.0d, logicalHeight - y * 2.0d);
    }

    public Point physicalToLogical(Point physical) {
        // windowScale 表示实际像素映射比例，不能再次叠乘游戏配置中的 guiScale。
        Objects.requireNonNull(physical, "physical point must not be null");
        return new Point(physical.x() / windowScale, physical.y() / windowScale);
    }

    public Point logicalToPhysical(Point logical) {
        // 与 physicalToLogical 使用同一比例，保证绘制点和输入点可以往返转换。
        Objects.requireNonNull(logical, "logical point must not be null");
        return new Point(logical.x() * windowScale, logical.y() * windowScale);
    }

    private static void requireMargin(double margin, String name) {
        // 负值或非有限边距会破坏安全区约束，在布局计算前直接拒绝。
        if (!Double.isFinite(margin) || margin < 0.0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    public enum Mode {
        COMPACT,
        REGULAR,
        WIDE
    }

    public record Point(double x, double y) {
        public Point {
            // 非有限坐标无法可靠比较或命中，不允许进入几何计算。
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("point coordinates must be finite");
            }
        }
    }

    public record Rect(double x, double y, double width, double height) {
        public Rect {
            // 几何交集允许零面积矩形，但坐标必须有限且尺寸不能为负。
            if (!Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(width)
                    || !Double.isFinite(height)
                    || width < 0.0d
                    || height < 0.0d) {
                throw new IllegalArgumentException(
                        "rect coordinates must be finite and dimensions non-negative");
            }
        }

        public double right() {
            return x + width;
        }

        public double bottom() {
            return y + height;
        }

        public Rect expand(double padding) {
            // 在四个方向等量扩展，用于增大点击区域而不改变视觉外框。
            if (!Double.isFinite(padding) || padding < 0.0d) {
                throw new IllegalArgumentException("padding must be finite and non-negative");
            }
            return new Rect(
                    x - padding, y - padding, width + padding * 2.0d, height + padding * 2.0d);
        }

        public Rect intersection(Rect other) {
            // 无交集时返回零面积，调用方可统一处理裁剪而不判断 null。
            Objects.requireNonNull(other, "other rect must not be null");
            double left = Math.max(x, other.x);
            double top = Math.max(y, other.y);
            double right = Math.min(right(), other.right());
            double bottom = Math.min(bottom(), other.bottom());
            return new Rect(left, top, Math.max(0.0d, right - left), Math.max(0.0d, bottom - top));
        }

        public boolean contains(Point point) {
            // 逻辑安全区包含边界点；窗口之间的输入归属另由窗口管理器决定。
            Objects.requireNonNull(point, "point must not be null");
            return point.x() >= x
                    && point.x() <= right()
                    && point.y() >= y
                    && point.y() <= bottom();
        }
    }
}
