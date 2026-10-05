package io.github.miuzarte.littlewhale.channel

import android.content.Context
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.tool.LwWakeWord
import io.github.miuzarte.littlewhale.wake.WakeWordState
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 模式: 手机模式与视频模式
 *
 * **只换提示词, 不换工具也不换预设**: dsh 拒绝让另一个预设接管一个已经起过轮次的会话
 * (`agent-preset/locked`), 而助手的提示词是 provider、**每一步组装都重读文件** (四环链, 见可行性稿
 * 3.1.3)。所以"中途换人设"的做法就是把另一份正文 cp 进 `dsh-custom-mode` 那个助手的 `prompt.md`,
 * 下一步模型请求读到的就是新内容 —— 不用重启、不用新开会话、也不动 dsh 的预设闸
 *
 * 两份正文随 APK 发 (`assets/modes/<名字>.md`), 首启落到 `$DSH_HOME/modes/`, 落在那里是因为主人
 * 可以自己改 —— 改完下一次切过去就用新的那份
 *
 * 与前缀是不是"预设"无关: 只有 **`dsh-custom-mode`(自定义模式)那个助手**读这份文件, 别的预设
 * (手机模式 / 视频模式那两个 preset)的人设写在自己的 YAML 里, 切文件对它们没有影响
 */
internal object LwModes {

    /** 手机模式: 默认就是它 */
    const val PHONE = "phone"

    /** 视频模式: 用本机摄像头 (Camera2 直连) 抓帧识图 */
    const val VIDEO = "video"

    /** 两个模式各自的显示名, 报给模型时用它 */
    private val NAMES = mapOf(PHONE to R.string.mode_phone, VIDEO to R.string.mode_video)

    /** 旧名字: 可行性稿里写的是 `assistant`, 留着当别名免得主人说惯了 */
    private const val PHONE_ALIAS = "assistant"

    private const val MODES_DIR = "modes"
    private const val ACTIVE = ".active"
    private const val CUSTOM_PROMPT = ".agent-presets/custom/prompt.md"

    /** 视频模式那条常驻语音链留下的记号 (`voice-input.sh on` 写的): 它在 = "这条链要一直开着" */
    private const val VOICE_MARKER = "voice-input.on"

    /** `$DSH_HOME/modes`, 两份正文与 `.active` 都在这儿 */
    fun modesDir(context: Context): File = File(dshHome(context), MODES_DIR)

    /** 那个助手的提示词文件, 切模式就是覆盖它 */
    fun promptFile(context: Context): File = File(dshHome(context), CUSTOM_PROMPT)

    /** 现在在哪一个模式: 读 `.active`, 读不到就当手机模式 (默认) */
    fun active(context: Context): String =
        File(modesDir(context), ACTIVE).takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { PHONE }

    /**
     * 把两份正文从 APK 里放出来
     *
     * **只写缺的那些**: 主人改过的那份不能被 APK 里的覆盖回去, 否则"可以自己改"就是一句空话
     * 已在设备上的 (手推的、主人改的) 一律留着
     */
    fun seed(context: Context): List<String> {
        val directory = modesDir(context)
        directory.mkdirs()
        val written = mutableListOf<String>()
        listOf(PHONE, VIDEO).forEach { name ->
            val target = File(directory, "$name.md")
            if (target.isFile && target.length() > 0) return@forEach
            runCatching {
                context.assets.open("$MODES_DIR/$name.md").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                written += name
            }
        }
        // 两个脚本各放一份, 都是"人在设备上直接做一次"的事 (走的是与插件同一条回环桥):
        // voice-input.sh 开/关那条常驻语音链的记号, camera.sh 切前摄/后摄 (前后摄是两个设备, 换一头
        // 就是一次开关)
        //
        // **脚本每次都覆盖**, 与上面那两份正文相反: 正文是给主人改的 (只写缺的), 脚本是代码的一部分,
        // 修一次就得跟着装机走进设备 —— 只写缺的会让设备上那份旧拷贝永远留在那儿
        listOf("voice-input.sh", "camera.sh").forEach { name ->
            val script = File(directory, name)
            runCatching {
                context.assets.open("$MODES_DIR/$name").use { input ->
                    script.outputStream().use { output -> input.copyTo(output) }
                }
                script.setExecutable(true)
                written += name
            }
        }
        // 退役的脚本就地删掉: `mode.sh` 那一套是"建虚拟屏 + 起相机应用"的旧链路 (2026-10-05 摘掉),
        // 而它曾经被放在这个目录里 —— 留着就是设备上一份能跑起来的旧链路
        val retired = File(directory, "mode.sh")
        if (retired.isFile) runCatching { retired.delete() }
        return written
    }

    /**
     * 首启把默认模式落到实处
     *
     * 没记过 `.active` 就按**手机模式**写一次: 不写的话, 那个助手的提示词还是插件自己 seed 的默认
     * 文本 —— 主人会以为装上了手机模式, 其实读到的不是我们这两份里的任何一份
     */
    fun ensureDefault(context: Context) {
        seed(context)
        if (!File(modesDir(context), ACTIVE).isFile) set(context, PHONE)
    }

