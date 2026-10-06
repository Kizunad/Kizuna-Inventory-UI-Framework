package dev.kizuna.inventoryui.client.svg;

import java.util.List;
import java.util.Objects;

/** SVG tessellation 的不可变三角形网格。 */
public final class SvgMesh {
    private final List<Triangle> triangles;
    private final float width;
    private final float height;

    public SvgMesh(List<? extends Triangle> triangles) {
        this(1.0f, 1.0f, triangles);
    }

    public SvgMesh(float width, float height, List<? extends Triangle> triangles) {
        // 冻结几何前验证画布尺寸，避免后端除零或生成非有限缩放。
        if (!Float.isFinite(width) || width <= 0 || !Float.isFinite(height) || height <= 0) {
            throw new IllegalArgumentException("SVG mesh 画布尺寸必须为有限正数");
        }
        this.width = width;
        this.height = height;
        this.triangles = List.copyOf(Objects.requireNonNull(triangles, "triangles"));
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public List<Triangle> triangles() {
        return triangles;
    }

    public int triangleCount() {
        return triangles.size();
    }

    public int vertexCount() {
        return triangles.size() * 3;
    }

    public record Vertex(float x, float y, int color) {
        public Vertex {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                throw new IllegalArgumentException("SVG mesh 顶点坐标必须是有限数");
            }
        }
    }

    public record Triangle(Vertex a, Vertex b, Vertex c) {
        public Triangle {
            // 公共模型在构造时保证数值与引用有效，渲染层只消费已验证快照。
            Objects.requireNonNull(a, "triangle.a");
            Objects.requireNonNull(b, "triangle.b");
            Objects.requireNonNull(c, "triangle.c");
        }
    }
}
