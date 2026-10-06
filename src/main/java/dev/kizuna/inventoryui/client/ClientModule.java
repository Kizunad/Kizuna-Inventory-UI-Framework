package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;

/** 子模组通过 fabric.mod.json 的 kizuna_inventory_ui 入口提供此接口。 */
public interface ClientModule {
    /** 返回模块身份、依赖和扩展声明；初始化时由框架统一验证。 */
    FrameworkCatalog.Module definition();

    /** 为当前模块声明的窗口、HUD 和槽位栏绑定运行时实现。 */
    void register(ClientBindings bindings);

    default void connected(ClientRuntime runtime) {
        // 纯本地模块无需连接逻辑；需要网络状态的模块在这里建立自己的连接上下文。
    }

    default void disconnected(ClientRuntime runtime) {
        // 模块可覆盖此回调清理待处理请求和连接状态，避免将旧数据带入下一次连接。
    }

    default void protocolReady(ClientRuntime runtime) {
        // 网络模块在能力协商成功后订阅状态；纯本地模块无需实现此回调。
    }
}
