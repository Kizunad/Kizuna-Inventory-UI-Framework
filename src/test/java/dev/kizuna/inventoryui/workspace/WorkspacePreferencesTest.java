package dev.kizuna.inventoryui.workspace;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.inventoryui.hud.HudLayout;
import dev.kizuna.inventoryui.window.UiWindowManager.Rect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

class WorkspacePreferencesTest {
    @TempDir Path directory;

    @Test
    void roundTripRetainsUnknownFieldsAndUnavailableModulePreferences() throws Exception {
        var path = directory.resolve("ui.json");
        Files.writeString(path, "{\"version\":1,\"extension\":{\"keep\":true}}");
        var store = new WorkspacePreferences(path);
        store.load();
        var snapshot =
                new WorkspacePreferences.Snapshot(
                        "test:theme",
                        null,
                        Map.of("absent:window", new Rect(10, 20, 100, 80)),
                        Map.of("test:hud", new HudLayout.Placement(false, -8, 12, 1.5f)),
                        Set.of("absent:feature"),
                        false,
                        Set.of("absent:window"));
        store.save(snapshot);
        assertEquals(snapshot, new WorkspacePreferences(path).load(), "重新启动仍能恢复同一组表现偏好");
        assertTrue(
                com.google.gson.JsonParser.parseString(Files.readString(path))
                        .getAsJsonObject()
                        .has("extension"));
    }

    @Test
    void originalFormatDefaultsToMotionAndNoAutomaticHudPins() throws Exception {
        var path = directory.resolve("old.json");
        Files.writeString(path, "{\"version\":1}");
        var loaded = new WorkspacePreferences(path).load();
        assertTrue(loaded.motion());
        assertTrue(loaded.pinnedWindows().isEmpty(), "升级旧配置不能擅自固定业务窗口");
    }

    @Test
    void unreadableAndNewerFilesArePreservedOnSave() throws Exception {
        for (String text :
                new String[] {
                    "broken", "{\"version\":99}", "{\"version\":1,\"huds\":{\"a\":{\"scale\":0}}}"
                }) {
            var path = directory.resolve("ui.json");
            Files.writeString(path, text);
            var store = new WorkspacePreferences(path);
            assertThrows(java.io.IOException.class, store::load);
            assertThrows(
                    java.io.IOException.class,
                    () -> store.save(WorkspacePreferences.Snapshot.empty()));
            assertEquals(text, Files.readString(path), "加载失败后不能用默认值覆盖用户文件");
        }
    }
}
