package dev.kizuna.inventoryui.protocol;

import dev.kizuna.inventoryui.inventory.InventoryDelta;
import dev.kizuna.inventoryui.inventory.InventoryGrid;
import dev.kizuna.inventoryui.inventory.InventorySnapshot;
import dev.kizuna.inventoryui.protocol.pb.InventoryProtocol;

import java.util.LinkedHashSet;

/** inventory.proto 与不可变显示模型之间的适配；不注册任何业务消息或服务端事务。 */
public final class InventoryWire {
    public static final UiWire.Codec<InventorySnapshot> SNAPSHOT =
            UiWire.Codec.protobuf(
                    InventoryProtocol.InventorySnapshot.getDefaultInstance(),
                    InventoryWire::snapshotToProto,
                    InventoryWire::snapshotFromProto);
    public static final UiWire.Codec<InventoryDelta> DELTA =
            UiWire.Codec.protobuf(
                    InventoryProtocol.InventoryDelta.getDefaultInstance(),
                    InventoryWire::deltaToProto,
                    InventoryWire::deltaFromProto);
    public static final UiWire.Codec<InventoryGrid.MoveIntent> MOVE =
            UiWire.Codec.protobuf(
                    InventoryProtocol.MoveIntent.getDefaultInstance(),
                    InventoryWire::moveToProto,
                    InventoryWire::moveFromProto);

    private InventoryWire() {}

    private static InventoryProtocol.InventorySnapshot snapshotToProto(InventorySnapshot value) {
        // 已验证的显示模型按字段转换，不把窗口状态或业务权限混入网络模型。
        return InventoryProtocol.InventorySnapshot.newBuilder()
                .setStreamId(value.streamId())
                .setRevision(value.revision())
                .addAllContainers(
                        value.containers().stream().map(InventoryWire::containerToProto).toList())
                .addAllPlacements(
                        value.placements().stream().map(InventoryWire::placementToProto).toList())
                .build();
    }

    private static InventorySnapshot snapshotFromProto(InventoryProtocol.InventorySnapshot value) {
        // 回到领域构造器统一检查身份、容器引用、尺寸、越界与重叠。
        return new InventorySnapshot(
                value.getStreamId(),
                value.getRevision(),
                value.getContainersList().stream().map(InventoryWire::containerFromProto).toList(),
                value.getPlacementsList().stream().map(InventoryWire::placementFromProto).toList());
    }

    private static InventoryProtocol.Container containerToProto(InventorySnapshot.Container value) {
        return InventoryProtocol.Container.newBuilder()
                .setId(value.id())
                .setTitle(value.title())
                .setRows(value.rows())
                .setColumns(value.columns())
                .build();
    }

    private static InventorySnapshot.Container containerFromProto(
            InventoryProtocol.Container value) {
        // 生成类允许默认空值，显示模型构造器负责拒绝空身份和非正尺寸。
        return new InventorySnapshot.Container(
                value.getId(), value.getTitle(), value.getRows(), value.getColumns());
    }

    private static InventoryProtocol.Placement placementToProto(InventorySnapshot.Placement value) {
        var item = value.item();
        return InventoryProtocol.Placement.newBuilder()
                .setContainerId(value.containerId())
                .setRow(value.row())
                .setColumn(value.column())
                .setRotated(value.rotated())
                .setItem(
                        InventoryProtocol.Item.newBuilder()
                                .setInstanceId(item.instanceId())
                                .setItemId(item.itemId())
                                .setName(item.name())
                                .setIconId(item.iconId())
                                .setWidth(item.width())
                                .setHeight(item.height())
                                .setCount(item.count()))
                .build();
    }

    private static InventorySnapshot.Placement placementFromProto(
            InventoryProtocol.Placement value) {
        // 缺失嵌套消息不能被默认 Item 冒充；其余物品约束复用领域模型。
        if (!value.hasItem()) {
            throw new IllegalArgumentException("placement item is required");
        }
        var item = value.getItem();
        return new InventorySnapshot.Placement(
                value.getContainerId(),
                value.getRow(),
                value.getColumn(),
                value.getRotated(),
                new InventorySnapshot.Item(
                        item.getInstanceId(),
                        item.getItemId(),
                        item.getName(),
                        item.getIconId(),
                        item.getWidth(),
                        item.getHeight(),
                        item.getCount()));
    }

