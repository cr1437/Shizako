package rikka.shizuku.server.storage;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * 把目录流式打成 gzip tar（规格 F3 的 external.tar.gz）。
 *
 * 为什么自己写而不调 toybox 的 `tar`：
 * - **不落临时包**：直接写进 ParcelFileDescriptor 管道，客户端边读边写 SAF，
 *   /data 或 /sdcard 上不会先出现一个同等大小的中间文件（备份大应用时那等于双倍空间）；
 * - **不经过 shell**：文件名里的空格/中文/引号不需要转义，也就没有注入面；
 * - **可控取消**：客户端关掉管道 write 会抛 IOException，打包立刻停。
 *
 * 格式是 ustar（toybox tar / GNU tar / 7-Zip 都能读）：单条目名超过 100 字节时按
 * POSIX 规则拆成 prefix(<=155) + name(<=100)，够放中文长路径。
 *
 * 符号链接/设备文件**不打包**：跟随符号链接可能指到白名单外，恢复端也会拒绝创建，
 * 打成普通文件反而是错的（备份里会多出一份误导性的内容）。
 */
public final class TarWriter {

    private static final int BLOCK = 512;

    /**
     * ustar 的 size 字段是 11 位八进制 → 最大 8 GiB - 1。超过就**明确报错**，
     * 不能像原来那样让 {@code putOctal} 截断高位 —— 那会写出一份"看起来正常、解压时错位"的包。
     */
    private static final long MAX_FILE_SIZE = 0x1FFFFFFFFL;

    public interface Progress {
        /** 每次写一个条目时回调（目录 size 传 0）。 */
        void onEntry(String name, long size);

        boolean isCancelled();
    }

    private TarWriter() {
    }

    /**
     * @return 实际写入的**文件内容字节数**（不含 512 对齐/头部/GZIP 开销）。
     */
    public static long write(File rootDir, OutputStream rawOut, Progress progress) throws IOException {
        if (!rootDir.isDirectory()) {
            throw new IOException("not a directory: " + rootDir.getAbsolutePath());
        }
        GZIPOutputStream out = new GZIPOutputStream(rawOut, 64 * 1024);
        Counter counter = new Counter(progress);
        long total = writeDir(out, rootDir, rootDir.getName(), counter);
        // tar 结束标志：两个全零块
        out.write(new byte[BLOCK * 2]);
        out.finish();
        out.flush();
        return total;
    }

    private static long writeDir(OutputStream out, File dir, String name, Counter counter) throws IOException {
        counter.checkCancelled();

        StructStat st = lstat(dir);
        int mode = st != null ? (int) (st.st_mode & 0777) : 0755;
        long mtime = st != null ? st.st_mtime : dir.lastModified() / 1000L;
        writeHeader(out, name, '5', 0, mode, mtime);
        counter.onEntry(name, 0);

        File[] children = dir.listFiles();
        if (children == null) {
            // 读不了这个目录：跳过内容但保留目录条目，别让整次备份失败
            return 0;
        }
        Arrays.sort(children, Comparator.comparing(File::getName));

        long total = 0;
        for (File child : children) {
            counter.checkCancelled();

            StructStat childStat = lstat(child);
            int format = childStat != null
                    ? (int) (childStat.st_mode & OsConstants.S_IFMT)
                    : -1;
            if (format == OsConstants.S_IFLNK) {
                continue; // 符号链接不跟随、不打包
            }

            String childName = name + "/" + child.getName();
            boolean directory = format == OsConstants.S_IFDIR
                    || (format < 0 && child.isDirectory());
            if (directory) {
                total += writeDir(out, child, childName, counter);
            } else if (format == OsConstants.S_IFREG || (format < 0 && child.isFile())) {
                total += writeFile(out, child, childName, childStat, counter);
            }
            // fifo / socket / 设备文件：跳过
        }
        return total;
    }

