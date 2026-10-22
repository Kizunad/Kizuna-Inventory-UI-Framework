package dev.kizuna.inventoryui.workspace;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import dev.kizuna.inventoryui.window.UiWindowManager;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 功能目录和窗口栏的纯逻辑控制器，由宿主负责绘制与输入适配。 */
public final class WorkspaceController {
    private final FrameworkCatalog catalog;
    private final UiWindowManager windows;
    private final Set<String> pinnedFeatures = new LinkedHashSet<>();
    private final java.util.Map<String, java.util.function.BooleanSupplier> featureAccess = new java.util.HashMap<>();

    public void featureAccess(String featureId, java.util.function.BooleanSupplier access) {
        // 内容提供当前连接的业务权限；菜单与直接打开共用同一个实时判定。
        if (!catalog.features().containsKey(featureId) || featureAccess.containsKey(featureId)) {
            throw new IllegalArgumentException("unknown or already guarded feature: " + featureId);
        }
        featureAccess.put(featureId, Objects.requireNonNull(access));
    }

    public boolean canOpen(String featureId) {
        // 未设权限的通用功能维持公开；已设权限每次读取，不缓存解锁事实。
        var access = featureAccess.get(featureId);
        return access == null || access.getAsBoolean();
    }

    public WorkspaceController(FrameworkCatalog catalog, int width, int height) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.windows = new UiWindowManager(width, height);
    }

    public List<FrameworkCatalog.Feature> features(String query) {
        // 详情入口不进入主菜单；可见入口按固定偏好、分类及稳定 ID 排序。
        String needle =
                Objects.requireNonNull(query, "query must not be null")
                        .strip()
                        .toLowerCase(Locale.ROOT);
        return catalog.features().values().stream()
                .filter(FrameworkCatalog.Feature::visibleInMenu)
                .filter(feature -> canOpen(feature.id()))
                .filter(
                        feature ->
                                feature.title().toLowerCase(Locale.ROOT).contains(needle)
                                        || feature.category()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(needle))
                .sorted(
                        Comparator.<FrameworkCatalog.Feature, Boolean>comparing(
                                        feature -> !pinnedFeatures.contains(feature.id()))
                                .thenComparing(FrameworkCatalog.Feature::category)
                                .thenComparing(FrameworkCatalog.Feature::title)
                                .thenComparing(FrameworkCatalog.Feature::id))
                .toList();
    }

    public UiWindowManager.WindowState openFeature(
            String featureId, String objectId, UiWindowManager.Rect initialBounds) {
        // 先由注册策略确定实例身份，再让窗口管理器决定创建还是恢复已有窗口。
        FrameworkCatalog.Feature feature = catalog.features().get(featureId);
        if (feature == null) {
            throw new IllegalArgumentException("unknown feature: " + featureId);
        }
        if (!canOpen(featureId)) {
            throw new IllegalStateException("feature access denied: " + featureId);
        }
        FrameworkCatalog.Window window = catalog.windows().get(feature.windowType());
        String identity =
                window.instancePolicy() == FrameworkCatalog.InstancePolicy.SINGLETON
                        ? "singleton"
                        : requiredObjectId(objectId);
        UiWindowManager.WindowKey key = windows.key(window.id(), identity);
        return windows.openOrFocus(window.definition(), key, initialBounds);
    }

    public void pinFeature(String featureId, boolean pinned) {
        // 固定功能入口只记录稳定 ID，不复制窗口或绑定某次连接中的窗口实例。
        if (!catalog.features().containsKey(featureId)) {
            throw new IllegalArgumentException("unknown feature: " + featureId);
        }
        if (pinned) {
            pinnedFeatures.add(featureId);
        } else {
            pinnedFeatures.remove(featureId);
        }
    }

    public List<String> pinnedFeatures() {
        return List.copyOf(pinnedFeatures);
    }

    public List<UiWindowManager.WindowState> windowBar() {
        return windows.snapshot();
    }

    public UiWindowManager windows() {
        return windows;
    }

    public void disconnect() {
        // 窗口属于连接生命周期；功能目录及本地固定入口仍可供下次连接复用。
        windows.reset();
    }

    private static String requiredObjectId(String objectId) {
        // 按对象分实例的窗口必须有明确业务身份，不能用一个默认值合并不同对象。
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("objectId is required for this window");
        }
        return objectId;
    }
}
