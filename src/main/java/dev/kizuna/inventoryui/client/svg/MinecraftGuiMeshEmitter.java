package dev.kizuna.inventoryui.client.svg;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

/** 将 SVG 三角形作为 GUI 渲染状态提交，由原版 GUI 渲染器按 QUADS 退化 quad 构建顶点。 */
public final class MinecraftGuiMeshEmitter {
    private static final int CHANNEL_MAX = 255;
    public static final int MAX_VERTICES = SvgTessellator.MAX_TRIANGLES * 3;

    public void emit(GuiGraphicsExtractor context, SvgMesh mesh, int x, int y, float scale, int tint) {
        emit(context, mesh, x, y, scale, scale, tint);
    }

    public void emit(
            GuiGraphicsExtractor context,
            SvgMesh mesh,
            int x,
            int y,
            float scaleX,
            float scaleY,
            int tint) {
        // 校验缩放与预算后记录当前 GUI 矩阵与裁剪的快照，顶点在 GUI 渲染器准备阶段才构建。
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
        context.guiRenderState.addGuiElement(
                MeshRenderState.of(
                        new Matrix3x2f(context.pose()),
                        mesh,
                        x,
                        y,
                        scaleX,
                        scaleY,
                        tint,
                        context.scissorStack.peek()));
    }

    static SvgMesh.Vertex guiQuadVertex(SvgMesh.Triangle triangle, int index) {
        // GUI 管线开启背面剔除，绕序必须与 fill 一致：反转三角绕序，重复末点补足四边形。
        Objects.requireNonNull(triangle, "triangle");
        return switch (index) {
            case 0 -> triangle.a();
            case 1 -> triangle.c();
            case 2, 3 -> triangle.b();
            default -> throw new IllegalArgumentException("GUI quad 顶点索引必须在 0 到 3 之间");
        };
    }

    static int tint(int color, int tint) {
        // 八位颜色通道按归一化乘法叠加，不把 tint 当作覆盖色。
        return ARGB.color(
                ARGB.alpha(color) * ARGB.alpha(tint) / CHANNEL_MAX,
                ARGB.red(color) * ARGB.red(tint) / CHANNEL_MAX,
                ARGB.green(color) * ARGB.green(tint) / CHANNEL_MAX,
                ARGB.blue(color) * ARGB.blue(tint) / CHANNEL_MAX);
    }

    /** 一次 SVG 绘制：画布坐标经 (x, y) 平移与 scaleX/scaleY 缩放到 GUI 坐标，再乘提交时的 GUI 矩阵。 */
    private record MeshRenderState(
            Matrix3x2fc pose,
            SvgMesh mesh,
            int x,
            int y,
            float scaleX,
            float scaleY,
            int tint,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds)
            implements GuiElementRenderState {
        static MeshRenderState of(
                Matrix3x2fc pose,
                SvgMesh mesh,
                int x,
                int y,
                float scaleX,
                float scaleY,
                int tint,
                ScreenRectangle scissorArea) {
            // 细分器保证网格落在画布内，画布矩形即几何外框；GUI 渲染器按外框决定层叠，无外框的元素不绘制。
            var bounds =
                    new ScreenRectangle(
                                    x,
                                    y,
                                    Mth.ceil(mesh.width() * scaleX),
                                    Mth.ceil(mesh.height() * scaleY))
                            .transformMaxBounds(pose);
            if (scissorArea != null) {
                bounds = scissorArea.intersection(bounds);
            }
            return new MeshRenderState(
                    pose, mesh, x, y, scaleX, scaleY, tint, scissorArea, bounds);
        }

        @Override
        public void buildVertices(VertexConsumer buffer) {
            for (SvgMesh.Triangle triangle : mesh.triangles()) {
                for (int index = 0; index < 4; index++) {
                    var vertex = guiQuadVertex(triangle, index);
                    buffer.addVertexWith2DPose(
                                    pose, x + vertex.x() * scaleX, y + vertex.y() * scaleY)
                            .setColor(MinecraftGuiMeshEmitter.tint(vertex.color(), tint));
                }
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }
    }
}
