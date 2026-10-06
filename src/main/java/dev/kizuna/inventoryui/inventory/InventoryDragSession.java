package dev.kizuna.inventoryui.inventory;

import dev.kizuna.inventoryui.contract.UiStateSource;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 多个网格、装备槽和快捷栏共用的拖放会话；不持有业务规则，也不提前改写库存。 */
public final class InventoryDragSession {
    private UiStateSource<InventorySnapshot> source;
    private Payload payload;
    private Object owner;
    private BooleanSupplier valid = () -> false;

    public void begin(
            Object owner,
            UiStateSource<InventorySnapshot> source,
            String instanceId,
            int count,
            int grabRow,
            int grabColumn) {
        // 从权威快照抓取，并保留抓取格偏移；不把鼠标抓到的格子误当成物品锚点。
        var snapshot = Objects.requireNonNull(source).snapshot();
        var placement =
                snapshot.placements().stream()
                        .filter(value -> value.item().instanceId().equals(instanceId))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("unknown item: " + instanceId));
        int w = placement.rotated() ? placement.item().height() : placement.item().width();
        int h = placement.rotated() ? placement.item().width() : placement.item().height();
        if (count <= 0
                || count > placement.item().count()
                || grabRow < 0
                || grabRow >= h
                || grabColumn < 0
                || grabColumn >= w) {
            throw new IllegalArgumentException("invalid drag count or grab offset");
        }
        this.owner = Objects.requireNonNull(owner);
        this.source = source;
        this.valid =
                () ->
                        source.snapshot().streamId().equals(snapshot.streamId())
                                && source.snapshot().revision() == snapshot.revision();
        payload =
                new Payload(
                        snapshot.streamId(),
                        snapshot.revision(),
                        placement.item(),
                        new GridOrigin(
                                placement.containerId(), placement.row(), placement.column()),
                        count,
                        placement.rotated(),
                        grabRow,
                        grabColumn);
    }

    public void beginSlot(
            Object owner,
            String streamId,
            long revision,
            String slotId,
            InventorySnapshot.Item item,
            int count,
            BooleanSupplier valid) {
        // 装备和引用槽不必伪装成占格容器；模块提供来源身份及失效判断。
        var next =
                new Payload(streamId, revision, item, new SlotOrigin(slotId), count, false, 0, 0);
        Objects.requireNonNull(valid);
        this.owner = Objects.requireNonNull(owner);
        this.source = null;
        this.valid = valid;
        this.payload = next;
    }

    public Payload current() {
        // 绘制、命中和释放均重新检查版本，覆盖最后一帧之后收到快照的竞态。
        if (payload != null) {
            if (!valid.getAsBoolean()) {
                cancel();
            }
        }
        return payload;
    }

    public boolean belongsTo(UiStateSource<InventorySnapshot> target) {
        // 只有共享同一状态源的容器才能做原子跨容器移动，禁止混合两个独立会话。
        return current() != null && source == target;
    }

    public void rotate() {
        // 旋转从锚点重新抓取，避免横长物品旋转后旧偏移跑到占格外。
        var value = current();
        if (value != null) {
            payload =
                    new Payload(
                            value.streamId(),
                            value.baseRevision(),
                            value.item(),
                            value.origin(),
                            value.count(),
                            !value.rotated(),
                            0,
                            0);
        }
    }

    public void count(int count) {
        // 数量选择只改变意图；原物品在服务器确认之前始终保留完整数量。
        var value = current();
        if (value != null) {
            int selected = Math.max(1, Math.min(value.item().count(), count));
            payload =
                    new Payload(
                            value.streamId(),
                            value.baseRevision(),
                            value.item(),
                            value.origin(),
                            selected,
                            value.rotated(),
                            value.grabRow(),
                            value.grabColumn());
        }
    }

    public boolean canMove(
            UiStateSource<InventorySnapshot> target, String container, int row, int column) {
        // 未知或已经撤销的目标属于正常取消，不把过期组件变成渲染异常。
        if (!belongsTo(target)) {
            return false;
        }
        var snapshot = target.snapshot();
        if (snapshot.containers().stream().noneMatch(value -> value.id().equals(container))) {
            return false;
        }
        return InventoryGrid.canPlace(
                snapshot,
                payload.item().instanceId(),
                container,
                row,
                column,
                payload.rotated(),
                payload.count());
    }

    public boolean move(
            UiStateSource<InventorySnapshot> target,
            String container,
            int row,
            int column,
            Consumer<InventoryGrid.MoveIntent> sink) {
        // 先完成全部本地检查，再撤销拖动并交付一次；回调重入也不能重复提交。
        if (!canMove(target, container, row, column)) {
            cancel();
            return false;
        }
        var placement =
                target.snapshot().placements().stream()
                        .filter(
                                value ->
                                        value.item()
                                                .instanceId()
                                                .equals(payload.item().instanceId()))
                        .findFirst()
                        .orElseThrow();
        if (placement.containerId().equals(container)
                && placement.row() == row
                && placement.column() == column
                && placement.rotated() == payload.rotated()
                && placement.item().count() == payload.count()) {
            // 单击或放回原位不发送无效移动，双击详情也无需先触发服务器版本变化。
            cancel();
            return true;
        }
        var intent =
                InventoryGrid.move(
                        target.snapshot(),
                        payload.item().instanceId(),
                        container,
                        row,
                        column,
                        payload.rotated(),
                        payload.count());
        cancel();
        sink.accept(intent);
        return true;
    }

    public void consume(Consumer<Payload> sink) {
        // 装备、绑定和自定义目标只消费通用载荷；具体权限和事务由目标模块处理。
        var value = current();
        cancel();
        if (value != null) {
            sink.accept(value);
        }
    }

    public void cancel(Object owner) {
        // 某个视图失焦或卸载只能取消它发起的拖动，不能干扰另一个窗口的新交互。
        if (this.owner == owner) {
            cancel();
        }
    }

    public void cancel() {
        payload = null;
        source = null;
        owner = null;
        valid = () -> false;
    }

    /** 来源身份、基础版本、请求数量、旋转和抓取偏移；行列以格为单位。 */
    public record Payload(
            // 来源状态流；不得用物品模板 ID 代替。
            String streamId,
            // 抓取时的权威版本，用于检测旧输入。
            long baseRevision,
            // 实例身份和显示数据，数量仍保留权威完整堆叠值。
            InventorySnapshot.Item item,
            // 网格锚点或独立槽位身份，不包含业务装备枚举。
            Origin origin,
            // 本次请求数量，可小于物品完整数量。
            int count,
            // 本次落点方向；不改写物品的原始宽高。
            boolean rotated,
            // 抓取位置相对物品锚点的行列偏移，单位为格。
            int grabRow,
            int grabColumn) {
        public Payload {
            // 来源类型与物品身份分离；外部槽位也必须携带有效的数量和连接内版本。
            if (Objects.requireNonNull(streamId).isBlank()
                    || baseRevision < 0
                    || count <= 0
                    || count > item.count()
                    || grabRow < 0
                    || grabColumn < 0) {
                throw new IllegalArgumentException("invalid drag payload");
            }
            Objects.requireNonNull(origin);
        }
    }

    public sealed interface Origin permits GridOrigin, SlotOrigin {}

    public record GridOrigin(String containerId, int row, int column) implements Origin {
        public GridOrigin {
            Objects.requireNonNull(containerId);
            if (row < 0 || column < 0) {
                throw new IllegalArgumentException("grid origin must be non-negative");
            }
        }
    }

    public record SlotOrigin(String slotId) implements Origin {
        public SlotOrigin {
            if (Objects.requireNonNull(slotId).isBlank()) {
                throw new IllegalArgumentException("slot identity is required");
            }
        }
    }

    /** 外部装备槽到网格的意图；来源和目标各自携带状态版本，业务事务由模块实现。 */
    public record GridDrop(
            // 含来源版本的完整拖动载荷。
            Payload source,
            // 目标状态身份与版本，可能与来源不同。
            String destinationStreamId,
            long destinationRevision,
            String containerId,
            // 目标锚点，单位为格，从零开始。
            int row,
            int column) {}
}
