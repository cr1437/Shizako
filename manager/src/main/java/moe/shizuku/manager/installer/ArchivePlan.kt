package moe.shizuku.manager.installer

/**
 * XAPK / APKS 的**归档目录解析**（规格 F6/F7），纯逻辑：输入 ZIP 里有什么、以及
 * manifest.json / toc.pb 的内容，输出「要写进 PackageInstaller 会话的 split 列表」。
 *
 * 这一层刻意不碰 `ZipFile` / `Context`，因为「该装哪几个 split、base 是哪个、
 * 旧格式怎么回退」才是容易出错的部分，必须能在 JVM 单测里对着构造的条目跑。
 * 真正解压、读签名、提权安装分别在 [PackageArchive] / [SessionInstaller] 里。
 */

/** 归档解析失败的分类（展示文案在 UI 层）。 */
enum class ArchiveError {
    /** ZIP 里既没有 AndroidManifest.xml、也不像 XAPK/APKS。 */
    UNKNOWN_ARCHIVE,

    /** 一个 .apk 都没找到。 */
    NO_APK,

    /** 找不到 base APK（只有 split 不是可安装的包）。 */
    MISSING_BASE,

    /** manifest.json / toc.pb 指向的条目在 ZIP 里不存在。 */
    MISSING_SPLIT_ENTRY,

    /** 两个 split 归一化后同名，写进会话会互相覆盖。 */
    DUPLICATE_SPLIT,
}

class ArchiveException(
    val code: ArchiveError,
    val detail: String? = null,
) : Exception("$code${if (detail.isNullOrBlank()) "" else ": $detail"}")

/** split 的来源，写进安装日志便于排查「为什么装进去的是这几个」。 */
enum class PlanSource {
    PLAIN_APK,
    XAPK_MANIFEST,
    XAPK_ZIP_SCAN,
    TOC_PB,
    APKS_ZIP_SCAN,
}

/** 一个待写入会话的 split。[sessionName] 是 `pm install-write` 的 name（base 必须是 `base.apk`）。 */
data class SplitSpec(
    val sessionName: String,
    val entryPath: String,
    val isBase: Boolean,
    val id: String? = null,
)

/** OBB 扩展文件：装完 APK 后复制到 `/sdcard/Android/obb/<包名>/`。 */
data class ObbSpec(val entryPath: String, val fileName: String)

data class ArchivePlan(
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val splits: List<SplitSpec>,
    val obbs: List<ObbSpec>,
    val source: PlanSource,
) {
    val base: SplitSpec
        get() = splits.first { it.isBase }
}

object ArchivePlanner {

    /** PackageInstaller 要求 base 的写入名就是 `base.apk`；其它名字只用于会话内去重。 */
    const val BASE_SESSION_NAME = "base.apk"

    /** 单个 APK（含 `xxx.apk.1`）：整份文件就是 base。 */
    fun planPlainApk(): ArchivePlan = ArchivePlan(
        packageName = null,
        versionName = null,
        versionCode = 0L,
        splits = listOf(SplitSpec(BASE_SESSION_NAME, "base.apk", isBase = true, id = "base")),
        obbs = emptyList(),
        source = PlanSource.PLAIN_APK,
    )

    /**
     * 统一入口：按格式 + 探测到的内容选解析器；manifest/toc 缺失或坏掉时按规格回退到
     * 「扫描 ZIP 内所有 .apk」。任何解析器都必须产出「恰好一个 base」。
     */
    fun plan(
        format: PackageFormat,
        entries: List<String>,
        manifestJson: String?,
        tocBytes: ByteArray?,
    ): ArchivePlan = when (format) {
        PackageFormat.XAPK -> if (manifestJson != null) {
            planXapk(entries, manifestJson)
        } else {
            planZipScan(entries, PlanSource.XAPK_ZIP_SCAN)
        }

        PackageFormat.APKS -> if (tocBytes != null) {
            planApks(entries, tocBytes)
        } else {
            planZipScan(entries, PlanSource.APKS_ZIP_SCAN)
        }

        PackageFormat.APK, PackageFormat.APK1 -> planPlainApk()
    }

