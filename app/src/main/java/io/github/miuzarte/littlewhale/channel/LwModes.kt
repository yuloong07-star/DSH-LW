package io.github.miuzarte.littlewhale.channel

import android.content.Context
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.host.DshHost
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

    /** 视频模式: 虚拟屏开相机连拍识图 */
    const val VIDEO = "video"

    /** 两个模式各自的显示名, 报给模型时用它 */
    private val NAMES = mapOf(PHONE to R.string.mode_phone, VIDEO to R.string.mode_video)

    /** 旧名字: 可行性稿里写的是 `assistant`, 留着当别名免得主人说惯了 */
    private const val PHONE_ALIAS = "assistant"

    private const val MODES_DIR = "modes"
    private const val ACTIVE = ".active"
    private const val CUSTOM_PROMPT = ".agent-presets/custom/prompt.md"

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
        if (!target.parentFile.isDirectory) {
            return buildJsonObject {
                put("switched", false)
                put(
                    "detail",
                    "the custom-mode assistant is not installed: ${target.parentFile.absolutePath} does not exist," +
                        " so there is no prompt file to write",
                )
            }
        }
        // 先把旧的留一份: 这个文件是那个助手的全部人设, 覆盖错了就没有第二份
        if (target.isFile) {
            runCatching { target.copyTo(File(target.parentFile, "prompt.md.before-$mode"), overwrite = true) }
        }
        target.writeText(source.readText())
        File(modesDir(context), ACTIVE).writeText(mode)
        return buildJsonObject {
            put("switched", true)
            put("mode", mode)
            put("name", context.getString(NAMES.getValue(mode)))
            put("characters", target.length())
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
