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

    public WorkspaceController(FrameworkCatalog catalog, int width, int height) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.windows = new UiWindowManager(width, height);
    }

    public List<FrameworkCatalog.Feature> features(String query) {
        // 搜索使用固定语言规则，排序用稳定 ID 兜底，避免同名入口每次刷新换位。
        String needle =
                Objects.requireNonNull(query, "query must not be null")
                        .strip()
                        .toLowerCase(Locale.ROOT);
        return catalog.features().values().stream()
                .filter(
                        feature ->
                                feature.title().toLowerCase(Locale.ROOT).contains(needle)
                                        || feature.category()
                                                .toLowerCase(Locale.ROOT)
                                                .contains(needle))
                .sorted(
                        Comparator.comparing(FrameworkCatalog.Feature::category)
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
