package dev.kizuna.inventoryui.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** 客户端分类资源的重载入口；当前表一次发布，读取方不会见到半张表。 */
public final class StatusGroups {
    private static final String DIRECTORY = "status_groups";
    private static final String PREFIX = DIRECTORY + "/";
    private static final String SUFFIX = ".json";
    private static final System.Logger LOG = System.getLogger(StatusGroups.class.getName());
    private static volatile StatusGroupTable current = StatusGroupTable.empty();

    private StatusGroups() {}

    public static StatusGroupTable current() {
        return current;
    }

    public static void reload(ResourceManager manager) {
        // 整摞资源保持原版从底层包到顶层包的顺序，不能只读覆盖后的最上层文件。
        var sources = new LinkedHashMap<String, List<String>>();
        var stacks = manager.listResourceStacks(DIRECTORY, id -> id.getPath().endsWith(SUFFIX));
        for (var entry : stacks.entrySet()) {
            var location = entry.getKey();
            String path = location.getPath();
            String groupId = location.getNamespace() + ":"
                    + path.substring(PREFIX.length(), path.length() - SUFFIX.length());
            sources.put(groupId, readStack(groupId, entry.getValue()));
        }
        var result = StatusGroupTable.parse(sources);
        for (var problem : result.problems()) {
            LOG.log(System.Logger.Level.WARNING, problem.groupId() + "：" + problem.reason());
        }
        current = result.table();
    }

    private static List<String> readStack(String groupId, List<Resource> resources) {
        // 每个输入流都独立关闭；一份文件读失败不妨碍同位置的其他资源包。
        var contents = new ArrayList<String>();
        for (var resource : resources) {
            try (var input = resource.open()) {
                contents.add(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException failure) {
                LOG.log(System.Logger.Level.WARNING,
                        groupId + "：读取分类文件失败（" + resource.sourcePackId() + "）", failure);
            }
        }
        return contents;
    }
}
