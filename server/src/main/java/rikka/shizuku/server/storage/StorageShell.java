package rikka.shizuku.server.storage;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 降级层的 shell 执行器（规格 F2 的 shell 管道 / run-as / content 三层共用）。
 *
 * 约定：脚本体是**固定字符串**，路径等变量一律走 `sh -c '<script>' sh <arg...>` 的
 * `$1..$N` 位置参数 —— 不拼字符串，路径里的内容就不会被当成 shell 语法。
 * 这一层之上还有 {@link StoragePaths} 的白名单/元字符校验做双保险。
 *
 * 为什么 openFile 的降级要用「进程 + 管道」而不是把命令输出读成字节再返回：
 * tar/备份文件可能上 GB，必须流式；PDD 管道让客户端边读边写 SAF，设备上不落临时文件。
 */
final class StorageShell {

    private static final int MAX_OUTPUT = 256 * 1024;
    private static final int PIPE_BUFFER = 64 * 1024;

    private StorageShell() {
    }

    static final class Result {
        final int exitCode;
        final String output;
        final boolean timedOut;

        Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }

    static Process start(String script, String... args) throws IOException {
        return start(true, script, args);
    }

    /**
     * @param mergeError true 时 stderr 并入 stdout（短命令收集输出用）；
     *                   false 时分开（Streaming 场景必须分开：stderr 进了文件流就是坏数据）。
     */
    private static Process start(boolean mergeError, String script, String... args) throws IOException {
        List<String> command = new ArrayList<>(4 + args.length);
        command.add("sh");
        command.add("-c");
        command.add(script);
        command.add("sh"); // $0
        Collections.addAll(command, args);
        return new ProcessBuilder(command).redirectErrorStream(mergeError).start();
    }

    /** 跑一条短命令并收集输出（ls/stat/test/rm/mkdir 这类，不要用它传大文件）。 */
    static Result run(String script, int timeoutMs, String... args) throws IOException {
        Process process = start(script, args);
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

        Thread reader = new Thread(() -> {
            byte[] buf = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (buffer) {
                        int room = MAX_OUTPUT - buffer.size();
                        if (room > 0) {
                            buffer.write(buf, 0, Math.min(n, room));
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程被 destroy 时会抛，属于正常路径
            }
        }, "storage-shell-out");
        reader.setDaemon(true);
        reader.start();

        boolean finished = waitFor(process, timeoutMs);
        if (!finished) {
            process.destroy();
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String output;
        synchronized (buffer) {
            output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
        return new Result(finished ? exitCode(process) : -1, output, !finished);
    }

    /**
     * 起一个进程，把它的 stdout 通过 {@link ParcelFileDescriptor} 管道流式交给客户端。
     * stderr 收到 {@code errorSink}（失败时用来给出可读原因）。
     */
    static ParcelFileDescriptor pipe(String script, StringBuilder errorSink, String... args) throws IOException {
        final Process process = start(false, script, args);
        final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();

        Thread out = new Thread(() -> {
            byte[] buf = new byte[PIPE_BUFFER];
            try (InputStream in = process.getInputStream();
                 OutputStream toClient = new ParcelFileDescriptor.AutoCloseOutputStream(fds[1])) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    toClient.write(buf, 0, n);
                }
            } catch (IOException ignored) {
                // 客户端提前关掉（取消/取消备份）或子进程被杀：正常结束路径
            } finally {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }, "storage-shell-pipe");
        out.setDaemon(true);
        out.start();

        Thread err = new Thread(() -> {
            byte[] buf = new byte[1024];
            try (InputStream in = process.getErrorStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (errorSink != null && errorSink.length() < 4096) {
                        errorSink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
            }
        }, "storage-shell-err");
        err.setDaemon(true);
        err.start();

        return fds[0];
    }

    private static boolean waitFor(Process process, int timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                if (System.currentTimeMillis() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (Throwable t) {
                return false;
            }
        }
    }

    private static int exitCode(Process process) {
        try {
            return process.exitValue();
        } catch (Throwable t) {
            return -1;
        }
    }
}
