package dev.kizuna.inventoryui.client.component;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;

/** 通用预览相机：角度为度，时间为秒，焦点是模型包围盒中的归一化坐标。 */
public final class ModelPreviewCamera {
    private static final float DEFAULT_YAW = 25f;
    private static final float DEFAULT_PITCH = -10f;
    private static final float MIN_ZOOM = 0.25f;
    private static final float MAX_ZOOM = 4f;
    private static final float MAX_PITCH = 85f;
    private static final float FULL_TURN = 360f;
    private static final float HALF_TURN = FULL_TURN / 2;
    private static final float YAW_PER_PIXEL = 0.75f;
    private static final float PITCH_PER_PIXEL = 0.5f;
    private static final float ROTATION_PER_SECOND = 22f;
    private static final double SCROLL_FACTOR = 1.12;
    private static final float FOCUS_SECONDS = 0.65f;
    private static final float ENTRANCE_SECONDS = 1.35f;
    private static final float ENTRANCE_ZOOM = 0.62f;
    private static final float ENTRANCE_YAW_OFFSET = 135f;
    private static final float ENTRANCE_PITCH = 8f;
    private static final int FIT_PADDING = 24;
    private static final double MIN_DIAMETER = 0.001;
    private static final Vec3d CENTER = new Vec3d(0.5, 0.5, 0.5);

    private float yaw = DEFAULT_YAW;
    private float pitch = DEFAULT_PITCH;
    private float zoom = 1f;
    private boolean autoRotate = true;
    private Vec3d focus = CENTER;
    private Vec3d startFocus = CENTER;
    private Vec3d endFocus = CENTER;
    private float startYaw, endYaw, startPitch, endPitch, startZoom, endZoom;
    private float elapsed, duration;

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float zoom() {
        return zoom;
    }

    public boolean autoRotate() {
        return autoRotate;
    }

    public boolean moving() {
        return elapsed < duration;
    }

    public void autoRotate(boolean enabled) {
        // 切换手动或自动控制时结束旧的聚焦目标，保留当前视觉姿态。
        duration = 0;
        autoRotate = enabled;
    }

    public void reset() {
        // 复位焦点、角度和缩放；保留用户选择的自动旋转模式。
        yaw = DEFAULT_YAW;
        pitch = DEFAULT_PITCH;
        zoom = 1;
        focus = CENTER;
        duration = 0;
    }

    public Vec3d center(Box bounds) {
        return new Vec3d(
                bounds.minX + bounds.getXLength() * focus.x,
                bounds.minY + bounds.getYLength() * focus.y,
                bounds.minZ + bounds.getZLength() * focus.z);
    }

    public void focus(
            Vec3d target, float targetZoom, float targetYaw, float targetPitch, boolean entrance) {
        // 聚焦仅改变相机，不触及模块模型姿态；无效值必须在进入渲染矩阵前拒绝。
        Objects.requireNonNull(target);
        if (!Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)
                || !Float.isFinite(targetZoom)
                || !Float.isFinite(targetYaw)
                || !Float.isFinite(targetPitch)) {
            throw new IllegalArgumentException("camera target must be finite");
        }
        autoRotate = false;
        targetYaw = (float) ((double) targetYaw % FULL_TURN);
        if (entrance) {
            focus = CENTER;
            zoom = ENTRANCE_ZOOM;
            yaw = targetYaw - ENTRANCE_YAW_OFFSET;
            pitch = ENTRANCE_PITCH;
        }
        startFocus = focus;
        endFocus = target;
        startYaw = yaw;
        endYaw =
                yaw
                        + ((targetYaw - yaw + HALF_TURN) % FULL_TURN + FULL_TURN) % FULL_TURN
                        - HALF_TURN;
        startPitch = pitch;
        endPitch = clamp(targetPitch, -MAX_PITCH, MAX_PITCH);
        startZoom = zoom;
        endZoom = clamp(targetZoom, MIN_ZOOM, MAX_ZOOM);
        elapsed = 0;
        duration = entrance ? ENTRANCE_SECONDS : FOCUS_SECONDS;
    }

    public void settle() {
        // 降低动态效果时直接到达目标，适用于无动画界面和确定性截图。
        if (moving()) {
            advance(duration);
        }
    }

    public void advance(float seconds) {
        // 只接受单调、有限的时间增量，隐藏后恢复时由组件限制首帧跨度。
        if (!Float.isFinite(seconds) || seconds <= 0) {
            return;
        }
        if (moving()) {
            elapsed = Math.min(duration, elapsed + seconds);
            float t = elapsed / duration;
            // 五次 smoothstep 的两端速度、加速度均为零，聚焦切换不会突停。
            float eased = t * t * t * (t * (t * 6 - 15) + 10);
            focus = startFocus.lerp(endFocus, eased);
            yaw = startYaw + (endYaw - startYaw) * eased;
            pitch = startPitch + (endPitch - startPitch) * eased;
            zoom = startZoom + (endZoom - startZoom) * eased;
        } else if (autoRotate) {
            yaw = (float) ((yaw + (double) seconds * ROTATION_PER_SECOND) % FULL_TURN);
        }
    }

    public void drag(double dx, double dy) {
        // 主动拖动接管自动旋转，同时停止过渡，避免相机继续追逐旧目标。
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            return;
        }
        autoRotate = false;
        duration = 0;
        yaw = (float) ((yaw + dx * YAW_PER_PIXEL) % FULL_TURN);
        pitch = (float) Math.max(-MAX_PITCH, Math.min(MAX_PITCH, pitch + dy * PITCH_PER_PIXEL));
    }

    public void scroll(double amount) {
        // 乘法缩放保持近景和远景的滚轮手感一致，极端输入仍收敛到有效范围。
        if (!Double.isFinite(amount)) {
            return;
        }
        duration = 0;
        zoom = clamp((float) (zoom * Math.pow(SCROLL_FACTOR, amount)), MIN_ZOOM, MAX_ZOOM);
    }

    public float scale(Box bounds, int width, int height) {
        // 包围球直径不随旋转变化，任意模型转向时保持稳定的适配比例。
        double diameter =
                Math.sqrt(
                        bounds.getXLength() * bounds.getXLength()
                                + bounds.getYLength() * bounds.getYLength()
                                + bounds.getZLength() * bounds.getZLength());
        return (float)
                        (Math.max(1, Math.min(width, height) - FIT_PADDING)
                                / Math.max(MIN_DIAMETER, diameter))
                * zoom;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
