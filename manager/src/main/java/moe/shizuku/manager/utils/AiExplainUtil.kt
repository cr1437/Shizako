package moe.shizuku.manager.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Comput 控制台的 AI 部分：把「当前命令 + 输出」丢给 Gemini 解释。
 *
 * 逻辑照搬 Shevery 的 `AiExplainUtil`：同一个 endpoint、同一个请求体形状、同一个模型默认值；
 * 差别只在 key 的存放位置 —— 上游放在 `ModuleSettings`（模块策略类）里，
 * 本项目没有那个类，所以 key / 模型的读写就近放在这里，仍复用
 * [ShizukuSettings.getPreferences]（和全项目同一个 SharedPreferences）。
 *
 * 为什么 key 要加密：API key 是明文凭据，`settings.xml` 在 root 设备上随手可读。
 * 这里用 Android Keystore 里的 AES-GCM 密钥加密后再落盘 ——
 * 密钥本身永远不出 TEE/StrongBox，拷走 XML 也解不开。
 * 只用系统 API（`java.security` / `javax.crypto` / `android.util.Base64`），不引新依赖。
 */
object AiExplainUtil {

    // ---------------- 提供商预设 ----------------
    //
    // 不绑死 Gemini：默认给一批常用的（自家 API / OpenAI 兼容端点都算），
    // 最后一个是「自定义」——填 base URL + 模型名就能接任何 OpenAI 兼容服务
    // （vLLM / Ollama / OneAPI / 自建网关都行）。

    enum class Protocol { GEMINI, OPENAI }

    data class AiProvider(
        val id: String,
        val label: String,
        val protocol: Protocol,
        val baseUrl: String,
        val defaultModel: String,
        /** 控制台里给用户看的说明（去哪申请 key 之类） */
        val hint: String = "",
    )

    val PROVIDERS: List<AiProvider> = listOf(
        AiProvider(
            "gemini", "Google Gemini", Protocol.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta",
            "gemini-2.5-flash",
            "aistudio.google.com/apikey",
        ),
        AiProvider(
            "openai", "OpenAI", Protocol.OPENAI,
            "https://api.openai.com/v1", "gpt-4o-mini",
            "platform.openai.com/api-keys",
        ),
        AiProvider(
            "deepseek", "DeepSeek", Protocol.OPENAI,
            "https://api.deepseek.com/v1", "deepseek-chat",
            "platform.deepseek.com",
        ),
        AiProvider(
            "moonshot", "Moonshot (Kimi)", Protocol.OPENAI,
            "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            "platform.moonshot.cn",
        ),
        AiProvider(
            "zhipu", "智谱 GLM", Protocol.OPENAI,
            "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
            "open.bigmodel.cn",
        ),
        AiProvider(
            "dashscope", "通义千问 (DashScope)", Protocol.OPENAI,
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
            "dashscope.console.aliyun.com",
        ),
        AiProvider(
            "openrouter", "OpenRouter", Protocol.OPENAI,
            "https://openrouter.ai/api/v1", "openai/gpt-4o-mini",
            "openrouter.ai/keys",
        ),
        AiProvider(
            "siliconflow", "SiliconFlow 硅基流动", Protocol.OPENAI,
            "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct",
            "cloud.siliconflow.cn",
        ),
        AiProvider(
            "custom", "自定义 (OpenAI 兼容)", Protocol.OPENAI,
            "", "",
            "填 base URL（例如 http://192.168.1.2:8000/v1）+ 模型名",
        ),
    )

    /** 老版本的默认模型，保留给「没设过 provider」的升级用户 */
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    // ---------------- 偏好 key ----------------

    private const val KEY_COMPUT_API_KEY = "comput_api_key"
    private const val KEY_COMPUT_GEMINI_MODEL = "comput_gemini_model"
    private const val KEY_PROVIDER = "comput_ai_provider"
    private const val KEY_MODEL = "comput_ai_model"
    private const val KEY_BASE_URL = "comput_ai_base_url"

    fun getProviderId(): String =
        ShizukuSettings.getPreferences().getString(KEY_PROVIDER, PROVIDERS.first().id)
            ?: PROVIDERS.first().id

    fun getProvider(): AiProvider =
        PROVIDERS.firstOrNull { it.id == getProviderId() } ?: PROVIDERS.first()

    fun setProviderId(id: String) {
        ShizukuSettings.getPreferences().edit().putString(KEY_PROVIDER, id).apply()
    }

    /** 自定义 / 覆盖用的 base URL；空表示用预设里的 */
    fun getBaseUrl(): String {
        val saved = ShizukuSettings.getPreferences().getString(KEY_BASE_URL, "").orEmpty()
        return saved.ifBlank { getProvider().baseUrl }
    }

    fun getRawBaseUrl(): String =
        ShizukuSettings.getPreferences().getString(KEY_BASE_URL, "").orEmpty()

