package io.github.yuloong07star.luwi.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检测更新」那一半判据: 从 Release JSON 里取数, 以及"谁更新的"这个比较
 *
 * 它是**纯的** (没有设备、没有网络、不碰 Compose), 于是"新版本认不认得出来""取不到字段时会不会炸"
 * 这些事都能在这里量完 —— 而真机上要量它们得先有一条真的 Release
 *
 * 那份 JSON 的形状按 GitHub 的 `releases/latest` 抄了一小份 (只留我们真的读的那几个键), 而且刻意
 * 排成"别的键在前面、assets 在中间、body 里有引号与换行"的样子: 那正是取值器最容易撞上的几处
 */
class ReleaseJsonTest {

    private val sample = """
        {
          "url": "https://api.github.com/repos/yuloong07-star/Luwi/releases/1",
          "html_url": "https://github.com/yuloong07-star/Luwi/releases/tag/v2.5.0",
          "id": 12345,
          "tag_name": "v2.5.0",
          "target_commitish": "release/2.0.0",
          "name": "Luwi 2.5.0",
          "draft": false,
          "prerelease": false,
          "created_at": "2026-10-08T10:00:00Z",
          "published_at": "2026-10-08T11:30:00Z",
          "body": "这一版修了转屏的偏移\n\n还有 \"引号\" 与反斜杠 \\ 这些字",
          "assets": [
            {
              "name": "notes.txt",
              "size": 12,
              "browser_download_url": "https://example.invalid/notes.txt"
            },
            {
              "name": "Luwi-2.5.0.apk",
              "size": 314572800,
              "browser_download_url": "https://example.invalid/Luwi-2.5.0.apk"
            }
          ]
        }
    """.trimIndent()

    /** 一条正常的 Release: 版本号去掉了 `v`, 说明与那两个数都在 */
    @Test
    fun `一条正常的 Release 取得到那几个数`() {
        val release = ReleaseJson.parse(sample)
        assertEquals("2.5.0", release.version)
        assertEquals("Luwi 2.5.0", release.name)
        assertEquals("2026-10-08", release.published)
        assertEquals("https://github.com/yuloong07-star/Luwi/releases/tag/v2.5.0", release.pageUrl)
        assertEquals("Luwi-2.5.0.apk", release.apkName)
        assertEquals(314_572_800L, release.apkBytes)
        assertEquals("https://example.invalid/Luwi-2.5.0.apk", release.apkUrl)
    }

    /** **asset 挑的是那个 .apk**, 不是列表里的第一个 (那份 notes.txt 就是为了这条判据摆的) */
    @Test
    fun `挑的是 apk 那一条资产`() {
        val release = ReleaseJson.parse(sample)
        assertTrue("不能挑到第一个 (那是 notes.txt)", release.apkName?.endsWith(".apk") == true)
        assertEquals(314_572_800L, release.apkBytes)
    }

    /** 说明里的那几种转义要还原: `\n` 是换行, `\"` 是引号, 反斜杠是反斜杠 */
    @Test
    fun `说明里的转义还原`() {
        val notes = ReleaseJson.parse(sample).notes
        assertTrue("换行要还原", notes.contains("\n"))
        assertTrue("引号要还原", notes.contains("\"引号\""))
        assertTrue("反斜杠要还原", notes.contains("\\"))
        assertFalse("不该把 \\n 原样留着", notes.contains("\\n"))
    }

    /** 说明太长时截断, 而且**要留一个省略号** (不然人以为说明就这么多) */
    @Test
    fun `说明太长会截断并留省略号`() {
        val long = "x".repeat(500)
        val release = ReleaseJson.parse("""{"tag_name":"v9.9.9","body":"$long"}""")
        assertTrue(release.notes.length < 500)
        assertTrue(release.notes.endsWith("…"))
    }

    /** 不是一条 Release (没有 `tag_name`) 时**抛出来**, 让调用方说"这个源不对", 而不是静默说没更新 */
    @Test
    fun `没有 tag_name 就报错`() {
        val failure = runCatching { ReleaseJson.parse("""{"message":"Not Found"}""") }
        assertTrue("要抛", failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message?.contains("tag_name") == true)
    }

    /** 一条没挂 APK 的 Release 仍然能用: 那几个字段是空 / 0, 而不是整条失败 */
    @Test
    fun `没挂 APK 也读得出来`() {
        val release = ReleaseJson.parse("""{"tag_name":"v2.6.0","assets":[]}""")
        assertEquals("2.6.0", release.version)
        assertNull(release.apkName)
        assertNull(release.apkUrl)
        assertEquals(0L, release.apkBytes)
    }

    /** 页面上带空格与换行的 JSON (GitHub 真的会那么答) 照样取得到 */
    @Test
    fun `键与冒号之间有空白也认`() {
        val release = ReleaseJson.parse("{\n  \"tag_name\" :\n  \"2.5.1\"\n}")
        assertEquals("2.5.1", release.version)
    }

    /** 版本号那几段数字 */
    @Test
    fun `版本号取那几段数字`() {
        assertEquals(listOf(2, 5, 0), ReleaseJson.numbers("2.5.0"))
        assertEquals(listOf(2, 5, 0), ReleaseJson.numbers("v2.5.0"))
        assertEquals(listOf(2, 5), ReleaseJson.numbers("2.5"))
        assertEquals(listOf(2, 5, 0, 1), ReleaseJson.numbers("v2.5.0-beta.1"))
        assertTrue(ReleaseJson.numbers("").isEmpty())
    }

    /** 谁更新: 逐段比, 位数不够的补 0 */
    @Test
    fun `比版本号`() {
        assertTrue(ReleaseJson.newer("2.5.0", "2.0.0"))
        assertTrue("远处那段更长也算新", ReleaseJson.newer("2.5.0.1", "2.5.0"))
        assertTrue("v 前缀不影响", ReleaseJson.newer("v2.5.0", "2.0.0"))
        assertTrue("2.5 等于 2.5.0, 于是 2.5.1 更新", ReleaseJson.newer("2.5.1", "2.5"))
        assertFalse("一样新就不是更新", ReleaseJson.newer("2.5.0", "2.5.0"))
        assertFalse("手上更新 (装了测试包) 时不催人", ReleaseJson.newer("2.0.0", "2.5.0"))
        assertFalse("2.5 与 2.5.0 是同一条", ReleaseJson.newer("2.5", "2.5.0"))
    }

    /**
     * 认不出来时一律答"没有新版本"
     *
     * "看不出谁新"应当落到"不催人", 而不是弹一个不知道是什么的东西让人去装
     */
    @Test
    fun `认不出来的版本号不催人`() {
        assertFalse(ReleaseJson.newer("", "2.0.0"))
        assertFalse(ReleaseJson.newer("2.0.0", ""))
        assertFalse(ReleaseJson.newer("latest", "2.0.0"))
    }
}
