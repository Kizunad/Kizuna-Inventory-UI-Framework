package dev.kizuna.inventoryui.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.client.component.ModelPreviewCamera;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import org.junit.jupiter.api.Test;

class ModelPreviewCameraTest {
    @Test
    void manualControlInterruptsMotionAndKeepsCameraRecoverable() {
        // 手动输入必须接管自动和聚焦，极端滚轮/拖动不能产生不可恢复的姿态。
        var camera = new ModelPreviewCamera();
        camera.advance(1);
        camera.focus(new Vec3d(0.5, 1, 0.5), 2, 10, -20, false);
        camera.advance(0.1f);
        float before = camera.yaw();
        camera.drag(20, 30);
        assertFalse(camera.moving());
        assertFalse(camera.autoRotate());
        assertNotEquals(before, camera.yaw());
        camera.scroll(Double.MAX_VALUE);
        assertTrue(Float.isFinite(camera.zoom()));
        camera.scroll(-Double.MAX_VALUE);
        assertTrue(camera.zoom() > 0);
        camera.reset();
        assertEquals(1, camera.zoom());
    }

    @Test
    void normalizedFocusScalesWithBoundsAndRejectsInvalidMatrices() {
        var camera = new ModelPreviewCamera();
        var target = new Vec3d(0.5, 1, 0.25);
        camera.focus(target, 2, 0, -20, true);
        camera.settle();
        assertEquals(new Vec3d(12, 26, 32), camera.center(new Box(10, 20, 30, 14, 26, 38)));
        assertThrows(
                IllegalArgumentException.class,
                () -> camera.focus(new Vec3d(Double.NaN, 0, 0), 1, 0, 0, false));
    }
}
