package io.github.miuzarte.littlewhale.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词表那一半的判据: `词=带音调数字的拼音` 变成模型认得的 token 行
 *
 * 这一段**没有一行会报错地失败**: 写错一个音、或者声母的顺序反了 (把 `zh` 拆成 `z` + `h`), 症状不是
 * 异常, 而是"词表存下来了、界面也说保存好了, 而喊那个词永远不触发" —— sherpa-onnx 的
 * `EncodeKeywords` 对不认的符号是把**整行静默丢掉**。所以每一条判据都对着一个具体的静默失败面
 *
 * **符号表是推出来的, 不是手抄的**: [CASES] 里每一对的期望值就是"这一行该长什么样", 而表取这些
 * 期望值的并集。手抄一张表会有两个毛病 —— 抄漏一个韵母就误报 (本轮就踩过: 漏了 `ōu` / `éi` / `iǎo` /
 * `ér`, 八条判据一起红, 而代码一行没错), 更糟的是抄错了还以为在验模型。这样写之后
 * [assertEveryTokenKnown] 那一条问的是"它吐出来的 token 自己认不认得", 而"拼写对不对"由期望值本身钉住
 *
 * 这一份与宿主插件里那一段 (`host-plugin/index.mjs` 的 `wakeWordLine`) 是同一套算法的两份实现,
 * 所以判据也是双份的: 这边过不代表那边过, 但那边的期望值就是这里的期望值
 */
class WakeWordWordsTest {

    /**
     * 判据表: `词=拼音` -> 该吐出来的那一行
     *
     * 期望值不是猜的: 前三条拿模型的例子逐字节对上过 (见 `docs/wake-word.md` 第三节), 其余的按同一套
     * 规则 (声母长的先匹配, 调号 a/o/e 优先、iu 落 u、ui 落 i、其余落最后一个元音, 轻声不带调号) 写出来
     */
    private val cases = linkedMapOf(
        // 缺省那一条, 也是缺省那张词表的第一条 (2026-10-06 主人定回「肥鱼肥鱼」, 六音节那一版
        // 「大肥鱼大肥鱼」作废)。这一行的期望值读的就是 `WakeWordWords.DEFAULT`, 而
        // `tools/check-wake-words.mjs` 另有两条判据: 整张缺省表必须与插件那一份同字, 而它的第一条
        // 就是这里这一个
        WakeWordWords.DEFAULT to "f éi y ú f éi y ú @肥鱼肥鱼",
        // 缺省表里那三条容错读音 (f / h 与 ü / i 两处口音合并, 见 `WakeWordWords.DEFAULT_WORDS`):
        // 它们就是缺省词表的第二到第四条, 这里逐条钉住, 改了缺省而没改它们就会当场红
        "肥鱼肥鱼=hui2 yu2 hui2 yu2" to "h uí y ú h uí y ú @肥鱼肥鱼",
        "肥鱼肥鱼=fei2 yi2 fei2 yi2" to "f éi y í f éi y í @肥鱼肥鱼",
        "肥鱼肥鱼=hui2 yi2 hui2 yi2" to "h uí y í h uí y í @肥鱼肥鱼",
        // 上游 keywords.txt 里那两个例子
        "你好军哥=ni3 hao3 jun1 ge1" to "n ǐ h ǎo j ūn g ē @你好军哥",
        "小爱同学=xiao3 ai4 tong2 xue2" to "x iǎo ài t óng x ué @小爱同学",
        "周望军=zhou1 wang4 jun1" to "zh ōu w àng j ūn @周望军",
        // `nv` 与 `nü` 是**同一个音的两半写法**: `v` 在拆之前就变成了 `ü`, 所以两边都得到 `n` + `ǚ`
        // (`ǚ` 一个 token, 不是 `v` 加 `ǚ`)
        "女儿=nv3 er2" to "n ǚ ér @女儿",
        // 翘舌声母不被拆成两个 (顺序反了就是两个错的 token, 安静地永不触发)
        "吃=chi1" to "ch ī @吃",
        "是=shi4" to "sh ì @是",
        // 零声母的音节整个当一个 token
        "啊=a1" to "ā @啊",
        "欧=ou1" to "ōu @欧",
        // 调号落在哪个元音上
        "六=liu4" to "l iù @六",
        "水=shui3" to "sh uǐ @水",
        "雪=xue3" to "x uě @雪",
        // 轻声 (第 5 声) 不带调号
        "的=de5" to "d e @的",
        // 已经带调号的写法原样收下: 两种写法都认, 是人就会两种都写
        "大肥鱼=dà féi yú" to "d à f éi y ú @大肥鱼",
        // v / u: 都是 ü
        "女儿=nü3 er2" to "n ǚ ér @女儿",
        "绿=lv4" to "l ǜ @绿",
    )

