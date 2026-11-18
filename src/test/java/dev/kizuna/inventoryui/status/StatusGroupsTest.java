package dev.kizuna.inventoryui.status;

import static dev.kizuna.inventoryui.status.StatusTestData.effect;
import static dev.kizuna.inventoryui.status.StatusTestData.file;
import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class StatusGroupsTest {
    @TempDir Path directory;

    @AfterEach
    void clearCurrentTable() {
        // 静态当前表不应把一个用例的资源残留给下一个用例。
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of())) {
            StatusGroups.reload(manager);
        }
    }

    @Test
    void realResourceStackAddsAllPacksAndKeepsBottomDefinition() throws IOException {
        var bottom = pack("bottom", Map.of("status_groups/nested/common.json",
                file(20, 30, List.of("example:one"))));
        var top = pack("top", Map.of("status_groups/nested/common.json",
                file(5, 7, List.of("example:two"))));
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(bottom, top))) {
            StatusGroups.reload(manager);
            var table = StatusGroups.current();
            var group = table.groupOf(effect("one"));
            assertEquals("example:nested/common", group.id());
            assertEquals(group, table.groupOf(effect("two")));
            assertEquals(20, group.mergeOrder(), "重载必须保留底层包定义，不能只读最顶层资源");
            assertEquals(30, group.displayOrder());
        }
    }

    @Test
    void reloadIgnoresOtherFilesAndPublishesANewCompleteSnapshot() throws IOException {
        var bottom = pack("bottom", Map.of(
                "status_groups/common.json", file(10, 20, List.of("example:valid")),
                "status_groups/ignored.txt", file(1, 1, List.of("example:ignored")),
                "other/ignored.json", file(1, 1, List.of("example:other"))));
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(bottom))) {
            StatusGroups.reload(manager);
        }
        var previous = StatusGroups.current();
        assertEquals("example:common", previous.groupOf(effect("valid")).id());
        assertSame(StatusGroup.NEUTRAL, previous.groupOf(effect("ignored")));
        assertSame(StatusGroup.NEUTRAL, previous.groupOf(effect("other")));
        clearCurrentTable();
        assertSame(StatusGroup.NEUTRAL, StatusGroups.current().groupOf(effect("valid")));
        assertEquals("example:common", previous.groupOf(effect("valid")).id(), "旧快照保持不可变");
    }

    @Test
    void malformedTopPackDoesNotLoseValidBottomMembers() throws IOException {
        var bottom = pack("bottom", Map.of("status_groups/common.json",
                file(10, 20, List.of("example:valid"))));
        var top = pack("top", Map.of("status_groups/common.json", "{\"effects\":true}"));
        try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(bottom, top))) {
            StatusGroups.reload(manager);
            assertEquals("example:common", StatusGroups.current().groupOf(effect("valid")).id());
        }
    }

    private PackResources pack(String name, Map<String, String> files) throws IOException {
        // 使用原版目录资源包验证真实的栈顺序和子路径映射，不模拟资源管理器的合并结果。
        var root = directory.resolve(name);
        for (var entry : files.entrySet()) {
            var path = root.resolve("assets/example").resolve(entry.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, entry.getValue());
        }
        var location = new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
        return new PathPackResources(location, root);
    }
}
