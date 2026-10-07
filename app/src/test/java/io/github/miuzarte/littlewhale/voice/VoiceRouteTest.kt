package io.github.miuzarte.littlewhale.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 一句话投给哪一场: 两条来源的优先级
 *
 * 主人 2026-10-07 的口径是"打开视频模式那句话与视频模式里接着说进的话要落在同一场" —— 也就是说视频
 * 模式的定格压过回复框点名; 而离开视频模式之后, 回复框那条例外照旧用
 */
class VoiceRouteTest {

    @Test
    fun `视频模式里定格优先`() {
        assertEquals(
            "session-video",
            VoiceRoute.target(boxTarget = "session-box", videoTarget = "session-video", videoResident = true),
        )
    }

    @Test
    fun `视频模式但没定格时落回复框点名`() {
        assertEquals(
            "session-box",
            VoiceRoute.target(boxTarget = "session-box", videoTarget = null, videoResident = true),
        )
        assertEquals(
            "session-box",
            VoiceRoute.target(boxTarget = "session-box", videoTarget = "  ", videoResident = true),
        )
    }

    @Test
    fun `不是视频模式时只看回复框`() {
        assertEquals(
            "session-box",
            VoiceRoute.target(boxTarget = "session-box", videoTarget = "session-video", videoResident = false),
        )
        assertNull(VoiceRoute.target(boxTarget = null, videoTarget = "session-video", videoResident = false))
    }

    @Test
    fun `两条都没有就回 null 交给宿主那笔账`() {
        assertNull(VoiceRoute.target(boxTarget = null, videoTarget = null, videoResident = true))
        assertNull(VoiceRoute.target(boxTarget = "  ", videoTarget = "", videoResident = true))
    }
}
