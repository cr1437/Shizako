package moe.shizuku.manager.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

/**
 * 配对连接的超时（毫秒）。与 [AdbClient] 保持一致（10s 连接 / 15s 读）。
 *
 * 必须有：配对时用户可能停在系统「使用配对码配对设备」对话框里不动，
 * connect / TLS 握手 / 读包任何一步永久阻塞，都会让通知一直停在「配对中」、
 * 分屏配对对话框的按钮永远禁用，只能杀进程恢复。
 */
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

private class PeerInfo(
        val type: Byte,
        data: ByteArray) {

    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
        val version: Byte,
        val type: Byte,
        val payload: Int) {

    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=${version})")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=${type}")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=${payload})")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

/**
 * @param hosts 候选地址，按顺序尝试。第一个能建立 TCP 连接的就用它跑完整个配对。
 *
 * 为什么要多个：adbd 的配对服务通常同时监听回环与局域网地址，所以硬编码
 * `127.0.0.1` 在绝大多数设备上都能用。但**有些设备/ROM 上应用连不上自己的回环地址**
 * （有实际反馈：同一台设备上 Termux 与电脑都能配对，本应用连 `127.0.0.1:<配对端口>`
 * 却直接 ConnectException）。这种情况下用设备自己的局域网 IP 是有效的备选 ——
 * 系统「使用配对码配对设备」对话框里显示的就是那个地址。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(private val hosts: List<String>, private val port: Int, private val pairCode: String, private val key: AdbKey) : Closeable {

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private lateinit var pairingContext: PairingContext
    private var state: State = State.Ready

    /**
     * 原生上下文是否已销毁。
     *
     * **必须**有这一道闸：native 侧的 `PairingContext_Destroy` 只做
     * `SPAKE2_CTX_free` + `EVP_AEAD_CTX_free` + `free(ctx)`，既不判空也不置空
     * （`adb_pairing.cpp:202-207`），指针仍留在 Java 字段里 —— 重复 destroy 就是
     * double free。而 [close] 现在是**真的会被调用两次**：[start] 的 finally 一次、
     * 调用方的 `use{}` 再一次。所以幂等性只能在这里保证。
     */
    private var pairingContextDestroyed = false

    /**
     * 跑完整个配对流程；失败一律抛异常。
     *
     * **清理由本方法自己负责**：无论成功、失败还是中途异常，都在 finally 里 [close]。
     * 调用方只需要 `use { it.start() }`（或不写 use 也不会泄漏 socket 与原生上下文）。
     * 这里不能用 `state` 当清理守卫 —— `state` 只在各阶段**成功之后**才推进，
     * 建连阶段失败时它仍是 [State.Ready]，守卫会把 `pairingContext.destroy()` 整段跳过，
     * 泄漏一块持有 SPAKE2 密钥的原生内存。
     *
     * 地址逐个尝试：只有 **TCP 连接建立不起来**（ConnectException / 连接超时）才会换下一个候选。
     * 一旦连上了，协议层或配对码的错误都直接抛出 —— 那说明地址没问题，换地址不会让配对码变对，
     * 反而会误导用户去改端口。
     */
    fun start() {
        var lastConnectFailure: Throwable? = null

        // 现场排查用：把「到底连了谁、用的哪个端口」先记下来。
        // 反馈「配对连不上」时，这一行能立刻区分「端口填错」「回环不通」「握手失败」。
        Log.i(TAG, "pairing start: hosts=$hosts port=$port")

        for ((index, candidate) in hosts.withIndex()) {
            try {
                setupTlsConnection(candidate)
            } catch (e: Throwable) {
                if (isConnectFailure(e) && index < hosts.lastIndex) {
                    // 这个地址连不上，且还有备选：换下一个（顺手关掉刚建出来的 socket）
                    closeSockets()
                    lastConnectFailure = e
                    Log.w(TAG, "Pairing connect to $candidate:$port failed (${e.javaClass.simpleName}), trying next host")
                    continue
                }
                Log.w(TAG, "pairing failed while connecting to $candidate:$port (${e.javaClass.simpleName}: ${e.message})")
                throw e
            }

            try {
                state = State.ExchangingMsgs
                doExchangeMsgs()

                state = State.ExchangingPeerInfo
                doExchangePeerInfo()

                state = State.Stopped
                return
            } catch (t: Throwable) {
                // 走到这里说明 TCP + TLS 都已经通了，失败发生在协议层：
                // 记下阶段与异常类型，避免下次又只能靠猜。
                Log.w(
                    TAG,
                    "pairing failed via $candidate:$port at state=$state (${t.javaClass.simpleName}: ${t.message})",
                )
                throw t
            } finally {
                // 建连成功但后续握手失败时，原生上下文同样必须销毁；close() 内部先关流、
                // 再销毁上下文，顺序不能反（destroy 之后 pairingContext 不可再用）。
                close()
            }
        }

        // 所有候选都连不上
        throw lastConnectFailure
            ?: AdbPairingFailedException("no usable host among ${hosts.joinToString()}")
    }

    private fun setupTlsConnection(host: String) {
        // 必须带超时：配对时用户可能停在系统「使用配对码配对设备」对话框里不动，
        // 而 connect / 握手 / 读包任何一步永久阻塞，都会让通知一直停在「配对中」、
        // 对话框按钮永远禁用（只能杀进程恢复）。数值与 AdbClient 保持一致（10s / 15s）。
        socket = java.net.Socket().apply {
            tcpNoDelay = true
            connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }

        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.soTimeout = READ_TIMEOUT_MS
        // 握手前先记一行：现场日志里能看出「连上了但握手失败」，
        // 这是判断「端口是连接端口而不是配对端口」的关键证据
        // （adbd 的连接服务会校验客户端证书，配对服务则明确接受任意证书）。
        Log.i(TAG, "TLS handshake -> $host:$port")
        sslSocket.startHandshake()
        // 记下**实际连上的是哪个地址**：回环还是局域网兜底。
        // 现场反馈「配对连不上」时，这一行能直接分辨「回环不通、已走兜底」和「压根没连上」，
        // 不必再靠猜。
        Log.i(TAG, "Handshake succeeded via $host:$port")

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, kExportedKeyLabel, null, kExportedKeySize)
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        val pairingContext = PairingContext.create(passwordBytes)
        checkNotNull(pairingContext) { "Unable to create PairingContext." }
        this.pairingContext = pairingContext
    }

    /**
     * 只有「立刻就能判定连不上」才换地址重试。
     *
     * 刻意**不含** [java.net.SocketTimeoutException]：那是等满 10s 建连 / 15s 读之后才得到的
     * 结论，换地址多半也是同样结果，却会把用户的等待时间翻倍（配对本身已有 10s+15s 的上限）。
     * 超时直接抛给界面，比让用户干等两轮更负责。
     */
    private fun isConnectFailure(e: Throwable): Boolean =
        e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException ||
                e is java.net.UnknownHostException

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        outputStream.write(buffer.array())
        outputStream.write(payload)
        Log.d(TAG, "write payload, size=${payload.size}")
    }

    private fun doExchangeMsgs() {
        // native 未能产出本地公钥消息（上下文已销毁 / 构造异常）→ 协议层失败，
        // 不是「配对码不对」。这里的 ?: 让 addExchangeMsgs 抛具名异常而不是 NPE。
        val msg = pairingContext.msg
            ?: throw AdbPairingFailedException("no local SPAKE2 message available")
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader()
            ?: throw AdbPairingFailedException("no SPAKE2 message header from peer")
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) {
            throw AdbPairingFailedException("unexpected packet type ${theirHeader.type} while waiting for SPAKE2 message")
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        // SPAKE2 的密码校验不过 = 两边算出来的配对码不一致：
        // 输错了，或者那个配对码已经作废（系统配对对话框一关，码就失效）。
        if (!pairingContext.initCipher(theirMessage)) {
            throw AdbInvalidPairingCodeException()
        }
    }

    private fun doExchangePeerInfo() {
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outbuf = pairingContext.encrypt(buf.array())
            ?: throw AdbPairingFailedException("unable to encrypt local peer info")

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size)
        writeHeader(ourHeader, outbuf)

        val theirHeader = readHeader()
            ?: throw AdbPairingFailedException("no peer info header from peer")
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) {
            throw AdbPairingFailedException("unexpected packet type ${theirHeader.type} while waiting for peer info")
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        val decrypted = pairingContext.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {
            throw AdbPairingFailedException("peer info size mismatch (got ${decrypted.size}, expected $kMaxPeerInfoSize)")
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, theirPeerInfo.toString())
    }

    /**
     * 只关 socket / 流，不动原生上下文。
     *
     * 供「某个候选地址连不上、准备换下一个」时使用：那时 `pairingContext` 还没建，
     * 而且马上要用同一个实例重试，不能走 [close] 的幂等销毁逻辑。
     */
    private fun closeSockets() {
        try {
            if (::inputStream.isInitialized) inputStream.close()
        } catch (e: Throwable) {
        }
        try {
            if (::outputStream.isInitialized) outputStream.close()
        } catch (e: Throwable) {
        }
        try {
            if (::socket.isInitialized) socket.close()
        } catch (e: Throwable) {
        }
    }

    /**
     * 幂等清理：关闭 socket，并销毁原生配对上下文。
     *
     * 不再用 `state != State.Ready` 当守卫 —— [start] 已经在 finally 里调用本方法，
     * 而 `pairingContext` 是 lateinit：只有建连成功过它才算初始化，所以这里必须用
     * [::pairingContext.isInitialized] 判断，否则会在“建连阶段就失败”的路径上抛
     * UninitializedPropertyAccessException（那是崩溃，不是清理）。
     */
    override fun close() {
        closeSockets()

        if (::pairingContext.isInitialized && !pairingContextDestroyed) {
            pairingContextDestroyed = true
            pairingContext.destroy()
        }
    }

    companion object {

        init {
            System.loadLibrary("adb")
        }

        /**
         * 组装候选地址：回环优先，其次本机局域网地址。
         *
         * 绝大多数设备用回环就够了（也是原来的行为），局域网地址只在回环连不上时兜底 ——
         * 见 [AdbPairingClient] 的类注释。拿不到局域网地址就只用回环。
         */
        fun candidateHosts(context: android.content.Context): List<String> {
            val list = mutableListOf("127.0.0.1")
            runCatching { moe.shizuku.manager.utils.NetworkUtils.findLanIpv4() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "127.0.0.1" }
                ?.let { list.add(it) }
            return list
        }

        @JvmStatic
        external fun available(): Boolean
    }
}
