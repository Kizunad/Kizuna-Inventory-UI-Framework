package dev.kizuna.inventoryui.window;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WindowMotionTest {
    @Test
    void reversingAnimationContinuesFromVisibleBoundsAndCanBeDisabled() {
        // 保护快速最小化再恢复时的连续性，以及降低动态效果时的立即到位。
        var start = new UiWindowManager.Rect(20, 30, 280, 160);
        var anchor = new UiWindowManager.Rect(100, 400, 112, 20);
        var motion = new WindowMotion(start);
        motion.target(anchor, 0, true);
        long middle = WindowMotion.DURATION_NANOS / 2;
        var visible = motion.sample(middle);
        assertNotEquals(start, visible);
        assertNotEquals(anchor, visible);
        motion.target(start, middle, true);
        assertEquals(visible, motion.sample(middle), "反向转场不得跳回旧起点");
        assertEquals(start, motion.sample(middle + WindowMotion.DURATION_NANOS));
        motion.target(anchor, middle, false);
        assertEquals(anchor, motion.sample(middle), "关闭动画必须立即到位");
    }
}
