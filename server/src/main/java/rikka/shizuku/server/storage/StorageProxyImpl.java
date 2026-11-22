package rikka.shizuku.server.storage;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import moe.shizuku.server.FileEntry;
import moe.shizuku.server.IStorageProxy;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.Service;
import rikka.shizuku.server.ShizukuService;
import rikka.shizuku.server.util.Logger;

/**
 * IStorageProxy 的服务端实现（规格 F1）+ 四层访问降级（规格 F2）。
 *
 * 四层顺序（先快后慢、先直连后降级）：
 * 1. **direct** —— 服务进程自己开文件。root 模式通吃；shell 模式下 /sdcard、
 *    /data/local/tmp 可用，别的应用 /data/data 会被 SELinux 拒绝；
 * 2. **shell 管道** —— `sh -c cat/ls`。和 direct 同一身份，真正能救场的是
 *    Android 13+ 对 /Android/data 的 FUSE 差异（部分 ROM 上 Java 层被挡、toybox 能过）
 *    以及 `dd`/`cat` 对特殊文件的容忍度；
 * 3. **run-as** —— ADB（shell）模式且目标包 **debuggable** 时，用 `run-as` 以应用身份
 *    读写它自己的 /data/data。非 debuggable 直接跳过，**不**尝试任何绕过；
 * 4. **content://** —— `content read/query` 管道，给 SAF/MediaStore 这类 URI 用。
 *
 * 每一层都是「先探测（test -r / test -e）再流式传输」，不是「先起管道再看运气」：
 * 管道是异步的，探测失败能给出确定性的错误，客户端不用猜是空文件还是失败。
 */
public class StorageProxyImpl extends IStorageProxy.Stub {

    private static final Logger LOGGER = new Logger("StorageProxy");

    private static final String LAYER_NONE = "none";
    private static final String LAYER_DIRECT = "direct";
    private static final String LAYER_SHELL = "shell";
    private static final String LAYER_RUN_AS = "run-as";
    private static final String LAYER_CONTENT = "content";

    private static final int PROBE_TIMEOUT_MS = 8000;
    private static final int LIST_TIMEOUT_MS = 20000;

    private final Service service;
    private final int serverUid;
    private final boolean rootMode;

    private final AtomicInteger directCount = new AtomicInteger();
    private final AtomicInteger shellCount = new AtomicInteger();
    private final AtomicInteger runAsCount = new AtomicInteger();
    private final AtomicInteger contentCount = new AtomicInteger();

    /**
     * 上一次读操作实际走的层（诊断用；多线程下可能被别的调用覆盖，只用于日志/自检）。
     */
    private volatile String lastLayer = LAYER_NONE;

    public StorageProxyImpl(Service service) {
        this.service = service;
        this.serverUid = Os.getuid();
        this.rootMode = serverUid == 0;
    }

    // ===================== F1：接口实现 =====================