    private static InventoryProtocol.InventoryDelta deltaToProto(InventoryDelta value) {
        // containers 未设置代表保留，设置为空代表清空，必须保留 message presence。
        requireNonBlank(value.streamId(), "streamId");
        value.removedInstances().forEach(id -> requireNonBlank(id, "removed instance"));
        var builder =
                InventoryProtocol.InventoryDelta.newBuilder()
                        .setStreamId(value.streamId())
                        .setBaseRevision(value.baseRevision())
                        .setRevision(value.revision())
                        .addAllRemovedInstances(value.removedInstances())
                        .addAllUpserts(
                                value.upserts().stream()
                                        .map(InventoryWire::placementToProto)
                                        .toList());
        if (value.containers() != null) {
            builder.setContainers(
                    InventoryProtocol.ContainerDirectory.newBuilder()
                            .addAllContainers(
                                    value.containers().stream()
                                            .map(InventoryWire::containerToProto)
                                            .toList()));
        }
        return builder.build();
    }

    private static InventoryDelta deltaFromProto(InventoryProtocol.InventoryDelta value) {
        // 不将重复删除身份静默折叠，避免另一语言发送的无效集合被误接受。
        requireNonBlank(value.getStreamId(), "streamId");
        value.getRemovedInstancesList().forEach(id -> requireNonBlank(id, "removed instance"));
        var removed = new LinkedHashSet<>(value.getRemovedInstancesList());
        if (removed.size() != value.getRemovedInstancesCount()) {
            throw new IllegalArgumentException("duplicate removed instance");
        }
        return new InventoryDelta(
                value.getStreamId(),
                value.getBaseRevision(),
                value.getRevision(),
                value.hasContainers()
                        ? value.getContainers().getContainersList().stream()
                                .map(InventoryWire::containerFromProto)
                                .toList()
                        : null,
                removed,
                value.getUpsertsList().stream().map(InventoryWire::placementFromProto).toList());
    }

    private static InventoryProtocol.MoveIntent moveToProto(InventoryGrid.MoveIntent value) {
        // 本地提交也执行同一字段检查；权限、当前版本与实际占格仍由服务端验证。
        validateMove(value);
        return InventoryProtocol.MoveIntent.newBuilder()
                .setStreamId(value.streamId())
                .setBaseRevision(value.baseRevision())
                .setInstanceId(value.instanceId())
                .setSourceContainerId(value.sourceContainerId())
                .setDestinationContainerId(value.destinationContainerId())
                .setRow(value.row())
                .setColumn(value.column())
                .setRotated(value.rotated())
                .setCount(value.count())
                .build();
    }

    private static InventoryGrid.MoveIntent moveFromProto(InventoryProtocol.MoveIntent value) {
        // MoveIntent 本身是轻量意图，来自网络的默认值必须在适配边界拒绝。
        var result =
                new InventoryGrid.MoveIntent(
                        value.getStreamId(),
                        value.getBaseRevision(),
                        value.getInstanceId(),
                        value.getSourceContainerId(),
                        value.getDestinationContainerId(),
                        value.getRow(),
                        value.getColumn(),
                        value.getRotated(),
                        value.getCount());
        validateMove(result);
        return result;
    }

    private static void validateMove(InventoryGrid.MoveIntent value) {
        // 协议层只检查稳定的字段约束，不替服务端猜测容器权限或是否允许分堆。
        requireNonBlank(value.streamId(), "streamId");
        requireNonBlank(value.instanceId(), "instanceId");
        requireNonBlank(value.sourceContainerId(), "sourceContainerId");
        requireNonBlank(value.destinationContainerId(), "destinationContainerId");
        if (value.baseRevision() < 0
                || value.row() < 0
                || value.column() < 0
                || value.count() <= 0) {
            throw new IllegalArgumentException("invalid move coordinates, revision or count");
        }
    }

    private static void requireNonBlank(String value, String field) {
        // 字符串默认值合法于 proto3，但不能充当领域身份。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
