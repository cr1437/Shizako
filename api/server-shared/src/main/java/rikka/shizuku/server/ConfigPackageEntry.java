package rikka.shizuku.server;

import rikka.shizuku.server.util.Logger;

public abstract class ConfigPackageEntry {

    protected static final Logger LOGGER = new Logger("ConfigPackageEntry");

    public ConfigPackageEntry() {
    }

    public abstract boolean isAllowed();

    public abstract boolean isDenied();

    /**
     * 该记录是否带某一位 flag（如 {@link ConfigManager#FLAG_STORAGE_PROXY}）。
     *
     * 默认 false：其它实现（Dhizuku 等）没有 flags 概念，问它一律「没有」，
     * 比抛异常更适合当授权判定用 —— 授权检查里异常等于把功能变成崩溃。
     */
    public boolean hasFlag(int flag) {
        return false;
    }
}
