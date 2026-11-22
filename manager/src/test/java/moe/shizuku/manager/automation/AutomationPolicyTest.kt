package moe.shizuku.manager.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动化功能的**安全边界**回归测试（纯 JVM，不需要模拟器）。
 *
 * 覆盖：白名单前缀匹配、shell 元字符拒绝/放行、绝对路径拒绝、日志脱敏与截断、
 * UTF-8 截断不劈字符、请求解析与超时夹取、历史筛选、状态归类。
 *
 * 运行：
 * ```
 * java -classpath gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
 *   :manager:testDebugUnitTest --tests "*AutomationPolicyTest*"
 * ```
 */
class AutomationPolicyTest {

    private val defaults = AutomationPolicy.DEFAULT_PREFIXES
    private val noMeta = emptySet<String>()

    // ══════════════ F3 白名单 ══════════════

    @Test
    fun `default whitelist matches the spec`() {
        assertEquals(
            listOf(
                "pm", "settings", "cmd", "am", "dumpsys", "getprop", "setprop", "wm", "input", "svc",
                "content", "appops", "device_policy",
            ),
            AutomationPolicy.DEFAULT_PREFIXES,
        )
    }

    @Test
    fun `whitelisted command passes`() {
        val d = AutomationPolicy.evaluate("pm install /data/local/tmp/a.apk", defaults, noMeta)
        assertTrue(d.allowed)
        assertEquals("pm", d.matchedPrefix)
        assertNull(d.reason)
    }

    @Test
    fun `leading and trailing whitespace is ignored`() {
        val d = AutomationPolicy.evaluate("   getprop ro.build.version.sdk   ", defaults, noMeta)
        assertTrue(d.allowed)
        assertEquals("getprop", d.matchedPrefix)
    }

    @Test
    fun `command outside the whitelist is denied`() {
        val d = AutomationPolicy.evaluate("reboot", defaults, noMeta)
        assertFalse(d.allowed)
        assertEquals("not_whitelisted:reboot", d.reason)
    }

    @Test
    fun `uppercase token does not match`() {
        // 白名单区分大小写：Linux 里没有 PM 这个二进制
        val d = AutomationPolicy.evaluate("PM list packages", defaults, noMeta)
        assertFalse(d.allowed)
        assertTrue(d.reason!!.startsWith("not_whitelisted:"))
    }

    @Test
    fun `absolute path is denied even if the basename is whitelisted`() {
        // /data/local/tmp/pm 是一个攻击者能放进去的同名程序：不能因为名字像就放行
        val d = AutomationPolicy.evaluate("/data/local/tmp/pm list packages", defaults, noMeta)
        assertFalse(d.allowed)
        assertTrue(d.reason!!.startsWith("absolute_path:"))
    }

    @Test
    fun `relative path is denied`() {
        assertFalse(AutomationPolicy.evaluate("./pm list packages", defaults, noMeta).allowed)
        assertFalse(AutomationPolicy.evaluate("../bin/pm list", defaults, noMeta).allowed)
    }

    @Test
    fun `disabled prefix does not pass`() {
        val onlyPm = listOf("pm")
        assertFalse(AutomationPolicy.evaluate("settings get global x", onlyPm, noMeta).allowed)
    }

    @Test
    fun `empty command is denied`() {
        assertEquals("empty_command", AutomationPolicy.evaluate("   ", defaults, noMeta).reason)
    }

    // ══════════════ F3 元字符 ══════════════

    @Test
    fun `every default-denied metacharacter blocks the command`() {
        val samples = mapOf(
            "pm ; reboot" to "SEMICOLON",
            "pm | sh" to "PIPE",
            "pm & reboot" to "AMPERSAND",
            "pm \$HOME" to "DOLLAR",
            "pm `id`" to "BACKTICK",
            "pm > /data/local/tmp/x" to "REDIRECT_OUT",
            "pm < /etc/hosts" to "REDIRECT_IN",
            "pm install a\nreboot" to "NEWLINE",
            "pm \r reboot" to "CARRIAGE_RETURN",
            "pm (reboot)" to "LPAREN",
            "pm {reboot}" to "LBRACE",
        )
        samples.forEach { (command, metaKey) ->
            val d = AutomationPolicy.evaluate(command, defaults, noMeta)
            assertFalse("should deny: $command", d.allowed)
            assertEquals("metachar:$metaKey", d.reason)
        }
    }

    @Test
    fun `metachar scanning happens before prefix matching`() {
        // 首词明明是白名单里的 pm，但命令里带了 ; —— 必须先拒
        val d = AutomationPolicy.evaluate("pm list packages; reboot", defaults, noMeta)
        assertEquals("metachar:SEMICOLON", d.reason)
    }

