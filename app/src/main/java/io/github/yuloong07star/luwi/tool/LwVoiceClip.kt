package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 两条在线引擎共用的"放一段拿回来的音频": 落一个临时文件 → MediaPlayer → 放完删掉
 *
 * 为什么走文件: 拿回来的是**编码过的**一整段 (mp3), `MediaPlayer` 认路径最省事; 文件落在应用自己的
 * cache 里, 放完就删, 不占用户空间也不用任何权限 —— 与自带那条 TTS 的做法一致
 *
 * 响度不在这一层改: 编码过的音频不解码就没有增益可加, 所以两条在线引擎的音量由服务与手机媒体音量
 * 决定 (设置页那一段如实写着)
 */
internal object LwVoiceClip {

    private val playing = AtomicBoolean(false)

    @Volatile
    private var player: MediaPlayer? = null

    /** 这一段是不是被叫停的 (play 回 false 时, 调用方据此分开说"停了"与"没放完") */
    @Volatile
    var calledOff: Boolean = false
        private set

    /** 现在有没有在放 (半双工的闸与「停止」那条路据此判断) */
    val speaking: Boolean get() = playing.get()

    /** 放一段字节, 等它真的放完; false = 被叫停或没放完 */
    fun play(context: Context, bytes: ByteArray, extension: String): Boolean {
        val directory = File(context.cacheDir, "read").apply { mkdirs() }
        val file = File.createTempFile("lw-voice", ".$extension", directory)
        file.writeBytes(bytes)
        val player = MediaPlayer()
        playing.set(true)
        calledOff = false
        this.player = player
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            // 预算: 能读到时长就用时长, 读不到给一个上限 —— 到点说没放完, 不无限等
            val budget = player.duration.takeIf { it > 0 }?.toLong() ?: FALLBACK_BUDGET_MS
            val deadline = System.currentTimeMillis() + budget + TAIL_MS
            while (player.isPlaying && System.currentTimeMillis() < deadline) {
                if (calledOff) break
                Thread.sleep(20)
            }
            val done = !player.isPlaying && !calledOff
            if (!done && !calledOff) Log.w(TAG, "the clip did not report finishing")
            done
        } finally {
            this.player = null
            playing.set(false)
            runCatching { player.release() }
            runCatching { file.delete() }
        }
    }

    /** 掐断: 没在放时回 null, 在放就停掉并说明 (与自带那条 TTS 的 stop 同形) */
    fun stop(): String? {
        val busy = playing.get()
        calledOff = true
        val held = player ?: return if (busy) "the clip was called off before it played" else null
        runCatching { held.stop() }
        Log.i(TAG, "online playback stopped on request")
        return "the online playback was cut off"
    }

    private const val TAG = "LwVoiceClip"

    /** 播完之后的余量 */
    private const val TAIL_MS = 2_000L

    /** 读不到时长时的兜底预算 */
    private const val FALLBACK_BUDGET_MS = 120_000L
}
