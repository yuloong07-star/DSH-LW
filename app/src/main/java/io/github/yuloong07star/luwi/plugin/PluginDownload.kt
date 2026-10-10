package io.github.yuloong07star.luwi.plugin

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从链接把一份插件包取下来 (协议第 13 节那条"从 URL 安装")
 *
 * 这一份只做"把字节拿到手", 一个字的校验都不省给后面: 拿到的那份交给 [PluginInstaller], 走的是与
 * 本地包**完全同一条链** (解包 → 验签名与逐文件哈希 → 查 protocol / minLw / 前缀 / 未知能力 → 才落地)。
 * 所以这里的每一句错话都只关于"这一趟下载", 不关于"这份包能不能装"
 *
 * 四条写在这里的规矩:
 *
 * - **只许 https**: 每一次跳转都重查一遍 URL —— 从 https 跳到 http 就地中止, 不跟着去
 * - **不信任任何头**: `Content-Length` 只当个早拒的提示, 真正的上限靠边读边数 (声明与实际不符的头
 *   到处都是), 超了就把半份文件删掉
 * - **下到 cache**: 它是 app 私有目录里的一份临时文件, 装完或失败都当场删 (设置页那条路在 `finally`
 *   里删, 桥那条路也一样)
 * - **下完先看它像不像一份包**: 前两个字节不是 `PK` 就当场说"这不是一份 .lwp", 而不是让安装器去报一句
 *   看不懂的错 (GitHub 那种 API 链接回的是 JSON, 这是最容易撞上的一种)
 */
internal object PluginDownload {

    private const val TAG = "LwPluginDownload"

    /** 协议第 3 节那份大小上限, 与 [PluginInstaller] 那一条对齐 */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** 跳几次就够: 一个短链加一次 302 到头了, 再多是环 */
    private const val MAX_HOPS = 5

    private const val CONNECT_MS = 15_000
    private const val READ_MS = 30_000

    /** 一份空 zip 的头两个字节, 也就是 `PK` */
    private const val ZIP_MARK = 0x50

    /**
     * 下到 `cacheDir/plugin-download/` 里的一份临时文件并返回它
     *
     * 抛出来的每一句都点名叫人不明白的那一处: 不是 https / 跳太多 / 回了别的状态码 / 太大 / 不像 zip。
     * **调用方负责删掉这一份** (它是临时文件, 装完就不需要了)
     */
    fun fetch(context: Context, url: String): File {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("链接是空的")
        val directory = File(context.cacheDir, "plugin-download").apply { mkdirs() }
        val target = File(directory, "package-${System.currentTimeMillis()}.lwp")
        try {
            var current = trimmed
            var hops = 0
            while (true) {
                requireHttps(current)
                val connection = open(current)
                val code = try {
                    connection.responseCode
                } catch (problem: IOException) {
                    connection.disconnect()
                    throw IllegalArgumentException("连不上这个链接: ${problem.message ?: problem.javaClass.simpleName}")
                }
                if (code in 300..399) {
                    val where = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (where.isNullOrBlank()) throw IllegalArgumentException("这个链接回了 $code, 却没说要跳到哪")
                    hops += 1
                    if (hops > MAX_HOPS) throw IllegalArgumentException("这个链接跳了 $MAX_HOPS 次还没到头, 不跟了")
                    // 相对地址要按当前这一跳解出来, 下一轮开头那句 requireHttps 会把 http 拦下
                    current = URL(URL(current), where).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    throw IllegalArgumentException("这个链接回了 $code (要 200 才能拿到字节)")
                }
                val declared = connection.contentLengthLong
                if (declared > MAX_BYTES) {
                    connection.disconnect()
                    throw IllegalArgumentException("这一份 ${declared / 1024 / 1024} MB, 超过 ${MAX_BYTES / 1024 / 1024} MB 的上限")
                }
                try {
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_BYTES) {
                                    throw IllegalArgumentException("这一份超过 ${MAX_BYTES / 1024 / 1024} MB 的上限")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                break
            }
            if (target.length() == 0L) throw IllegalArgumentException("这个链接下下来是空的")
            checkLooksLikePackage(target)
            Log.i(TAG, "fetched ${target.length()} bytes from $trimmed")
            return target
        } catch (problem: Throwable) {
            target.delete()
            throw problem
        }
    }

    /** 只许 https: 这一句在每一跳都跑一次, 所以"跳到 http"这一条也是当场中止 */
    private fun requireHttps(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw IllegalArgumentException("只收 https 的链接 (协议第 13 节那一条), 这一条是 ${url.take(80)}")
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        // 自己看 302: 跟着走就没机会查"下一跳是不是还是 https"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_MS
        connection.readTimeout = READ_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("User-Agent", "Luwi-plugin-install")
        return connection
    }

    /** 前两个字节得是 `PK`: 不是的话下下来的多半是一页 JSON 或者一页网页 */
    private fun checkLooksLikePackage(file: File) {
        val head = ByteArray(2)
        val read = file.inputStream().use { it.read(head) }
        if (read < 2 || (head[0].toInt() and 0xFF) != ZIP_MARK || (head[1].toInt() and 0xFF) != ZIP_MARK) {
            throw IllegalArgumentException("这个链接下的不是一份 .lwp (包是一个 zip); 拿到的头两个字节不像 zip")
        }
    }
}