    @Test
    fun `explicitly allowed metachar passes the syntax check`() {
        val d = AutomationPolicy.evaluate("pm list packages -3", defaults, setOf(";"))
        assertTrue(d.allowed)

        // 放行「;」之后，串联确实能过（这是用户显式选择的风险，必须在 UI 上确认过）
        val chained = AutomationPolicy.evaluate("dumpsys battery; reboot", defaults, setOf(";"))
        assertTrue(chained.allowed)
        assertEquals("dumpsys", chained.matchedPrefix)
    }

    @Test
    fun `firstToken splits on any whitespace`() {
        assertEquals("pm", AutomationPolicy.firstToken("  pm\tinstall"))
        assertEquals("", AutomationPolicy.firstToken("   "))
    }

    @Test
    fun `normalizePrefix keeps only the first token`() {
        assertEquals("mycmd", AutomationPolicy.normalizePrefix("  mycmd --flag "))
    }

    // ══════════════ F5 脱敏与摘要 ══════════════

    @Test
    fun `secret option values are masked`() {
        assertEquals(
            "settings put global wifi --password ***",
            AutomationPolicy.maskSecrets("settings put global wifi --password hunter2"),
        )
        assertEquals(
            "am set-debug-app --passwd=***",
            AutomationPolicy.maskSecrets("am set-debug-app --passwd=p@ss"),
        )
        assertEquals(
            "cmd foo password=***",
            AutomationPolicy.maskSecrets("cmd foo password=abc123"),
        )
        assertEquals(
            "cmd foo token:***",
            AutomationPolicy.maskSecrets("cmd foo token:deadbeef"),
        )
    }

    @Test
    fun `non-secret short options are left alone`() {
        // -p 在 pm 里是「只显示包名」，误打码会让日志失去价值
        assertEquals("pm list packages -p", AutomationPolicy.maskSecrets("pm list packages -p"))
    }

    @Test
    fun `summary is truncated and flattened`() {
        val long = "pm install " + "a".repeat(300)
        val summary = AutomationPolicy.summarize(long)
        assertTrue(summary.length <= AutomationContract.MAX_COMMAND_SUMMARY)
        assertTrue(summary.endsWith("…"))
        assertFalse(summary.contains("\n"))

        val multiLine = "pm list\n packages\t-3"
        assertEquals("pm list packages -3", AutomationPolicy.summarize(multiLine))
    }

    @Test
    fun `summary masks before truncating`() {
        val command = "am broadcast --token " + "x".repeat(200)
        val summary = AutomationPolicy.summarize(command)
        assertFalse(summary.contains("xxxxx"))
        assertTrue(summary.contains("***"))
    }

    // ══════════════ F4 截断 ══════════════

    @Test
    fun `utf8 truncation keeps the byte budget`() {
        val ascii = "a".repeat(200).toByteArray()
        assertEquals(64, AutomationPolicy.truncateUtf8(ascii, 64).length)
        assertEquals(200, AutomationPolicy.truncateUtf8(ascii, 4096).length)
    }

    @Test
    fun `utf8 truncation never splits a multi byte char`() {
        // 每个汉字 3 字节。上限 15 = 正好卡在字符边界 → 5 个字，不该出现替换符
        val text = "自动化令牌测试".toByteArray(Charsets.UTF_8)
        val exact = AutomationPolicy.truncateUtf8(text, 15)
        assertEquals("自动化令牌", exact)

        // 上限 14 = 切在第 6 个字的中间 → 必须退到 12 字节（4 个字），
        // 而不是吐出「自动化令牌 + 半个字符」那种肉眼可见的乱码
        val cut = AutomationPolicy.truncateUtf8(text, 14)
        assertEquals("自动化令", cut)
        assertFalse(cut.contains('\uFFFD'))
    }

    // ══════════════ 协议解析 ══════════════