    private static long writeFile(OutputStream out, File file, String name,
                                  StructStat st, Counter counter) throws IOException {
        long size = st != null ? st.st_size : file.length();
        if (size < 0) {
            size = 0;
        }
        if (size > MAX_FILE_SIZE) {
            throw new IOException("file too large for ustar tar (> 8 GiB): " + name);
        }
        int mode = st != null ? (int) (st.st_mode & 0777) : 0644;
        long mtime = st != null ? st.st_mtime : file.lastModified() / 1000L;
        writeHeader(out, name, '0', size, mode, mtime);
        counter.onEntry(name, size);

        byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (copied < size) {
                counter.checkCancelled();
                int want = (int) Math.min(buffer.length, size - copied);
                int n = in.read(buffer, 0, want);
                if (n <= 0) {
                    break;
                }
                out.write(buffer, 0, n);
                copied += n;
                counter.onBytes(n);
            }
        }
        if (copied < size) {
            // 文件在打包过程中被截短：按 header 声明的大小补 0，否则 tar 流整体错位
            byte[] zeros = new byte[64 * 1024];
            while (copied < size) {
                int n = (int) Math.min(zeros.length, size - copied);
                out.write(zeros, 0, n);
                copied += n;
            }
        }
        int padding = (int) ((BLOCK - (size % BLOCK)) % BLOCK);
        if (padding > 0) {
            out.write(new byte[padding]);
        }
        return size;
    }

    // ---------------- ustar 头 ----------------

    private static void writeHeader(OutputStream out, String name, char type, long size,
                                    int mode, long mtime) throws IOException {
        byte[] header = new byte[BLOCK];

        String prefix = "";
        String base = name;
        if (utf8Length(name) > 100) {
            int slash = name.lastIndexOf('/');
            boolean split = false;
            while (slash > 0) {
                String candidatePrefix = name.substring(0, slash);
                String candidateBase = name.substring(slash + 1);
                if (utf8Length(candidatePrefix) <= 155 && utf8Length(candidateBase) <= 100) {
                    prefix = candidatePrefix;
                    base = candidateBase;
                    split = true;
                    break;
                }
                slash = name.lastIndexOf('/', slash - 1);
            }
            if (!split) {
                throw new IOException("tar entry name too long: " + name);
            }
        }

        putString(header, 0, 100, base);
        putOctal(header, 100, 8, mode & 07777);
        putOctal(header, 108, 8, 0);  // uid：恢复时会被 run-as/服务身份决定，不写死
        putOctal(header, 116, 8, 0);  // gid
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, mtime);

        for (int i = 148; i < 156; i++) {
            header[i] = ' '; // 校验和计算时该字段按空格算
        }
        header[156] = (byte) type;

        // magic "ustar\0" + version "00"
        putString(header, 257, 6, "ustar\u0000");
        header[263] = '0';
        header[264] = '0';
        putString(header, 265, 32, "root");
        putString(header, 297, 32, "root");
        putOctal(header, 329, 8, 0);
        putOctal(header, 337, 8, 0);
        if (!prefix.isEmpty()) {
            putString(header, 345, 155, prefix);
        }

        long sum = 0;
        for (byte b : header) {
            sum += b & 0xFF;
        }
        putAscii(header, 148, String.format(Locale.US, "%06o", sum), 6);
        header[154] = 0;
        header[155] = ' ';

        out.write(header);
    }

    private static void putString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, header, offset, copy);
    }

    private static void putAscii(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, header, offset, copy);
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(Math.max(0, value));
        int digits = length - 1;
        if (octal.length() > digits) {
            octal = octal.substring(octal.length() - digits);
        }
        int padding = digits - octal.length();
        for (int i = 0; i < digits; i++) {
            header[offset + i] = (byte) (i < padding ? '0' : octal.charAt(i - padding));
        }
        header[offset + length - 1] = 0;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static StructStat lstat(File file) {
        try {
            return Os.lstat(file.getAbsolutePath());
        } catch (ErrnoException | RuntimeException e) {
            return null;
        }
    }

    private static final class Counter {
        private final Progress progress;

        Counter(Progress progress) {
            this.progress = progress;
        }

        void onEntry(String name, long size) {
            if (progress != null) {
                progress.onEntry(name, size);
            }
        }

        void onBytes(int n) {
        }

        void checkCancelled() throws InterruptedIOException {
            if (progress != null && progress.isCancelled()) {
                throw new InterruptedIOException("tar cancelled");
            }
        }
    }
}
