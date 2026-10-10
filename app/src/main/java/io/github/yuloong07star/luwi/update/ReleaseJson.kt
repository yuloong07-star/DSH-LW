package io.github.yuloong07star.luwi.update

/**
 * GitHub Release 那份 JSON 里我们真正要的几个数 —— **不依赖任何 JSON 库, 也不碰设备** (见
 * UpdateCheckTest)
 *
 * 为什么手写而不用 `org.json`: 那套类在 Android 上是 `android.jar` 里的, 而单元测试跑在 JVM 上,
 * 拿到的是"一调用就抛 Stub!"的桩 —— 于是解析这件事就变成"只能在设备上量"。这里要的字段一共六个
 * (版本号 / 名字 / 说明 / 页面地址 / APK 名字与体积), 手搓一个**只看顶层键**的取值器反而更好量,
 * 而且它只认这一件事: 值取不到就是 null, 由调用方说"这个源没按 Release 那份格式答"
 *
 * **它的边界要写清**: 它找的是"`"键"` 后面跟一个冒号"这**一串字节**, 所以说明正文里出现
 * `"tag_name":` 这种字样时可能先撞上那一段。GitHub 的 release 正文里不会带这种引号包着的键
 * (Markdown 里 `"` 与 `:` 之间还有别的东西), 而这是给"检查一下有没有新版本"用的, 不值得为它引一份
 * 完整的 JSON 语法
 */
internal object ReleaseJson {

    /** 一条 Release: 版本号已经去掉 `v` 前缀, 说明截到一段够看的长度 */
    internal data class Release(
        val version: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val published: String,
        val apkName: String?,
        val apkBytes: Long,
        val apkUrl: String?,
    )

    /** 说明最多留这么多字 (设置页那一个对话框里放不下长文, 全文在 Release 页面上) */
    private const val NOTE_LIMIT = 200

    /**
     * 解析一份 Release JSON
     *
     * 只对"里面有个 `tag_name`"这一件事较真 (没有它就不是一条 Release), 别的一律缺就是空 ——
     * 一个字段取不到时整条报错, 会让人以为"检查更新坏了", 而实际只是那条 release 没挂 APK
     */
    fun parse(raw: String): Release {
        val tag = pick(raw, "tag_name")?.let(::unquote)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("这份回答里没有 tag_name, 不像是一条 Release")
        val assets = pick(raw, "assets").orEmpty()
        val apk = asset(assets, ".apk")
        return Release(
            version = tag.removePrefix("v").trim(),
            name = pick(raw, "name")?.let(::unquote).orEmpty(),
            notes = pick(raw, "body")?.let(::unquote).orEmpty().let(::excerpt),
            pageUrl = pick(raw, "html_url")?.let(::unquote).orEmpty(),
            published = pick(raw, "published_at")?.let(::unquote).orEmpty().take(10),
            apkName = apk?.let { pick(it, "name")?.let(::unquote) },
            apkBytes = apk?.let { pick(it, "size")?.trim()?.toLongOrNull() } ?: 0L,
            apkUrl = apk?.let { pick(it, "browser_download_url")?.let(::unquote) },
        )
    }