    // ---------------- XAPK ----------------

    /**
     * XAPK 的 `manifest.json`：
     * ```json
     * {"package_name":"com.foo","version_name":"1.2","version_code":"120",
     *  "split_apks":[{"file":"base.apk","id":"base"},
     *                {"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"}],
     *  "obb":[{"file":"Android/obb/com.foo/main.120.com.foo.obb"}]}
     * ```
     * 老 XAPK 可能没有 `split_apks`（只有一个 APK，或者额外 split 只是散在 ZIP 里），
     * 这时按 ZIP 扫描补齐，但包名/版本仍用 manifest 里的。
     */
    fun planXapk(entries: List<String>, manifestJson: String): ArchivePlan {
        val known = entries.toHashSet()
        val root = try {
            MiniJson.asObject(MiniJson.parse(manifestJson))
                ?: throw ArchiveException(ArchiveError.UNKNOWN_ARCHIVE, "manifest.json 不是对象")
        } catch (e: ArchiveException) {
            throw e
        } catch (t: Throwable) {
            // manifest 坏了：按规格退回 ZIP 扫描，而不是直接失败（很多 XAPK 的 manifest 是手改过的）
            return planZipScan(entries, PlanSource.XAPK_ZIP_SCAN)
        }

        val packageName = MiniJson.string(root, "package_name")
        val versionName = MiniJson.string(root, "version_name")
        val versionCode = MiniJson.long(root, "version_code") ?: 0L

        val listed = ArrayList<Pair<String, String?>>() // entryPath to id
        MiniJson.asArray(root["split_apks"])?.forEach { item ->
            val map = MiniJson.asObject(item) ?: return@forEach
            val file = MiniJson.string(map, "file")?.trim()?.trimStart('/') ?: return@forEach
            if (!file.endsWith(".apk", ignoreCase = true)) return@forEach
            if (!known.contains(file)) {
                throw ArchiveException(ArchiveError.MISSING_SPLIT_ENTRY, file)
            }
            listed.add(file to MiniJson.string(map, "id"))
        }

        if (listed.isEmpty()) {
            // 老格式：manifest 没列 split，退回 ZIP 扫描（保留 manifest 的包名/版本）
            val scanned = planZipScan(entries, PlanSource.XAPK_ZIP_SCAN)
            return scanned.copy(packageName = packageName ?: scanned.packageName, versionName = versionName, versionCode = versionCode)
        }

        val splits = buildSplits(listed)
        val plan = ArchivePlan(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            splits = splits,
            obbs = obbsOf(root, entries),
            source = PlanSource.XAPK_MANIFEST,
        )
        return plan.validated()
    }

    // ---------------- APKS ----------------

    /**
     * APKS（bundletool 产物）：`toc.pb` 里按 `BuildApksResult.variant[].apk_set[].apk_description[].path`
     * 记着每个 split 的相对路径。字段号对不上（不同 bundletool 版本）或解析不出路径时，
     * 退回「在 toc.pb 里扫出以 .apk 结尾的字符串」；再退一步按规格扫描 ZIP。
     */
    fun planApks(entries: List<String>, tocBytes: ByteArray): ArchivePlan {
        val known = entries.toHashSet()
        val paths = TocPb.parsePaths(tocBytes)
            .map { it.trimStart('/') }
            .filter { it.endsWith(".apk", ignoreCase = true) }
            .distinct()
            .filter { known.contains(it) }

        if (paths.isEmpty()) {
            return planZipScan(entries, PlanSource.APKS_ZIP_SCAN)
        }

        val listed = paths.map { it to (it.substringAfterLast('/').removeSuffix(".apk")) }
        val plan = ArchivePlan(
            packageName = null,
            versionName = null,
            versionCode = 0L,
            splits = buildSplits(listed),
            // APKS 没有 OBB（扩展文件走 asset pack / 手工），规格 F7 也写了「无 OBB」
            obbs = emptyList(),
            source = PlanSource.TOC_PB,
        )
        return plan.validated()
    }

