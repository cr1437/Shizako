package moe.shizuku.server;

import android.os.ParcelFileDescriptor;

import moe.shizuku.server.FileEntry;

/**
 * 特权存储代理（规格 F1/F2）。
 *
 * 由 shizako_server（shell 或 root 身份）实现，用来读写应用管理器自己够不着的目录：
 * /data/data、/data/app、/sdcard/Android/data 等。所有方法都先做两层校验：
 * 1. 调用方持有 Shizuku 授权（复用 Service.enforceCallingPermission）；
 * 2. 调用方被授予「存储代理」能力（通行证里单独勾选，见 FLAG_STORAGE_PROXY）。
 *
 * 路径参数一律先过 safePath：拒绝 ../ 穿越、shell 元字符，且必须落在允许的根目录内。
 * 方法内部按「能直连就直连、失败再降级」走四层访问（direct → shell 管道 → run-as →
 * content://），降级结果可用 lastLayer() 查询（审计与自检用）。
 */
interface IStorageProxy {

    /** 列目录。降级层的 mtime 可能是 0（拿不到可靠值），客户端不要据此做删除判断。 */
    List<FileEntry> listDirectory(String path) = 1;

    /** 打开文件，mode 用 ParcelFileDescriptor.MODE_* 位。写模式只支持直连层。 */
    ParcelFileDescriptor openFile(String path, int mode) = 2;

    /**
     * 把目录打成一个 **gzip 压缩的 tar 流**返回（条目以目录名本身为前缀，
     * 等价于 `tar -C <父目录> -czf - <目录名>`）。客户端边读边写，不在设备上落临时包。
     */
    ParcelFileDescriptor tarDirectory(String path) = 3;

    /**
     * 把 tar.gz 流解压到 path（path 是被解压条目的**父目录**）。
     *
     * 语义是**替换**：先解到目标目录里的 staging，成功后按 tar 的顶层条目
     * （如 `<包名>/`）删掉旧目录再 rename 过去 —— 同名目录里不在备份中的文件会被清掉，
     * 解压/校验失败时旧数据保持原样。拒绝绝对路径、../ 穿越、符号链接/设备文件；
     * 空间不足抛 IOException。
     */
    void restoreTarDirectory(String path, in ParcelFileDescriptor tar) = 4;

    void copy(String source, String dest) = 5;

    /** 删除文件或目录（递归）。拒绝删除允许根目录本身。 */
    void delete(String path) = 6;

    void mkdir(String path) = 7;

    boolean exists(String path) = 8;

    /** 自检/环境摘要（JSON）：uid、模式（root/shell）、SDK、各层可用性与使用计数。 */
    String info() = 9;

    /** 上一次成功的读操作实际走的那一层：direct / shell / run-as / content。 */
    String lastLayer() = 10;
}
