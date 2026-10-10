package io.github.yuloong07star.luwi.tool

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuloong07star.luwi.R
import io.github.yuloong07star.luwi.host.DshHost
import java.io.File

/**
 * 视频模式取景那三条: 每张间隔 / 截图张数 / 截图清晰度
 *
 * 主人 2026-10-06 点的三个数, 与「截图」那两条预算**是两笔账**: 那两条管的是 `lw_screenshot` 那条
 * 路 (整屏画面缩到多少像素、多少字节), 而这三条管的是视频模式那一台**自己开的相机** (见
 * [LwCamera]): 它抓的是 JPEG 帧, 尺寸在**开相机那一刻**按这里挑一档, 张数与间隔在 `op=snapshot`
 * 时读
 *
 * 三件事各自为什么是这个形状:
 *
 * - **间隔**是墙钟上两张之间隔多久 (要那个数, 而不是"拍完再歇多久"): 一次抓帧本身 200-400 ms,
 *   所以答案里报的永远是**量到的**那几拍 (`offsets`), 不是要的那个数 —— 与 `lw_screenshot` 连拍
 *   同一条纪律
 * - **张数**的上限也就是 `lw_look` 那个 `frames` 参数的上限 (见 [LwCamera.MAX_COUNT]), 缺省 4 与
 *   `lw_look` 的缺省同一个数: 用户在设置页定的就是**缺省**, 模型在调用里点名要几张时听它的
 * - **清晰度**是三档而不是连着滑: 每一档要对的是一张图交出去之后的处理预算 (`imagePixelBudget`),
 *   中间的值没有对应的预算可言 (与 `ScreenshotBudget` 那三档同一个道理)
 */
object VideoLooks {

    /** 与 [io.github.yuloong07star.luwi.host.HostSettings] 同一个偏好文件, 全 app 一个 */
    private const val STORE = "luwi"

    private const val INTERVAL_KEY = "look-interval-ms"
    private const val COUNT_KEY = "look-count"
    private const val QUALITY_KEY = "look-quality"

    /**
     * "取景的几张也拼成一张网格" —— 这条**两半都要读**, 所以除了偏好还落一份到 host 的
     * `$DSH_HOME/lw/look-sheet` (见 [DshHost.settingsDirectory])
     *
     * 为什么不能只写偏好: 拼图是**插件那一侧**做的 (应用只交原图, 见 `lw_look` 与 `attachPictures`),
     * 而插件跑在 host 进程里, 读不到应用的 `SharedPreferences`。桥那一侧也没有一条"读个偏好"的调用,
     * 于是两边共读一个文件是最短的一条路 —— 视频模式那个常驻语音记号 (`modes/voice-resident.on`) 是
     * 同一个先例
     *
     * **文件不在时的那一档是"不拼"**, 这正是缺省: 谁都没设过的时候行为与上一版一致
     */
    private const val SHEET_KEY = "look-sheet"

    /** 那个共享记号在 host 那一侧的文件名 (插件照这个名字读) */
    private const val SHEET_FILE = "look-sheet"

    /** 间隔的两端 (毫秒): 一头是"几乎不隔", 一头是两秒 (再长就不像在连拍了) */
    val intervalRange: ClosedFloatingPointRange<Float> = 100f..2000f

    /** 间隔的吸附点: 120 是连拍那条路的缺省, 200 是这里不放间隔时的缺省 */
    val intervalKeyPoints: List<Float> = listOf(120f, 150f, 200f, 300f, 500f, 800f, 1000f, 1500f)

    /** 离吸附点多近算吸住, 占整条 range 的比例 (与 Miuix 的 `magnetThreshold` 一个算法) */
    const val INTERVAL_MAGNET = 0.03f

    /** 一张都不隔时的那个值 */
    const val DEFAULT_INTERVAL_MS = 200

    /** 缺省张数: 与 `lw_look` 的缺省 (第一组 4 张) 是同一个数 */
    const val DEFAULT_COUNT = 4

