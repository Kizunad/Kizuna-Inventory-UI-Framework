package dev.kizuna.inventoryui.client.svg;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.FastColor.ARGB32;

/** 将 SVG 三角形以 GUI QUADS 的退化 quad 形式提交给 Minecraft。 */
public final class MinecraftGuiMeshEmitter {
    private static final int CHANNEL_MAX = 255;
    public static final int MAX_VERTICES = SvgTessellator.MAX_TRIANGLES * 3;

    public void emit(GuiGraphics context, SvgMesh mesh, int x, int y, float scale, int tint) {
        emit(context, mesh, x, y, scale, scale, tint);
    }

    public void emit(
            GuiGraphics context, SvgMesh mesh, int x, int y, float scaleX, float scaleY, int tint) {
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
        PoseStack.Pose matrix = context.pose().last();
        VertexConsumer buffer = context.bufferSource().getBuffer(RenderType.gui());
        for (SvgMesh.Triangle triangle : mesh.triangles()) {
            // GUI layer 开启背面剔除，绕序必须与 GuiGraphics.fill 一致；末点重复形成退化 quad。
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
            PoseStack.Pose matrix,
            SvgMesh.Vertex vertex,
            int x,
            int y,
            float scaleX,
            float scaleY,
            int tint) {
        // 保持当前 GUI 矩阵，颜色与外部 tint 按通道相乘。
        int color = tint(vertex.color(), tint);
        buffer.vertex(
                        matrix.pose(),
                        x + vertex.x() * scaleX,
                        y + vertex.y() * scaleY,
                        0.0f)
                .color(
                        ARGB32.red(color),
                        ARGB32.green(color),
                        ARGB32.blue(color),
                        ARGB32.alpha(color))
                .endVertex();
    }

    static int tint(int color, int tint) {
        // 八位颜色通道按归一化乘法叠加，不把 tint 当作覆盖色。
        return ARGB32.color(
                ARGB32.alpha(color) * ARGB32.alpha(tint) / CHANNEL_MAX,
                ARGB32.red(color) * ARGB32.red(tint) / CHANNEL_MAX,
                ARGB32.green(color) * ARGB32.green(tint) / CHANNEL_MAX,
                ARGB32.blue(color) * ARGB32.blue(tint) / CHANNEL_MAX);
    }
}
