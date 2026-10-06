package dev.kizuna.inventoryui.contract;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 创建恰好执行一次清理的订阅句柄。 */
public final class UiSubscriptions {
    private UiSubscriptions() {}

    public static UiSubscription once(Runnable closer) {
        // 为任意清理动作增加幂等关闭保护，调用方可以安全地在多个退出路径释放。
        return new OnceSubscription(Objects.requireNonNull(closer, "closer must not be null"));
    }

    public static UiSubscription closed() {
        // 空订阅也返回真实句柄，调用方无需用 null 区分是否存在资源。
        UiSubscription subscription = once(() -> {});
        subscription.close();
        return subscription;
    }

    /** 把多个订阅合并为一个句柄。关闭顺序与登记顺序相反；单个关闭失败不会 阻断其余订阅清理，后续失败按执行顺序挂到首个异常上。 */
    public static UiSubscription combine(UiSubscription... subscriptions) {
        // 先验证并复制整组句柄，防止关闭时受到外部数组修改影响。
        Objects.requireNonNull(subscriptions, "subscriptions must not be null");
        List<UiSubscription> delegates =
                Arrays.stream(subscriptions)
                        .map(
                                subscription ->
                                        Objects.requireNonNull(
                                                subscription, "subscription must not be null"))
                        .toList();
        return once(() -> closeAll(delegates));
    }

    private static void closeAll(List<UiSubscription> subscriptions) {
        // 按登记逆序清理全部订阅，某一项失败不影响其他项释放。
        Throwable primary = null;
        for (int index = subscriptions.size() - 1; index >= 0; index--) {
            try {
                subscriptions.get(index).close();
            } catch (Throwable failure) {
                if (primary == null) {
                    primary = failure;
                } else if (primary != failure) {
                    primary.addSuppressed(failure);
                }
            }
        }
        if (primary != null) {
            throwUnchecked(primary);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
        // 直接传播原异常类型，调用方可以读取聚合在其中的 suppressed 失败。
        throw (T) failure;
    }

    private static final class OnceSubscription implements UiSubscription {
        private final Runnable closer;
        private boolean closed;

        private OnceSubscription(Runnable closer) {
            this.closer = closer;
        }

        @Override
        public synchronized void close() {
            // 在执行外部清理前标记关闭，避免回调重入或抛错后再次释放同一资源。
            if (closed) {
                return;
            }
            closed = true;
            closer.run();
        }

        @Override
        public synchronized boolean isClosed() {
            return closed;
        }
    }
}