    // ---------------- ZIP 扫描（回退） ----------------

    /**
     * 扫描 ZIP 里所有 `.apk` 当 split。base 的判定：
     * 1. 名字正好是 `base.apk`；
     * 2. 只有一个 APK → 就是它；
     * 3. 名字里带 `base` 且不带 `config.` 的第一个（bundletool 的 `base-master.apk`）；
     * 4. 否则取第一个（后面 `pm install-commit` 会给出明确失败原因，总比直接放弃好）。
     */
    fun planZipScan(entries: List<String>, source: PlanSource): ArchivePlan {
        val apks = entries
            .filter { !it.endsWith("/") }
            .filter { it.endsWith(".apk", ignoreCase = true) }
            .distinct()
        if (apks.isEmpty()) throw ArchiveException(ArchiveError.NO_APK)

        val basePath = apks.firstOrNull { it.substringAfterLast('/').equals("base.apk", true) }
            ?: apks.singleOrNull()
            ?: apks.firstOrNull {
                val n = it.substringAfterLast('/').lowercase()
                n.contains("base") && !n.startsWith("config.")
            }
            ?: apks.first()

        val splits = buildSplits(apks.map { it to null }.toList(), forcedBase = basePath)
        return ArchivePlan(
            packageName = null,
            versionName = null,
            versionCode = 0L,
            splits = splits,
            obbs = obbsOf(null, entries),
            source = source,
        ).validated()
    }

    // ---------------- 公共部分 ----------------

    private fun buildSplits(
        listed: List<Pair<String, String?>>,
        forcedBase: String? = null,
    ): List<SplitSpec> {
        // base 的位置：优先 manifest 明确标了 id="base" 的，其次名字是 base.apk 的
        val basePath = forcedBase
            ?: listed.firstOrNull { it.second == "base" }?.first
            ?: listed.firstOrNull { it.first.substringAfterLast('/').equals("base.apk", true) }?.first
            ?: listed.firstOrNull {
                val n = it.first.substringAfterLast('/').lowercase()
                n.contains("base") && !n.startsWith("config.")
            }?.first
            ?: listed.firstOrNull()?.first

        val used = HashSet<String>()
        val out = ArrayList<SplitSpec>(listed.size)
        // base 排在最前面：pm install-write 的顺序不影响结果，但日志里更好读
        for ((path, id) in listed.sortedBy { if (it.first == basePath) 0 else 1 }) {
            val isBase = path == basePath
            var name = if (isBase) BASE_SESSION_NAME else sessionNameOf(path)
            if (!used.add(name)) {
                // 同名（例如 `splits/base.apk` 与 `base.apk`）：加后缀而不是静默覆盖
                var index = 2
                while (!used.add("${name.removeSuffix(".apk")}-$index.apk")) index++
                name = "${name.removeSuffix(".apk")}-$index.apk"
            }
            out.add(SplitSpec(sessionName = name, entryPath = path, isBase = isBase, id = id))
        }
        return out
    }

    private fun sessionNameOf(path: String): String {
        val base = path.substringAfterLast('/')
        val safe = base.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("")
        val trimmed = safe.trimStart('.')
        val name = if (trimmed.endsWith(".apk", ignoreCase = true)) trimmed else "$trimmed.apk"
        return name.ifBlank { "split.apk" }
    }