    @Test
    fun `missing fields produce BAD_REQUEST`() {
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest(null, "pm", "t", 0).error)
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest("rid", null, "t", 0).error)
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest("rid", "pm", "", 0).error)
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest(" ", "pm", "t", 0).error)
    }

    @Test
    fun `request id with log separators is rejected`() {
        // requestId 会原样写进「逗号分列」的审计日志：带换行/逗号会串列
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest("a,b", "pm", "t", 0).error)
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest("a\nb", "pm", "t", 0).error)
        assertEquals(AutomationContract.ERR_BAD_REQUEST, parseRequest("a".repeat(129), "pm", "t", 0).error)
    }

    @Test
    fun `valid request is parsed and timeout clamped`() {
        val parsed = parseRequest("3f2b1c1e", " getprop ro.build.version.sdk ", "tok", 0)
        assertNull(parsed.error)
        assertNotNull(parsed.request)
        assertEquals("3f2b1c1e", parsed.request!!.requestId)
        assertEquals(" getprop ro.build.version.sdk ", parsed.request.command)
        assertEquals(AutomationContract.DEFAULT_TIMEOUT_MS, parsed.request.timeoutMs)
        assertEquals("tok", parsed.token)
    }

    @Test
    fun `timeout clamp covers the whole range`() {
        assertEquals(5_000, AutomationContract.clampTimeout(1))
        assertEquals(AutomationContract.DEFAULT_TIMEOUT_MS, AutomationContract.clampTimeout(-100))
        assertEquals(AutomationContract.DEFAULT_TIMEOUT_MS, AutomationContract.clampTimeout(0))
        assertEquals(45_000, AutomationContract.clampTimeout(45_000))
        assertEquals(300_000, AutomationContract.clampTimeout(999_999))
    }

    // ══════════════ F5 历史与状态 ══════════════

    @Test
    fun `history status classification`() {
        assertEquals(HistoryStatus.OK, HistoryStatus.of(null, 0))
        assertEquals(HistoryStatus.ERROR, HistoryStatus.of(null, 1))
        assertEquals(HistoryStatus.TIMEOUT, HistoryStatus.of(AutomationContract.ERR_TIMEOUT, -1))
        assertEquals(HistoryStatus.DENIED, HistoryStatus.of(AutomationContract.ERR_DENIED_TOKEN, -1))
        assertEquals(HistoryStatus.DENIED, HistoryStatus.of(AutomationContract.ERR_DENIED_SIGNATURE, -1))
        assertEquals(HistoryStatus.DENIED, HistoryStatus.of(AutomationContract.ERR_DENIED_WHITELIST, -1))
        assertEquals(HistoryStatus.DENIED, HistoryStatus.of(AutomationContract.ERR_BAD_REQUEST, -1))
        assertEquals(HistoryStatus.DENIED, HistoryStatus.of(AutomationContract.REASON_NOT_EXPLICIT, -1))
        assertEquals(HistoryStatus.ERROR, HistoryStatus.of(AutomationContract.ERR_NO_PRIVILEGE, -1))
        assertEquals(HistoryStatus.ERROR, HistoryStatus.of(AutomationContract.ERR_EXEC_FAILED, -1))
    }

    @Test
    fun `history filter by caller status and time`() {
        val now = 1_700_000_000_000L
        val entries = listOf(
            entry("a", now, HistoryStatus.OK),
            entry("b", now, HistoryStatus.DENIED),
            entry("a", now - 7_200_000L, HistoryStatus.OK),
        )
        assertEquals(2, filterHistory(entries, caller = "a").size)
        assertEquals(1, filterHistory(entries, status = HistoryStatus.DENIED).size)
        assertEquals(2, filterHistory(entries, since = now - 3_600_000L).size)
        assertEquals(1, filterHistory(entries, caller = "a", status = HistoryStatus.OK, since = now - 3_600_000L).size)
        assertEquals(3, filterHistory(entries).size)
        assertEquals(3, filterHistory(entries, caller = null, status = null, since = 0L).size)
    }

    @Test
    fun `history callers are deduplicated and sorted`() {
        val now = 1L
        val entries = listOf(
            entry("b", now, HistoryStatus.OK),
            entry("a", now, HistoryStatus.OK),
            entry("b", now, HistoryStatus.ERROR),
        )
        assertEquals(listOf("a", "b"), historyCallers(entries))
    }

    @Test
    fun `whitelist display order keeps defaults before custom entries`() {
        val items = listOf(
            WhitelistEntry("mine", enabled = true, custom = true),
            WhitelistEntry("pm", enabled = true, custom = false),
            WhitelistEntry("getprop", enabled = false, custom = false),
        )
        assertEquals(
            listOf("pm", "getprop", "mine"),
            AutomationPolicy.sortForDisplay(items).map { it.prefix },
        )
    }

    private fun entry(pkg: String, time: Long, status: String) = HistoryEntry(
        requestId = "r-$pkg-$time",
        time = time,
        callerPackage = pkg,
        callerUid = 10000,
        tokenLabel = "t",
        commandSummary = "pm list packages",
        exitCode = 0,
        durationMs = 10,
        status = status,
        reason = null,
        truncated = false,
    )
}
