package rikka.shizuku.server;

import androidx.annotation.Nullable;

import java.util.List;

import rikka.shizuku.server.util.Logger;

public abstract class ConfigManager {

    protected static final Logger LOGGER = new Logger("ConfigManager");

    public static final int FLAG_ALLOWED = 1 << 1;
    public static final int FLAG_DENIED = 1 << 2;
    public static final int MASK_PERMISSION = FLAG_ALLOWED | FLAG_DENIED;

    /**
     * 「存储代理」能力位（规格 F5）：应用除了要有 Shizuku 授权，还要在通行证里被**单独**
     * 勾上这一位才能用水 IStorageProxy。
     *
     * 刻意放在 {@link #MASK_PERMISSION} 之外：现有的授权/撤销流程只按 MASK_PERMISSION 写
     * flags，如果不独立成位，用户平时授一次权就会顺手把存储代理也放开，等于没有二次确认。
     */
    public static final int FLAG_STORAGE_PROXY = 1 << 3;


    @Nullable
    public abstract ConfigPackageEntry find(int uid);

    public abstract void update(int uid, List<String> packages, int mask, int values);

    public abstract void remove(int uid);
}
