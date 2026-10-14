package dev.kizuna.inventoryui.server;

import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Capability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 启动期间的模块注册表；C 是宿主提供的已认证上下文，不从网络载荷取得。 */
public final class ServerModuleRegistry<C> {
    private final Map<String, Module<C>> modules = new LinkedHashMap<>();
    private final Map<String, Capability> capabilities = new LinkedHashMap<>();
    private boolean frozen;

    public void register(
            String module,
            Map<String, Capability> messages,
            Function<ServerConnection<C>, ServerModuleSession<C>> factory) {
        // 先完整校验再一次性登记，重复模块或消息不能覆盖已有处理器。
        ServerProtocol.requireId(module);
        Objects.requireNonNull(factory);
        var definitions = Map.copyOf(messages);
        if (frozen || modules.containsKey(module) || definitions.isEmpty()) {
            throw new IllegalArgumentException("模块重复、为空或注册表已冻结：" + module);
        }
        definitions.forEach((id, value) -> {
            ServerProtocol.validate(id, value);
            if (!value.getModule().equals(module) || capabilities.containsKey(id)) {
                throw new IllegalArgumentException("消息重复或不属于模块：" + id);
            }
        });
        modules.put(module, new Module<>(factory));
        capabilities.putAll(definitions);
    }

    public void freeze() {
        // 启动注册结束后禁止动态改写契约，所有连接使用相同能力目录。
        frozen = true;
    }

    Map<String, Module<C>> modules() {
        // 连接必须使用同一份已完成注册的目录，不能观察启动中途的部分模块。
        if (!frozen) {
            throw new IllegalStateException("创建连接前必须冻结模块注册表");
        }
        return Map.copyOf(modules);
    }

    public Map<String, Capability> capabilities() {
        return Map.copyOf(capabilities);
    }

    record Module<C>(Function<ServerConnection<C>, ServerModuleSession<C>> factory) {}
}
