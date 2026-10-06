package dev.kizuna.inventoryui.inventory;

import dev.kizuna.inventoryui.contract.UiStateSource;
import dev.kizuna.inventoryui.contract.UiSubscription;
import dev.kizuna.inventoryui.contract.UiSubscriptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 每连接一份的权威快照状态；所有窗口和 HUD 订阅同一个实例。 */
public final class InventoryState implements UiStateSource<InventorySnapshot> {
    private InventorySnapshot current;
    private final List<Consumer<? super InventorySnapshot>> listeners = new ArrayList<>();

    public InventoryState(InventorySnapshot initial) {
        // 新状态实例绑定到初始快照的状态流；切换状态流应重新创建状态实例。
        current = Objects.requireNonNull(initial, "initial snapshot must not be null");
    }

    @Override
    public synchronized InventorySnapshot snapshot() {
        return current;
    }

    @Override
    public synchronized UiSubscription subscribe(Consumer<? super InventorySnapshot> listener) {
        // 订阅只接收后续更新，首次内容由调用方读取 snapshot；释放句柄只移除自己的监听器。
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        return UiSubscriptions.once(
                () -> {
                    synchronized (this) {
                        listeners.remove(listener);
                    }
                });
    }

    /** 旧 revision 和其他状态流不进入当前 UI；回调在调用者线程执行。 */
    public void replace(InventorySnapshot next) {
        Objects.requireNonNull(next, "next snapshot must not be null");
        List<Consumer<? super InventorySnapshot>> notification;
        synchronized (this) {
            // 在同一临界区校验并替换，避免旧版本或其他连接的数据覆盖当前库存。
            if (!current.streamId().equals(next.streamId())) {
                throw new IllegalArgumentException("snapshot stream mismatch");
            }
            if (next.revision() <= current.revision()) {
                throw new IllegalArgumentException("snapshot revision must advance");
            }
            current = next;
            notification = List.copyOf(listeners);
        }
        notifyListeners(notification, next);
    }

    public boolean applyDelta(InventoryDelta delta, Runnable requestFullSnapshot) {
        Objects.requireNonNull(delta);
        Objects.requireNonNull(requestFullSnapshot);
        InventorySnapshot next = null;
        List<Consumer<? super InventorySnapshot>> notification = List.of();
        synchronized (this) {
            // 同一临界区内对拍基础版本并应用，另一更新不能插入校验和替换之间。
            if (current.streamId().equals(delta.streamId())
                    && current.revision() == delta.baseRevision()) {
                next = delta.apply(current);
                current = next;
                notification = List.copyOf(listeners);
            }
        }
        if (next == null) {
            // 缺口不猜补；回调在锁外申请完整状态，由模块映射到自己的协议。
            requestFullSnapshot.run();
            return false;
        }
        notifyListeners(notification, next);
        return true;
    }

    private static void notifyListeners(
            List<Consumer<? super InventorySnapshot>> notification, InventorySnapshot next) {
        // 在锁外通知，避免 UI 回调占用状态锁；某个视图失败仍要通知其余视图。
        RuntimeException firstFailure = null;
        for (Consumer<? super InventorySnapshot> listener : notification) {
            try {
                listener.accept(next);
            } catch (RuntimeException failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(failure);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
