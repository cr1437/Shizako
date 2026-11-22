package moe.shizuku.manager.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信任判定的穷举（规格 F2 的三条判定 + 验收清单里的「UID 匹配但签名不符」）。
 *
 * 这些用例不需要设备：判定是纯函数，输入「调用方 / 信任记录 / 当前签名」，输出结论与代码。
 */
class InstallPolicyTest {

    private fun trusted(
        packageName: String = "com.foo",
        uid: Int = 10123,
        signatureHash: String = "sig-a",
    ) = TrustedInstaller(
        packageName = packageName,
        uid = uid,
        signatureHash = signatureHash,
        note = null,
        addedAt = 0L,
        lastUsedAt = null,
    )

    private fun app(
        packageName: String? = "com.foo",
        uid: Int = 10123,
        signature: String? = "sig-a",
    ) = CallerInfo(uid = uid, packageName = packageName, signatureHash = signature, kind = CallerKind.APP)

    @Test
    fun `caller unknown is denied`() {
        val decision = InstallPolicy.decide(null, null, null)
        assertTrue(decision.denied)
        assertEquals(TrustCodes.NO_CALLER, decision.code)
    }

    @Test
    fun `caller kind UNKNOWN is denied even with data`() {
        val caller = CallerInfo(1, "com.foo", "sig-a", CallerKind.UNKNOWN)
        val decision = InstallPolicy.decide(caller, trusted(), "sig-a")
        assertTrue(decision.denied)
        assertEquals(TrustCodes.NO_CALLER, decision.code)
    }

    @Test
    fun `self is allowed as UI`() {
        val caller = CallerInfo(10001, "com.churan.shizako", null, CallerKind.SELF)
        val decision = InstallPolicy.decide(caller, null, null)
        assertTrue(decision.allow)
        assertEquals(TrustCodes.SELF, decision.code)
        assertEquals(InstallSource.UI, InstallPolicy.sourceOf(decision))
    }

    @Test
    fun `system resolver is allowed`() {
        val caller = CallerInfo(1000, "android", null, CallerKind.SYSTEM)
        val decision = InstallPolicy.decide(caller, null, null)
        assertTrue(decision.allow)
        assertEquals(TrustCodes.SYSTEM, decision.code)
        assertEquals(InstallSource.SYSTEM, InstallPolicy.sourceOf(decision))
    }

    @Test
    fun `untrusted app must be asked`() {
        val decision = InstallPolicy.decide(app(), null, "sig-a")
        assertFalse(decision.denied)
        assertTrue(decision.needsConfirm)
        assertEquals(TrustCodes.UNTRUSTED, decision.code)
        assertEquals(InstallSource.USER_APPROVED, InstallPolicy.sourceOf(decision))
    }

    @Test
    fun `trusted app with matching uid and signature is allowed`() {
        val decision = InstallPolicy.decide(app(), trusted(), "sig-a")
        assertTrue(decision.allow)
        assertEquals(TrustCodes.TRUSTED, decision.code)
        assertEquals(InstallSource.TRUSTED_APP, InstallPolicy.sourceOf(decision))
    }

    @Test
    fun `uid matched but signature differs is denied without asking`() {
        val decision = InstallPolicy.decide(app(signature = "sig-b"), trusted(), "sig-b")
        assertTrue(decision.denied)
        assertEquals(TrustCodes.SIG_MISMATCH, decision.code)
    }

    @Test
    fun `missing current signature is denied`() {
        // 包被卸载 / 读不到签名：不能当成「跳过校验」
        val decision = InstallPolicy.decide(app(signature = null), trusted(), null)
        assertTrue(decision.denied)
        assertEquals(TrustCodes.SIG_UNAVAILABLE, decision.code)
    }

    @Test
    fun `package mismatch is denied`() {
        val decision = InstallPolicy.decide(app(packageName = "com.bar"), trusted(), "sig-a")
        assertTrue(decision.denied)
        assertEquals(TrustCodes.PKG_MISMATCH, decision.code)
    }

    @Test
    fun `null caller package is denied`() {
        // sharedUserId 下反查不出唯一包名 → caller.packageName 为 null
        val decision = InstallPolicy.decide(app(packageName = null), trusted(), "sig-a")
        assertTrue(decision.denied)
        assertEquals(TrustCodes.PKG_MISMATCH, decision.code)
    }

    @Test
    fun `uid mismatch is denied even when signature equals`() {
        // 应用卸载后 UID 被别的应用复用：签名相同（同一个 app 重装）也不放过旧 uid 记录
        val decision = InstallPolicy.decide(app(uid = 10999), trusted(uid = 10123), "sig-a")
        assertTrue(decision.denied)
        assertEquals(TrustCodes.UID_MISMATCH, decision.code)
    }

    @Test
    fun `signature compare is case insensitive`() {
        val decision = InstallPolicy.decide(app(signature = "SIG-A"), trusted(signatureHash = "sig-a"), "SIG-A")
        assertTrue(decision.allow)
    }
}
