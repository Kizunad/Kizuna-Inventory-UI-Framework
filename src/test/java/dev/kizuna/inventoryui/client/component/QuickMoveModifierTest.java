package dev.kizuna.inventoryui.client.component;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class QuickMoveModifierTest {
    private static MouseButtonEvent leftClick(int modifiers) {
        return new MouseButtonEvent(0, 0, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers));
    }

    @Test
    void noneNeverFiresEvenWithShiftHeld() {
        assertFalse(QuickMoveModifier.NONE.held(leftClick(0)));
        assertFalse(QuickMoveModifier.NONE.held(leftClick(GLFW.GLFW_MOD_SHIFT)));
    }

    @Test
    void shiftFiresOnlyWhileShiftIsHeld() {
        assertTrue(QuickMoveModifier.SHIFT.held(leftClick(GLFW.GLFW_MOD_SHIFT)));
        assertFalse(QuickMoveModifier.SHIFT.held(leftClick(0)));
        assertFalse(QuickMoveModifier.SHIFT.held(leftClick(GLFW.GLFW_MOD_CONTROL)));
    }
}
