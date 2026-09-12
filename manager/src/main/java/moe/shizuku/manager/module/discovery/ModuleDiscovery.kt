package moe.shizuku.manager.module.discovery

import moe.shizuku.manager.ShizukuSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 模块目录发现（照搬 Shevery 的思路：从 GitHub 上找 ADB 模块）。
 *
 * 判定一个仓库是不是模块：**它里面得有 `module.prop`**（根目录，或者某个子目录）。
 * 只有 module.prop 能解析出合法的 `id` / `name`，才认为这个仓库是个可用模块。
 *
 * 为什么不用 kotlinx-serialization：本项目没有这个依赖，而这里只需要读几个字段，
 * `org.json` 足够且零成本。网络请求是阻塞的，**必须在 IO 线程调用**。
 */
object ModuleDiscovery {

    private const val API_ROOT = "https://api.github.com"
    private const val USER_AGENT = "Shizako-ModuleDiscovery/1.0"
    private const val KEY_TOKEN = "github_token"

    /** 上一次请求后 GitHub 剩下多少配额（RateLimitTracker） */
    @Volatile
    var rateLimitRemaining: Int = -1
        private set

    /** 模块信息（和上游 DiscoveredModule 对齐的精简版） */
    data class DiscoveredModule(
        val repoFullName: String,
        val repoUrl: String,
        val moduleId: String,
        val moduleName: String,
        val version: String?,
        val versionCode: Long?,
        val author: String?,
        val description: String?,
        val stars: Int,
        val repoDescription: String?,
        val ownerAvatar: String,
        val subPath: String?,
        val isValid: Boolean,
        val zipUrl: String? = null,
    )

    fun getToken(context: android.content.Context): String =
        ShizukuSettings.getPreferences().getString(KEY_TOKEN, "").orEmpty()

    fun setToken(context: android.content.Context, token: String) {
        ShizukuSettings.getPreferences().edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    /**
     * 搜索模块仓库。
     *
     * @param query 关键词；空串 = 用 `topic:shizuku-module` 找官方话题下的仓库
     */
    fun search(query: String, perPage: Int = 20): List<DiscoveredModule> {
        val q = if (query.isBlank()) {
            "topic:shizuku-module"
        } else {
            "$query in:name,description,readme filename:module.prop"
        }
        val url = "$API_ROOT/search/repositories?q=${enc(q)}&sort=stars&order=desc&per_page=$perPage"
        val json = get(url) ?: return emptyList()
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()

        val result = ArrayList<DiscoveredModule>(items.length())
        for (i in 0 until items.length()) {
            val repo = items.optJSONObject(i) ?: continue
            val fullName = repo.optString("full_name")
            if (fullName.isBlank()) continue
            val branch = repo.optString("default_branch", "main").ifBlank { "main" }
            val owner = repo.optJSONObject("owner")
            val avatar = owner?.optString("avatar_url").orEmpty()

            // 根目录没有 module.prop 就试着找一层子目录（monorepo 很常见）
            val located: Pair<String?, Map<String, String>> =
                fetchModuleProp(fullName, branch, null)?.let { null to it }
                    ?: findInSubDirectory(fullName, branch)
                    ?: continue
            val subPath = located.first
            val props = located.second

            val id = props["id"]?.trim().orEmpty()
            if (!id.matches(Regex("[A-Za-z][A-Za-z0-9._-]{1,63}"))) continue

            result += DiscoveredModule(
                repoFullName = fullName,
                repoUrl = repo.optString("html_url"),
                moduleId = id,
                moduleName = props["name"]?.takeIf { it.isNotBlank() } ?: id,
                version = props["version"]?.takeIf { it.isNotBlank() },
                versionCode = props["versionCode"]?.toLongOrNull(),
                author = props["author"]?.takeIf { it.isNotBlank() },
                description = props["description"]?.takeIf { it.isNotBlank() }
                    ?: repo.optString("description").takeIf { it.isNotBlank() },
                stars = repo.optInt("stargazers_count"),
                repoDescription = repo.optString("description").takeIf { it.isNotBlank() },
                ownerAvatar = avatar,
                subPath = subPath,
                isValid = true,
                zipUrl = props["zipUrl"]?.takeIf { it.isNotBlank() }
                    ?: "https://github.com/$fullName/archive/refs/heads/$branch.zip",
            )
        }
        return result.sortedByDescending { it.stars }
    }

    /** 根目录的 module.prop；没有就返回 null */
    private fun fetchModuleProp(
        repo: String,
        branch: String,
        subPath: String?,
    ): Map<String, String>? {
        val path = if (subPath.isNullOrBlank()) "module.prop" else "$subPath/module.prop"
        val raw = "https://raw.githubusercontent.com/$repo/$branch/$path"
        val text = get(raw, rawHost = true) ?: return null
        return parseProp(text)
    }

    /** 只在仓库根的第一层目录里找 module.prop（不递归，避免把别人的示例模块也当模块） */
    private fun findInSubDirectory(repo: String, branch: String): Pair<String, Map<String, String>>? {
        val json = get("$API_ROOT/repos/$repo/contents?ref=$branch") ?: return null
        val array = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            return null
        }
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("type") != "dir") continue
            val name = entry.optString("name")
            val props = fetchModuleProp(repo, branch, name) ?: continue
            if (props["id"] != null) return name to props
        }
        return null
    }

    private fun parseProp(raw: String): Map<String, String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
        .associate {
            val index = it.indexOf('=')
            it.substring(0, index).trim() to it.substring(index + 1).trim()
        }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun get(url: String, rawHost: Boolean = false): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", if (rawHost) "text/plain" else "application/vnd.github+json")
                val token = ShizukuSettings.getPreferences().getString(KEY_TOKEN, "").orEmpty()
                if (!rawHost && token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connectTimeout = 12_000
                readTimeout = 12_000
            }
            rateLimitRemaining = connection.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
                ?: rateLimitRemaining
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    /** 供界面显示"上次检查时间"用 */
    fun formatStars(stars: Int): String = when {
        stars >= 1000 -> String.format(Locale.US, "%.1fk", stars / 1000f)
        else -> stars.toString()
    }
}
