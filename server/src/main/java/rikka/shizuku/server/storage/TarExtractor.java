package rikka.shizuku.server.storage;

import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 把 gzip tar 流解压到目标目录（规格 F4 的 restoreTarDirectory）。
 *
 * 安全约束（规格 §四 硬性要求）比"能解开"更重要，这里逐条落实：
 * - 条目名必须是**相对路径**：拒绝绝对路径、`..` 段、控制字符、反斜杠；
 * - 落盘前 `getCanonicalPath()` 必须仍在目标目录内（fail closed）—— 防的是
 *   目标目录里本来就有符号链接把人引到别处；
 * - 只接受普通文件（'0'）和目录（'5'）：符号链接/硬链接/设备文件直接跳过，
 *   否则一次恢复就能在 /sdcard 里种下指向任意位置的链接；
 * - 条目数上限 + 写入前查剩余空间，避免畸形 tar 把设备写满；
 * - 还原 mtime（目录最后统一补，因为写子文件会改父目录 mtime）；权限 chmod 是 best-effort
 *   （/sdcard 的 FUSE 会拒绝 chmod，忽略即可）。
 *
 * 两种语义：
 * - {@link #extract}：解到目标目录，同名覆盖、其它保留（merge）；
 * - {@link #extractReplacing}：**先解到 staging，成功后再按顶层条目替换**——
 *   删旧的同名目录/文件，再把 staging 里的条目 rename 过去。这是管理器恢复的默认语义
 *   （规格 F4「覆盖」），失败时旧数据保持原样（staging 会被清掉）。
 */
public final class TarExtractor {

    private static final int BLOCK = 512;
    private static final int MAX_ENTRIES = 200_000;
    private static final long MIN_FREE_BYTES = 1024L * 1024L;
    private static final String STAGING_PREFIX = ".shizako-restore-";

    public interface Progress {
        void onEntry(String name, long size);

        boolean isCancelled();
    }

    private TarExtractor() {
    }

    /** 合并式解压：同名覆盖、其它保留。@return 解出的条目数（目录 + 文件）。 */
    public static int extract(File destRoot, InputStream raw, Progress progress) throws IOException {
        return extractInto(destRoot, raw, progress);
    }

    /**
     * 替换式解压（管理器恢复默认用它）：
     * 1. 解到 `destRoot/.shizako-restore-<ts>`；任何失败只清 staging，目标目录不动；
     * 2. 按 staging 的**顶层条目**（如 `<包名>/`）替换：删目标里同名旧目录 → rename 过去；
     * 3. 删 staging。
     *
     * 这样既满足「覆盖」语义（旧文件不会残留），又不会在解压中途失败时把用户数据删一半。
     *
     * @return 解出的条目数
     */
    public static int extractReplacing(File destRoot, InputStream raw, Progress progress) throws IOException {
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            throw new IOException("cannot create destination: " + destRoot.getAbsolutePath());
        }

        File staging = new File(destRoot, STAGING_PREFIX
                + System.currentTimeMillis() + "-"
                + Integer.toHexString(System.identityHashCode(Thread.currentThread())));
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("cannot create staging directory in " + destRoot.getAbsolutePath());
        }

        int entries;
        try {
            entries = extractInto(staging, raw, progress);
        } catch (IOException e) {
            deleteRecursively(staging);
            throw e;
        } catch (RuntimeException e) {
            deleteRecursively(staging);
            throw e;
        }

        File[] staged = staging.listFiles();
        if (staged == null) {
            deleteRecursively(staging);
            throw new IOException("cannot list staging directory");
        }

        try {
            for (File item : staged) {
                String name = item.getName();
                if (name.startsWith(STAGING_PREFIX)) {
                    continue; // 防御：嵌套 staging 不参与替换
                }
                File target = new File(destRoot, name);
                if (target.exists() && !deleteRecursively(target)) {
                    throw new IOException("cannot replace existing " + name);
                }
                if (!item.renameTo(target)) {
                    throw new IOException("cannot move " + name + " into place");
                }
            }
        } finally {
            deleteRecursively(staging);
        }
        return entries;
    }

    private static int extractInto(File destRoot, InputStream raw, Progress progress) throws IOException {
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            throw new IOException("cannot create destination: " + destRoot.getAbsolutePath());
        }
        String rootCanonical = destRoot.getCanonicalPath();

        byte[] header = new byte[BLOCK];
        int entries = 0;
        List<DirMeta> directories = new ArrayList<>();

        try (InputStream in = new BufferedInputStream(
                new GZIPInputStream(raw, 64 * 1024), 64 * 1024)) {

            while (true) {
                if (progress != null && progress.isCancelled()) {
                    throw new InterruptedIOException("restore cancelled");
                }

                int first = readFully(in, header, 0, BLOCK);
                if (first == 0) {
                    break; // 正常结束（有的实现不写结束块）
                }
                if (first < BLOCK) {
                    throw new IOException("truncated tar header");
                }
                if (isZeroBlock(header)) {
                    // 结束标志：通常后面还有一个零块（到 EOF 也算正常）
                    int second = readFully(in, header, 0, BLOCK);
                    if (second == 0 || isZeroBlock(header)) {
                        break;
                    }
                    continue;
                }

                long storedChecksum = parseOctal(header, 148, 8);
                long computed = checksum(header);
                if (storedChecksum != computed) {
                    throw new IOException("tar header checksum mismatch");
                }

                long size = parseOctal(header, 124, 12);
                if (size < 0) {
                    throw new IOException("bad tar entry size");
                }
                long mtime = parseOctal(header, 136, 12);
                int mode = (int) (parseOctal(header, 100, 8) & 07777);
                String name = entryName(header);
                char type = (char) (header[156] & 0xFF);
                String safeName = sanitize(name);
                File target = resolveInside(rootCanonical, safeName);

                if (type == '5') {
                    mkdirs(target);
                    skipFully(in, size);
                    directories.add(new DirMeta(target, mtime, mode));
                    entries++;
                } else if (type == '0' || type == 0) {
                    ensureFreeSpace(destRoot, size);
                    writeFile(in, target, size);
                    touch(target, mtime);
                    chmodQuietly(target, mode);
                    entries++;
                } else {
                    // '1' 硬链接 / '2' 符号链接 / '3' '4' 设备 / 其它：跳过内容，也不计入条目数
                    skipFully(in, size);
                }

                int padding = (int) ((BLOCK - (size % BLOCK)) % BLOCK);
                skipFully(in, padding);

                if (entries > MAX_ENTRIES) {
                    throw new IOException("too many tar entries");
                }
                if (progress != null && (type == '5' || type == '0' || type == 0)) {
                    progress.onEntry(safeName, size);
                }
            }
        }

        // 目录的 mtime 必须最后补：写子文件会把父目录 mtime 改掉；倒序保证子目录先定稿
        for (int i = directories.size() - 1; i >= 0; i--) {
            DirMeta meta = directories.get(i);
            touch(meta.dir, meta.mtime);
            chmodQuietly(meta.dir, meta.mode);
        }
        return entries;
    }

    // ---------------- 条目处理 ----------------

    private static void writeFile(InputStream in, File target, long size) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            mkdirs(parent);
        }
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = size;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, want);
                if (n <= 0) {
                    throw new IOException("truncated tar entry: " + target.getName());
                }
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create directory: " + dir.getAbsolutePath());
        }
    }

    private static void ensureFreeSpace(File destRoot, long size) throws IOException {
        try {
            StatFs stat = new StatFs(destRoot.getAbsolutePath());
            long available = stat.getAvailableBytes();
            if (available < size + MIN_FREE_BYTES) {
                throw new IOException("no space left on device");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable ignored) {
            // 拿不到 statfs 就不要因为探测失败而拒绝恢复
        }
    }

    /** 条目名 -> 相对路径，违规抛 IOException。 */
    private static String sanitize(String rawName) throws IOException {
        if (rawName == null || rawName.isEmpty()) {
            throw new IOException("empty tar entry name");
        }
        String name = rawName;
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isEmpty() || "/".equals(name)) {
            throw new IOException("invalid tar entry name");
        }
        if (name.startsWith("/")) {
            throw new IOException("absolute path in tar: " + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw new IOException("path traversal in tar: " + name);
            }
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '\\') {
                throw new IOException("illegal character in tar entry name");
            }
        }
        return name;
    }

    private static File resolveInside(String rootCanonical, String name) throws IOException {
        File target = new File(rootCanonical, name);
        String canonical = target.getCanonicalPath();
        if (canonical.equals(rootCanonical) || canonical.startsWith(rootCanonical + File.separator)) {
            return new File(canonical);
        }
        throw new IOException("tar entry escapes destination: " + name);
    }

    private static void touch(File target, long mtimeSeconds) {
        if (mtimeSeconds <= 0) {
            return;
        }
        try {
            target.setLastModified(mtimeSeconds * 1000L);
        } catch (Throwable ignored) {
            // FUSE/只读挂载上可能失败：时间戳只是附加信息
        }
    }

    static boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return true;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return target.delete();
    }

    // ---------------- tar 头解析 ----------------

    private static String entryName(byte[] header) {
        String name = readString(header, 0, 100);
        String prefix = readString(header, 345, 155);
        if (prefix.isEmpty() || isOldGnuHeader(header)) {
            return name;
        }
        return prefix + "/" + name;
    }

    /** 老 GNU 格式把 prefix 字段当别的东西用，只有 magic 是 ustar 才认 prefix。 */
    private static boolean isOldGnuHeader(byte[] header) {
        return !"ustar".equals(readString(header, 257, 5));
    }

    private static String readString(byte[] buffer, int offset, int length) {
        int end = offset;
        while (end < offset + length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] buffer, int offset, int length) throws IOException {
        long value = 0;
        int i = offset;
        int end = offset + length;
        // 跳过前导空格 / NUL
        while (i < end && (buffer[i] == 0 || buffer[i] == ' ')) {
            i++;
        }
        if (i < end && (buffer[i] & 0x80) != 0) {
            // GNU base-256：不常见，直接拒绝（我们自己写的和 toybox 都不用它）
            throw new IOException("unsupported tar number encoding");
        }
        boolean any = false;
        while (i < end) {
            byte b = buffer[i];
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > '7') {
                throw new IOException("bad tar number field");
            }
            value = (value << 3) + (b - '0');
            any = true;
            i++;
        }
        return any ? value : 0;
    }

    private static long checksum(byte[] header) {
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xFF);
        }
        return sum;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static int readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int n = in.read(buffer, offset + total, length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void skipFully(InputStream in, long length) throws IOException {
        long remaining = length;
        byte[] buffer = new byte[8 * 1024];
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new IOException("truncated tar stream");
            }
            remaining -= n;
        }
    }

    /** 仅供调试：把文件权限改成 header 里的 mode（失败忽略）。 */
    static void chmodQuietly(File target, int mode) {
        try {
            Os.chmod(target.getAbsolutePath(), mode);
        } catch (ErrnoException | RuntimeException ignored) {
        }
    }

    private static final class DirMeta {
        final File dir;
        final long mtime;
        final int mode;

        DirMeta(File dir, long mtime, int mode) {
            this.dir = dir;
            this.mtime = mtime;
            this.mode = mode;
        }
    }
}
