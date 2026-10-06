package dev.kizuna.inventoryui.contract;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** 适配器使用的默认生命周期域。关闭时先标记已关闭，再按后进先出顺序执行全部 清理，避免某个失败回调让后续清理永久遗失。 */
public final class DefaultUiScreenScope implements UiScreenScope {
    private final Object lock = new Object();
    private final Deque<Runnable> cleanups = new ArrayDeque<>();
    private boolean opened;
    private boolean closed;
    private long lastTickMs = -1L;

    @Override
    public void onOpen() {
        // 已关闭的生命周期不能复活，重开窗口应创建新的 scope。
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("cannot open a closed UI scope");
            }
            opened = true;
        }
    }

    @Override
    public void addCleanup(Runnable cleanup) {
        // 使用栈登记资源，关闭时按获取顺序的反方向释放。
        Objects.requireNonNull(cleanup, "cleanup must not be null");
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("cannot register cleanup after scope close");
            }
            cleanups.push(cleanup);
        }
    }

    @Override
    public void onTick(long nowMs) {
        // 仅开放的生命周期推进时间，时钟回退时保留最后一次有效 tick。
        synchronized (lock) {
            if (!closed && opened && nowMs >= lastTickMs) {
                lastTickMs = nowMs;
            }
        }
    }

    @Override
    public boolean runIfOpen(Runnable task) {
        // 检查与任务执行共用锁，使关闭动作不能插入两者之间。
        Objects.requireNonNull(task, "task must not be null");
        synchronized (lock) {
            if (closed || !opened) {
                return false;
            }
            task.run();
            return true;
        }
    }

    @Override
    public void close() {
        // 先阻止新任务与重复关闭，再在锁外逐项清理；失败不能跳过剩余资源。
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        Throwable primary = null;
        while (true) {
            Runnable cleanup;
            synchronized (lock) {
                cleanup = cleanups.pollFirst();
            }
            if (cleanup == null) {
                break;
            }
            try {
                cleanup.run();
            } catch (Throwable failure) {
                primary = appendFailure(primary, failure);
            }
        }
        if (primary != null) {
            throwUnchecked(primary);
        }
    }

    @Override
    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    public boolean isOpen() {
        synchronized (lock) {
            return opened && !closed;
        }
    }

    public long lastTickMs() {
        synchronized (lock) {
            return lastTickMs;
        }
    }

    private static Throwable appendFailure(Throwable primary, Throwable failure) {
        // 首个失败保留为主因，后续失败作为 suppressed；避免异常对自身进行抑制。
        if (primary == null) {
            return failure;
        }
        if (primary != failure) {
            primary.addSuppressed(failure);
        }
        return primary;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        // 清理结束后抛回原始失败，保留具体异常类型和完整的附加错误。
        throw (T) failure;
    }
}