    /** 能给的张数区间: 上界就是相机那一侧的上限, 不是这里另写一个数 */
    val countRange: ClosedFloatingPointRange<Float> = 1f..LwCamera.MAX_COUNT.toFloat()

    /**
     * 清晰度三档: 名字 (字符串资源) 与它允许的像素数, 下标就是存进偏好的值
     *
     * 三个数照着常见的档位取: 480p 看个大概、720p 与 1080p 是识别常用的两档。抓帧不是拍大片
     * (见 `LwCamera` 那条注释), 所以上端就停在 1080p
     */
    val levels: List<Pair<Int, Int>> = listOf(
        R.string.settings_look_quality_low to 640 * 480,
        R.string.settings_look_quality_default to 1280 * 720,
        R.string.settings_look_quality_high to MAX_PIXELS,
    )

    /**
     * 清晰度最高那一档是多少像素: 1920x1080
     *
     * **它就是"抓帧不是拍大片"那一条的上限** (原来写在 `LwCamera` 里): 档位与上限说的是同一件事,
     * 所以只有这一份。`LwCamera` 挑尺寸时读的是当前这一档 ([pixels]), 不再是这个常量
     */
    const val MAX_PIXELS = 1920 * 1080

    /**
     * 从相机那张尺寸表里挑一档: **不超过 [maxPixels] 里最大的那一个**
     *
     * 一个都没有 (表里最小的都比预算大 —— 老设备上 1080p 起步是常见的) 时取最小的那一个: 交一张
     * 比预算大的图总比什么都不交好, 而"实际交出去多大"由 [LwCamera] 在答案里印出来
     *
     * **吃的是两个整数 (宽, 高), 不是 `android.util.Size`**: 这一条要能单测, 而 `Size` 在 JVM 上
     * 是 android.jar 的桩 (`Method getWidth not mocked`)。转换放在相机那一侧 —— 那里本来就在跟
     * `CameraCharacteristics` 打交道
     *
     * 宽高是 0 的那些是坏数据 (有的 ROM 会报), 一个都不参与挑选
     */
    fun pick(sizes: List<Pair<Int, Int>>, maxPixels: Int): Pair<Int, Int>? = sizes
        .filter { it.first > 0 && it.second > 0 && it.first.toLong() * it.second <= maxPixels }
        .maxByOrNull { it.first.toLong() * it.second }
        ?: sizes.filter { it.first > 0 && it.second > 0 }.minByOrNull { it.first.toLong() * it.second }

    /** 张数上限就是相机那一侧那一个数, 不在这里另写一份 */
    private val COUNT_MAX = LwCamera.MAX_COUNT

    /**
     * 把任意给来的间隔夹到能用的范围里
     *
     * **纯函数**: 设置页那个滑块、打字的框、以及桥那一侧点名给的数都从这里过一遍, 于是"越界"这件事
     * 只有一处处置 (滑块滑不到外面去, 而桥收到 1 ms 或 60 s 时也得有人说了算)。夹的下端与相机那一侧
     * 的下端同一个道理: 再快也没用, 抓一张本身就要两百到四百毫秒
     */
    fun clampInterval(value: Int): Int =
        value.coerceIn(intervalRange.start.toInt(), intervalRange.endInclusive.toInt())

    /** 默认档的下标, 也就是没选过时用的那一档 (中间那一档) */
    private const val DEFAULT_LEVEL = 1

    /** 每张之间隔多久 (毫秒), 相机那一侧读它 */
    var intervalMs: Int by mutableStateOf(DEFAULT_INTERVAL_MS)
        private set

    /** 一次取景抓几张, `lw_look` 不带 frames 时用它 */
    var count: Int by mutableStateOf(DEFAULT_COUNT)
        private set

    /** 清晰度那一档的下标 */
    var level: Int by mutableStateOf(DEFAULT_LEVEL)
        private set

