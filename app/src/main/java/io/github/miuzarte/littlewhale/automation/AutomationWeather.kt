package io.github.miuzarte.littlewhale.automation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 天气那一条要的两步: 地名换经纬度, 拿实况
 *
 * 数据源与 `skills/weather/SKILL.md` 是同一份 Open-Meteo (免 key, 两步调用) —— 技能那一份是给模型
 * 按需查的, 这一份是给引擎定时查的, **参数名与对照表必须和它一致**, 不然同一天里技能报"小雨"而
 * 规则说"没下雨"。`tools/check-automations.mjs` 比一遍两条 URL 里那几个参数名
 *
 * 三条纪律: 网络超时就当"这一轮没问到" (不编数字); 读不出来的字段给 null (不说成 0); 出错只写日志,
 * 判定那一层会把它记成 `unavailable`
 */
internal object AutomationWeather {

    /** 一次请求最多等多久: 引擎是定时问的, 慢就等于这一轮没有 */
    private const val TIMEOUT_MS = 10_000

    private const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    /** 地名 → 第一个结果的经纬度; 认不出来就是 null */
    fun geocode(name: String): AutomationStore.Place? {
        val url = "$GEOCODING?name=${URLEncoder.encode(name, "UTF-8")}&count=1&language=zh&format=json"
        val body = get(url) ?: return null
        val root = runCatching { AutomationJson.parse(body) }.getOrNull() ?: return null
        val first = (root["results"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? JsonObject
            ?: return null
        val latitude = first["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val longitude = first["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        return AutomationStore.Place(latitude, longitude, System.currentTimeMillis())
    }

    /** 一次实况: 三个数 (各可能为 null) */
    fun fetch(latitude: Double, longitude: Double): AutomationEngine.WeatherNow? {
        val url = "$FORECAST?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,precipitation,weather_code&timezone=auto"
        val body = get(url) ?: return null
        val root = runCatching { AutomationJson.parse(body) }.getOrNull() ?: return null
        val current = root["current"]?.jsonObject ?: return null
        return AutomationEngine.WeatherNow(
            temperature = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull,
            precipitation = current["precipitation"]?.jsonPrimitive?.doubleOrNull,
            code = current["weather_code"]?.jsonPrimitive?.intOrNull,
        )
    }

    /** 一行读数 (给 `status` 看) */
    fun describe(reading: AutomationEngine.WeatherNow): String {
        val rows = mutableListOf<String>()
        reading.temperature?.let { rows += "${trim(it)}°C" }
        reading.precipitation?.let { rows += "降水 ${trim(it)} mm" }
        reading.code?.let { rows += codeName(it) }
        return if (rows.isEmpty()) "没有可用的数" else rows.joinToString(", ")
    }

    /** `weather_code` 对照表: 与技能那一份同一张 (报天气必须按它说) */
    fun codeName(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "少云"
        2 -> "多云"
        3 -> "阴"
        45 -> "雾"
        48 -> "结霜雾"
        51 -> "毛毛雨"
        53 -> "毛毛雨"
        55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "阵雨"
        81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴夹冰雹"
        else -> "天气代码 $code"
    }

    /** 一次 GET, 失败 (超时 / 断网 / 非 200) 就是 null */
    private fun get(url: String): String? = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "LittleWhale")
        }
        try {
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    } catch (problem: Throwable) {
        android.util.Log.w("LwAutomation", "weather request failed: ${problem.message}")
        null
    }

    private fun trim(value: Double): String = when {
        value == value.toLong().toDouble() -> value.toLong().toString()
        else -> "%.1f".format(value)
    }
}
