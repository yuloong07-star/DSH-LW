package io.github.miuzarte.littlewhale.overlay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在想」那 5 个环色怎么发: **纯算术, 没有设备也能量** (2026-10-08)
 *
 * 那一档的颜色是用来认会话的 ("现在这个正在想是不是我先前那一场"), 所以两条性质最要紧:
 * **同一个会话永远同一个色**, 以及**轮转** (第 6 个会话与第 1 个同色 —— 只有 5 个色, 主人接受这一点)。
 * 表是落盘的, 所以再补一条"存下去再读回来还是同一张"
 */
class BallPhaseFileTest {

    private val size = 5

    /** 新会话按顺序拿到 0,1,2,3,4, 第 6 个回到 0 */
    @Test
    fun `轮转发号`() {
        var table = BallPhaseFile.Colors(0, emptyList())
        val got = mutableListOf<Int>()
        for (at in 0 until 6) {
            val (next, index) = table.assign("s$at", size)
            table = next
            got += index
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 0), got)
    }

    /** 同一个会话再问一次: 还是那个号, 而且**表没变** (调用方据此不落盘) */
    @Test
    fun `同一个会话永远同一个色`() {
        val (table, first) = BallPhaseFile.Colors(0, emptyList()).assign("s1", size)
        val (same, again) = table.assign("s1", size)
        assertEquals(first, again)
        assertSame("分过就不该再动那张表", table, same)
        assertNotSame("第一次是要落盘的", table, BallPhaseFile.Colors(0, emptyList()))
    }

    /** 编码再解析: 计数与每一个号都还在 (落盘那一趟不能丢东西) */
    @Test
    fun `存下去再读回来是同一张表`() {
        var table = BallPhaseFile.Colors(0, emptyList())
        listOf("a", "b", "c").forEach { session ->
            table = table.assign(session, size).first
        }
        val read = BallPhaseFile.Colors.parse(table.encode())
        assertEquals(table.next, read.next)
        assertEquals(table.entries, read.entries)
        assertEquals(0, read.index("a"))
        assertEquals(2, read.index("c"))
    }

    /** 读不懂的东西一律当"还没发过" (宁可重发一遍, 也不要抛出去把球那一拍打断) */
    @Test
    fun `读不懂就当空表`() {
        val empty = BallPhaseFile.Colors.parse("这不是 JSON")
        assertEquals(0, empty.next)
        assertTrue(empty.entries.isEmpty())
        assertNull(empty.index("a"))
        assertEquals(0, BallPhaseFile.Colors.parse(null).next)
        assertEquals(0, BallPhaseFile.Colors.parse("").next)
    }

    /** 表满了就丢最老的 (KEEP = 64), 但**发号还在往前走** (颜色仍然轮转) */
    @Test
    fun `表满了丢最老的`() {
        var table = BallPhaseFile.Colors(0, emptyList())
        for (at in 0 until 80) {
            table = table.assign("s$at", size).first
        }
        assertEquals(80, table.next)
        assertEquals(64, table.entries.size)
        assertNull("最老的那些已经不在表里了", table.index("s0"))
        assertEquals(79 % size, table.index("s79"))
    }

    /* ── 那份文件的两半: 在跑的 (turns) 与刚结束的 (last, 2026-10-08 加) ───────── */

    /** 把一段 JSON 变成那两个纯解析函数吃的根对象 */
    private fun root(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    /** `turns` 里取**最近开始**的那一场: 两场同时在跑时球上写谁, 由这个数说了算 */
    @Test
    fun `在跑的那几场里取最近开始的`() {
        val turns = BallPhaseFile.parseTurns(
            root(
                """{"turns":[{"id":"old","startedAt":100},{"id":"new","startedAt":900},{"id":"mid","startedAt":500}]}""",
            ),
        )
        assertEquals("new", turns?.id)
        assertEquals(900L, turns?.startedAt)
    }

    /** 空表 / 没有这个键 / 不是数组 / 单条缺字段: 一律当"没有在跑", 而且坏的那几条要跳过 */
    @Test
    fun `空表与坏条目都不算在跑`() {
        assertNull(BallPhaseFile.parseTurns(root("""{"turns":[]}""")))
        assertNull(BallPhaseFile.parseTurns(root("""{"at":1}""")))
        assertNull(BallPhaseFile.parseTurns(root("""{"turns":"不是数组"}""")))
        assertNull(BallPhaseFile.parseTurns(null))
        val only = BallPhaseFile.parseTurns(
            root("""{"turns":[{"startedAt":100},{"id":"ok","startedAt":200},{"id":"no-at"}]}"""),
        )
        assertEquals("ok", only?.id)
    }

    /**
     * **整份清单要一起读回来** (2026-10-09 加的那两笔自动收口要它): 球上那个字只要最近那一场, 而
     * "同一场起了新的一轮"要能把那一场在这一批里找出来 —— 只留 `latest` 一个就答不了后面那一问。
     * "最新的开始时刻" ([BallPhaseFile.Snapshot.newestAt]) 是"起了一轮新的"那个边沿的判据
     */
    @Test
    fun `在跑的整份清单与最新的开始时刻`() {
        val snapshot = BallPhaseFile.Snapshot(
            running = BallPhaseFile.parseRunning(
                root("""{"turns":[{"id":"a","startedAt":100},{"id":"b","startedAt":900}]}"""),
            ),
            last = null,
            note = "",
        )
        assertEquals(listOf("a", "b"), snapshot.running.map { it.id })
        assertEquals("b", snapshot.latest?.id)
        assertEquals(900L, snapshot.newestAt)
        assertEquals(0L, BallPhaseFile.Snapshot(emptyList(), null, "").newestAt)
        assertNull(BallPhaseFile.Snapshot(emptyList(), null, "").latest)
    }

    /** 坏条目跳过而好的那几条照留: 一份坏 entry 不许把整批在跑的带走 */
    @Test
    fun `清单里坏的那些跳过`() {
        val running = BallPhaseFile.parseRunning(
            root("""{"turns":[{"id":"a","startedAt":100},{"startedAt":9},{"id":"c","startedAt":700}]}"""),
        )
        assertEquals(listOf("a", "c"), running.map { it.id })
    }

    @Test
    fun `last 那一条整份读出来`() {
        val ended = BallPhaseFile.parseEnded(
            root("""{"last":{"id":"s1","at":1759600000000,"kind":"error","why":"ENOSPC"}}"""),
        )
        assertEquals("s1", ended?.id)
        assertEquals(1_759_600_000_000L, ended?.at)
        assertEquals("error", ended?.kind)
        assertEquals("ENOSPC", ended?.why)
    }

    /**
     * 缺了或读不懂都当"没有失败"
     *
     * 三种现实来源都要落在这里: 旧宿主 (没有这个键)、宿主刚重启 (那条记忆随进程没了)、以及文件被
     * 写坏。**宁可什么都不说, 也不要凭空在球上亮两个字**
     */
    @Test
    fun `last 缺了或读不懂都是没有失败`() {
        assertNull(BallPhaseFile.parseEnded(root("""{"turns":[]}""")))
        assertNull(BallPhaseFile.parseEnded(root("""{"last":"error"}""")))
        assertNull(BallPhaseFile.parseEnded(root("""{"last":{"at":1,"kind":"error"}}""")))
        assertNull(BallPhaseFile.parseEnded(root("""{"last":{"id":"s1","kind":"error"}}""")))
        assertNull(BallPhaseFile.parseEnded(root("""{"last":{"id":"s1","at":1}}""")))
        assertNull(BallPhaseFile.parseEnded(null))
    }

    /** 失败没带理由 (`blocked` / `max-tokens` 那两条): `why` 是空串 —— 球上反正不显示它 */
    @Test
    fun `没有 why 时是空串`() {
        val ended = BallPhaseFile.parseEnded(
            root("""{"last":{"id":"s1","at":1,"kind":"blocked"}}"""),
        )
        assertEquals("blocked", ended?.kind)
        assertEquals("", ended?.why)
    }

    /**
     * **两半互不牵连**: `last` 读不懂不许把"哪几场在跑"一起带走 —— 球上第一个字段比第二个要紧
     */
    @Test
    fun `last 读不懂不影响在跑的那几场`() {
        val one = root("""{"turns":[{"id":"live","startedAt":700}],"last":{"id":"s1"}}""")
        assertEquals("live", BallPhaseFile.parseTurns(one)?.id)
        assertNull(BallPhaseFile.parseEnded(one))
    }
}
