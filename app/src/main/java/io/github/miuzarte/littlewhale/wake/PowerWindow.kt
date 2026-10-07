package io.github.miuzarte.littlewhale.wake

import java.time.Instant
import java.time.ZoneId

/**
 * 省电模式那个"定时开关"的纯算术: 一句 `23:00-07:00` 怎么解析、此刻在不在里面
 *
 * 主人 2026-10-07 的口径: **省电模式 = 只停唤醒词监听** (麦克风整个关掉, 喊不醒), host / 浮标 /
 * 通知都留着, 点球照样能说一句话; 而"定时开关"放在设置页, **时间由主人自己定**。
 *
 * 这一层不碰安卓也不碰偏好, 所以"几点算凌晨、跨零点怎么算"这些判据 JVM 单测能直接量 (见
 * [PowerWindowTest]) —— 它们的错法是静默的: 一个算反的判据只会让麦克风在不该关的时候关掉
 *
 * 三条口径写在这里, 别处不再解释:
 *
 * - **跨零点**: 起 > 止 (如 `23:00-07:00`) 就是把一天切成"晚上那一段 + 凌晨那一段"
 * - **起 == 止**: 空窗, **永远不省电** —— 把同一个时刻填两遍多半是没写完, 而猜成"全天省电"会让
 *   麦克风静默地一直关着, 那是两边里更坏的一边
 * - 区间**左闭右开**: `[起, 止)`, 到点那一刻就算出来了 (07:00 已经在省电时段之外)
 */
internal object PowerWindow {

    /** 一个时段: 起止都按"当天第几分钟"记 (0..1439) */
    data class Window(val start: Int, val end: Int) {
        /** 起止相同 = 空窗 (见上面那条口径) */
        val empty: Boolean get() = start == end
    }

    /**
     * 解析 `23:00-07:00` 这种一句
     *
     * 收这三种写法, 别的都回 null 由调用方说"看不懂": 全角冒号与波浪线当分隔符一并认 (中文输入法
     * 下它们是默认打出来的), 空格无所谓, 每个点也可以只写小时 (`23-7` = `23:00-07:00`)
     */
    fun parse(text: String): Window? {
        val cleaned = text.trim()
            .replace('：', ':')
            .replace('～', '-')
            .replace('~', '-')
            .replace('—', '-')
            .replace('–', '-')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('-')
        if (parts.size != 2) return null
        val start = parsePoint(parts[0]) ?: return null
        val end = parsePoint(parts[1]) ?: return null
        return Window(start, end)
    }

    /** `23:00` / `23` -> 1380; 小时或分钟出界就是看不懂 */
    private fun parsePoint(text: String): Int? {
        val pieces = text.trim().split(':')
        if (pieces.isEmpty() || pieces.size > 2) return null
        val hour = pieces[0].trim().toIntOrNull() ?: return null
        val minute = if (pieces.size == 2) pieces[1].trim().toIntOrNull() ?: return null else 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** 此刻在不在这个时段里 (`minutes` 是"当天第几分钟") */
    fun inside(minutes: Int, window: Window): Boolean = when {
        window.empty -> false
        window.start < window.end -> minutes >= window.start && minutes < window.end
        // 跨零点: 起那一头到午夜 + 午夜到止那一头
        else -> minutes >= window.start || minutes < window.end
    }

    /** 一个 epoch 时刻落在当天的第几分钟 (时区显式给, 单测可以点名 UTC) */
    fun minutesOfDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val time = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    /** 1380 -> `23:00` (恒两位, 读起来才是时刻不是数字) */
    fun label(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** 给人看的那一句 */
    fun describe(window: Window): String = "${label(window.start)} - ${label(window.end)}"
}
