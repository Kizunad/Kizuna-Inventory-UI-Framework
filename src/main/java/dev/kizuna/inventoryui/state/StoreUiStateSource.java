package dev.kizuna.inventoryui.state;

import dev.kizuna.inventoryui.contract.UiStateSource;
import dev.kizuna.inventoryui.contract.UiSubscription;
import dev.kizuna.inventoryui.contract.UiSubscriptions;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 将现有 Store 的 snapshot/listener 对适配为 UI source，不把 Store 字段暴露给 Screen。即使旧 Store
 * 在拆卸阶段补发一个排队回调，关闭后的句柄也会 先拦截该回调。
 */
public final class StoreUiStateSource<S> implements UiStateSource<S> {
    private final Supplier<? extends S> snapshotReader;
    private final Function<Consumer<? super S>, ? extends UiSubscription> listenerRegistrar;

    private StoreUiStateSource(
            Supplier<? extends S> snapshotReader,
            Function<Consumer<? super S>, ? extends UiSubscription> listenerRegistrar) {
        this.snapshotReader =
                Objects.requireNonNull(snapshotReader, "snapshotReader must not be null");
        this.listenerRegistrar =
                Objects.requireNonNull(listenerRegistrar, "listenerRegistrar must not be null");
    }

    public static <S> StoreUiStateSource<S> pullOnOpen(Supplier<? extends S> snapshotReader) {
        // 只读型源不监听后续变化，用已关闭句柄明确表示没有需要释放的监听器。
        return new StoreUiStateSource<>(snapshotReader, ignored -> UiSubscriptions.closed());
    }

    public static <S> StoreUiStateSource<S> push(
            Supplier<? extends S> snapshotReader,
            Function<Consumer<? super S>, ? extends UiSubscription> listenerRegistrar) {
        // 宿主提供读快照和登记订阅两项能力，视图不需要知道原 Store 的具体类型。
        return new StoreUiStateSource<>(snapshotReader, listenerRegistrar);
    }

    @Override
    public S snapshot() {
        // 空状态必须由领域模型显式表达，不能用 null 让视图猜测是否仍在加载。
        return Objects.requireNonNull(snapshotReader.get(), "Store snapshot must not be null");
    }

    @Override
    public UiSubscription subscribe(Consumer<? super S> listener) {
        // 先在包装层屏蔽已关闭回调，再释放源订阅，避免源清理期间补发事件进入旧界面。
        Objects.requireNonNull(listener, "listener must not be null");
        AtomicBoolean closed = new AtomicBoolean();
        UiSubscription delegate =
                Objects.requireNonNull(
                        listenerRegistrar.apply(
                                value -> {
                                    if (!closed.get()) {
                                        listener.accept(value);
                                    }
                                }),
                        "listener registrar must return a subscription");
        UiSubscription guarded =
                UiSubscriptions.once(
                        () -> {
                            closed.set(true);
                            delegate.close();
                        });
        if (delegate.isClosed()) {
            guarded.close();
        }
        return guarded;
    }
}
