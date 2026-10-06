package dev.kizuna.inventoryui.client.svg;

import java.util.List;
import java.util.Objects;

/**
 * 受限 SVG 的不可变解析结果。
 *
 * <p>只保留 HUD 几何需要的基础图元；文字、图片和滤镜不进入这个模型。
 */
public final class SvgDocument {
    private final float width;
    private final float height;
    private final List<Shape> shapes;

    public SvgDocument(float width, float height, List<? extends Shape> shapes) {
        // 冻结几何前验证画布尺寸，避免后端除零或生成非有限缩放。
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0.0f || height <= 0.0f) {
            throw new IllegalArgumentException("SVG viewport 必须是有限正数");
        }
        this.width = width;
        this.height = height;
        this.shapes = List.copyOf(Objects.requireNonNull(shapes, "shapes"));
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public List<Shape> shapes() {
        return shapes;
    }

    public sealed interface Shape permits Rect, Circle, Ellipse, Polygon {
        int color();

        float opacity();
    }

    public record Rect(float x, float y, float width, float height, int color, float opacity)
            implements Shape {
        public Rect {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            requireFinitePositive(width, "rect.width");
            requireFinitePositive(height, "rect.height");
            requireFinite(x, "rect.x");
            requireFinite(y, "rect.y");
            requireOpacity(opacity);
        }
    }

    public record Circle(float cx, float cy, float radius, int color, float opacity)
            implements Shape {
        public Circle {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            requireFinite(cx, "circle.cx");
            requireFinite(cy, "circle.cy");
            requireFinitePositive(radius, "circle.r");
            requireOpacity(opacity);
        }
    }

    public record Ellipse(float cx, float cy, float rx, float ry, int color, float opacity)
            implements Shape {
        public Ellipse {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            requireFinite(cx, "ellipse.cx");
            requireFinite(cy, "ellipse.cy");
            requireFinitePositive(rx, "ellipse.rx");
            requireFinitePositive(ry, "ellipse.ry");
            requireOpacity(opacity);
        }
    }

    public record Polygon(List<Point> points, int color, float opacity) implements Shape {
        public Polygon {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            points = List.copyOf(Objects.requireNonNull(points, "polygon.points"));
            if (points.size() < 3) {
                throw new IllegalArgumentException("polygon 至少需要三个点");
            }
            requireOpacity(opacity);
        }
    }

    public record Point(float x, float y) {
        public Point {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            requireFinite(x, "point.x");
            requireFinite(y, "point.y");
        }
    }

    private static void requireFinite(float value, String name) {
        // 拒绝非有限坐标，防止无效顶点传播到渲染缓冲。
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须是有限数");
        }
    }

    private static void requireFinitePositive(float value, String name) {
        // 几何尺寸必须占据正面积，不能由负值或零值蒙混过关。
        requireFinite(value, name);
        if (value <= 0.0f) {
            throw new IllegalArgumentException(name + " 必须大于零");
        }
    }

    private static void requireOpacity(float opacity) {
        // 透明度是封闭单位区间，错误值不得通过颜色截断掩盖。
        if (!Float.isFinite(opacity) || opacity < 0.0f || opacity > 1.0f) {
            throw new IllegalArgumentException("SVG opacity 必须在 0 到 1 之间");
        }
    }
}
