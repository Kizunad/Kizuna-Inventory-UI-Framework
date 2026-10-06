package dev.kizuna.inventoryui.window;

import dev.kizuna.inventoryui.contract.DefaultUiScreenScope;
import dev.kizuna.inventoryui.contract.UiScreenScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 窗口 identity、z-order、输入捕获和 viewport 约束的唯一实现。 */
public final class UiWindowManager {
    private final Map<WindowKey, WindowState> windows = new LinkedHashMap<>();
    private WindowKey capturedKey;
    private double captureOffsetX;
    private double captureOffsetY;
    private boolean dragging;
    private int viewportWidth;
    private int viewportHeight;
    private long generation;
    private boolean clearing;

    public UiWindowManager(int viewportWidth, int viewportHeight) {
        resizeViewport(viewportWidth, viewportHeight);
    }

    public synchronized WindowState openOrFocus(
            UiWindowDefinition definition, WindowKey key, Rect initialBounds) {
        // 窗口身份包含连接代数；清理中或属于旧连接的请求不能重新挂回窗口。
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (clearing || key.connectionGeneration() != generation) {
            throw new IllegalStateException("window belongs to an inactive generation");
        }
        if (!definition.windowType().equals(key.windowType())) {
            throw new IllegalArgumentException("window key type does not match definition");
        }
        WindowState existing = windows.get(key);
        // 同一身份始终复用同一实例及其订阅，最小化后再打开也只恢复和聚焦。
        if (existing != null) {
            existing.minimized = false;
            focus(key);
            return existing;
        }
        WindowState created =
                new WindowState(
                        key,
                        definition,
                        Objects.requireNonNull(initialBounds, "initialBounds must not be null"),
                        new DefaultUiScreenScope());
        created.scope().onOpen();
        created.bounds(clamp(initialBounds, definition));
        windows.put(key, created);
        focus(key);
        return created;
    }

    public synchronized boolean contains(WindowKey key) {
        return windows.containsKey(Objects.requireNonNull(key, "key must not be null"));
    }

    public synchronized boolean focus(WindowKey key) {
        // LinkedHashMap 的尾部代表最上层；移出再插入统一更新绘制与命中层序。
        WindowState state = windows.remove(Objects.requireNonNull(key, "key must not be null"));
        if (state == null) {
            return false;
        }
        windows.put(key, state);
        return true;
    }

    public synchronized WindowState hitTest(double x, double y) {
        // 从最上层向后命中，避免点击穿透到被遮挡或已最小化的窗口。
        List<WindowState> states = new ArrayList<>(windows.values());
        for (int index = states.size() - 1; index >= 0; index--) {
            WindowState state = states.get(index);
            if (!state.closed() && !state.minimized() && state.bounds().contains(x, y)) {
                return state;
            }
        }
        return null;
    }

    public synchronized boolean beginDrag(double x, double y) {
        // 坐标入口先命中最上层窗口，再建立拖动捕获。
        WindowState state = capture(x, y);
        return startDrag(state, x, y);
    }

    public synchronized boolean beginDrag(WindowKey key, double x, double y) {
        // 已确定窗口身份的适配器可以直接捕获，无需再次通过屏幕坐标查找。
        return startDrag(capture(key), x, y);
    }

    private boolean startDrag(WindowState state, double x, double y) {
        // 保留按下点相对外框的偏移，拖动开始时窗口不会跳到鼠标左上角。
        if (state == null || !editable(state)) {
            return false;
        }
        dragging = true;
        captureOffsetX = x - state.bounds().x();
        captureOffsetY = y - state.bounds().y();
        return true;
    }

    public synchronized boolean dragTo(double x, double y) {
        // 整次拖动只更新被捕获的窗口；鼠标经过其他窗口时不转移目标。
        if (capturedKey == null || !dragging) {
            return false;
        }
        WindowState state = windows.get(capturedKey);
        if (state == null || state.closed()) {
            cancelCapture();
            return false;
        }
        Rect requested =
                new Rect(
                        (int) Math.round(x - captureOffsetX),
                        (int) Math.round(y - captureOffsetY),
                        state.desiredBounds.width(),
                        state.desiredBounds.height());
        Rect effective = clamp(requested, state.definition());
        // 位置必须在视口内，但保留期望尺寸，供视口变大时恢复。
        state.desiredBounds =
                new Rect(effective.x(), effective.y(), requested.width(), requested.height());
        state.bounds(effective);
        return true;
    }

