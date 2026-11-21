package dev.kizuna.inventoryui.client;

import dev.kizuna.inventoryui.window.UiWindowManager.WindowState;
import java.util.function.Consumer;

/** 每实例一次只读观察；订阅和未决请求随窗口 scope 释放。 */
public interface RestoreBinding {
    RestoreReadiness readiness();

    void observe(WindowState state, Consumer<RestoreStatus> status);
}