    @Override
    public List<FileEntry> listDirectory(String path) {
        authorize("listDirectory", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.checkAny(path);
            List<FileEntry> entries = listInternal(checked);
            audit("listDirectory", checked, "ok;entries=" + entries.size() + ";layer=" + lastLayer, started);
            return entries;
        } catch (SecurityException e) {
            audit("listDirectory", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("listDirectory", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(String path, int mode) {
        authorize("openFile", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.checkAny(path);
            ParcelFileDescriptor fd = openInternal(checked, mode);
            audit("openFile", checked, "ok;layer=" + lastLayer, started);
            return fd;
        } catch (SecurityException e) {
            audit("openFile", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("openFile", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public ParcelFileDescriptor tarDirectory(String path) {
        authorize("tarDirectory", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.check(path);
            ParcelFileDescriptor fd = tarInternal(checked);
            audit("tarDirectory", checked, "ok;layer=" + lastLayer, started);
            return fd;
        } catch (SecurityException e) {
            audit("tarDirectory", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("tarDirectory", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public void restoreTarDirectory(String path, ParcelFileDescriptor tar) {
        authorize("restoreTarDirectory", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.check(path);
            if (tar == null) {
                throw new IOException("no tar stream");
            }
            File dest = new File(checked);
            int entries;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(tar)) {
                // 替换式恢复（规格 F4「覆盖」）：先解到 staging，成功后按顶层条目替换，
                // 中途失败只清 staging，用户原有数据不动。
                entries = TarExtractor.extractReplacing(dest, in, new TarExtractor.Progress() {
                    @Override
                    public void onEntry(String name, long size) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                });
            }
            lastLayer = LAYER_DIRECT;
            audit("restoreTarDirectory", checked, "ok;entries=" + entries, started);
        } catch (SecurityException e) {
            audit("restoreTarDirectory", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("restoreTarDirectory", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public void copy(String source, String dest) {
        authorize("copy", source);
        long started = SystemClock.elapsedRealtime();
        try {
            String from = StoragePaths.check(source);
            String to = StoragePaths.check(dest);
            copyInternal(new File(from), new File(to));
            audit("copy", from, "ok;layer=" + lastLayer + ";dest=" + StoragePaths.digest(to), started);
        } catch (SecurityException e) {
            audit("copy", source, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("copy", source, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public void delete(String path) {
        authorize("delete", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.check(path);
            if (StoragePaths.isDeleteForbidden(checked)) {
                throw new SecurityException("safePath rejected: refusing to delete storage root");
            }
            deleteInternal(new File(checked));
            audit("delete", checked, "ok;layer=" + lastLayer, started);
        } catch (SecurityException e) {
            audit("delete", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("delete", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public void mkdir(String path) {
        authorize("mkdir", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.check(path);
            mkdirInternal(new File(checked));
            audit("mkdir", checked, "ok;layer=" + lastLayer, started);
        } catch (SecurityException e) {
            audit("mkdir", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("mkdir", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public boolean exists(String path) {
        authorize("exists", path);
        long started = SystemClock.elapsedRealtime();
        try {
            String checked = StoragePaths.checkAny(path);
            boolean exists = existsInternal(checked);
            audit("exists", checked, "ok;result=" + exists + ";layer=" + lastLayer, started);
            return exists;
        } catch (SecurityException e) {
            audit("exists", path, "deny;reason=" + shortMessage(e), started);
            throw e;
        } catch (IOException e) {
            audit("exists", path, "err;" + shortMessage(e), started);
            throw failure(e);
        }
    }

    @Override
    public String info() {
        authorize("info", null);
        try {
            JSONObject json = new JSONObject();
            json.put("uid", serverUid);
            json.put("root", rootMode);
            json.put("sdk", Build.VERSION.SDK_INT);
            json.put("lastLayer", lastLayer);
            json.put("runAsCapable", !rootMode);
            JSONObject layers = new JSONObject();
            layers.put(LAYER_DIRECT, directCount.get());
            layers.put(LAYER_SHELL, shellCount.get());
            layers.put(LAYER_RUN_AS, runAsCount.get());
            layers.put(LAYER_CONTENT, contentCount.get());
            json.put("layers", layers);
            JSONArray roots = new JSONArray();
            for (String root : StoragePaths.ALLOWED_ROOTS) {
                roots.put(root);
            }
            json.put("roots", roots);
            json.put("server", "shizako-storage-1");
            return json.toString();
        } catch (Throwable t) {
            return "{\"error\":\"" + shortMessage(t) + "\"}";
        }
    }

    @Override
    public String lastLayer() {
        authorize("lastLayer", null);
        return lastLayer;
    }

    // ===================== 授权 + 审计 =====================

    private void authorize(String op, @Nullable String path) {
        try {
            service.enforceStorageProxyPermission("storage." + op);
        } catch (SecurityException e) {
            audit(op, path, "deny;reason=" + shortMessage(e), SystemClock.elapsedRealtime());
            throw e;
        }
    }

    private void audit(String op, @Nullable String path, String result, long startedAt) {
        long ms = SystemClock.elapsedRealtime() - startedAt;
        String detail = "src=storage;op=" + op
                + ";target=" + StoragePaths.digest(path)
                + ";result=" + result.replace(';', ':')
                + ";ms=" + ms;
        ShizukuService.auditDetail("storage." + op, detail);
    }

    private static String shortMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getSimpleName();
        }
        return msg.length() > 160 ? msg.substring(0, 160) : msg;
    }

    /**
     * 把内部 IOException 翻译成客户端能解析的机器码。
     *
     * AIDL 能跨进程传输的异常类型有限（IOException 不在里面，会被包成 RuntimeException），
     * 所以统一用 IllegalStateException + `storage:<code>:<message>` 前缀：
     * 客户端既能看到可读原因，也能按 code 决定「继续队列 / 提示重试」。
     */
    private IllegalStateException failure(IOException e) {
        String message = shortMessage(e);
        String code;
        if (e instanceof InterruptedIOException) {
            code = "cancelled";
        } else if (message.contains("no space")) {
            code = "no_space";
        } else if (message.contains("Permission denied") || message.contains("EACCES")) {
            // 权限判定要排在 not_found 前面：list 失败时的消息常常是
            // "cannot list <path> (permission denied or not a directory)"，
            // 先匹配 "cannot list" 会把「没权限」误报成「找不到」（设备补测时发现）。
            code = "denied";
        } else if (e instanceof java.io.FileNotFoundException
                || message.contains("No such file")
                || message.contains("cannot list")) {
            code = "not_found";
        } else {
            code = "io";
        }
        LOGGER.w("storage op failed (%s): %s", code, message);
        return new IllegalStateException("storage:" + code + ":" + message);
    }

    // ===================== 目录列举（四层） =====================

    private List<FileEntry> listInternal(String path) throws IOException {
        if (StoragePaths.isContentUri(path)) {
            List<FileEntry> entries = contentList(path);
            markLayer(LAYER_CONTENT);
            return entries;
        }

        File dir = new File(path);
        File[] children = dir.listFiles();
        if (children != null) {
            List<FileEntry> entries = new ArrayList<>(children.length);
            for (File child : children) {
                boolean isDirectory;
                long size = 0;
                long mtime;
                try {
                    isDirectory = child.isDirectory();
                    if (!isDirectory) {
                        size = child.length();
                    }
                    mtime = child.lastModified();
                } catch (Throwable t) {
                    isDirectory = false;
                    mtime = 0;
                }
                entries.add(new FileEntry(child.getName(), isDirectory, size, mtime));
            }
            markLayer(LAYER_DIRECT);
            return entries;
        }

        // 1) shell 管道
        String shellOut = shellStat(path);
        if (shellOut != null) {
            markLayer(LAYER_SHELL);
            return parseStatOutput(shellOut);
        }

        // 2) run-as（只对 debuggable 应用，且只在 ADB/shell 模式）
        String runAsOut = runAsStat(path);
        if (runAsOut != null) {
            markLayer(LAYER_RUN_AS);
            return parseStatOutput(runAsOut);
        }

        throw new IOException("cannot list " + path + " (permission denied or not a directory)");
    }

    private String shellStat(String path) throws IOException {
        StorageShell.Result result = StorageShell.run(
                "cd \"$1\" || exit 3; for f in ./* ./.[!.]*; do [ -e \"$f\" ] || continue; "
                        + "stat -c \"%f|%s|%Y|%n\" \"$f\" 2>/dev/null; done",
                LIST_TIMEOUT_MS, path);
        if (result.exitCode != 0) {
            return null;
        }
        return result.output;
    }

    private String runAsStat(String path) throws IOException {
        String pkg = runAsPackage(path);
        if (pkg == null) {
            return null;
        }
        // 外层 sh：$1 = 包名，$2 = 路径；内层 run-as 里的脚本用 "$1" 拿路径
        // （内层的 $1 来自 `sh "$2"` 的位置参数）。单引号里的 $1 由内层展开，外层不碰。
        StorageShell.Result result = StorageShell.run(
                "run-as \"$1\" sh -c 'cd \"$1\" || exit 3; "
                        + "for f in ./* ./.[!.]*; do [ -e \"$f\" ] || continue; "
                        + "stat -c \"%f|%s|%Y|%n\" \"$f\" 2>/dev/null; done' sh \"$2\"",
                LIST_TIMEOUT_MS, pkg, path);
        if (result.exitCode != 0) {
            return null;
        }
        return result.output;
    }

    /** 解析 `stat -c '%f|%s|%Y|%n'` 输出（mode|size|mtime|name）。 */
    private static List<FileEntry> parseStatOutput(String output) {
        List<FileEntry> entries = new ArrayList<>();
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int p1 = trimmed.indexOf('|');
            int p2 = p1 < 0 ? -1 : trimmed.indexOf('|', p1 + 1);
            int p3 = p2 < 0 ? -1 : trimmed.indexOf('|', p2 + 1);
            if (p1 <= 0 || p2 <= 0 || p3 <= 0) {
                continue;
            }
            String name = trimmed.substring(p3 + 1);
            while (name.startsWith("./")) {
                name = name.substring(2);
            }
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            int mode;
            long size;
            long mtime;
            try {
                mode = Integer.parseInt(trimmed.substring(0, p1), 16);
                size = Long.parseLong(trimmed.substring(p1 + 1, p2));
                mtime = Long.parseLong(trimmed.substring(p2 + 1, p3)) * 1000L;
            } catch (NumberFormatException e) {
                continue;
            }
            boolean isDirectory = (mode & 0xF000) == 0x4000;
            entries.add(new FileEntry(name, isDirectory, isDirectory ? 0 : size, mtime));
        }
        return entries;
    }

    // ===================== 打开文件（四层） =====================

    private ParcelFileDescriptor openInternal(String path, int mode) throws IOException {
        int access = mode & (ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_READ_WRITE);
        boolean writing = access == ParcelFileDescriptor.MODE_WRITE_ONLY
                || access == ParcelFileDescriptor.MODE_READ_WRITE;

        if (!StoragePaths.isContentUri(path)) {
            try {
                ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(path), mode);
                markLayer(LAYER_DIRECT);
                return fd;
            } catch (IOException directError) {
                if (writing) {
                    // 写模式没有可靠的降级手段（run-as/cat 都不能安全地写回原文件），
                    // 直接报错比"写进一个影子文件"好。
                    throw new IOException("write is only supported on direct layer: "
                            + shortMessage(directError));
                }
                LOGGER.d("openFile direct failed, falling back: %s", shortMessage(directError));
            }
        }

        if (StoragePaths.isContentUri(path)) {
            ParcelFileDescriptor fd = contentPipe(path);
            markLayer(LAYER_CONTENT);
            return fd;
        }

        StringBuilder shellError = new StringBuilder();
        if (shellReadable(path)) {
            ParcelFileDescriptor fd = StorageShell.pipe("cat \"$1\"", shellError, path);
            markLayer(LAYER_SHELL);
            return fd;
        }

        String pkg = runAsPackage(path);
        if (pkg != null && runAsReadable(pkg, path)) {
            ParcelFileDescriptor fd = StorageShell.pipe("run-as \"$1\" cat \"$2\"", shellError, pkg, path);
            markLayer(LAYER_RUN_AS);
            return fd;
        }

        String reason = shellError.length() > 0
                ? " (" + shellError.toString().trim() + ")"
                : "";
        throw new IOException("cannot open " + path + reason);
    }

    private boolean shellReadable(String path) throws IOException {
        StorageShell.Result result = StorageShell.run("test -r \"$1\"", PROBE_TIMEOUT_MS, path);
        return result.exitCode == 0;
    }

    private boolean runAsReadable(String pkg, String path) throws IOException {
        StorageShell.Result result = StorageShell.run(
                "run-as \"$1\" test -r \"$2\"", PROBE_TIMEOUT_MS, pkg, path);
        return result.exitCode == 0;
    }

    private ParcelFileDescriptor contentPipe(String uri) throws IOException {
        StringBuilder error = new StringBuilder();
        ParcelFileDescriptor fd = StorageShell.pipe("content read --uri \"$1\"", error, uri);
        return fd;
    }

    // ===================== tar 打包（direct，失败退 shell tar） =====================

    private ParcelFileDescriptor tarInternal(String path) throws IOException {
        File dir = new File(path);
        final boolean[] cancelled = {false};
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();

        // 直连层能列目录才用 Java 打包；否则退到 toybox tar（同一条流，条目前缀一致）
        boolean directCapable = dir.isDirectory() && dir.listFiles() != null;
        if (directCapable) {
            markLayer(LAYER_DIRECT);
            Thread writer = new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    TarWriter.write(dir, out, new TarWriter.Progress() {
                        @Override
                        public void onEntry(String name, long size) {
                        }

                        @Override
                        public boolean isCancelled() {
                            return cancelled[0];
                        }
                    });
                } catch (IOException e) {
                    // 客户端关掉管道 = 取消；真正的失败在写日志里能看出来
                    LOGGER.d("tar stream ended: %s", shortMessage(e));
                }
            }, "storage-tar");
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        }

        // shell 回退：tar -C <parent> -czf - <basename>，条目名同样以目录名为前缀
        File parent = dir.getParentFile();
        if (parent == null || !dir.isDirectory()) {
            closeQuietly(pipe);
            throw new IOException("not a directory: " + path);
        }
        markLayer(LAYER_SHELL);
        StringBuilder error = new StringBuilder();
        ParcelFileDescriptor shellPipe = StorageShell.pipe(
                "tar -C \"$1\" -czf - \"$2\"", error, parent.getAbsolutePath(), dir.getName());
        // shell 管道已经是独立的 PFD，把上面自己建的 pipe 关掉，避免 fd 泄漏
        closeQuietly(pipe);
        return shellPipe;
    }

    // ===================== 其它文件操作（direct → shell 回退） =====================

    private void copyInternal(File source, File dest) throws IOException {
        boolean ok = false;
        try {
            copyRecursive(source, dest);
            ok = true;
        } catch (IOException directError) {
            LOGGER.d("copy direct failed, falling back: %s", shortMessage(directError));
        }
        if (ok) {
            markLayer(LAYER_DIRECT);
            return;
        }
        StorageShell.Result result = StorageShell.run(
                "cp -a \"$1\" \"$2\"", 30000, source.getAbsolutePath(), dest.getAbsolutePath());
        if (result.exitCode != 0) {
            throw new IOException("copy failed: " + result.output.trim());
        }
        markLayer(LAYER_SHELL);
    }

    private static void copyRecursive(File source, File dest) throws IOException {
        if (source.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs() && !dest.isDirectory()) {
                throw new IOException("cannot create " + dest.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children == null) {
                throw new IOException("cannot list " + source.getAbsolutePath());
            }
            for (File child : children) {
                copyRecursive(child, new File(dest, child.getName()));
            }
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        }
    }

    private void deleteInternal(File target) throws IOException {
        if (!target.exists()) {
            markLayer(LAYER_DIRECT);
            return;
        }
        if (deleteRecursive(target)) {
            markLayer(LAYER_DIRECT);
            return;
        }
        StorageShell.Result result = StorageShell.run(
                "rm -rf \"$1\"", 60000, target.getAbsolutePath());
        if (result.exitCode != 0 || target.exists()) {
            throw new IOException("delete failed: " + result.output.trim());
        }
        markLayer(LAYER_SHELL);
    }

    private static boolean deleteRecursive(File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return target.delete();
    }

    private void mkdirInternal(File dir) throws IOException {
        if (dir.isDirectory()) {
            markLayer(LAYER_DIRECT);
            return;
        }
        if (dir.mkdirs() || dir.isDirectory()) {
            markLayer(LAYER_DIRECT);
            return;
        }
        StorageShell.Result result = StorageShell.run(
                "mkdir -p \"$1\"", 15000, dir.getAbsolutePath());
        if (result.exitCode != 0 || !dir.isDirectory()) {
            throw new IOException("mkdir failed: " + result.output.trim());
        }
        markLayer(LAYER_SHELL);
    }

    private boolean existsInternal(String path) throws IOException {
        if (StoragePaths.isContentUri(path)) {
            StorageShell.Result result = StorageShell.run(
                    "content query --uri \"$1\" --projection _id", PROBE_TIMEOUT_MS, path);
            markLayer(LAYER_CONTENT);
            return result.exitCode == 0 && result.output.contains("Row:");
        }
        if (new File(path).exists()) {
            markLayer(LAYER_DIRECT);
            return true;
        }
        StorageShell.Result result = StorageShell.run("test -e \"$1\"", PROBE_TIMEOUT_MS, path);
        if (result.exitCode == 0) {
            markLayer(LAYER_SHELL);
            return true;
        }
        String pkg = runAsPackage(path);
        if (pkg != null) {
            StorageShell.Result runAs = StorageShell.run(
                    "run-as \"$1\" test -e \"$2\"", PROBE_TIMEOUT_MS, pkg, path);
            if (runAs.exitCode == 0) {
                markLayer(LAYER_RUN_AS);
                return true;
            }
        }
        markLayer(LAYER_DIRECT);
        return false;
    }

    // ===================== run-as / content 辅助 =====================

    /**
     * 路径属于哪个可 run-as 的包；不满足条件返回 null（调用方跳过这一层）。
     * 非 debuggable 一律 null —— 规格 §四：不绕过系统检查。
     */
    @Nullable
    private String runAsPackage(String path) {
        if (rootMode) {
            return null; // root 模式直连就够了，run-as 反而降权
        }
        String pkg = StoragePaths.packageOf(path);
        if (pkg == null) {
            return null;
        }
        try {
            ApplicationInfo ai = PackageManagerApis.getApplicationInfoNoThrow(pkg, 0, userIdOf(path));
            if (ai == null || (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                return null;
            }
            return pkg;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int userIdOf(String path) {
        String[] parts = path.split("/");
        if (path.startsWith("/data/user/") && parts.length >= 4) {
            try {
                return Integer.parseInt(parts[3]);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * content:// 目录列举：`content query` 的输出是 `Row: N k=v, k=v`，
     * 优先取 _display_name / _size，取不到就退化成 row-N（provider 不暴露名字时
     * 客户端也只能拿到条目数，不做假的"空目录"）。
     */
    private List<FileEntry> contentList(String uri) throws IOException {
        StorageShell.Result result = StorageShell.run(
                "content query --uri \"$1\"", 15000, uri);
        if (result.exitCode != 0) {
            throw new IOException("content query failed: " + result.output.trim());
        }
        List<FileEntry> entries = new ArrayList<>();
        int index = 0;
        for (String line : result.output.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("Row:")) {
                continue;
            }
            String name = null;
            long size = 0;
            for (String pair : trimmed.substring(4).split(",")) {
                String item = pair.trim();
                int eq = item.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = item.substring(0, eq).trim();
                String value = item.substring(eq + 1).trim();
                if ("_display_name".equals(key) || "display_name".equals(key)) {
                    name = value;
                } else if ("_size".equals(key) || "size".equals(key)) {
                    try {
                        size = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            entries.add(new FileEntry(name != null ? name : "row-" + index, false, size, 0));
            index++;
        }
        return entries;
    }

    // ===================== 杂项 =====================

    private void markLayer(String layer) {
        lastLayer = layer;
        if (LAYER_DIRECT.equals(layer)) {
            directCount.incrementAndGet();
        } else if (LAYER_SHELL.equals(layer)) {
            shellCount.incrementAndGet();
        } else if (LAYER_RUN_AS.equals(layer)) {
            runAsCount.incrementAndGet();
        } else if (LAYER_CONTENT.equals(layer)) {
            contentCount.incrementAndGet();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor[] fds) {
        for (ParcelFileDescriptor fd : fds) {
            if (fd != null) {
                try {
                    fd.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
