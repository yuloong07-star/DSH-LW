package io.github.yuloong07star.luwi.automation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 主人自己定的那个冷却 ([AutomationRule.withCooldown] / [AutomationRule.coerceCooldown])
 *
 * 为什么值得单测: 设置页那条"直接改冷却"是**不经过模型**的直改 —— 它只替换 `cooldownMinutes` 一个键,
 * 别的键一个都不许动。这条"只换一个键"的算术只在这一个纯函数里, 而它要是错了, 主人改冷却会顺手把
 * 整条规则的条件或动作改掉
 */
class AutomationCooldownTest {

    /** 边界内原样, 越界收到 0..1440 */
    @Test
    fun `冷却数收进合法范围`() {
        assertEquals(0, AutomationRule.coerceCooldown(-5))
        assertEquals(0, AutomationRule.coerceCooldown(0))
        assertEquals(45, AutomationRule.coerceCooldown(45))
        assertEquals(AutomationRule.MAX_COOLDOWN_MINUTES, AutomationRule.coerceCooldown(99_999))
    }

    /** 那几档给人挑的冷却: 头是"不冷却", 尾是上限 */
    @Test
    fun `给人挑的那几档头尾是零与上限`() {
        assertEquals(0, AutomationRule.COOLDOWN_CHOICES.first())
        assertEquals(AutomationRule.MAX_COOLDOWN_MINUTES, AutomationRule.COOLDOWN_CHOICES.last())
        assertEquals(
            AutomationRule.COOLDOWN_CHOICES.sorted(),
            AutomationRule.COOLDOWN_CHOICES,
        )
    }

    /** 只换冷却那一个键: 别的一律原样, 连不认识的键都留着 (以后的版本加的键不被这一条吃掉) */
    @Test
    fun `只换冷却那一个键`() {
        val before = buildJsonObject {
            put("name", "带伞")
            put("enabled", true)
            put("cooldownMinutes", 30)
            put("dailyLimit", 5)
            put("futureKey", "留着")
        }
        val after = AutomationRule.withCooldown(before, 15)
        assertEquals(15, after["cooldownMinutes"]?.jsonPrimitive?.int)
        assertEquals("带伞", after["name"]?.jsonPrimitive?.content)
        assertEquals(5, after["dailyLimit"]?.jsonPrimitive?.int)
        assertEquals("留着", after["futureKey"]?.jsonPrimitive?.content)
    }

    /** 改完的数照样过校验 (0 与上限都能收) */
    @Test
    fun `改完的数过得了校验`() {
        for (minutes in AutomationRule.COOLDOWN_CHOICES + listOf(45, AutomationRule.MAX_COOLDOWN_MINUTES)) {
            val root = buildJsonObject {
                put("name", "带伞")
                put("when", buildJsonObject { put("kind", "time"); put("at", "08:00") })
                put("then", buildJsonObject { put("kind", "remind"); put("text", "提醒带伞") })
            }
            val next = AutomationRule.withCooldown(root, minutes)
            val text = AutomationJson.writer.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                next,
            )
            val parsed = AutomationRule.parse("带伞", text)
            assertNotNull(parsed)
            assertEquals(AutomationRule.coerceCooldown(minutes), parsed.cooldownMinutes)
        }
    }
}