    /**
     * 一次取景的几张也拼成一张网格 (**缺省关**, 主人 2026-10-06: "可以加, 在设置里加上这个开关")
     *
     * 关着的时候一次取景交出去的是分开的几张图 (各是原尺寸那一档), 能逐张看清颜色与小字; 开着的时候
     * 多拼一张网格, 由插件在结果里先给出那一张 —— 十来张图变成一次读图, 代价是每一格都被缩过
     */
    var sheet: Boolean by mutableStateOf(false)
        private set

    /** 这一档是多少像素 (开相机那一侧读它) */
    val pixels: Int get() = levels[level.coerceIn(0, levels.lastIndex)].second

    /** 能在档位之外点名的最小像素数 (清除上限的时候用) */
    const val MIN_PIXELS = 1

    /** 从磁盘读一次, 在第一帧之前调, 免得先按默认值画一遍再跳 */
    fun initialize(context: Context) {
        val stored = preferences(context)
        intervalMs = clampInterval(stored.getInt(INTERVAL_KEY, DEFAULT_INTERVAL_MS))
        count = stored.getInt(COUNT_KEY, DEFAULT_COUNT).coerceIn(1, COUNT_MAX)
        level = stored.getInt(QUALITY_KEY, DEFAULT_LEVEL).coerceIn(0, levels.lastIndex)
        sheet = stored.getBoolean(SHEET_KEY, false)
        // 那一个记号要**现在**就跟宿主的 `$DSH_HOME/lw` 对上: 偏好里是开而文件不在 (或被谁删了)
        // 的时候, 插件读到的会是"不拼", 于是设置页画着开而实际不拼 —— 那种不一致正是这一条要防的
        publishSheet(context)
    }

    /**
     * 收下新的"拼不拼网格": 先让界面用上, 再落盘, 再给 host 那一侧写一份
     *
     * 三件事的顺序不要紧 (界面读的是内存里那一个), 而**两处都要写**是必须的, 见 [SHEET_KEY]
     */
    fun setSheet(context: Context, value: Boolean) {
        sheet = value
        preferences(context).edit().putBoolean(SHEET_KEY, value).apply()
        publishSheet(context)
    }

    /** 把"拼不拼"写成 host 那一侧读得到的那一个文件: 开 = 文件在, 关 = 文件删掉 */
    private fun publishSheet(context: Context) {
        val mark = File(DshHost.settingsDirectory(context), SHEET_FILE)
        if (!sheet) {
            mark.delete()
            return
        }
        runCatching { mark.writeText("the app asked for one grid picture per look\n") }
            .onFailure { Log.w(TAG, "the sheet mark could not be written: ${it.message}") }
    }

    /**
     * host 那一侧那个记号还在不在 (给插件那一份实现留的读法, 也是设置页与 `camera op=status` 的对账)
     *
     * 它读的是**文件**而不是偏好: 那个文件是两半之间唯一那份事实
     */
    fun sheetMarkPresent(context: Context): Boolean =
        File(DshHost.settingsDirectory(context), SHEET_FILE).isFile

    /** 收下新的间隔 (毫秒): 先让界面用上再落盘 */
    fun setInterval(context: Context, value: Int) {
        intervalMs = clampInterval(value)
        preferences(context).edit().putInt(INTERVAL_KEY, intervalMs).apply()
    }

    /** 收下新的张数 */
    fun setCount(context: Context, value: Int) {
        count = value.coerceIn(1, COUNT_MAX)
        preferences(context).edit().putInt(COUNT_KEY, count).apply()
    }

    /** 收下新的清晰度档 (下标) */
    fun setLevel(context: Context, value: Int) {
        level = value.coerceIn(0, levels.lastIndex)
        preferences(context).edit().putInt(QUALITY_KEY, level).apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** 日志标记: 那一个共享记号写不进去时只记一行 (设置页拖一下不该弹任何东西) */
    private const val TAG = "LwLooks"
}