    /**
     * 远处那个版本是不是比手上这个新
     *
     * 只比数字那三段 (`2.5.0` 与 `v2.5.0` 一样), 位数不够的补 0 (`2.5` = `2.5.0`); 认不出来的一律
     * 答 false —— "看不出谁新"应当落到"没有新版本", 而不是催人去装一个不知道是什么的东西
     */
    fun newer(remote: String, local: String): Boolean {
        val left = numbers(remote)
        val right = numbers(local)
        if (left.isEmpty() || right.isEmpty()) return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 版本号里的那几段数字: `v2.5.0-beta.1` 取到 `[2, 5, 0, 1]`, 认不出来就是空表 */
    fun numbers(version: String): List<Int> = version
        .split('.', '-', '+', '_', ' ')
        .map { part -> part.filter { it.isDigit() } }
        .filter { it.isNotEmpty() }
        .mapNotNull { it.toIntOrNull() }
        .take(4)

    /* ── 下面都是这一份取值器自己 ───────────────────────────────────────────── */

    /** 在 [assets] 这一段的若干个对象里挑名字以 [suffix] 结尾的那一个 (第二个参数是它的原样文字) */
    private fun asset(assets: String, suffix: String): String? {
        var at = 0
        while (true) {
            val start = assets.indexOf('{', at)
            if (start < 0) return null
            val end = matching(assets, start)
            if (end < 0) return null
            val one = assets.substring(start, end + 1)
            val name = pick(one, "name")?.let(::unquote).orEmpty()
            if (name.endsWith(suffix, ignoreCase = true)) return one
            at = end + 1
        }
    }

    /** 说明那一段: 去掉空行与多余空白, 太长就截断 (截了要说一句, 免得人以为说明就这些) */
    private fun excerpt(body: String): String {
        val flat = body.replace("\r\n", "\n").trim()
        if (flat.length <= NOTE_LIMIT) return flat
        return flat.take(NOTE_LIMIT).trimEnd() + "…"
    }

    /**
     * 找 [key] 那个键的值, 原样回那一段文字 (字符串连着两个引号一起回)
     *
     * 只认"键后面跟着冒号"的那种位置, 于是同一段文字里作为**值**出现的同一个词不会被当成键
     */
    private fun pick(raw: String, key: String): String? {
        val token = "\"$key\""
        var at = raw.indexOf(token)
        while (at >= 0) {
            var cursor = at + token.length
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            if (cursor < raw.length && raw[cursor] == ':') {
                cursor++
                while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
                return value(raw, cursor)
            }
            at = raw.indexOf(token, at + token.length)
        }
        return null
    }

    /** 从 [from] 起取一个完整的 JSON 值: 字符串 / 数组 / 对象各自认自己的边界, 别的取到一个分隔符为止 */
    private fun value(raw: String, from: Int): String? {
        if (from >= raw.length) return null
        return when (raw[from]) {
            '"' -> {
                val end = stringEnd(raw, from)
                if (end < 0) null else raw.substring(from, end + 1)
            }

            '[', '{' -> {
                val end = matching(raw, from)
                if (end < 0) null else raw.substring(from, end + 1)
            }

            else -> {
                var cursor = from
                while (cursor < raw.length && raw[cursor] !in ",}]") cursor++
                raw.substring(from, cursor).trim().takeIf { it.isNotEmpty() }
            }
        }
    }

    /** 一个字符串值到哪结束 (那个不吃转义的后引号) */
    private fun stringEnd(raw: String, from: Int): Int {
        var cursor = from + 1
        while (cursor < raw.length) {
            when (raw[cursor]) {
                '\\' -> cursor += 2
                '"' -> return cursor
                else -> cursor++
            }
        }
        return -1
    }

    /** 数组 / 对象那一对括号配到哪 (字符串里的括号不算数) */
    private fun matching(raw: String, from: Int): Int {
        val open = raw[from]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var cursor = from
        while (cursor < raw.length) {
            when (raw[cursor]) {
                '"' -> {
                    val end = stringEnd(raw, cursor)
                    if (end < 0) return -1
                    cursor = end + 1
                    continue
                }

                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return cursor
                }
            }
            cursor++
        }
        return -1
    }

    /** 把一个带引号的字符串值拆出来并还原那几种转义 (`\n` / `\"` / `\\` / `\uXXXX` 之类) */
    private fun unquote(text: String): String {
        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length < 2) return text
        val body = text.substring(1, text.length - 1)
        if (!body.contains('\\')) return body
        val out = StringBuilder(body.length)
        var cursor = 0
        while (cursor < body.length) {
            val char = body[cursor]
            if (char != '\\' || cursor + 1 >= body.length) {
                out.append(char)
                cursor++
                continue
            }
            when (val escape = body[cursor + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = body.substring(cursor + 2, minOf(cursor + 6, body.length))
                    val code = hex.toIntOrNull(16)
                    if (code == null) out.append(escape) else out.append(code.toChar())
                    cursor += 4
                }

                else -> out.append(escape)
            }
            cursor += 2
        }
        return out.toString()
    }
}
