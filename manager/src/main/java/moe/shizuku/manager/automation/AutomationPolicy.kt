package moe.shizuku.manager.automation

/**
 * 命令白名单与元字符策略（规格 F3 / §四 安全要求）。
 *
 * 纯 Kotlin，不依赖 Android —— 这是整条链路上最容易写错、也最必须单测的部分。
 *
 * 设计要点（为什么这么写）：
 * 1. **只做一次 shell 解析**：最终执行的是 `sh -c "<command>"`，没有第二次拼接。
 *    所以注入面 = 命令里的 shell 元字符。默认全禁，用户在白名单页逐个放行。
 * 2. **白名单只认第一个 token**：`pm install x.apk` → `pm`。按规格「等于或以白名单项
 *    开头」，`pm` 命中后整条命令都放行 —— 所以白名单项等于「允许这个二进制」。
 * 3. **先查元字符、再查白名单**：元字符是「绕过前缀检查」的唯一手段（`pm ; reboot`），
 *    必须先拦住；拒绝原因里带上第一个命中的元字符，日志里能看出是哪种绕过尝试。
 */
object AutomationPolicy {

    /** 规格 F3 的默认项（按现有能力裁剪：都是 shell 身份可执行的系统命令）。 */
    val DEFAULT_PREFIXES: List<String> = listOf(
        "pm", "settings", "cmd", "am", "dumpsys", "getprop", "setprop", "wm", "input", "svc",
        "content", "appops", "device_policy",
    )

    /**
     * 默认拒绝的 shell 元字符。
     *
     * 列表比规格里列的（`; | & $ ` > < 换行`）多几个，理由写在每一项的注释里；
     * 用户在白名单页可以逐个放行（放行有确认框，说明风险）。
     */
    enum class MetaChar(val ch: Char, val key: String, val label: String) {
        SEMICOLON(';', ";", "命令分隔 ;"),
        PIPE('|', "|", "管道 |"),
        AMPERSAND('&', "&", "后台/与 &&"),
        DOLLAR('$', "$", "变量展开 $"),
        BACKTICK('`', "`", "命令替换 `"),
        REDIRECT_OUT('>', ">", "输出重定向 >"),
        REDIRECT_IN('<', "<", "输入重定向 <"),
        NEWLINE('\n', "\\n", "换行"),
        CARRIAGE_RETURN('\r', "\\r", "回车"),

        // 子 shell / 分组：`(cmd)`、`{ cmd; }` 同样能绕开前缀判断
        LPAREN('(', "(", "子 shell ("),
        RPAREN(')', ")", "子 shell )"),
        LBRACE('{', "{", "命令分组 {"),
        RBRACE('}', "}", "命令分组 }"),
        ;
    }

    /** 判定结果。`reason` 直接写日志（不含命令原文，避免二次泄漏）。 */
    data class Decision(
        val allowed: Boolean,
        /** 拒绝原因：`empty_command` / `metachar:<key>` / `not_whitelisted:<token0>` */
        val reason: String?,
        /** 命中的白名单前缀（放行时非空，用于日志） */
        val matchedPrefix: String?,
    ) {
        companion object {
            fun deny(reason: String) = Decision(false, reason, null)
            fun allow(prefix: String) = Decision(true, null, prefix)
        }
    }

    /**
     * 求值。
     *
     * @param command 调用方给的完整命令
     * @param enabledPrefixes 已启用的白名单前缀（调用方负责把「禁用的」筛掉）
     * @param allowedMeta 已放行的元字符 key 集合（[MetaChar.key]）
     */
    fun evaluate(
        command: String,
        enabledPrefixes: Collection<String>,
        allowedMeta: Collection<String>,
    ): Decision {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Decision.deny("empty_command")

        // ① 元字符：默认拒绝，用户显式放行才继续
        for (meta in MetaChar.entries) {
            if (allowedMeta.contains(meta.key)) continue
            if (trimmed.indexOf(meta.ch) >= 0) {
                // 用枚举名而不是裸字符：审计日志用 ; 分列，裸 ; 会被 sanitize 成全角，
                // 日志里就分不清到底命中的是哪一个元字符
                return Decision.deny("metachar:" + meta.name)
            }
        }

        // ② 第一个 token 必须命中白名单
        val token0 = firstToken(trimmed)
        if (token0.isEmpty()) return Decision.deny("empty_command")

        // 绝对路径不许直接写：`/system/bin/pm` 看着像 pm，实际可以是任意路径上的同名程序
        if (token0.startsWith("/") || token0.startsWith("./") || token0.startsWith("../")) {
            return Decision.deny("absolute_path:" + token0.take(40))
        }

        // 「等于或以白名单项开头」（规格 F3）。
        // 用 startsWith 而不是只认相等，是为了让 `amx` 这类扩展名二进制也被拦进白名单
        // 语义里 —— 反过来并不构成提权：bin 目录里没有更长的同名程序时，shell 只会
        // 返回 127（command not found），不会落到别的二进制上。
        val matched = enabledPrefixes
            .asSequence()
            .filter { it.isNotBlank() }
            .firstOrNull { token0 == it || token0.startsWith(it) }
            ?: return Decision.deny("not_whitelisted:" + token0.take(40))

        return Decision.allow(matched)
    }

