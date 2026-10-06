package dev.kizuna.inventoryui.registry;

import dev.kizuna.inventoryui.window.UiWindowDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 启动时一次性验证模块和扩展定义，成功后提供不可变目录。 */
public final class FrameworkCatalog {
    private final Map<String, Module> modules;
    private final Map<String, Feature> features;
    private final Map<String, Window> windows;
    private final Map<String, Hud> huds;
    private final Map<String, SlotBar> slotBars;
    private final Map<String, Theme> themes;
    private final Map<String, Background> backgrounds;
    private final Map<String, SvgAsset> svgAssets;
    private final List<String> moduleOrder;

    private FrameworkCatalog(Builder builder, List<String> moduleOrder) {
        // 校验全部成功后才发布只读目录，同时保留注册顺序供界面稳定展示。
        this.modules = immutable(builder.modules);
        this.features = immutable(builder.features);
        this.windows = immutable(builder.windows);
        this.huds = immutable(builder.huds);
        this.slotBars = immutable(builder.slotBars);
        this.themes = immutable(builder.themes);
        this.backgrounds = immutable(builder.backgrounds);
        this.svgAssets = immutable(builder.svgAssets);
        this.moduleOrder = List.copyOf(moduleOrder);
    }

    public static FrameworkCatalog build(List<Module> modules) {
        // 先收齐模块再检查依赖，允许依赖模块出现在输入列表的后面。
        Objects.requireNonNull(modules, "modules must not be null");
        Builder builder = new Builder();
        for (Module module : modules) {
            Objects.requireNonNull(module, "module must not be null");
            if (builder.modules.putIfAbsent(module.id(), module) != null) {
                throw new IllegalArgumentException("duplicate module: " + module.id());
            }
        }
        // 拓扑排序保证依赖先于使用方注册；缺失依赖或循环依赖直接阻止目录构建。
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String id : builder.modules.keySet()) {
            visit(id, builder.modules, new LinkedHashSet<>(), visited, order);
        }
        for (String id : order) {
            for (Entry entry : builder.modules.get(id).entries()) {
                builder.add(entry, id);
            }
        }
        for (Feature feature : builder.features.values()) {
            // 功能入口可以引用稍后声明的窗口，因此引用检查放在全部扩展注册之后。
            if (!builder.windows.containsKey(feature.windowType())) {
                throw new IllegalArgumentException(
                        "feature "
                                + feature.id()
                                + " references missing window "
                                + feature.windowType());
            }
        }
        return new FrameworkCatalog(builder, order);
    }

    private static void visit(
            String id,
            Map<String, Module> modules,
            Set<String> visiting,
            Set<String> visited,
            List<String> order) {
        // visiting 只表示当前递归路径，visited 表示已完成节点，避免把共享依赖误判为环。
        if (visited.contains(id)) {
            return;
        }
        if (!visiting.add(id)) {
            throw new IllegalArgumentException("module dependency cycle: " + id);
        }
        Module module = modules.get(id);
        if (module == null) {
            throw new IllegalArgumentException("missing module dependency: " + id);
        }
        for (String dependency : module.dependencies()) {
            visit(dependency, modules, visiting, visited, order);
        }
        visiting.remove(id);
        visited.add(id);
        order.add(id);
    }

    private static <T> Map<String, T> immutable(Map<String, T> values) {
        // 复制而非仅包装原映射，确保构建器的后续变化无法透过只读视图泄漏。
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Module> modules() {
        return modules;
    }

    public Map<String, Feature> features() {
        return features;
    }

    public Map<String, Window> windows() {
        return windows;
    }

    public Map<String, Hud> huds() {
        return huds;
    }

    public Map<String, SlotBar> slotBars() {
        return slotBars;
    }

    public Map<String, Theme> themes() {
        return themes;
    }

    public Map<String, Background> backgrounds() {
        return backgrounds;
    }

    public Map<String, SvgAsset> svgAssets() {
        return svgAssets;
    }

    public List<String> moduleOrder() {
        return moduleOrder;
    }

    public record Module(String id, Set<String> dependencies, List<Entry> entries) {
        public Module {
            // 冻结声明集合；依赖的存在性和拓扑关系留到整个目录构建时验证。
            id = namespaced(id);
            dependencies =
                    Set.copyOf(
                            Objects.requireNonNull(dependencies, "dependencies must not be null"));
            dependencies.forEach(FrameworkCatalog::namespaced);
            entries = List.copyOf(Objects.requireNonNull(entries, "entries must not be null"));
        }

        public static Module of(String id, Set<String> dependencies, Entry... entries) {
            return new Module(id, dependencies, List.of(entries));
        }
    }

    public sealed interface Entry
            permits Feature, Window, Hud, SlotBar, Theme, Background, SvgAsset {
        String id();
    }

    public record Feature(
            String id, String title, String category, String iconId, String windowType)
            implements Entry {
        public Feature {
            // 展示文案与稳定身份分开校验，窗口类型引用随后在完整目录中解析。
            id = namespaced(id);
            title = nonBlank(title, "title");
            category = nonBlank(category, "category");
            iconId = namespaced(iconId);
            windowType = namespaced(windowType);
        }
    }

    public record Window(UiWindowDefinition definition, InstancePolicy instancePolicy)
            implements Entry {
        public Window {
            // 实例策略决定使用单例身份还是业务对象身份，不由窗口标题决定。
            Objects.requireNonNull(definition, "definition must not be null");
            Objects.requireNonNull(instancePolicy, "instancePolicy must not be null");
        }

        public Window(UiWindowDefinition definition) {
            this(definition, InstancePolicy.SINGLETON);
        }

        @Override
        public String id() {
            return definition.windowType();
        }
    }

    public enum InstancePolicy {
        SINGLETON,
        BY_OBJECT
    }

    public record Hud(String id, String title, Anchor anchor, int width, int height)
            implements Entry {
        public Hud {
            // 注册的是未缩放的逻辑尺寸；实际位置和缩放由 HudLayout 计算。
            id = namespaced(id);
            title = nonBlank(title, "title");
            Objects.requireNonNull(anchor, "anchor must not be null");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("HUD size must be positive");
            }
        }
    }

    public enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
        CENTER
    }

    public record SlotBar(String id, List<Slot> slots) implements Entry {
        public SlotBar {
            // 槽位数量来自声明，槽位 ID 在同一栏内必须唯一，才能稳定绑定内容。
            id = namespaced(id);
            slots = List.copyOf(Objects.requireNonNull(slots, "slots must not be null"));
            Set<String> seen = new LinkedHashSet<>();
            for (Slot slot : slots) {
                if (!seen.add(slot.id())) {
                    throw new IllegalArgumentException("duplicate slot: " + slot.id());
                }
            }
        }
    }

    public record Slot(String id, String kind) {
        public Slot {
            // kind 由模块解释，框架不把某种业务槽位写死为枚举。
            id = namespaced(id);
            kind = nonBlank(kind, "kind");
        }
    }

    public record Theme(String id, Map<String, Integer> colors) implements Entry {
        public Theme {
            // 色彩令牌可以由模块扩展；复制后主题不会因调用方修改原映射而变化。
            id = namespaced(id);
            colors = Map.copyOf(Objects.requireNonNull(colors, "colors must not be null"));
            colors.keySet().forEach(key -> nonBlank(key, "color token"));
        }
    }

    public record Background(String id, String resourceId, Fit fit) implements Entry {
        public Background {
            // 此处只检查声明格式，资源是否存在由客户端资源管理器加载时判断。
            id = namespaced(id);
            resourceId = namespaced(resourceId);
            Objects.requireNonNull(fit, "fit must not be null");
        }
    }

    /** SVG 是模块声明的资源，不接受服务器传入任意资源路径。 */
    public record SvgAsset(String id, String resourceId) implements Entry {
        public SvgAsset {
            // 所属命名空间在目录构建时校验，后缀与路径在进入资源管理器前校验。
            id = namespaced(id);
            resourceId = namespaced(resourceId);
            if (!resourceId.endsWith(".svg") || resourceId.contains("..")) {
                throw new IllegalArgumentException("invalid SVG resource: " + resourceId);
            }
        }
    }

    public enum Fit {
        COVER,
        CONTAIN,
        TILE
    }

    private static String namespaced(String id) {
        // 所有公共注册项采用相同的命名空间格式，防止模块间意外撞名。
        String value = nonBlank(id, "id");
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid namespaced id: " + value);
        }
        return value;
    }

    private static String nonBlank(String value, String name) {
        // 保留有效原文，只拒绝缺失值，避免悄悄修改模块身份或展示文案。
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static final class Builder {
        private final Map<String, Module> modules = new LinkedHashMap<>();
        private final Map<String, Feature> features = new LinkedHashMap<>();
        private final Map<String, Window> windows = new LinkedHashMap<>();
        private final Map<String, Hud> huds = new LinkedHashMap<>();
        private final Map<String, SlotBar> slotBars = new LinkedHashMap<>();
        private final Map<String, Theme> themes = new LinkedHashMap<>();
        private final Map<String, Background> backgrounds = new LinkedHashMap<>();
        private final Map<String, SvgAsset> svgAssets = new LinkedHashMap<>();
        private final Set<String> registeredIds = new LinkedHashSet<>();

        private void add(Entry entry, String moduleId) {
            // 扩展项只能占用所属模块的命名空间，而且不同扩展类型也不能重用同一 ID。
            Objects.requireNonNull(entry, "entry must not be null");
            String id = namespaced(entry.id());
            if (!id.substring(0, id.indexOf(':'))
                    .equals(moduleId.substring(0, moduleId.indexOf(':')))) {
                throw new IllegalArgumentException(
                        "entry " + id + " is outside module namespace " + moduleId);
            }
            if (!registeredIds.add(id)) {
                throw new IllegalArgumentException("duplicate registration: " + id);
            }
            if (entry instanceof Feature value) {
                features.put(id, value);
            } else if (entry instanceof Window value) {
                windows.put(id, value);
            } else if (entry instanceof Hud value) {
                huds.put(id, value);
            } else if (entry instanceof SlotBar value) {
                slotBars.put(id, value);
            } else if (entry instanceof Theme value) {
                themes.put(id, value);
            } else if (entry instanceof Background value) {
                backgrounds.put(id, value);
            } else if (entry instanceof SvgAsset value) {
                svgAssets.put(id, value);
            }
        }
    }
}
