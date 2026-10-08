package io.github.miuzarte.littlewhale.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 相机占用表那条解析 (批次 3 的需求 3)
 *
 * 这一份**不碰设备**: 表是宿主插件写的一份 JSON, 而应用这一侧只读它 (显示那两行 + 手动释放那两条
 * 入口)。所以这里量的是"写坏了 / 过期了"那几档的判法 —— 那些档在设备上很难造, 而它们的下场都一样:
 * 当成"没人占" (闸那一侧也这么判, 两边必须是同一个答案)
 */
internal class CameraOwnerTest {

    /** 一个时间基准: 用固定的数, 免得测试跟着墙上那口钟漂 */
    private val now = 1_760_000_000_000L

    private fun table(
        sessionId: String = "abc12345-def6-7890-abcd-ef1234567890",
        since: String = (now - 60_000).toString(),
        at: String = now.toString(),
        lens: String? = "back",
    ) = buildString {
        append("{\"sessionId\":\"$sessionId\",\"since\":$since,\"at\":$at")
        if (lens != null) append(",\"lens\":\"$lens\"")
        append("}\n")
    }

    @Test
    fun `一条正常的占用表把四个字段都读出来`() {
        val owner = CameraOwner.parse(table(), now)
        assertEquals("abc12345-def6-7890-abcd-ef1234567890", owner?.sessionId)
        assertEquals(now - 60_000, owner?.since)
        assertEquals(now, owner?.at)
        assertEquals("back", owner?.lens)
    }

    /** 镜头那一栏是可缺的: 大多数调用不点名镜头, 而它不该让整条记录读不出来 */
    @Test
    fun `没有 lens 也读得出来`() {
        assertEquals(null, CameraOwner.parse(table(lens = null), now)?.lens)
    }

    /** `since` 取不到就按 `at`: 那是拿来给人看的一行, 不值得为它把整条判死 */
    @Test
    fun `没有 since 就按 at 算`() {
        val raw = "{\"sessionId\":\"s1\",\"at\":$now,\"lens\":\"front\"}"
        assertEquals(now, CameraOwner.parse(raw, now)?.since)
    }

    @Test
    fun `少了 sessionId 的那一条不算占用`() {
        assertEquals(null, CameraOwner.parse("{\"at\":$now}", now))
    }

    @Test
    fun `at 不是数字的那一条不算占用`() {
        assertEquals(null, CameraOwner.parse("{\"sessionId\":\"s1\",\"at\":\"一会\"}", now))
    }

    @Test
    fun `读到半个字符的那一条不算占用`() {
        assertEquals(null, CameraOwner.parse("{\"sessionId\":\"s1\",\"at\":", now))
    }

    /** 20 分钟那一档: 边界上还算占用 (`now - at > STALE_MS` 才过期) */
    @Test
    fun `刚好二十分钟还算占用, 过了一毫秒就不算`() {
        val edge = table(at = (now - CameraOwner.STALE_MS).toString())
        assertEquals("abc12345-def6-7890-abcd-ef1234567890", CameraOwner.parse(edge, now)?.sessionId)
        val late = table(at = (now - CameraOwner.STALE_MS - 1).toString())
        assertEquals(null, CameraOwner.parse(late, now))
    }

    /** 界面上那一行只报前八个字符与钟点: 整条 id 放不进那一行, 也没人念得出来 */
    @Test
    fun `短 id 与钟点那两个读数`() {
        assertEquals("abc12345", CameraOwner.short("abc12345-def6-7890"))
        assertEquals(5, CameraOwner.clock(now).length)
        assertEquals(':', CameraOwner.clock(now)[2])
    }

    /** 取值器只认"键后面跟冒号"的位置: 值里出现同样的字样不算 (与 `ReleaseJson.pick` 同一个写法) */
    @Test
    fun `键认错位置时不算占用`() {
        val raw = "{\"note\":\"sessionId 与 at 这些字样出现在值里\",\"sessionId\":\"s1\",\"at\":$now}"
        assertEquals("s1", CameraOwner.parse(raw, now)?.sessionId)
        assertNull(CameraOwner.parse("{\"sessionId\":\"\",\"at\":$now}", now))
    }
}
