package dev.kizuna.inventoryui.client;

import net.minecraft.client.input.KeyEvent;

/** 窗口根组件可实现的补充输入；普通控件优先，菜单和模态输入不会传入窗口快捷键。 */
public interface WindowInputHandler {
    /** 仅活动窗口收到未被子控件消费的按键，包含 Escape；返回 true 阻止工作台继续处理。 */
    boolean keyPressed(KeyEvent event);

    /** 所有存活窗口都收到释放，用于清理长按状态；释放本身不能执行业务操作。 */
    void keyReleased(KeyEvent event);

    /** 切换窗口、点击空白、打开菜单、离开工作台及关闭窗口时取消临时交互。 */
    void cancelInput();
}
