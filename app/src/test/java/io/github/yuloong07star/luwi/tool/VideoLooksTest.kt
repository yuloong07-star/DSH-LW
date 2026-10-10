package io.github.yuloong07star.luwi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「视频识别」那三条规则与它们对相机说的话
 *
 * 量的是三件没有设备也能量的事: 三个数各自的**范围与夹取** (设置页拖到头不该写进一个越界的值),
 * **挑尺寸**那一步 (三档清晰度各自会在相机那张表里挑到哪一个), 以及"张数的上界就是相机那一侧那个
 * 数"这条不许漂的对应关系
 *
 * 参考的是 `ScreenshotBudget` 那一套的形状: 规则只此一份, 相机与设置页都读它 —— 所以"设置页说 720p
 * 而相机挑了 1080p"这种事不靠人去对账, 靠这里几条断言
 *
 * **挑尺寸吃的是两个整数而不是 `Size`**: 后者在 JVM 单测里是 android.jar 的桩 (`Method getWidth
 * not mocked`), 所以换算那一步留在相机那一侧
 */
class VideoLooksTest {

    /** 一台常见手机的 JPEG 档位表 (从大到小乱序放着: 挑尺寸那一步不该依赖表的顺序) */
    private val sizes = listOf(
        4032 to 3024,
        1920 to 1080,
        1600 to 1200,
        1280 to 720,
        640 to 480,
        320 to 240,
        0 to 0,
    )

    /* ── 挑尺寸: 不超过预算里最大的那一个 ─────────────────────────────────── */

    @Test
    fun `低档挑到 480p`() {
        assertEquals(640 to 480, VideoLooks.pick(sizes, 640 * 480))
    }

    @Test
    fun `默认档挑到 720p`() {
        assertEquals(1280 to 720, VideoLooks.pick(sizes, 1280 * 720))
    }

    /** 高档的上限就是 1080p: 表里那张 4032x3024 超出预算, 所以挑的是 1920x1080 */
    @Test
    fun `高档不会挑到比上限更大的那一张`() {
        assertEquals(1920 to 1080, VideoLooks.pick(sizes, VideoLooks.MAX_PIXELS))
    }

    /** 预算比表里最小的一张还小 (老设备上 1080p 起步): 交最小的一张, 而不是什么都不交 */
    @Test
    fun `预算太小时退回最小的一张`() {
        assertEquals(320 to 240, VideoLooks.pick(sizes, 100))
    }

    @Test
    fun `表是空的就回 null`() {
        assertNull(VideoLooks.pick(emptyList(), 1280 * 720))
    }

    /** 宽高是 0 的那些是坏数据 (有的 ROM 会报), 一个都不该被挑中 */
    @Test
    fun `坏尺寸不参与挑选`() {
        assertEquals(640 to 480, VideoLooks.pick(listOf(0 to 0, 640 to 480, -1 to 100), 1280 * 720))
    }

    /* ── 三档与上限 ───────────────────────────────────────────────────────── */

    /** 三档必须**递增**: 设置页那一行的意思是"低 / 默认 / 高", 顺序反了就是一个会骗人的开关 */
    @Test
    fun `三档像素递增而且最高那档就是上限`() {
        val pixels = VideoLooks.levels.map { it.second }
        assertEquals(pixels.sorted(), pixels)
        assertEquals(VideoLooks.MAX_PIXELS, pixels.last())
        assertEquals(3, pixels.size)
    }

    /** 默认那一档是有名字的那一个: 没选过时 [VideoLooks.pixels] 该落在它上面 */
    @Test
    fun `默认档是中间那一档`() {
        val middle = VideoLooks.levels.size / 2
        assertEquals(1, middle)
        assertTrue(VideoLooks.levels[middle].second == 1280 * 720)
    }

    /* ── 张数与间隔: 范围与缺省 ───────────────────────────────────────────── */

    /**
     * 张数的上界**就是相机那一侧那一个数**: 少了这条, "设置页能给 20 张而相机最多抓 12 张"这种事
     * 就会在某一版悄悄发生 (两边各写一个数就是两份实现)
     */
    @Test
    fun `张数的上界就是相机那一侧的上限`() {
        assertEquals(LwCamera.MAX_COUNT.toFloat(), VideoLooks.countRange.endInclusive)
        assertEquals(1f, VideoLooks.countRange.start)
    }

    /** 缺省张数与 `lw_look` 的缺省是同一个数 (4): 那里不说 frames 时用的就是它 */
    @Test
    fun `缺省张数是 4`() {
        assertEquals(4, VideoLooks.DEFAULT_COUNT)
        assertTrue(VideoLooks.countRange.contains(VideoLooks.DEFAULT_COUNT.toFloat()))
    }

    /** 缺省的间隔落在范围里, 而且它是吸附点之一 (滑块开头就该停在好看的数上) */
    @Test
    fun `缺省间隔在范围里而且是吸附点之一`() {
        assertTrue(VideoLooks.intervalRange.contains(VideoLooks.DEFAULT_INTERVAL_MS.toFloat()))
        assertTrue(VideoLooks.intervalKeyPoints.contains(VideoLooks.DEFAULT_INTERVAL_MS.toFloat()))
    }

    /** 吸附点全在范围里, 而且是递增的 —— 否则滑块会跳到一个够不着的位置上 */
    @Test
    fun `吸附点都在范围里且递增`() {
        assertEquals(VideoLooks.intervalKeyPoints.sorted(), VideoLooks.intervalKeyPoints)
        VideoLooks.intervalKeyPoints.forEach { point ->
            assertTrue(
                "$point is outside ${VideoLooks.intervalRange}",
                VideoLooks.intervalRange.contains(point),
            )
        }
    }

    /** 间隔的两端: 一头是"几乎不隔", 一头是两秒 (再长就不像在连拍了) */
    @Test
    fun `间隔的两端是 100 与 2000 毫秒`() {
        assertEquals(100f, VideoLooks.intervalRange.start)
        assertEquals(2000f, VideoLooks.intervalRange.endInclusive)
    }

    /**
     * 夹取: 设置页的滑块、打字的框与桥点名给的数都过这一道
     *
     * 它要能挡两头 —— 桥那一侧收到 1 ms 或 60 s 时也得有一个说了算的地方, 而"说了算"就是这里
     */
    @Test
    fun `间隔两端之外的数会被夹回来`() {
        assertEquals(100, VideoLooks.clampInterval(1))
        assertEquals(100, VideoLooks.clampInterval(-5000))
        assertEquals(2000, VideoLooks.clampInterval(60_000))
        assertEquals(350, VideoLooks.clampInterval(350))
        assertEquals(200, VideoLooks.clampInterval(VideoLooks.DEFAULT_INTERVAL_MS))
    }
}