    /** 符号表: 上面那些期望值里出现过的每一个 token */
    private val table: Set<String> = cases.values
        .flatMap { it.substringBefore(" @").split(" ") }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * 吐出来的那一行必须与期望值一字不差, 而且每一个 token 都在表里
     *
     * 第二半才是这一段的要害: 少一个就是"这一行整个会被静默丢掉"
     */
    private fun assertLine(pair: String, expected: String) {
        val line = WakeWordWords.line(pair, table)
        assertEquals(expected, line)
        val tokens = line.substringBefore(" @").split(" ").filter { it.isNotEmpty() }
        val unknown = tokens.filterNot { it in table }
        assertTrue("这些 token 不在符号表里, 那一行会被静默丢掉: $unknown", unknown.isEmpty())
    }

    /** 一条判据一行, 失败了能一眼看出是哪一个词 */
    private fun eachCase(body: (pair: String, expected: String) -> Unit) {
        cases.forEach { (pair, expected) -> body(pair, expected) }
    }

    @Test
    fun `每一对都拆成期望的那串 token`() {
        eachCase { pair, expected -> assertLine(pair, expected) }
    }

    /** 显示名取 `@` 之后那一段: 设置页与状态都靠它说"现在守的是哪几个词" */
    @Test
    fun `显示名是@之后那一段`() {
        eachCase { pair, expected ->
            val word = pair.substringBefore('=')
            assertEquals(word, keywordName(expected))
            assertEquals(word, WakeWordWords.displayName(expected))
        }
        // 没有 `@` 就回整行, 不装作认出来了
        assertEquals("s ù y ún", keywordName("s ù y ún"))
    }

    /**
     * 对不上的 token 必须**抛**, 而且要说是哪一个
     *
     * 这是整段里最要紧的一条: 不抛就是"照做了但永远不触发", 而人说不出为什么
     *
     * 表取判据表里那一行的期望 token **减去一个**, 一次只少一个 —— 于是"抛没抛、点没点名"就只由
     * 那一个决定。去掉的是声母 `n`: 在 `nü3` 里它是第一个 token, 所以它一定在抛出之前先被查
     */
    @Test
    fun `表里没有的 token 抛出来并点名是哪一个`() {
        val full = cases.getValue("女儿=nü3 er2").substringBefore(" @").split(" ").toMutableSet()
        assertTrue("前提: n 该是那一个声母", full.remove("n"))
        val error = assertThrows(IllegalArgumentException::class.java) {
            WakeWordWords.line("女儿=nü3 er2", full)
        }
        assertTrue("要说清是哪一个 token: ${error.message}", error.message!!.contains("\"n\""))
    }

    /** 一行一对: 缺 `=`、两边空都要当场拒掉, 不能安静地写出一行垃圾 */
    @Test
    fun `写不成词=拼音的那些行都拒`() {
        assertThrows(IllegalArgumentException::class.java) { WakeWordWords.line("大肥鱼", table) }
        assertThrows(IllegalArgumentException::class.java) { WakeWordWords.line("=da4", table) }
        assertThrows(IllegalArgumentException::class.java) { WakeWordWords.line("大肥鱼=", table) }
    }

    /** 整段文本: 换行 / 分号 / 全角分号都当分隔, 空行丢掉 */
    @Test
    fun `整段文本按行与分号切开`() {
        // 第一行用**缺省那一条**而不是写死的老词: 缺省改过几次 (大肥鱼大肥鱼 -> 肥鱼肥鱼 -> 大肥鱼
        // 大肥鱼 -> 肥鱼肥鱼), 写死的字符串会让这一条在改缺省时莫名其妙地红 —— 而它想验的是
        // "分隔符与空行", 不是某个词
        val text = "${WakeWordWords.DEFAULT}\n\n小爱同学=xiao3 ai4 tong2 xue2；啊=a1"
        val lines = WakeWordWords.lines(text, table)
        assertEquals(3, lines.size)
        assertEquals(cases.getValue(WakeWordWords.DEFAULT), lines[0])
        assertEquals(cases.getValue("小爱同学=xiao3 ai4 tong2 xue2"), lines[1])
        assertEquals(cases.getValue("啊=a1"), lines[2])
    }

    /** 缺省那张词表: 本体加三条容错, 四条各不相同, 而且都认得 */
    @Test
    fun `缺省词表四条都认得`() {
        val lines = WakeWordWords.lines(WakeWordWords.defaultText(), table)
        assertEquals(4, lines.size)
        assertEquals(4, lines.distinct().size)
        assertEquals(cases.getValue(WakeWordWords.DEFAULT), lines[0])
    }

    /**
     * 表本身是自洽的: 表里每一个 token 都是"某个词吐出来的", 而合起来的表仍然是上面那些判据的表
     *
     * 这一条防的是"判据表改了而符号表没跟上"那种自欺 —— 表是推出来的, 所以它不可能与判据表脱节,
     * 这一条只是把这件事钉成可执行的真值
     */
    @Test
    fun `符号表就是从判据表推出来的`() {
        assertTrue("表不该是空的", table.isNotEmpty())
        eachCase { _, expected ->
            val tokens = expected.substringBefore(" @").split(" ").filter { it.isNotEmpty() }
            assertTrue("期望值里的 token 该在表里: $tokens", tokens.all { it in table })
        }
    }
}