    private fun obbsOf(root: Map<String, Any?>?, entries: List<String>): List<ObbSpec> {
        val out = LinkedHashMap<String, ObbSpec>()
        val existing = entries.toHashSet()
        // manifest.json 里显式列的（**只收 ZIP 里真有的**：manifest 写了但文件缺的 OBB
        // 不该让整个安装失败 —— 规格只要求「OBB 未安装」提示，APK 照装）
        MiniJson.asArray(root?.get("obb"))?.forEach { item ->
            val map = MiniJson.asObject(item)
            val file = MiniJson.string(map, "file")?.trimStart('/')
                ?: (item as? String)?.trimStart('/')
            if (!file.isNullOrBlank() && file.endsWith(".obb", ignoreCase = true) && existing.contains(file)) {
                out[file] = ObbSpec(file, file.substringAfterLast('/'))
            }
        }
        // ZIP 里实际有的（有些 XAPK 不写 obb 字段，但文件就在里面）
        entries.forEach { entry ->
            if (entry.endsWith(".obb", ignoreCase = true) && !entry.endsWith("/")) {
                out.putIfAbsent(entry, ObbSpec(entry, entry.substringAfterLast('/')))
            }
        }
        return out.values.toList()
    }

    private fun ArchivePlan.validated(): ArchivePlan {
        val bases = splits.count { it.isBase }
        if (bases == 0) throw ArchiveException(ArchiveError.MISSING_BASE)
        if (bases > 1) throw ArchiveException(ArchiveError.MISSING_BASE, "多个 base")
        if (splits.isEmpty()) throw ArchiveException(ArchiveError.NO_APK)
        return this
    }
}

/**
 * `toc.pb` 的最小 protobuf 读取。
 *
 * 结构（bundletool `commands.proto`）：
 * ```
 * BuildApksResult { repeated Variant variant = 2; ... }
 * Variant         { repeated ApkSet apk_set = 2; ... }
 * ApkSet          { ModuleMetadata module_metadata = 1; repeated ApkDescription apk_description = 2; }
 * ApkDescription  { string path = 1; ApkTargeting targeting = 2; ... }
 * ```
 * 字段号在 bundletool 不同版本里可能挪动，所以**两条路**：先按上面结构走；走不出来
 * 就把 toc.pb 当字节流扫「以 .apk 结尾的可打印串」。两条都空 → 调用方回退到 ZIP 扫描。
 */
internal object TocPb {

    fun parsePaths(bytes: ByteArray): List<String> {
        val out = LinkedHashSet<String>()
        runCatching { walk(bytes, 0, out) }
        if (out.isEmpty()) scanAscii(bytes, out)
        return out.toList()
    }

    private fun walk(bytes: ByteArray, depth: Int, out: MutableSet<String>) {
        var pos = 0
        while (pos < bytes.size) {
            val (key, next) = readVarint(bytes, pos) ?: return
            pos = next
            val field = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            when (wire) {
                2 -> {
                    val (len, afterLen) = readVarint(bytes, pos) ?: return
                    if (len < 0 || afterLen + len > bytes.size) return
                    val payload = bytes.copyOfRange(afterLen, afterLen + len.toInt())
                    pos = afterLen + len.toInt()
                    when (depth) {
                        0 -> if (field == 2) walk(payload, 1, out)
                        1 -> if (field == 2) walk(payload, 2, out)
                        2 -> if (field == 2) walk(payload, 3, out)
                        3 -> if (field == 1) {
                            val path = String(payload, Charsets.UTF_8)
                            if (path.endsWith(".apk", ignoreCase = true)) out.add(path)
                        }
                    }
                }

                0 -> pos = skipVarint(bytes, pos) ?: return
                1 -> pos += 8
                5 -> pos += 4
                else -> return
            }
            if (pos > bytes.size) return
        }
    }

    private fun scanAscii(bytes: ByteArray, out: MutableSet<String>) {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) {
                sb.append(c.toChar())
            } else {
                take(sb, out)
            }
        }
        take(sb, out)
    }

    private fun take(sb: StringBuilder, out: MutableSet<String>) {
        if (sb.endsWith(".apk")) {
            // 前面的字节可能是长度前缀/其它字段内容，从最后一个允许的路径分隔符处截断
            val text = sb.toString()
            val start = text.indexOfFirst { it == '/' || it == '-' || it.isLetter() }
            if (start >= 0) out.add(text.substring(start))
        }
        sb.setLength(0)
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun skipVarint(bytes: ByteArray, start: Int): Int? =
        readVarint(bytes, start)?.second
}
