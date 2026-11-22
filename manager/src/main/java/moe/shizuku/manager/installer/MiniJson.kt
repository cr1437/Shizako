package moe.shizuku.manager.installer

/**
 * 够用的最小 JSON 解析器 —— **只服务 XAPK 的 manifest.json**。
 *
 * 为什么不用 `org.json`：本仓库的 JVM 单测直接跑在宿主机上，Android 里的 `org.json`
 * 在单测里是"每个方法都抛 RuntimeException"的 stub，用它写解析器等于把这段逻辑
 * 从单测覆盖里划掉。XAPK 的 manifest.json 结构固定、体积只有几百字节，自己写一个
 * 递归下降解析器（约 150 行）比"为它引一个 json 依赖、还要确认离线能拉到"便宜得多。
 *
 * 支持的语法就是标准 JSON：对象 / 数组 / 字符串（含 `\uXXXX` 转义）/ 数字（整型返回
 * [Long]，带小数点或指数的返回 [Double]）/ true / false / null。不接受注释和尾逗号
 * ——那本来就不是 JSON，XAPK 里也不会出现。
 *
 * 解析失败抛 [IllegalArgumentException]，调用方按"归档损坏"处理。
 */
internal object MiniJson {

    fun parse(text: String): Any? = Parser(text).document()

    @Suppress("UNCHECKED_CAST")
    fun asObject(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asArray(value: Any?): List<Any?>? = value as? List<Any?>

    /** 取字符串字段；数字/bool 也顺手转字符串（XAPK 里 `version_code` 两种写法都有）。 */
    fun string(obj: Map<String, Any?>?, key: String): String? {
        val v = obj?.get(key) ?: return null
        return when (v) {
            is String -> v
            is Number -> v.toString()
            is Boolean -> v.toString()
            else -> null
        }
    }

    fun long(obj: Map<String, Any?>?, key: String): Long? {
        val v = obj?.get(key) ?: return null
        return when (v) {
            is Long -> v
            is Double -> v.toLong()
            is String -> v.trim().toLongOrNull()
            is Boolean -> if (v) 1L else 0L
            else -> null
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            skipWhitespace()
            val value = value()
            skipWhitespace()
            if (i != s.length) throw error("trailing content at $i")
            return value
        }

        private fun value(): Any? {
            skipWhitespace()
            if (i >= s.length) throw error("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) numberValue() else throw error("unexpected '$c'")
            }
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                val key = stringValue()
                skipWhitespace()
                expect(':')
                map[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { i++; return list }
            while (true) {
                list.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        private fun stringValue(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw error("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw error("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw error("bad unicode escape")
                                val hex = s.substring(i, i + 4)
                                i += 4
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw error("bad unicode escape"))
                            }
                            else -> throw error("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun numberValue(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i].isDigit()) i++
            var isDouble = false
            if (i < s.length && s[i] == '.') {
                isDouble = true
                i++
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isDouble = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i].isDigit()) i++
            }
            val raw = s.substring(start, i)
            return if (isDouble) raw.toDoubleOrNull() ?: throw error("bad number '$raw'")
            else raw.toLongOrNull() ?: throw error("bad number '$raw'")
        }

        private fun <T> literal(text: String, result: T): T {
            if (!s.startsWith(text, i)) throw error("expected '$text'")
            i += text.length
            return result
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun expect(c: Char) {
            if (i >= s.length || s[i] != c) throw error("expected '$c'")
            i++
        }

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun error(message: String) = IllegalArgumentException("manifest.json: $message")
    }
}
