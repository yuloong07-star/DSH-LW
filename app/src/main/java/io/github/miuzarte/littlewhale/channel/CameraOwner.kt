package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.util.Log
import io.github.miuzarte.littlewhale.host.DshHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相机占用表: 现在哪一场会话在用视频模式那台相机 (批次 3 的需求 3)
 *
 * **它是应用与宿主插件之间的一份共享事实**, 与 `$DSH_HOME/lw/look-sheet` 那个记号同一个先例: 插件跑在
 * 宿主进程里 (读不到应用的偏好文件), 而"相机有没有被别场占着"两边必须看同一个东西。判据在**插件那一侧**
 * —— 只有它拿得到 `exec.agent.id`; 应用这一侧只做两件事: **显示**它 (设置页「视频识别」那一段), 以及
 * **手动强制释放** (球菜单与设置页那两条入口, 表卡住时唯一的出口)
 *
 * 形态就是插件 `claimCamera` 写出来的那四个键:
 * `{"sessionId":"…","since":<epoch ms>,"at":<epoch ms>,"lens":"back"}` —— `at` 每次取景续期,
 * `since` 是这一轮占用的起点 (话术里的"什么时候开始的")
 *
 * **为什么手搓解析**: 与 [io.github.miuzarte.littlewhale.update.ReleaseJson] 同一个理由 —— `org.json`
 * 那套类在 Android 上是 `android.jar` 里的, 而单元测试跑在 JVM 上 (一调用就抛桩)。这里要的键一共四个
 * 而且值是扁平的, 手写一个"只看键后面跟冒号"的取值器反而更好量
 *
 * **过期的那些数**: 宿主那一侧把"20 分钟没续期"当没人占 (它读的时候顺手判), 而这里读到一个过期的文件
 * 会**顺手删掉** —— 设置页那一行说的必须与闸判的是同一件事, 否则会出现"界面上说有人占着, 而取景根本
 * 不被拦"这种最费解的状态
 */
internal object CameraOwner {

    /** 与宿主插件同一个名字 (`host-plugin/index.mjs` 的 `CAMERA_OWNER_FILE`) */
    const val FILE = "camera-owner.json"

    /** 多久没有续期就算过期, 与插件那一侧同一个数 (`CAMERA_OWNER_STALE_MS`) */
    const val STALE_MS = 20 * 60 * 1000L

    /** 表里的一条: 谁、从什么时候起、最近一次用是什么时候、在哪一头上取的景 */
    internal data class Owner(
        val sessionId: String,
        val since: Long,
        val at: Long,
        val lens: String?,
    )

    /** 占用表的位置: `$DSH_HOME/modes/camera-owner.json` (与 [LwModes] 那三份正文同一个目录) */
    fun file(context: Context): File = File(LwModes.modesDir(context), FILE)

    /**
     * 解析一份占用表, **不碰设备** (所以能单测)
     *
     * 回 null 的三种情况都说一说: 少了 `sessionId` 或一个能认的 `at` (写坏 / 被别的版本写过), 以及
     * [now] 那一刻已经过了 [STALE_MS]。`since` 取不到就按 `at` 算 —— 时间是拿来给人看的, 不值得为它
     * 把整条记录判死
     */
    fun parse(raw: String, now: Long): Owner? {
        val sessionId = pick(raw, "sessionId")?.trim().orEmpty()
        val at = pick(raw, "at")?.trim()?.toLongOrNull()
        if (sessionId.isEmpty() || at == null) return null
        if (now - at > STALE_MS) return null
        return Owner(
            sessionId = sessionId,
            since = pick(raw, "since")?.trim()?.toLongOrNull() ?: at,
            at = at,
            lens = pick(raw, "lens")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** 现在谁占着 (没人占 / 文件不在 / 已经过期都回 null); **过期的文件顺手删掉**, 见类注释 */
    fun read(context: Context, now: Long = System.currentTimeMillis()): Owner? {
        val file = file(context)
        if (!file.isFile) return null
        val owner = runCatching { parse(file.readText(), now) }
            .onFailure { Log.w(TAG, "the camera owner table could not be read: ${it.message}") }
            .getOrNull()
        if (owner == null) runCatching { file.delete() }
        return owner
    }

    /**
     * 强制释放 (球菜单与设置页那两条入口走它)
     *
     * 不看表里是谁: 那两条入口存在的理由就是"表卡住了, 而人知道现在没有谁在取景"
     */
    fun release(context: Context): Boolean =
        runCatching { file(context).delete() }
            .onFailure { Log.w(TAG, "the camera owner table could not be released: ${it.message}") }
            .getOrDefault(false)

    /** 会话 id 只报前八个字符: 表里放的是整条, 而界面上那一行要能读 */
    fun short(sessionId: String): String = sessionId.take(8)

    /** 一个时间点的钟点 (设备本地时间), 用在"从什么时候起"那一句里 */
    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    /* ── 下面都是这一份取值器自己 ───────────────────────────────────────────── */

    /**
     * 找 [key] 那个键的值, 原样回那一段文字 (**字符串连着两个引号一起回**)
     *
     * 只认"键后面跟着冒号"的位置, 所以值里出现同一个词不会被当成键 (与 `ReleaseJson.pick` 同一个写法);
     * 占用表是扁平的四个键, 所以对象与数组那两条路这里都不需要
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
                if (cursor >= raw.length) return null
                if (raw[cursor] == '"') {
                    val end = raw.indexOf('"', cursor + 1)
                    return if (end < 0) null else raw.substring(cursor + 1, end)
                }
                return raw.substring(cursor).takeWhile { !it.isWhitespace() && it != ',' && it != '}' }
            }
            at = raw.indexOf(token, at + token.length)
        }
        return null
    }

    /** 日志标记: 这一条只在表读不动 / 删不掉时说话 (那两种情况都不该弹任何东西给主人) */
    private const val TAG = "LwCameraOwner"
}
