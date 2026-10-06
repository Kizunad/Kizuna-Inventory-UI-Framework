package dev.kizuna.inventoryui.client;

/** 工作台共享尺寸，单位均为逻辑像素；绘制、输入命中及窗口内容布局使用同一份定义。 */
final class WorkspaceMetrics {
    static final int TOOLBAR_HEIGHT = 28;
    static final int BOTTOM_BAR_HEIGHT = 58;

    static final int WINDOW_HEADER_HEIGHT = 22;
    static final int WINDOW_BORDER = 2;
    static final int WINDOW_ACTION_WIDTH = 22;
    static final int WINDOW_TITLE_PADDING_X = 9;
    static final int WINDOW_TITLE_PADDING_Y = 7;
    static final int RESIZE_GRIP_SIZE = 12;

    static final int INITIAL_WINDOW_X = 20;
    static final int INITIAL_WINDOW_Y = 38;
    static final int INITIAL_WINDOW_WIDTH = 280;
    static final int INITIAL_WINDOW_HEIGHT = 160;
    static final int CASCADE_POSITION_COUNT = 5;
    static final int CASCADE_STEP = 16;

    private WorkspaceMetrics() {}
}
