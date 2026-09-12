package moe.shizuku.manager.adb

/**
 * ADB 配对用的原生上下文（native 实现在 `manager/src/main/jni/adb_pairing.cpp`）。
 *
 * **必须**是**顶层类**、且全名正好是 `moe.shizuku.manager.adb.PairingContext`：
 * JNI 那边是 `FindClass("moe/shizuku/manager/adb/PairingContext")`，
 * 一旦把它写成别的类里的嵌套类（二进制名会变成 `AdbPairingClient$PairingContext`），
 * `FindClass` 就找不到类，`RegisterNatives` 带着未决异常继续跑 →
 * JNI 直接 abort，**App 一启动就闪退**（native crash，不是普通异常）。
 */
internal class PairingContext private constructor(private val nativePtr: Long) {

    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}
