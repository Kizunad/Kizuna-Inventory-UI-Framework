package dev.kizuna.inventoryui.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.kizuna.inventoryui.hud.HudLayout;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 本地表现偏好；只保存稳定扩展 ID，不保存服务器对象、物品或连接身份。 */
public final class WorkspacePreferences {
    public static final int FORMAT_VERSION = 2;
    private static final int FIRST_VERSION = 1;
    private static final long MAX_FILE_BYTES = 1_048_576L;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private JsonObject document = new JsonObject();
    private boolean writable = true;

    public WorkspacePreferences(Path path) {
        this.path = path;
    }

    public Snapshot load() throws IOException {
        // 缺失文件代表首次启动；损坏或更高版本文件不得被当前程序自动覆盖。
        if (!Files.exists(path)) {
            return Snapshot.empty();
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("preference file exceeds size limit");
            }
            var root =
                    JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            int version = root.get("version").getAsInt();
            if (version < FIRST_VERSION || version > FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported preference version");
            }
            var windows = rects(root, "windows");
            var huds = new LinkedHashMap<String, HudLayout.Placement>();
            if (root.has("huds")) {
                root.getAsJsonObject("huds")
                        .entrySet()
                        .forEach(
                                entry -> {
                                    var value = entry.getValue().getAsJsonObject();
                                    huds.put(
                                            entry.getKey(),
                                            new HudLayout.Placement(
                                                    value.get("visible").getAsBoolean(),
                                                    value.get("offsetX").getAsInt(),
                                                    value.get("offsetY").getAsInt(),
                                                    value.get("scale").getAsFloat()));
                                });
            }
            var pins = new LinkedHashSet<String>();
            if (root.has("pinnedFeatures")) {
                root.getAsJsonArray("pinnedFeatures")
                        .forEach(value -> pins.add(value.getAsString()));
            }
            var windowPins = new LinkedHashSet<String>();
            if (root.has("pinnedWindows")) {
                root.getAsJsonArray("pinnedWindows")
                        .forEach(value -> windowPins.add(value.getAsString()));
            }
            document = root;
            var classic = new Snapshot(
                    text(root, "theme"),
                    text(root, "background"),
                    windows,
                    huds,
                    pins,
                    !root.has("motion") || root.get("motion").getAsBoolean(),
                    windowPins);
            return classic.withUiVersion(UiVersion.of(text(root, "uiVersion")))
                    .withModern(modern(root))
                    .withBlur(root.has("blur") && root.get("blur").getAsBoolean());
        } catch (RuntimeException | IOException failure) {
            writable = false;
            throw new IOException("cannot load UI preferences: " + path, failure);
        }
    }

    private static Map<String, Rect> rects(JsonObject object, String field) {
        var rects = new LinkedHashMap<String, Rect>();
        if (object.has(field)) {
            for (var entry : object.getAsJsonObject(field).entrySet()) {
                var value = entry.getValue().getAsJsonObject();
                rects.put(entry.getKey(), new Rect(value.get("x").getAsInt(), value.get("y").getAsInt(),
                        value.get("width").getAsInt(), value.get("height").getAsInt()));
            }
        }
        return rects;
    }

    private static Snapshot.Modern modern(JsonObject root) {
        // 新版的布局单独放在 modern 下；没有这一项就是还没进过新版。
        if (!root.has("modern")) {
            return Snapshot.Modern.empty();
        }
        var modern = root.getAsJsonObject("modern");
        var compact = new LinkedHashMap<String, CompactPlacement>();
        if (modern.has("compact")) {
            for (var entry : modern.getAsJsonObject("compact").entrySet()) {
                compact.put(entry.getKey(), JSON.fromJson(entry.getValue(), CompactPlacement.class));
            }
        }
        var migrated = new LinkedHashSet<String>();
        if (modern.has("migratedPins")) {
            modern.getAsJsonArray("migratedPins").forEach(value -> migrated.add(value.getAsString()));
        }
        var icons = IconStyle.of(text(modern, "icons"));
        return new Snapshot.Modern(rects(modern, "windows"), compact, migrated, icons);
    }

    private static String text(JsonObject object, String field) {
        return !object.has(field) || object.get(field).isJsonNull()
                ? null
                : object.get(field).getAsString();
    }

    public void save(Snapshot snapshot) throws IOException {
        // 未知顶层字段原样保留，使用同目录临时文件和替换避免半写入配置。
        if (!writable) {
            throw new IOException("preferences were unreadable; preserve original file: " + path);
        }
        var root = document.deepCopy();
        root.addProperty("version", FORMAT_VERSION);
        root.addProperty("theme", snapshot.theme());
        root.addProperty("background", snapshot.background());
        mergeEntries(root, "windows", snapshot.windows());
        mergeEntries(root, "huds", snapshot.huds());
        root.add("pinnedFeatures", JSON.toJsonTree(snapshot.pinnedFeatures()));
        root.addProperty("motion", snapshot.motion());
        root.add("pinnedWindows", JSON.toJsonTree(snapshot.pinnedWindows()));
        root.addProperty("uiVersion", snapshot.uiVersion().id());
        root.addProperty("blur", snapshot.blur());
        var modern = new JsonObject();
        if (root.has("modern")) {
            modern = root.getAsJsonObject("modern");
        }
        mergeEntries(modern, "windows", snapshot.modern().windows());
        mergeEntries(modern, "compact", snapshot.modern().compact());
        modern.add("migratedPins", JSON.toJsonTree(snapshot.modern().migratedPins()));
        modern.addProperty("icons", snapshot.modern().icons().id());
        root.add("modern", modern);
        var parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, "kiui-preferences-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            document = root;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void mergeEntries(JsonObject root, String key, Map<String, ?> entries) {
        // 已知字段逐项覆盖；旧条目里别的程序加的字段也随配置保留。
        var previous = root.getAsJsonObject(key);
        var result = new JsonObject();
        entries.forEach((id, value) -> {
            var item = new JsonObject();
            if (previous != null && previous.has(id)) {
                item = previous.getAsJsonObject(id).deepCopy();
            }
            var fields = JSON.toJsonTree(value).getAsJsonObject();
            for (var field : fields.entrySet()) {
                item.add(field.getKey(), field.getValue());
            }
            result.add(id, item);
        });
        root.add(key, result);
    }

    /**
     * 一份偏好。theme、windows、pinnedWindows 属于旧版界面；modern 里是新版自己的窗口矩形和小窗；
     * 背景、独立 HUD、固定入口、动画和模糊两版共用。
     */
    public record Snapshot(
            String theme,
            String background,
            Map<String, Rect> windows,
            Map<String, HudLayout.Placement> huds,
            Set<String> pinnedFeatures,
            boolean motion,
            Set<String> pinnedWindows,
            UiVersion uiVersion,
            Modern modern,
            boolean blur) {
        /**
         * 新版界面自己的偏好：展开窗矩形按窗口类型存，小窗按功能存；
         * migratedPins 记下已经从旧版锁窗迁过的窗口类型；icons 是图标风格。
         */
        public record Modern(
                Map<String, Rect> windows,
                Map<String, CompactPlacement> compact,
                Set<String> migratedPins,
                IconStyle icons) {
            public Modern {
                windows = Map.copyOf(windows);
                compact = Map.copyOf(compact);
                migratedPins = Set.copyOf(migratedPins);
                Objects.requireNonNull(icons);
            }

            public static Modern empty() {
                return new Modern(Map.of(), Map.of(), Set.of(), IconStyle.DEFAULT);
            }
        }

        public Snapshot {
            // 复制后保持偏好快照稳定，缺少的模块 ID 暂时保留，重新安装时可以恢复。
            windows = Map.copyOf(windows);
            huds = Map.copyOf(huds);
            pinnedFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(pinnedFeatures));
            pinnedWindows = Set.copyOf(pinnedWindows);
            Objects.requireNonNull(uiVersion);
            Objects.requireNonNull(modern);
        }

        /** 只有旧版字段的快照；界面版本取缺省值，新版布局为空。 */
        public Snapshot(
                String theme,
                String background,
                Map<String, Rect> windows,
                Map<String, HudLayout.Placement> huds,
                Set<String> pinnedFeatures,
                boolean motion,
                Set<String> pinnedWindows) {
            this(theme, background, windows, huds, pinnedFeatures, motion, pinnedWindows, UiVersion.DEFAULT,
                    Modern.empty(), false /* blur */);
        }

        /** 兼容初版调用方；新增本地选项采用默认值，不改变旧 JSON 版本。 */
        public Snapshot(
                String theme,
                String background,
                Map<String, Rect> windows,
                Map<String, HudLayout.Placement> huds,
                Set<String> pinnedFeatures) {
            this(theme, background, windows, huds, pinnedFeatures, true, Set.of());
        }

        public static Snapshot empty() {
            return new Snapshot(null, null, Map.of(), Map.of(), Set.of());
        }

        public Snapshot withWindows(Map<String, Rect> next) {
            return new Snapshot(theme, background, next, huds, pinnedFeatures, motion, pinnedWindows, uiVersion,
                    modern, blur);
        }

        public Snapshot withPinnedWindows(Set<String> next) {
            return new Snapshot(theme, background, windows, huds, pinnedFeatures, motion, next, uiVersion, modern,
                    blur);
        }

        public Snapshot withUiVersion(UiVersion next) {
            return new Snapshot(theme, background, windows, huds, pinnedFeatures, motion, pinnedWindows, next,
                    modern, blur);
        }

        public Snapshot withModern(Modern next) {
            return new Snapshot(theme, background, windows, huds, pinnedFeatures, motion, pinnedWindows, uiVersion,
                    next, blur);
        }

        public Snapshot withBlur(boolean next) {
            return new Snapshot(theme, background, windows, huds, pinnedFeatures, motion, pinnedWindows, uiVersion,
                    modern, next);
        }
    }
}
