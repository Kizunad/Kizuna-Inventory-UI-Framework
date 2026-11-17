package dev.kizuna.inventoryui.client.component;

import net.minecraft.client.input.MouseButtonEvent;

/** 左键点物品时，算不算「快速移动」由模块判定；框架不认定任何一个键。 */
@FunctionalInterface
public interface QuickMoveModifier {
    /** 默认：从不触发，左键照常开始拖动。 */
    QuickMoveModifier NONE = event -> false;

    /** 现成的一种：按住 Shift。要这个习惯的模块显式传它。 */
    QuickMoveModifier SHIFT = MouseButtonEvent::hasShiftDown;

    /** 这一下左键按下时，模块自己的快速移动修饰键是不是按着。 */
    boolean held(MouseButtonEvent event);
}
