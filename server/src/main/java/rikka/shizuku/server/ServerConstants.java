package rikka.shizuku.server;

public class ServerConstants {

    public static final int MANAGER_APP_NOT_FOUND = 50;

    public static final String PERMISSION = "com.churan.shizako.permission.API_V23";
    // 官方 Shizuku-API 客户端（dev.rikka.shizuku:api）在清单中请求的权限名。
    // 仅作为"是否推送 binder / 是否列入授权列表"的匹配条件被引用；
    // Shizako 不声明、不定义该权限（符合上游 Shizuku 许可条款）。
    public static final String PERMISSION_UPSTREAM_API = "moe.shizuku.manager.permission.API_V23";
    public static final String MANAGER_APPLICATION_ID = "com.churan.shizako";
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_APPLICATION_ID + ".intent.action.REQUEST_PERMISSION";

    public static final int BINDER_TRANSACTION_getApplications = 10001;
}
