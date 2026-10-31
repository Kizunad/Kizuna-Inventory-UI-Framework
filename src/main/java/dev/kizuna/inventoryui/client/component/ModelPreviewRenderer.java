package dev.kizuna.inventoryui.client.component;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fc;

/** 模型预览的画中画渲染器：在独立纹理里按相机角度提交模型，再由 GUI 渲染器把纹理贴回画布。 */
public final class ModelPreviewRenderer
        extends PictureInPictureRenderer<ModelPreviewRenderer.State> {
    // 模型深度压缩到十分之一，放大后的模型仍落在画中画正交投影 ±1000 的深度范围内。
    private static final float DEPTH_SCALE = 0.1f;

    @Override
    public Class<State> getRenderStateClass() {
        return State.class;
    }

    @Override
    protected void renderToTexture(State state, PoseStack matrices, SubmitNodeCollector collector) {
        // 基础矩阵已把原点移到纹理中心、按 GUI 缩放乘 state.scale() 放大并翻转 Z；
        // 这里翻回 Z 并压缩深度，Y 向上的模型再翻转 Y，使模型坐标到屏幕的映射与旧 GUI 即时绘制一致。
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
        matrices.scale(1f, state.model().yUp() ? -1f : 1f, -DEPTH_SCALE);
        matrices.mulPose(Axis.XP.rotationDegrees(state.pitch()));
        matrices.mulPose(Axis.YP.rotationDegrees(state.yaw()));
        var center = state.center();
        matrices.translate(-center.x, -center.y, -center.z);
        state.model().render(matrices, collector, state.partialTicks());
    }

    @Override
    protected float getTranslateY(int height, int guiScale) {
        // 默认原点在纹理底边；预览绕画布中心旋转。
        return height / 2f;
    }

    @Override
    protected String getTextureLabel() {
        return "kizuna_inventory_ui model preview";
    }

    /**
     * 一帧预览的快照：画布 GUI 坐标 (x0, y0)–(x1, y1)，scale 为每个模型单位的 GUI 像素数，
     * yaw/pitch 为相机角度（度），center 为模型空间中的旋转中心。
     */
    record State(
            ModelPreviewComponent.Model model,
            float yaw,
            float pitch,
            Vec3 center,
            float partialTicks,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            Matrix3x2fc pose,
            ScreenRectangle scissorArea,
            ScreenRectangle bounds)
            implements PictureInPictureRenderState {
        static State of(
                ModelPreviewComponent.Model model,
                float yaw,
                float pitch,
                Vec3 center,
                float partialTicks,
                int x0,
                int y0,
                int x1,
                int y1,
                float scale,
                Matrix3x2fc pose,
                ScreenRectangle scissorArea) {
            // 外框按提交时的 GUI 矩阵变换再与裁剪求交；GUI 渲染器据此决定层叠，无外框时不绘制。
            var bounds = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose);
            if (scissorArea != null) {
                bounds = scissorArea.intersection(bounds);
            }
            return new State(
                    model,
                    yaw,
                    pitch,
                    center,
                    partialTicks,
                    x0,
                    y0,
                    x1,
                    y1,
                    scale,
                    pose,
                    scissorArea,
                    bounds);
        }
    }
}
