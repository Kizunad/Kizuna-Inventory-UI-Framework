package dev.kizuna.inventoryui.client.svg;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ColorHelper.Argb;

import java.util.Objects;

/** 将 SVG 三角形以 GUI QUADS 的退化 quad 形式提交给 Minecraft。 */
public final class MinecraftGuiMeshEmitter {
    private static final int CHANNEL_MAX = 255;
    public static final int MAX_VERTICES = SvgTessellator.MAX_TRIANGLES * 3;

    public void emit(DrawContext context, SvgMesh mesh, int x, int y, float scale, int tint) {
        emit(context, mesh, x, y, scale, scale, tint);
    }

    public void emit(
            DrawContext context, SvgMesh mesh, int x, int y, float scaleX, float scaleY, int tint) {
        // 校验缩放与预算后复用当前 GUI 缓冲，不改变调用方的矩阵和裁剪。
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mesh, "mesh");
        if (!Float.isFinite(scaleX)
                || scaleX <= 0.0f
                || !Float.isFinite(scaleY)
                || scaleY <= 0.0f) {
            throw new IllegalArgumentException("SVG scale 必须是有限正数");
        }
        if (mesh.vertexCount() > MAX_VERTICES) {
            throw new IllegalArgumentException("SVG mesh 顶点数量超过预算: " + MAX_VERTICES);
        }
        MatrixStack.Entry matrix = context.getMatrices().peek();
        VertexConsumer buffer = context.getVertexConsumers().getBuffer(RenderLayer.getGui());
        for (SvgMesh.Triangle triangle : mesh.triangles()) {
            // GUI layer 开启背面剔除，绕序必须与 DrawContext.fill 一致；末点重复形成退化 quad。
            for (int index = 0; index < 4; index++) {
                emitVertex(
                        buffer, matrix, guiQuadVertex(triangle, index), x, y, scaleX, scaleY, tint);
            }
        }
        // 调用方在 SVG 与 GUI 命令切换时统一提交，连续 SVG 几何仍可共用缓冲。
    }

    static SvgMesh.Vertex guiQuadVertex(SvgMesh.Triangle triangle, int index) {
        // GUI 背面剔除要求反转三角绕序，重复末点补足四边形。
        Objects.requireNonNull(triangle, "triangle");
        return switch (index) {
            case 0 -> triangle.a();
            case 1 -> triangle.c();
            case 2, 3 -> triangle.b();
            default -> throw new IllegalArgumentException("GUI quad 顶点索引必须在 0 到 3 之间");
        };
    }

    private static void emitVertex(
            VertexConsumer buffer,
            MatrixStack.Entry matrix,
            SvgMesh.Vertex vertex,
            int x,
            int y,
            float scaleX,
            float scaleY,
            int tint) {
        // 保持当前 GUI 矩阵，颜色与外部 tint 按通道相乘。
        int color = tint(vertex.color(), tint);
        buffer.vertex(
                        matrix.getPositionMatrix(),
                        x + vertex.x() * scaleX,
                        y + vertex.y() * scaleY,
                        0.0f)
                .color(
                        Argb.getRed(color),
                        Argb.getGreen(color),
                        Argb.getBlue(color),
                        Argb.getAlpha(color))
                .next();
    }

    static int tint(int color, int tint) {
        // 八位颜色通道按归一化乘法叠加，不把 tint 当作覆盖色。
        return Argb.getArgb(
                Argb.getAlpha(color) * Argb.getAlpha(tint) / CHANNEL_MAX,
                Argb.getRed(color) * Argb.getRed(tint) / CHANNEL_MAX,
                Argb.getGreen(color) * Argb.getGreen(tint) / CHANNEL_MAX,
                Argb.getBlue(color) * Argb.getBlue(tint) / CHANNEL_MAX);
    }
}
