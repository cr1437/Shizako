package rikka.shizuku.server.storage;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 存储代理的路径白名单与注入防线（规格 F2「路径校验」）。
 *
 * 被 IStorageProxy 的**每一个**方法在入口调用一次，之后才允许碰文件系统：
 * - 拒绝相对路径、控制字符、shell 元字符；
 * - 拒绝任何 {@code ..} 段（**先按字符串段判断，再 canonicalize**：即使用户写
 *   `/sdcard/Android/data/..%2F..` 这种编码，或者符号链接指出去，canonical 这一步
 *   也会把它落回真实路径，再和白名单根目录比一次）；
 * - canonical 之后必须落在 {@link #ALLOWED_ROOTS} 之内，否则拒绝（fail closed）。
 *
 * 为什么连 shell 元字符都要在**通用**路径校验里拒绝：降级层（shell 管道 / run-as /
 * content）最终会把路径交给 `sh`。所有调用点确实都用了 argv 传参或单引号转义，
 * 但「路径里根本不允许出现元字符」不依赖每个调用点的记性，后续加新操作也不会破防。
 */
public final class StoragePaths {

    private StoragePaths() {
    }

    /** 允许访问的根目录（canonical 前缀匹配）。 */
    public static final String[] ALLOWED_ROOTS = {
            "/data/data",          // /data/data/<pkg>（root 或 run-as 才有权限）
            "/data/user",          // /data/user/<userId>/<pkg>
            "/data/app",           // APK 本体（只读方向使用）
            "/data/local/tmp",     // 管理器与服务的交换目录
            "/sdcard",             // 外部存储（FUSE 视图）
            "/storage/emulated",   // /sdcard 的真实路径
            "/mnt/user",           // 旧版 /storage/emulated 路径
            "/data/media",         // /sdcard 的后备路径
    };

    private static final int MAX_LENGTH = 4096;

    /**
     * 会进 shell 命令行的字符。空格**不在**里面：所有调用点都按单个 argv 传参
     * （`sh -c '...' sh <path>` 里的 $1），空格不会重新分词。
     *
     * `~` **必须**放行：Android 10+ 的 `/data/app` 目录名是 `~~<hash>==/`，
     * 应用 APK 路径天然带 `~`（模拟器实测：拒绝 `~` 会让「同时保存 APK」对用户应用也失败）。
     * 传参是 `"$1"` 双引号内的变量展开，结果不会再做波浪号展开/通配，安全。
     */
    private static final String SHELL_META = ";|&$`()<>{}[]*?\"'\\";

    /** content:// URI 是否（大小写不敏感，Android 习惯小写，但接受大写）。 */
    public static boolean isContentUri(String path) {
        return path != null && path.regionMatches(true, 0, "content://", 0, 10);
    }

    /**
     * 绝对路径校验，返回 canonical 路径。违规抛 {@link SecurityException}
     * （Binder 专门有 SecurityException 的跨进程序列化，客户端能拿到原始类型）。
     */
    public static String check(String path) {
        if (path == null) {
            throw deny("path is null");
        }
        if (path.isEmpty()) {
            throw deny("path is empty");
        }
        if (path.length() > MAX_LENGTH) {
            throw deny("path is too long");
        }
        if (!path.startsWith("/")) {
            throw deny("path must be absolute");
        }
        if (hasShellMetacharacter(path)) {
            throw deny("control or shell metacharacter");
        }

        // 先按「段」拒绝 ..：canonical 也能挡住，但先拒可以给出更准确的错误，
        // 并且堵住「canonical 之前就已经被别的组件解析过一遍」的时序问题。
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw deny("path traversal '..'");
            }
        }

        String canonical;
        try {
            canonical = new File(path).getCanonicalPath();
        } catch (IOException e) {
            throw deny("cannot canonicalize path");
        }
        if (!isInsideAllowedRoot(canonical)) {
            throw deny("path is outside allowed roots");
        }
        return canonical;
    }

    /**
     * 字符层面检查（不碰文件系统），被 {@link #check} 复用，单测可直接覆盖。
     *
     * 单独抽出来的原因：Android 10+ 的 `/data/app/~~<hash>==/…/base.apk` 必须**通过**这里
     * （见 {@link #SHELL_META} 的说明），而 Windows 上跑单测时 `File` 无法接受 `/data/app`
     * 绝对路径，只能在这一层断言。
     */
    public static boolean hasShellMetacharacter(String path) {
        if (path == null) {
            return true;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
            if (SHELL_META.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** content:// 校验：只做注入面检查（URI 没有 ../ 穿越语义）。 */
    public static String checkContentUri(String uri) {
        if (uri == null || uri.length() < 10 || uri.length() > MAX_LENGTH) {
            throw deny("bad content uri");
        }
        String scheme = uri.substring(0, 10);
        if (!scheme.equalsIgnoreCase("content://")) {
            throw deny("not a content uri");
        }
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw deny("control character in uri");
            }
            if (SHELL_META.indexOf(c) >= 0) {
                throw deny("shell metacharacter in uri");
            }
            if (Character.isWhitespace(c)) {
                throw deny("whitespace in uri");
            }
        }
        // 至少要有 authority：content://<authority>/
        int slash = uri.indexOf('/', 10);
        if (slash < 0 || slash == 10) {
            throw deny("content uri has no authority");
        }
        return uri;
    }

    /** 任何路径：content:// 走 URI 校验，其余走绝对路径校验。 */
    public static String checkAny(String path) {
        return isContentUri(path) ? checkContentUri(path) : check(path);
    }

    public static boolean isInsideAllowedRoot(String canonicalPath) {
        for (String root : ALLOWED_ROOTS) {
            if (canonicalPath.equals(root)) {
                return true;
            }
            if (canonicalPath.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 允许根目录本身或它们的祖先（如 `/sdcard/Android`、`/data`）。
     * delete 用：删掉一个根目录会连带删掉所有人的备份/数据，必须拒绝。
     */
    public static boolean isRootOrAncestor(String canonicalPath) {
        for (String root : ALLOWED_ROOTS) {
            if (root.equals(canonicalPath) || root.startsWith(canonicalPath + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 删除保护（比 {@link #isRootOrAncestor} 更严）：
     * 除了根目录与它们的祖先，还保护「所有人的外部数据总目录」——
     * `/sdcard/Android`、`/sdcard/Android/data`、`/sdcard/Android/obb`。
     * 删掉它们等于把设备上所有应用的外部数据一起清空，接口层面直接堵死。
     */
    public static boolean isDeleteForbidden(String canonicalPath) {
        if (isRootOrAncestor(canonicalPath)) {
            return true;
        }
        for (String protectedPath : PROTECTED_CONTAINERS) {
            if (protectedPath.equals(canonicalPath)
                    || protectedPath.startsWith(canonicalPath + "/")) {
                return true;
            }
        }
        return false;
    }

    private static final String[] PROTECTED_CONTAINERS = {
            "/sdcard/Android",
            "/sdcard/Android/data",
            "/sdcard/Android/obb",
            "/sdcard/Android/media",
    };

    /**
     * 从路径里取出应用包名（run-as 只能对这个包用）。
     *
     * 支持 /data/data/<pkg>、/data/user/<n>/<pkg>、…/Android/data/<pkg>、
     * …/Android/obb/<pkg>；取不到或格式不合法返回 null（调用方据此跳过 run-as 层）。
     */
    public static String packageOf(String canonicalPath) {
        if (canonicalPath == null) {
            return null;
        }
        String[] parts = canonicalPath.split("/");
        String candidate = null;

        if (canonicalPath.startsWith("/data/data/") && parts.length >= 4) {
            candidate = parts[3];
        } else if (canonicalPath.startsWith("/data/user/") && parts.length >= 5) {
            candidate = parts[4];
        } else {
            for (int i = 0; i + 2 < parts.length; i++) {
                if ("Android".equals(parts[i])
                        && ("data".equals(parts[i + 1]) || "obb".equals(parts[i + 1]))) {
                    candidate = parts[i + 2];
                    break;
                }
            }
        }
        return isPackageName(candidate) ? candidate : null;
    }

    /** 包名白名单格式：字母开头、点分段、只允许字母数字下划线（run-as 的参数）。 */
    public static boolean isPackageName(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) {
            return false;
        }
        boolean segmentStart = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.') {
                if (segmentStart) {
                    return false;
                }
                segmentStart = true;
            } else if (c == '_' || (c >= '0' && c <= '9' && !segmentStart)
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                segmentStart = false;
            } else {
                return false;
            }
        }
        return !segmentStart;
    }

    /** 审计用的路径摘要：不进完整路径，只留最后一段 + 路径哈希。 */
    public static String digest(String path) {
        if (path == null) {
            return "-";
        }
        String tail = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) {
            tail = path.substring(slash + 1);
        }
        if (tail.length() > 48) {
            tail = tail.substring(0, 48);
        }
        return tail + "#" + hash8(path);
    }

    private static String hash8(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "00000000";
        }
    }

    private static SecurityException deny(String why) {
        return new SecurityException("safePath rejected: " + why);
    }
}