    /**
     * 切模式: 把选中的那份正文写进那个助手的 `prompt.md`
     *
     * 覆盖提示词而**不动** `preset.yml` / `agent.cordis.yml`: 那些是助手的装配, 与"现在是谁"无关
     */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        val asked = request["mode"]?.toString()?.trim('"')?.trim()?.lowercase().orEmpty()
        return when (asked) {
            "", "status" -> status(context)
            "list" -> buildJsonObject {
                put("modes", NAMES.keys.joinToString(", "))
                put("active", active(context))
            }

            else -> set(context, asked)
        }
    }

    private fun set(context: Context, asked: String): JsonObject {
        val mode = when (asked) {
            PHONE, PHONE_ALIAS -> PHONE
            VIDEO -> VIDEO
            else -> return buildJsonObject {
                put("switched", false)
                put(
                    "detail",
                    "unknown mode \"$asked\": this build has ${NAMES.keys.joinToString(" and ")}",
                )
            }
        }
        seed(context)
        val source = File(modesDir(context), "$mode.md")
        if (!source.isFile) {
            return buildJsonObject {
                put("switched", false)
                put("detail", "the body of $mode is missing: ${source.absolutePath} is not there")
            }
        }
        val target = promptFile(context)
        // parentFile 是可空的 (根目录上的文件就没有): 先拿在手里判一次, 别在后面每处都写 `!!`
        val assistant = target.parentFile
        if (assistant == null || !assistant.isDirectory) {
            return buildJsonObject {
                put("switched", false)
                put(
                    "detail",
                    "the custom-mode assistant is not installed: ${assistant?.absolutePath ?: target.path} does not exist," +
                        " so there is no prompt file to write",
                )
            }
        }
        // 先把旧的留一份: 这个文件是那个助手的全部人设, 覆盖错了就没有第二份
        if (target.isFile) {
            runCatching { target.copyTo(File(assistant, "prompt.md.before-$mode"), overwrite = true) }
        }
        target.writeText(source.readText())
        File(modesDir(context), ACTIVE).writeText(mode)
        // 视频模式是"说话为主"的, 所以**给它常驻语音那一个许可** —— 但许可不等于打开: 服务照旧只在
        // 唤醒词命中之后才把切段与出字铺开 (状态机那条 ②, 主人 2026-10-05 定), 改动之前这里直接
        // `op=start` 把整条常驻链起起来, 那是"设置开关 = 常驻监听"那个错换了个触发口
        //
        // **一个许可都不许自己打开** (三条路都是同一个错): 唤醒词那一个 (`W` 列 = 允许唤醒) 是主人
        // 在设置页上的决定, 这里只把它读出来用; 关着时说清"是那个开关挡着", 而不是替人按下去
        val listening = if (mode == VIDEO) runCatching {
            val wakeAllowed = LwWakeWord.allow(context)
            LwWakeWord.setAllow(context, wakeAllowed, true)
            if (!wakeAllowed) {
                "always-listening voice: allowed, but the allow-wake switch is off, so nothing is" +
                    " listening; turn that on in the settings page"
            } else if (WakeWordState.listening) {
                // 已经在跑: 把新许可告诉它, 不必重启 (重启会丢掉命中计数与那半句正在说的话)
                LwWakeWord.refresh(context)
                "always-listening voice: allowed (the listener was already up)"
            } else {
                LwWakeWord.listen(context)
                "always-listening voice: allowed and the wake word is listening"
            }
        }.getOrElse { error ->
            "always-listening voice: could not be allowed (${error.message ?: error})"
        }
        else null
        // 切回手机模式是一次收工: 把常驻语音那一个许可收回去 (状态机那条 ④) —— 手机模式不是"说话为主",
        // 留着它命中一次就会把那条链又拉起来, **唤醒词本身不动**, 而且那一个许可连读都不读、更不写:
        // 它是主人在设置页上的决定, 这里伸手改一次就等于"切个模式, 设置被悄悄改了", 主人想整条停掉
        // 有通知栏那个「停止」与设置页那个「允许唤醒」, 相机那一半由宿主那侧的 lw_mode 关 (它才知道
        // 相机是不是这条链开的)
        val teardown = if (mode == PHONE) runCatching {
            LwWakeWord.setAllow(context, LwWakeWord.allow(context), false)
            LwWakeWord.refresh(context)
            val marker = File(modesDir(context), VOICE_MARKER)
            val cleared = marker.isFile && marker.delete()
            "always-listening voice: no longer allowed" +
                (if (cleared) ", the asked-for marker is gone" else "")
        }.getOrElse { error -> "disallowing the voice chain failed: ${error.message ?: error}" }
        else null
        return buildJsonObject {
            put("switched", true)
            put("mode", mode)
            put("name", context.getString(NAMES.getValue(mode)))
            put("characters", target.length())
            listening?.let { put("listening", it) }
            teardown?.let { put("teardown", it) }
            // 两条链现在各在不在跑: 这一段答案里要有这个, 否则"许可给了"与"它真的在听"分不开 ——
            // 而这两件事正是这次改动要分开的
            put("wakeWordListening", WakeWordState.listening)
            put("alwaysListeningVoice", WakeWordState.voiceActive)
            put(
                "detail",
                "$mode is in place: the next model step of a session on the custom-mode assistant reads it",
            )
        }
    }

    private fun status(context: Context): JsonObject {
        val mode = active(context)
        val target = promptFile(context)
        return buildJsonObject {
            put("mode", mode)
            put("name", context.getString(NAMES[mode] ?: R.string.mode_phone))
            put("available", NAMES.keys.joinToString(", "))
            put("promptFile", target.absolutePath)
            put("promptWritten", target.isFile)
            put("modesDirectory", modesDir(context).absolutePath)
        }
    }

    private fun dshHome(context: Context): File = File(context.filesDir, DshHost.HOME_DIR)
}
