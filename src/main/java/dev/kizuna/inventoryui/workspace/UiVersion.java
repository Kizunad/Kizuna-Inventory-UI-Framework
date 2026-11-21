package dev.kizuna.inventoryui.workspace;

import java.util.Locale;

/** 界面的两版：CLASSIC 是整屏工作台和整窗锁，MODERN 是可收成小窗的琉璃窗口。两版各有自己的窗口布局。 */
public enum UiVersion {
    CLASSIC,
    MODERN;

    /** 没有偏好时用的版本。 */
    public static final UiVersion DEFAULT = CLASSIC;

    /** 写进偏好文件和命令里的名字。 */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 没写或认不出的名字按缺省的旧版处理，偏好文件里的拼写错误不会让界面打不开。 */
    public static UiVersion of(String id) {
        for (var version : values()) {
            if (version.id().equals(id)) {
                return version;
            }
        }
        return DEFAULT;
    }
}
