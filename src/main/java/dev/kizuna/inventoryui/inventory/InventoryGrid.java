package dev.kizuna.inventoryui.inventory;

import java.util.Objects;

/** 只计算网格交互和请求意图；权威容量与移动事务仍由服务端决定。 */
public final class InventoryGrid {
    private InventoryGrid() {}

    public static boolean canPlaceExternal(
            InventorySnapshot snapshot,
            InventorySnapshot.Item item,
            String containerId,
            int row,
            int column,
            boolean rotated) {
        // 外部来源不释放当前网格里的任何占位；只验证几何，不猜测装备或跨状态流权限。
        return fits(snapshot, item, containerId, row, column, rotated, null);
    }

    private static boolean fits(
            InventorySnapshot snapshot,
            InventorySnapshot.Item item,
            String containerId,
            int row,
            int column,
            boolean rotated,
            String ignoredInstance) {
        // 来源是否释放由调用方决定；边界和碰撞计算在所有落点路径保持一致。
        var container =
                snapshot.containers().stream()
                        .filter(value -> value.id().equals(containerId))
                        .findFirst()
                        .orElse(null);
        int width = rotated ? item.height() : item.width();
        int height = rotated ? item.width() : item.height();
        if (container == null
                || row < 0
                || column < 0
                || (long) row + height > container.rows()
                || (long) column + width > container.columns()) {
            return false;
        }
        for (var other : snapshot.placements()) {
            if (!other.containerId().equals(containerId)
                    || other.item().instanceId().equals(ignoredInstance)) {
                continue;
            }
            int otherWidth = other.rotated() ? other.item().height() : other.item().width();
            int otherHeight = other.rotated() ? other.item().width() : other.item().height();
            if (column < other.column() + otherWidth
                    && other.column() < column + width
                    && row < other.row() + otherHeight
                    && other.row() < row + height) {
                return false;
            }
        }
        return true;
    }

    public static boolean canPlace(
            InventorySnapshot snapshot,
            String instanceId,
            String destinationId,
            int row,
            int column,
            boolean rotated) {
        return canPlace(
                snapshot, instanceId, destinationId, row, column, rotated, Integer.MAX_VALUE);
    }

    /** 分堆时原位置仍被剩余物品占用，不能按整堆移动忽略来源占格。 */
    public static boolean canPlace(
            InventorySnapshot snapshot,
            String instanceId,
            String destinationId,
            int row,
            int column,
            boolean rotated,
            int count) {
        // 查找失败表示调用方引用了未知对象；正常的越界或占格冲突则返回 false 供预览使用。
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(destinationId, "destinationId must not be null");
        InventorySnapshot.Placement source =
                snapshot.placements().stream()
                        .filter(placement -> placement.item().instanceId().equals(instanceId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown item instance: " + instanceId));
        InventorySnapshot.Container destination =
                snapshot.containers().stream()
                        .filter(container -> container.id().equals(destinationId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown container: " + destinationId));
        // 只有整堆移动释放来源占位，外部来源及分堆共用完整碰撞规则。
        return fits(
                snapshot,
                source.item(),
                destination.id(),
                row,
                column,
                rotated,
                count >= source.item().count() ? instanceId : null);
    }

    public static MoveIntent move(
            InventorySnapshot snapshot,
            String instanceId,
            String destinationId,
            int row,
            int column,
            boolean rotated,
            int count) {
        // 只做基于当前快照的本地预检，实际数量、权限与移动事务仍须由服务端确认。
        InventorySnapshot.Placement source =
                snapshot.placements().stream()
                        .filter(placement -> placement.item().instanceId().equals(instanceId))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown item instance: " + instanceId));
        if (count <= 0 || count > source.item().count()) {
            throw new IllegalArgumentException("move count exceeds source stack");
        }
        if (!canPlace(snapshot, instanceId, destinationId, row, column, rotated, count)) {
            throw new IllegalArgumentException("invalid grid destination");
        }
        // 携带状态流和基础版本，让接收方识别基于旧库存生成的请求；此处不修改快照。
        return new MoveIntent(
                snapshot.streamId(),
                snapshot.revision(),
                instanceId,
                source.containerId(),
                destinationId,
                row,
                column,
                rotated,
                count);
    }

    /** 一次移动的意图；count 是请求数量，是否支持分堆由接入方协议和服务端决定。 */
    public record MoveIntent(
            String streamId,
            long baseRevision,
            String instanceId,
            String sourceContainerId,
            String destinationContainerId,
            int row,
            int column,
            boolean rotated,
            int count) {}
}
