package dev.kizuna.inventoryui.state;

import dev.kizuna.inventoryui.contract.UiScreenScope;
import dev.kizuna.inventoryui.contract.UiStateSource;
import dev.kizuna.inventoryui.contract.UiSubscription;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 把一个状态源绑定到屏幕生命周期。首帧先读 snapshot，再订阅后续变化； scope 关闭后，已排队的旧 Store 回调也不能再进入渲染层。 */
public final class UiStateBinder {
    private static final Logger LOGGER = Logger.getLogger(UiStateBinder.class.getName());

    private UiStateBinder() {}

    public static <S> UiSubscription bind(
            UiStateSource<S> source, UiScreenScope scope, Consumer<? super S> listener) {
        // 未提供执行器时同步分发，适用于已经位于 UI 线程的状态源。
        return bind(source, scope, listener, Runnable::run);
    }

    /**
     * 在状态源与屏幕之间建立带线程边界的绑定。
     *
     * <p>生产适配器传入 Minecraft 主线程 executor；测试可以传入直接 executor。 状态源回调本身只负责提交任务，真正的 UI listener 仍由
     * executor 执行。
     */
    public static <S> UiSubscription bind(
            UiStateSource<S> source,
            UiScreenScope scope,
            Consumer<? super S> listener,
            Executor uiExecutor) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        Objects.requireNonNull(uiExecutor, "uiExecutor must not be null");

        if (!scope.runIfOpen(() -> {})) {
            throw new IllegalStateException("cannot bind state to a scope that is not open");
        }

        DispatchQueue<S> queue = new DispatchQueue<>(scope, listener, uiExecutor);
        // 首帧快照也进入同一个串行队列，避免与稍后的订阅更新乱序。
        S initial = source.snapshot();
        queue.enqueue(initial);

        UiSubscription subscription = source.subscribe(queue::enqueue);
        try {
            // 订阅完成后再读一次，补上 snapshot 与 listener 登记之间发生的更新。
            S afterSubscription = source.snapshot();
            if (!Objects.equals(queue.lastEnqueued(), afterSubscription)) {
                queue.enqueue(afterSubscription);
            }
            scope.addCleanup(subscription::close);
        } catch (Throwable failure) {
            closeAfterRegistrationFailure(subscription, failure);
        }
        return subscription;
    }

    private static void closeAfterRegistrationFailure(
            UiSubscription subscription, Throwable registrationFailure) {
        // 生命周期登记失败时仍要撤销源订阅，清理异常附加到原始失败而不覆盖它。
        try {
            subscription.close();
        } catch (Throwable closeFailure) {
            if (registrationFailure != closeFailure) {
                registrationFailure.addSuppressed(closeFailure);
            }
        }
        throwUnchecked(registrationFailure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        // 保留原始异常类型与附加清理异常，避免再包装一层丢失调用方的诊断入口。
        throw (T) failure;
    }

    /** 串行化所有 UI 更新，避免 executor 队列中的新事件越过补发快照。 */
    private static final class DispatchQueue<S> {
        private final UiScreenScope scope;
        private final Consumer<? super S> listener;
        private final Executor executor;
        private final Object lock = new Object();
        private final Deque<S> values = new ArrayDeque<>();
        private boolean drainScheduled;
        private S lastEnqueued;

        private DispatchQueue(
                UiScreenScope scope, Consumer<? super S> listener, Executor executor) {
            this.scope = scope;
            this.listener = listener;
            this.executor = executor;
        }

        private void enqueue(S value) {
            // 只有队列由空闲转为待处理时安排 drain，保证多个生产回调共用一个消费任务。
            Objects.requireNonNull(value, "state update must not be null");
            boolean schedule;
            synchronized (lock) {
                values.addLast(value);
                lastEnqueued = value;
                schedule = !drainScheduled;
                if (schedule) {
                    drainScheduled = true;
                }
            }
            if (schedule) {
                try {
                    executor.execute(this::drain);
                } catch (Throwable failure) {
                    synchronized (lock) {
                        drainScheduled = false;
                    }
                    throwUnchecked(failure);
                }
            }
        }

        private S lastEnqueued() {
            synchronized (lock) {
                return lastEnqueued;
            }
        }

        private void drain() {
            // 取值时持锁，调用 UI 时交给 scope 检查是否仍开放，已关闭窗口不消费排队事件。
            while (true) {
                S value;
                synchronized (lock) {
                    value = values.pollFirst();
                    if (value == null) {
                        drainScheduled = false;
                        return;
                    }
                }
                try {
                    scope.runIfOpen(() -> listener.accept(value));
                } catch (Throwable failure) {
                    // 单个 UI 消费者失败不能让队列永久保持 drainScheduled=true。
                    // 后续 authoritative 更新仍需继续进入主线程队列。
                    LOGGER.log(Level.SEVERE, "UI state listener failed", failure);
                }
            }
        }
    }
}
