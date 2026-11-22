package moe.shizuku.manager.automation

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 令牌的加密存储 + 包签名摘要。
 *
 * 「复用现有安全存储」的落点：本仓库没有引入 `androidx.security:security-crypto`
 * （依赖树里没有，离线构建也不该为它多拉一个包），但已有等价设施 ——
 * `utils/AiExplainUtil.kt` 用 Android Keystore 的 AES-GCM 加密落盘。
 * 这里沿用同一套做法（同一 Provider、同一 `iv:密文` 格式），只换独立的密钥别名，
 * 以免「控制台的 AI key」和「自动化令牌」共用一把密钥 —— 一个是可丢弃的第三方 key，
 * 一个是能换来自动化执行权的凭据，不该绑在一起。
 *
 * 与 AiExplainUtil 的一处**有意分歧**：那边在 Keystore 不可用时会退回明文保存
 * （宁可明文可用也不能把用户填的 key 吞掉）；令牌这里**不允许**退化成明文 ——
 * 令牌等价于一条特权通道的钥匙，明文落盘等于把钥匙贴在门上。生成失败就报错。
 */
object AutomationCrypto {

    private const val TAG = "ShizakoAutomation"
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "ShizakoAutomationTokenKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** 32 字节随机值的 Base64（NO_WRAP）——规格 F1 要求的令牌本体。 */
    fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 展示用掩码：前 4 后 4。 */
    fun mask(token: String): String = when {
        token.isEmpty() -> ""
        token.length <= 8 -> "…"
        else -> token.take(4) + "…" + token.takeLast(4)
    }

    /** 定时比较：长度不同也走一次计算，抹掉「长度」这条时间侧信道。 */
    fun safeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) {
            MessageDigest.isEqual(x, x)
            return false
        }
        return MessageDigest.isEqual(x, y)
    }

    /** 加密成 `iv:密文`（两段 Base64 NO_WRAP）。失败抛异常，由调用方拒绝创建令牌。 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(
            cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$iv:$body"
    }

    /** 解密；格式不对/Keystore 密钥被清 → 返回 null（调用方当作「令牌已失效」处理）。 */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return null
        val parts = stored.split(":")
        if (parts.size != 2) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "token decrypt failed: ${t.javaClass.simpleName}")
            null
        }
    }

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
                // 不要求每次解锁：Tasker 在锁屏/后台触发是常态，弹指纹等于功能不可用
                .build(),
        )
        return generator.generateKey()
    }

    // ---------------- 包名 / 签名 ----------------

    /**
     * 某个包的签名摘要（当前安装的签名，多签名时按字典序拼接后再哈希）。
     *
     * 返回 null 表示包没装（或读不到签名）——调用方必须把它当成「校验失败」，
     * 不能当成「跳过校验」。
     *
     * 实现已抽到 [moe.shizuku.manager.utils.AppSignatures]：安装器的信任列表要算
     * 同一个摘要，两处各写一份的话，「签名集合的规范化顺序」稍有不同就会让同一个包
     * 算出两个值，表现为「信任列表加过却一直签名不符」。
     */
    fun signatureHash(context: Context, packageName: String): String? =
        moe.shizuku.manager.utils.AppSignatures.packageHash(context, packageName)

    /** 把证书字节数组集合规范化成一个摘要（与安装器共用 [moe.shizuku.manager.utils.AppSignatures]）。 */
    fun hashCertificates(certs: List<ByteArray>): String =
        moe.shizuku.manager.utils.AppSignatures.hashCertificates(certs)

    /** uid → 包名列表（`getSentFromUid()` 之后用它做匹配）。 */
    fun packagesForUid(context: Context, uid: Int): List<String> = try {
        context.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }
}