    public synchronized boolean endDrag() {
        // 释放时消费已有捕获，阻止同一次释放继续作用到下层窗口。
        if (capturedKey == null) {
            return false;
        }
        cancelCapture();
        return true;
    }

    public synchronized void cancelCapture() {
        // 关窗、失焦和断线共用此入口，确保不存在没有目标的残留拖动态。
        capturedKey = null;
        dragging = false;
    }

    public synchronized WindowState capture(double x, double y) {
        // 空白处捕获也会撤销旧目标，不能延续上一次的输入会话。
        WindowState state = hitTest(x, y);
        return capture(state == null ? null : state.key());
    }

    public synchronized WindowState capture(WindowKey key) {
        // 每次捕获替换旧目标，并让获得输入的窗口同步置顶。
        cancelCapture();
        WindowState state = windows.get(key);
        if (state != null && state.minimized()) {
            return null;
        }
        if (state != null) {
            focus(state.key());
            capturedKey = state.key();
        }
        return state;
    }

    public synchronized boolean minimize(WindowKey key) {
        // 最小化保留实例和订阅，但必须释放鼠标捕获。
        WindowState state = windows.get(key);
        if (!editable(state)) {
            return false;
        }
        state.minimized = true;
        if (key.equals(capturedKey)) {
            cancelCapture();
        }
        return true;
    }

    public synchronized boolean restore(WindowKey key) {
        // 恢复同时置顶；不创建新实例，也不重复初始化窗口内容。
        WindowState state = windows.get(key);
        if (state == null) {
            return false;
        }
        state.minimized = false;
        return focus(key);
    }

    public synchronized boolean pin(WindowKey key, boolean pinned) {
        // 工位窗口依赖会话上下文，不允许通过固定状态变成常驻 HUD。
        WindowState state = windows.get(key);
        if (!editable(state)) {
            return false;
        }
        if (!state.canPin()) {
            return false;
        }
        state.pinned = pinned;
        return true;
    }

    /** 用户在转场中抓住窗口时，从当帧可见外框接续交互。 */
    public synchronized void settleAt(WindowKey key, Rect displayed) {
        // 使用当帧显示位置作为新起点，避免抓住转场窗口时跳回旧目标位置。
        WindowState state = windows.get(key);
        if (!editable(state)) {
            return;
        }
        Rect effective = clamp(displayed, state.definition);
        state.bounds(effective);
        state.desiredBounds = effective;
    }

    public synchronized boolean resize(WindowKey key, String width, String height) {
        // 文本输入无效时保留原尺寸；有效时分别保存期望尺寸和视口内实际尺寸。
        try {
            int w = Integer.parseInt(width.strip());
            int h = Integer.parseInt(height.strip());
            if (w <= 0 || h <= 0) {
                return false;
            }
            WindowState state = windows.get(key);
            if (!editable(state)) {
                return false;
            }
            state.desiredBounds =
                    new Rect(
                            state.bounds.x(),
                            state.bounds.y(),
                            Math.max(state.definition.minimumWidth(), w),
                            Math.max(state.definition.minimumHeight(), h));
            state.bounds(clamp(state.desiredBounds, state.definition));
            return true;
        } catch (NumberFormatException | NullPointerException invalid) {
            return false;
        }
    }

    private static boolean editable(WindowState state) {
        // 系统窗口不可由用户改布局，其余窗口也必须显式声明可编辑的能力。
        return state != null
                && !state.definition.supports(UiWindowDefinition.Capability.SYSTEM)
                && (state.definition.supports(UiWindowDefinition.Capability.WINDOW)
                        || state.definition.supports(UiWindowDefinition.Capability.STATION)
                        || state.definition.supports(UiWindowDefinition.Capability.OFFER));
    }

    public synchronized boolean close(WindowKey key) {
        // 先撤销可查到的窗口身份，再运行外部清理，防止回调继续找到半关闭实例。
        WindowState state = windows.remove(Objects.requireNonNull(key, "key must not be null"));
        if (state == null) {
            return false;
        }
        if (key.equals(capturedKey)) {
            cancelCapture();
        }
        state.close();
        return true;
    }