    fun setBaseUrl(value: String) {
        ShizukuSettings.getPreferences().edit().putString(KEY_BASE_URL, value.trim()).apply()
    }

    // ---------------- Keystore ----------------

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "ShizakoComputGeminiKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** 取（必要时生成）Keystore 里的 AES 密钥。密钥不存在就现生成一个。 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求用户认证：控制台是随手用的工具，每次都要解锁就没法用了
                .build(),
        )
        return generator.generateKey()
    }

    /** 格式 `iv:密文`（两段都是 Base64），和上游一致 —— 老数据里没有 `:` 说明是明文。 */
    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(
            cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$iv:$body"
    }

    private fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        val parts = stored.split(":")
        if (parts.size != 2) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            Charsets.UTF_8,
        )
    }

    // ---------------- API key / 模型 ----------------

    /**
     * 读 API key。
     *
     * 解不开（换代工厂重置、Keystore 密钥被清）时返回空串而不是抛异常 ——
     * 上层靠 isBlank() 就能把按钮置灰，不会因为一条坏记录把页面干崩。
     */
    fun getApiKey(): String {
        val raw = ShizukuSettings.getPreferences().getString(KEY_COMPUT_API_KEY, "").orEmpty()
        if (raw.isEmpty()) return ""
        if (!raw.contains(":")) {
            // 历史遗留的明文：立刻补加密再返回，别让明文继续躺在盘上
            return try {
                val encrypted = encrypt(raw)
                ShizukuSettings.getPreferences().edit()
                    .putString(KEY_COMPUT_API_KEY, encrypted).apply()
                raw
            } catch (e: Throwable) {
                raw
            }
        }
        return try {
            decrypt(raw)
        } catch (e: Throwable) {
            ""
        }
    }

    fun setApiKey(value: String) {
        val stored = try {
            encrypt(value)
        } catch (e: Throwable) {
            // 极端情况（Keystore 不可用）宁可明文可用，也不能把用户填的 key 吞掉
            value
        }
        ShizukuSettings.getPreferences().edit().putString(KEY_COMPUT_API_KEY, stored).apply()
    }

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    /** 当前模型：优先读新 key；老的 Gemini 专用 key 也兼容 */
    fun getModel(): String {
        val prefs = ShizukuSettings.getPreferences()
        val saved = prefs.getString(KEY_MODEL, "").orEmpty()
        if (saved.isNotBlank()) return saved
        val legacy = prefs.getString(KEY_COMPUT_GEMINI_MODEL, "").orEmpty()
        if (legacy.isNotBlank()) return legacy
        return getProvider().defaultModel.ifBlank { DEFAULT_MODEL }
    }

    fun setModel(value: String) {
        ShizukuSettings.getPreferences().edit().putString(KEY_MODEL, value.trim()).apply()
    }

    // ---------------- 模型列表：直接用 API 拉 ----------------

    /**
     * 从当前厂商的 API 拉可用模型列表（阻塞，请在 IO 线程调用）。
     *
     * - OpenAI 兼容：`GET {base}/models`，`Authorization: Bearer <key>`，读 `data[].id`
     *   （OpenAI / DeepSeek / Kimi / GLM / 千问 / OpenRouter / 硅基流动 / 自建都吃这一套）；
     * - Gemini：`GET {base}/models?key=<key>`，读 `models[].name`（前缀 `models/` 去掉）。
     *
     * 没 key / 没地址 / HTTP 非 2xx 都抛异常，调用方把 message 显示给主人。
     */
    fun fetchModels(apiKeyOverride: String? = null): List<String> {
        val provider = getProvider()
        val key = (apiKeyOverride ?: getApiKey()).trim()
        if (key.isBlank()) throw IllegalStateException("还没填 API key")
        val base = getBaseUrl().trimEnd('/')
        if (base.isBlank()) throw IllegalStateException("还没填 base URL")

        return when (provider.protocol) {
            Protocol.GEMINI -> fetchGeminiModels(base, key)
            Protocol.OPENAI -> fetchOpenAiModels(base, key)
        }
    }

    private fun fetchOpenAiModels(base: String, key: String): List<String> {
        val conn = (URL("$base/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val data = org.json.JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
            val out = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) out.add(id)
            }
            out.sort()
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchGeminiModels(base: String, key: String): List<String> {
        val conn = (URL("$base/models?key=$key").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val models = org.json.JSONObject(body).optJSONArray("models") ?: org.json.JSONArray()
            val out = ArrayList<String>(models.length())
            for (i in 0 until models.length()) {
                val name = models.optJSONObject(i)?.optString("name").orEmpty()
                if (name.isNotBlank()) out.add(name.removePrefix("models/"))
            }
            out.sort()
            return out
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 请求 ----------------
    // 两套协议：Gemini 自家的 generateContent；其余全走 OpenAI 兼容的 chat/completions。

    private fun request(prompt: String, apiKey: String): Result<String> = when (getProvider().protocol) {
        Protocol.GEMINI -> requestGemini(prompt, apiKey, getBaseUrl())
        Protocol.OPENAI -> requestOpenAi(prompt, apiKey, getBaseUrl())
    }

    /** OpenAI 兼容端点（OpenAI / DeepSeek / Kimi / GLM / 千问 / OpenRouter / 自建都能吃） */
    private fun requestOpenAi(prompt: String, apiKey: String, baseUrl: String): Result<String> {
        if (baseUrl.isBlank()) {
            return Result.failure(AiException(AiError.Http(0, "还没填 base URL")))
        }
        return try {
            val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection()
                as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            val body = JSONObject().apply {
                put("model", getModel())
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        },
                    ),
                )
                put("temperature", 0.3)
            }
            conn.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = try {
                    JSONObject(text).getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } catch (e: Exception) {
                    null
                }
                if (parsed.isNullOrBlank()) {
                    Result.failure(AiException(AiError.Empty))
                } else {
                    Result.success(parsed)
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Result.failure(AiException(AiError.Http(code, err)))
            }
        } catch (e: Exception) {
            Result.failure(AiException(AiError.Network(e.message ?: e.javaClass.simpleName)))
        }
    }

    // ---------------- Gemini ----------------

    /** AI 调用的失败分类。文案留在 UI 层，util 只回报「为什么失败」。 */
    sealed class AiError {
        /** 没配 key（或 key 解不出来） */
        data object NoApiKey : AiError()

        /** 网络 / 解析异常 */
        data class Network(val message: String) : AiError()

        /** 非 200 响应 */
        data class Http(val code: Int, val body: String) : AiError()

        /** 200 但正文里没有可用的文本（被安全策略拦、或格式变了） */
        data object Empty : AiError()
    }

    /**
     * 解释一条命令及其输出。
     *
     * 提示词照搬上游：先**强制**用系统语言作答（否则中文用户会拿到英文解释），
     * 再要求解释错误原因和解决办法。
     */
    suspend fun explainCommand(
        command: String,
        output: String,
        apiKey: String = getApiKey(),
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(AiException(AiError.NoApiKey))

        val locale = Locale.getDefault()
        val prompt = "CRITICAL: You must write the entire explanation in the following language: " +
            "${locale.displayName} (locale code: ${locale.toLanguageTag()}).\n\n" +
            "Explain the following shell command and its execution output in a clear, concise, " +
            "and helpful developer-focused way. If there are errors or warnings, explain what " +
            "caused them and how to resolve them:\n\n" +
            "Command: $command\n\n" +
            "Output:\n$output"

        request(prompt, apiKey)
    }

    /**
     * 解释一次失败（上游 `explainFailure` 的同款入口）。
     *
     * @param contextStr 在什么场景下失败的，例如「Comput 控制台执行命令」
     */
    suspend fun explainFailure(
        contextStr: String,
        inputDetail: String,
        outputLog: String,
        apiKey: String = getApiKey(),
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(AiException(AiError.NoApiKey))

        val locale = Locale.getDefault()
        val prompt = "CRITICAL: You must write the entire explanation in the following language: " +
            "${locale.displayName} (locale code: ${locale.toLanguageTag()}).\n\n" +
            "An error or failure occurred in the application context: $contextStr.\n" +
            "Input / Action details:\n$inputDetail\n\n" +
            "Output / Error Log:\n$outputLog\n\n" +
            "Explain this failure in a clear, concise, and helpful developer-focused way, " +
            "and suggest how to resolve it."

        request(prompt, apiKey)
    }

    /** Gemini 的 generateContent（其余厂商走 OpenAI 兼容协议） */
    private fun requestGemini(prompt: String, apiKey: String, baseUrl: String): Result<String> {
        return try {
            val url = URL(
                baseUrl.trimEnd('/') + "/models/" +
                    "${getModel()}:generateContent?key=$apiKey",
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")

            val body = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                    ),
                )
            }

            conn.outputStream.use { stream ->
                stream.write(body.toString().toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val code = conn.responseCode
            if (code == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = try {
                    JSONObject(text).getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                } catch (e: Exception) {
                    null
                }
                if (parsed.isNullOrBlank()) {
                    Result.failure(AiException(AiError.Empty))
                } else {
                    Result.success(parsed)
                }
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Result.failure(AiException(AiError.Http(code, err.ifBlank { "No details." })))
            }
        } catch (e: Exception) {
            Result.failure(AiException(AiError.Network(e.message ?: "Connection error.")))
        }
    }

    /** 用 `Result` 带 [AiError] 出去（Kotlin 的 `Result` 只能带 `Throwable`）。 */
    class AiException(val error: AiError) : Exception()

    /** 取 `Result` 里的 [AiError]（不是我们包出来的异常就归到网络错误）。 */
    fun errorOf(throwable: Throwable?): AiError =
        (throwable as? AiException)?.error
            ?: AiError.Network(throwable?.message ?: "Connection error.")
}
