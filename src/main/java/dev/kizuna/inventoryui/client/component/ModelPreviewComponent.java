package dev.kizuna.inventoryui.client.component;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.kizuna.inventoryui.theme.ThemeTokens;

import io.wispforest.owo.ui.base.BaseComponent;
import io.wispforest.owo.ui.core.OwoUIDrawContext;
import io.wispforest.owo.ui.core.Sizing;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;

import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** 旋转、缩放和自动适配的模型画布；模型几何、纹理与渲染实现由子 JAR 提供。 */
public final class ModelPreviewComponent extends BaseComponent {
    private static final float NANOS_PER_SECOND = 1_000_000_000f;
    private static final float MAX_FRAME_SECONDS = 0.1f;
    private static final float MODEL_DEPTH = 30f;
    private static final float DEPTH_SCALE = 0.1f;
    private final Supplier<Model> model;
    private final ToIntFunction<String> colors;
    private final ModelPreviewCamera camera = new ModelPreviewCamera();
    private long previousFrame;
    private boolean rotating;

    public ModelPreviewComponent(
            int width, int height, Supplier<Model> model, ToIntFunction<String> colors) {
        // 画布只依赖渲染接口，不缓存特定物品或玩家，切换模型由供应方完成。
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("preview dimensions must be positive");
        }
        this.model = Objects.requireNonNull(model);
        this.colors = Objects.requireNonNull(colors);
        sizing(Sizing.fixed(width), Sizing.fixed(height));
    }

    @Override
    public void draw(
            OwoUIDrawContext context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 用包围盒对角线适配任意模型，旋转后也不会因长边方向改变而突然缩放。
        context.fill(
                x, y, x + width, y + height, colors.applyAsInt(ThemeTokens.PREVIEW_BACKGROUND));
        var value = model.get();
        if (value == null) {
            return;
        }
        var bounds = value.bounds();
        long now = System.nanoTime();
        if (previousFrame != 0) {
            camera.advance(Math.min(MAX_FRAME_SECONDS, (now - previousFrame) / NANOS_PER_SECOND));
        }
        previousFrame = now;
        float scale = camera.scale(bounds, width, height);
        var matrices = context.getMatrices();
        var buffers = MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();
        context.draw();
        context.enableScissor(x, y, x + width, y + height);
        matrices.push();
        try {
            matrices.translate(x + width / 2f, y + height / 2f, MODEL_DEPTH);
            matrices.scale(scale, value.yUp() ? -scale : scale, scale * DEPTH_SCALE);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.pitch()));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.yaw()));
            var center = camera.center(bounds);
            matrices.translate(-center.x, -center.y, -center.z);
            DiffuseLighting.enableGuiDepthLighting();
            RenderSystem.enableDepthTest();
            value.render(matrices, buffers, partialTicks);
        } finally {
            // 包括模块绘制异常在内，顶点缓冲、矩阵、裁剪和光照都必须恢复。
            try {
                buffers.draw();
            } finally {
                matrices.pop();
                context.disableScissor();
                RenderSystem.disableDepthTest();
                DiffuseLighting.enableGuiDepthLighting();
            }
        }
    }

    @Override
    public boolean canFocus(FocusSource source) {
        return true;
    }

    @Override
    public boolean onMouseDown(double x, double y, int button) {
        // 右键切换自动旋转，中键复位，左键拖动同时控制水平角与俯仰角。
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            camera.autoRotate(!camera.autoRotate());
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            camera.reset();
            return true;
        }
        rotating = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        return rotating;
    }

    @Override
    public boolean onMouseDrag(double x, double y, double dx, double dy, int button) {
        // 仅按下后拖动才旋转，鼠标经过画布不会改变模型朝向。
        if (rotating) {
            camera.drag(dx, dy);
        }
        return rotating;
    }

    @Override
    public boolean onMouseUp(double x, double y, int button) {
        boolean handled = rotating;
        rotating = false;
        return handled;
    }

    @Override
    public boolean onMouseScroll(double x, double y, double amount) {
        // 限定缩放范围，使滚轮输入不会生成负缩放或不可恢复的巨大模型。
        camera.scroll(amount);
        return true;
    }

    @Override
    public void onFocusLost() {
        rotating = false;
        super.onFocusLost();
    }

    public ModelPreviewCamera camera() {
        return camera;
    }

    public interface Model {
        Box bounds();

        default boolean yUp() {
            return true;
        }

        void render(MatrixStack matrices, VertexConsumerProvider buffers, float tickDelta);
    }
}
