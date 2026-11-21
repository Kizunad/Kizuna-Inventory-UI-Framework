package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import java.util.Optional;

/** 子模组通过 fabric.mod.json 的 kizuna_inventory_ui 入口提供此接口。 */
public interface ClientModule {
    /** 返回模块身份、依赖和扩展声明；初始化时由框架统一验证。 */
    FrameworkCatalog.Module definition();

    /** 为当前模块声明的窗口、HUD 和槽位栏绑定运行时实现。 */
    void register(ClientBindings bindings);

    /** 新版界面另有一套窗口时给出那一版的声明（模块 ID 和依赖不变）；不给就两版共用 definition()。 */
    default Optional<FrameworkCatalog.Module> modernDefinition() {
        return Optional.empty();
    }

    /**
     * 为 modernDefinition() 里新增或换了内容的窗口绑定实现，只在给了新版声明时调用；
     * 没重新绑的窗口、入口权限和入口动作沿用 register 里绑的。
     * 协议消息、网络接收和按键两版共用，仍只在 register 里登记一次。
     */
    default void registerModern(ClientBindings bindings) {
        // 两版共用同一套窗口的模块无需实现。
    }

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
