package dev.kizuna.inventoryui.client.component;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.kizuna.inventoryui.theme.ThemeTokens;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import io.wispforest.owo.ui.core.Sizing;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix3x2f;

/** 旋转、缩放和自动适配的模型画布；模型几何、纹理与渲染实现由子 JAR 提供，经 {@link ModelPreviewRenderer} 画中画绘制。 */
public final class ModelPreviewComponent extends BaseUIComponent {
    private static final float NANOS_PER_SECOND = 1_000_000_000f;
    private static final float MAX_FRAME_SECONDS = 0.1f;
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
            OwoUIGraphics context, int mouseX, int mouseY, float partialTicks, float delta) {
        // 用包围盒对角线适配任意模型，旋转后也不会因长边方向改变而突然缩放。
        // 背景画在当前层；模型交给画中画渲染器，贴回的纹理与背景外框相交，层叠在背景之上。
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
        context.guiRenderState.addPicturesInPictureState(
                ModelPreviewRenderer.State.of(
                        value,
                        camera.yaw(),
                        camera.pitch(),
                        camera.center(bounds),
                        partialTicks,
                        x,
                        y,
                        x + width,
                        y + height,
                        camera.scale(bounds, width, height),
                        new Matrix3x2f(context.pose()),
                        context.scissorStack.peek()));
    }

    @Override
    public boolean canFocus(FocusSource source) {
        return true;
    }

    @Override
    public boolean onMouseDown(MouseButtonEvent click, boolean doubled) {
        // 右键切换自动旋转，中键复位，左键拖动同时控制水平角与俯仰角。
        int button = click.button();
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
    public boolean onMouseDrag(MouseButtonEvent click, double dx, double dy) {
        // 仅按下后拖动才旋转，鼠标经过画布不会改变模型朝向。
        if (rotating) {
            camera.drag(dx, dy);
        }
        return rotating;
    }

    @Override
    public boolean onMouseUp(MouseButtonEvent click) {
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
        AABB bounds();

        default boolean yUp() {
            return true;
        }

        /** 在画中画渲染阶段调用，矩阵原点为 {@link #bounds()} 坐标系的原点；提交到 collector，不立即绘制。 */
        void render(PoseStack matrices, SubmitNodeCollector collector, float tickDelta);
    }
}
