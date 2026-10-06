package dev.kizuna.inventoryui.window;

/** 窗口外框转场；时间单位为纳秒，输入捕获时可以立即停在当前可见位置。 */
public final class WindowMotion {
    public static final long DURATION_NANOS = 180_000_000L;
    private UiWindowManager.Rect from;
    private UiWindowManager.Rect target;
    private long started;
    private long duration;

    public WindowMotion(UiWindowManager.Rect initial) {
        from = target = initial;
    }

    public void target(UiWindowManager.Rect next, long now, boolean animate) {
        // 连续反向操作从当帧外框接续，不能跳回上一轮动画的起点。
        if (next.equals(target) && animate) {
            return;
        }
        from = sample(now);
        target = next;
        started = now;
        duration = animate ? DURATION_NANOS : 0;
    }

    public UiWindowManager.Rect sample(long now) {
        // 三次 ease-out 保留原有 180 ms 手感；关闭动态效果时直接返回目标。
        double time =
                duration == 0 ? 1 : Math.max(0, Math.min((double) (now - started) / duration, 1));
        double remaining = 1 - time;
        double eased = 1 - remaining * remaining * remaining;
        return new UiWindowManager.Rect(
                mix(from.x(), target.x(), eased),
                mix(from.y(), target.y(), eased),
                Math.max(1, mix(from.width(), target.width(), eased)),
                Math.max(1, mix(from.height(), target.height(), eased)));
    }

    public boolean settled(long now) {
        return duration == 0 || now - started >= duration;
    }

    private static int mix(int from, int to, double progress) {
        return (int) Math.round(from + ((double) to - from) * progress);
    }
}