    /** 第一个空白分隔的 token（不用正则，避免 `\s` 在 Java/Kotlin 里的口味差异）。 */
    fun firstToken(command: String): String {
        val t = command.trim()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') break
            i++
        }
        return t.substring(0, i)
    }

    /** 规范化用户输入的白名单前缀：取第一个 token、去空白。 */
    fun normalizePrefix(raw: String): String = firstToken(raw.trim()).trim()

    /**
     * 日志脱敏：把明确的密码/token 类参数的值换成 `***`，再把换行/连续空白压平。
     *
     * 两类写法都覆盖：`--password xxx` / `--password=xxx` / `password=xxx`。
     * 只认长名字（password/passwd/pass/token/secret/api_key/pin），不碰 `-p` 这种
     * 多义短选项 —— 误删参数值会让日志失去可读性，而 `-p` 在 pm/am 里根本不是密码。
     */
    fun maskSecrets(command: String): String {
        var s = command
        // ① --password xxx / --passwd=xxx / -token xxx（长选项）
        s = LONG_SECRET_WITH_SPACE.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "***" }
        // ② password=xxx / token:xxx（key=value 形式）
        s = KEY_VALUE_SECRET.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "***" }
        return s
    }

    /**
     * 写进日志的一行摘要：脱敏 → 压平 → 截断到 [AutomationContract.MAX_COMMAND_SUMMARY]。
     * 截断时用 `…` 收尾，长度不含省略号本身。
     */
    fun summarize(command: String, limit: Int = AutomationContract.MAX_COMMAND_SUMMARY): String {
        val masked = maskSecrets(command)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(WHITESPACE_RUN, " ")
            .trim()
        if (masked.length <= limit) return masked
        return masked.take((limit - 1).coerceAtLeast(0)) + "…"
    }

    /** 白名单项的展示顺序：默认项在前，自定义项在后，各自保持原顺序。 */
    fun sortForDisplay(items: List<WhitelistEntry>): List<WhitelistEntry> =
        items.filter { !it.custom } + items.filter { it.custom }

    private val WHITESPACE_RUN = Regex("\\s+")

    private const val SECRET_NAMES =
        "(?:password|passwd|passphrase|pass|pwd|token|secret|apikey|api[_-]key|pin)"

    /** 长选项：`--password xxx`、`--passwd=xxx`、`--api-key:xxx`（不区分大小写）。 */
    private val LONG_SECRET_WITH_SPACE = Regex(
        "(?i)(--?[\\w-]*$SECRET_NAMES[\\w-]*)(\\s*=\\s*|\\s+|\\s*:\\s*)(\\S+)",
    )

    /** key=value / key:value 形式。 */
    private val KEY_VALUE_SECRET = Regex(
        "(?i)\\b($SECRET_NAMES)(\\s*=\\s*|\\s*:\\s*)(\\S+)",
    )

    /**
     * 截断一个字节数组到最多 [maxBytes] 个 UTF-8 字节，且不把一个多字节字符劈成两半
     * （劈开的话 `String(bytes, UTF_8)` 会多出一个 U+FFFD，输出看起来像乱码）。
     */
    fun truncateUtf8(bytes: ByteArray, maxBytes: Int): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size <= maxBytes) return String(bytes, Charsets.UTF_8)
        // 从 maxBytes 往回退到字符边界：UTF-8 的续字节形如 10xxxxxx
        var end = maxBytes
        var guard = 0
        while (end > 0 && guard < 4 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
            guard++
        }
        if (end <= 0) end = maxBytes
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}

/** 白名单里的一项（持久化结构，见 [AutomationStore]）。 */
data class WhitelistEntry(
    val prefix: String,
    val enabled: Boolean,
    /** 是否是用户自己加的（默认项不可删除，只能禁用） */
    val custom: Boolean,
)
