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
import java.util.Set;

/** 本地表现偏好；只保存稳定扩展 ID，不保存服务器对象、物品或连接身份。 */
public final class WorkspacePreferences {
    public static final int FORMAT_VERSION = 1;
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
            if (root.get("version").getAsInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported preference version");
            }
            var windows = new LinkedHashMap<String, Rect>();
            if (root.has("windows")) {
                root.getAsJsonObject("windows")
                        .entrySet()
                        .forEach(
                                entry -> {
                                    var value = entry.getValue().getAsJsonObject();
                                    windows.put(
                                            entry.getKey(),
                                            new Rect(
                                                    value.get("x").getAsInt(),
                                                    value.get("y").getAsInt(),
                                                    value.get("width").getAsInt(),
                                                    value.get("height").getAsInt()));
                                });
            }
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
            return new Snapshot(
                    text(root, "theme"),
                    text(root, "background"),
                    windows,
                    huds,
                    pins,
                    !root.has("motion") || root.get("motion").getAsBoolean(),
                    windowPins);
        } catch (RuntimeException | IOException failure) {
            writable = false;
            throw new IOException("cannot load UI preferences: " + path, failure);
        }
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
        root.add("windows", JSON.toJsonTree(snapshot.windows()));
        root.add("huds", JSON.toJsonTree(snapshot.huds()));
        root.add("pinnedFeatures", JSON.toJsonTree(snapshot.pinnedFeatures()));
        root.addProperty("motion", snapshot.motion());
        root.add("pinnedWindows", JSON.toJsonTree(snapshot.pinnedWindows()));
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

    public record Snapshot(
            String theme,
            String background,
            Map<String, Rect> windows,
            Map<String, HudLayout.Placement> huds,
            Set<String> pinnedFeatures,
            boolean motion,
            Set<String> pinnedWindows) {
        public Snapshot {
            // 复制后保持偏好快照稳定，缺少的模块 ID 暂时保留，重新安装时可以恢复。
            windows = Map.copyOf(windows);
            huds = Map.copyOf(huds);
            pinnedFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(pinnedFeatures));
            pinnedWindows = Set.copyOf(pinnedWindows);
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
    }
}