    public synchronized void resizeViewport(int width, int height) {
        // 视口缩小时只压缩实际外框，变大后仍根据 desiredBounds 恢复布局。
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("viewport size must be positive");
        }
        viewportWidth = width;
        viewportHeight = height;
        for (WindowState state : windows.values()) {
            state.bounds(clamp(state.desiredBounds, state.definition()));
        }
    }

    public synchronized WindowKey key(String windowType, String identity) {
        // 统一附加当前代数，调用方无需自己跟踪断线后的窗口身份变化。
        return new WindowKey(windowType, generation, identity);
    }

    /** 先撤销全部 identity，再执行全部清理；清理失败也不能保留旧连接的窗口。 */
    public synchronized void reset() {
        // 先使全部旧 key 失效；即使某个清理回调失败，也不能留下旧窗口可继续操作。
        generation = Math.incrementExact(generation);
        cancelCapture();
        List<WindowState> previous = snapshot();
        windows.clear();
        clearing = true;
        // 聚合所有清理并阻止清理回调重入开窗，最后恢复新连接可创建窗口的状态。
        DefaultUiScreenScope cleanup = new DefaultUiScreenScope();
        previous.forEach(state -> cleanup.addCleanup(state::close));
        try {
            cleanup.close();
        } finally {
            clearing = false;
        }
    }

    public synchronized void tick(long nowMs) {
        // 遍历副本，避免生命周期处理影响正在遍历的窗口集合。
        snapshot().forEach(state -> state.scope().onTick(nowMs));
    }

    public synchronized List<WindowState> snapshot() {
        return List.copyOf(windows.values());
    }

    public synchronized WindowKey capturedKey() {
        return capturedKey;
    }

    private Rect clamp(Rect bounds, UiWindowDefinition definition) {
        // 视口容不下最小尺寸时优先保持可见，再把左上角约束到可容纳的位置。
        int width = Math.min(viewportWidth, Math.max(definition.minimumWidth(), bounds.width()));
        int height =
                Math.min(viewportHeight, Math.max(definition.minimumHeight(), bounds.height()));
        int x = Math.max(0, Math.min(bounds.x(), viewportWidth - width));
        int y = Math.max(0, Math.min(bounds.y(), viewportHeight - height));
        return new Rect(x, y, width, height);
    }

    public record WindowKey(String windowType, long connectionGeneration, String identity) {
        public WindowKey {
            // 类型、业务身份和连接代数共同构成 key，避免重连后复用旧实例。
            windowType = requireText(windowType, "windowType");
            identity = requireText(identity, "identity");
            if (connectionGeneration < 0) {
                throw new IllegalArgumentException("connectionGeneration must not be negative");
            }
        }

        private static String requireText(String value, String name) {
            // 窗口身份统一去除首尾空白，避免外观相同的输入生成两个不同实例。
            Objects.requireNonNull(value, name + " must not be null");
            String normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return normalized;
        }
    }

    public record Rect(int x, int y, int width, int height) {
        public Rect {
            // 窗口外框必须有实际面积，空窗口不能参与命中和布局。
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("rectangle size must be positive");
            }
        }

        public boolean contains(double pointX, double pointY) {
            // 右边和下边采用开区间，两个紧邻窗口不会同时命中共享边界。
            return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
        }
    }

    public static final class WindowState {
        private final WindowKey key;
        private final UiWindowDefinition definition;
        private final UiScreenScope scope;
        private Rect bounds;
        private Rect desiredBounds;
        private boolean closed;
        private boolean minimized;
        private boolean pinned;

        private WindowState(
                WindowKey key, UiWindowDefinition definition, Rect bounds, UiScreenScope scope) {
            this.key = key;
            this.definition = definition;
            this.bounds = bounds;
            this.desiredBounds = bounds;
            this.scope = scope;
        }

        public WindowKey key() {
            return key;
        }

        public UiWindowDefinition definition() {
            return definition;
        }

        public UiScreenScope scope() {
            return scope;
        }

        public Rect bounds() {
            return bounds;
        }

        private void bounds(Rect value) {
            bounds = value;
        }

        public boolean closed() {
            return closed;
        }

        public boolean minimized() {
            return minimized;
        }

        public boolean pinned() {
            return pinned;
        }

        public boolean canPin() {
            // 按能力决定入口和动作，渲染层不另写一套工位例外规则。
            return editable(this) && !definition.supports(UiWindowDefinition.Capability.STATION);
        }

        public boolean hudVisible() {
            return pinned && !minimized && !closed;
        }

        public Rect desiredBounds() {
            return desiredBounds;
        }

        private void close() {
            // 先标记关闭再释放订阅，即使清理抛错或重入也不会重复执行关闭动作。
            if (!closed) {
                closed = true;
                scope.close();
            }
        }
    }
}
