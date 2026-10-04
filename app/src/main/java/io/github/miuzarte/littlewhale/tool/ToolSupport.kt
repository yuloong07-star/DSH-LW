package io.github.miuzarte.littlewhale.tool

import android.content.Context
import io.github.miuzarte.littlewhale.channel.PrivilegedChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 新工具的公共脚手架
 *
 * 这一批能力与屏幕那套不一样: 屏幕那套必须过特权通道 (注入、建屏、读 evdev 都是 app uid 做不到的),
 * 而通知、剪贴板、电池、系统设置这些**在 app 进程里用 Context 就能做**, 走通道只是多绕一圈 binder。
 * 所以它们的落点是这里: app 进程直接执行, 结果由 `PrivilegedBridge` 像别的桥方法一样回给 host
 *
 * 三条一起定下来的规矩:
 *
 * - 每个能力先过权限闸, 缺权限时回一句"缺哪一条、怎么给", **不假装成功**
 * - 能读回的都读回 (音量、亮度写完再读一次), 免得一个退出码被当成成功
 * - 回给模型的是一份给人看的文本, 放在 `{"text": ...}` 里; 要结构化字段的另说
 */
internal val toolContext: Context?
    get() = PrivilegedChannel.context()

/** 这个工具要的那个 context, 没有就是个错误而不是空操作 */
internal fun requireContext(): Context = toolContext
    ?: throw IllegalStateException("this app has not been initialized, so there is no context to use")

/** 一句话的答案, 交给 `{"text": ...}` 那条路 */
internal fun text(value: String): JsonObject = buildJsonObject { put("text", value) }

/**
 * 已经写好的字段再加一句总结
 *
 * 有的工具要回不止一句话 (等一个控件出现: 找到了没有、等了多久、在哪), 而那些字段是给插件看的;
 * 真正念给模型的还是 `text` 这一个字段, 所以两边都要有
 */
internal fun JsonObject.message(summary: String): JsonObject = buildJsonObject {
    this@message.forEach { (name, element) -> put(name, element) }
    put("text", summary)
}

/** 要一个字符串参数 */
internal fun JsonObject.string(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("this call has to name $key")

/** 要一个字符串参数, 没有就给一个默认值 */
internal fun JsonObject.string(key: String, fallback: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fallback

/** 一个可能没有的字符串参数 */
internal fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** 要一个整数参数 */
internal fun JsonObject.int(key: String): Int =
    this[key]?.jsonPrimitive?.longOrNull?.toInt()
        ?: throw IllegalArgumentException("this call has to name $key as a whole number")

/** 一个整数参数, 没有就用默认值 */
internal fun JsonObject.int(key: String, fallback: Int): Int =
    this[key]?.jsonPrimitive?.longOrNull?.toInt() ?: fallback

/** 一个浮点参数, 没有就用默认值 */
internal fun JsonObject.number(key: String, fallback: Double): Double =
    this[key]?.jsonPrimitive?.doubleOrNull ?: fallback

/** 一个布尔参数, 没有就用默认值 */
internal fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: fallback

/** 子对象的数组, 用来传手势的那种路径 */
internal fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

/** 一个字符串数组 */
internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()

/**
 * 把一张表按"名字: 值"排成文本
 *
 * 模型读文本比读 JSON 省事, 而这些信息本来就是给人看的
 */
internal fun table(rows: List<Pair<String, String>>): String =
    rows.joinToString("\n") { (name, value) -> "$name: $value" }

/** 人读的字节数 */
internal fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.2f GiB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> "%.1f MiB".format(value / (1024.0 * 1024))
    value >= 1024 -> "%.1f KiB".format(value / 1024.0)
    else -> "$value B"
}

/** 一条"没有这个能力"的原因, 与权限闸那句话拼在一起 */
internal fun unavailable(what: String, why: String): Nothing =
    throw IllegalStateException("$what is not available: $why")
