package moe.shizuku.manager.dhizuku

/**
 * Dhizuku 客户端协议常量（与 Dhizuku-API 的 wire 协议保持兼容，
 * 使现有 Dhizuku-API 应用无需修改即可连接 Shizako）。
 */
object DhizukuConstants {

    /** IDhizuku AIDL 接口描述符 */
    const val BINDER_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizuku"

    /** remote binder transact 的接口描述符 */
    const val REMOTE_BINDER_DESCRIPTOR = "com.rosan.dhizuku.server"

    /** ContentProvider call 方法名 */
    const val PROVIDER_METHOD_CLIENT = "client"

    /** call 入参 extras 里的客户端 binder 键 */
    const val EXTRA_CLIENT = "client"

    /** call 返回 Bundle 里的服务端 binder 键 */
    const val PARAM_DHIZUKU_BINDER = "dhizuku_binder"

    /** 授权请求 Intent extras 里的客户端 uid 键 */
    const val PARAM_CLIENT_UID = "uid"

    /** 授权请求 Intent extras 里的授权结果回调 binder 键 */
    const val PARAM_CLIENT_REQUEST_PERMISSION_BINDER = "request_permission_binder"

    /** 对客户端报告的 server 版本 */
    const val VERSION_CODE = 1
    const val VERSION_NAME = "1.0.0"
}
