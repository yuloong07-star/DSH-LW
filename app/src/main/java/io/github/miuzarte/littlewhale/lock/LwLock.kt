package io.github.miuzarte.littlewhale.lock

import android.content.Context
import io.github.miuzarte.littlewhale.tool.string
import io.github.miuzarte.littlewhale.tool.stringOrNull
import io.github.miuzarte.littlewhale.tool.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 锁屏那一条的入口 (批次 5): `lw_lock` 的工具落点, 也是设置页那几行做的事
 *
 * 六件事: 读状态 / 立刻解开一次 / 看录了什么 / 清掉 / 录一段 / **导入一份脚本**。**没有一件能绕过前面
 * 那两条开关** —— 自动解锁默认关, 而"点亮屏幕"也是缺省开、可以关的一条 (所以模型能把序列换成它, 却
 * 打不开那把总闸)
 *
 * 回执一律是应用这一侧写好的 `{"text": …}` (与别的工具同一条协定), 所以插件那一层只是一行转发
 */
internal object LwLock {

    fun dispatch(context: Context, request: JsonObject): JsonObject = when (val op = request.string("op")) {
        "status" -> LockReplay.status(context)
        "unlock" -> unlock(context)
        "steps" -> steps(context)
        "clear" -> clear(context)
        "record" -> record(context, request)
        "import" -> importScript(context, request)
        else -> throw IllegalArgumentException(
            "op has to be status, unlock, steps, clear, record or import, not \"$op\"",
        )
    }

    /** 一份脚本文件的字节上限: 它对不上就是别的文件, 不是解锁 */
    private const val MAX_SCRIPT_BYTES = 64 * 1024L

    /**
     * 导入一份脚本, 用它替掉现在这一条序列 (批次 5 追加)
     *
     * 脚本两种来源: `script` 直接给正文 (设置页那个框就是这一条), 或者 `path` 给一个文件。两条都过同一个
     * 解析器 [LockScript.parse], 所以校验只有一份
     *
     * **密码不在脚本里**: 序列里的 `text` 只说明"这里要一段密码", 内容从 `password` 来并加密存 —— 不传
     * 密码时**原来那一格不动** (改一条脚本不该顺手把密码抹掉)
     */
    private fun importScript(context: Context, request: JsonObject): JsonObject {
        val app = context.applicationContext
        val inline = request.stringOrNull("script")
        val path = request.stringOrNull("path")
        val source = when {
            inline != null -> inline
            path != null -> {
                val file = java.io.File(path)
                if (!file.isFile) return text("nothing was imported: there is no file at $path")
                if (file.length() > MAX_SCRIPT_BYTES) {
                    return text(
                        "nothing was imported: $path is ${file.length()} bytes, and a script that" +
                            " big is not an unlock",
                    )
                }
                runCatching { file.readText() }.getOrElse { error ->
                    return text("nothing was imported: $path could not be read (${error.message})")
                }
            }

            else -> return text(
                "import needs the script itself (script) or a file to read it from (path)",
            )
        }
        val parsed = LockScript.parse(source)
        parsed.problem?.let { return text("nothing was imported: $it") }
        if (parsed.steps.isEmpty()) return text("nothing was imported: that script has no steps")
        val password = request.stringOrNull("password").orEmpty()
        val stored = if (password.isEmpty()) {
            null
        } else {
            LockSecret.save(app, LockSecretData(kind = LockSecretData.TEXT, text = password))
        }
        LockSetting.saveSteps(app, parsed.steps)
        LockSetting.setTries(app, 0)
        val (secret, secretProblem) = LockSecret.load(app)
        val shape = LockSetting.describe(parsed.steps).joinToString(" then ")
        val needsSecret = parsed.steps.any { it is LockStep.Secret }
        val sentence = buildString {
            if (stored != null) {
                append("the script was read, but $stored")
            } else {
                append("imported ${parsed.steps.size} step(s): $shape")
                if (password.isNotEmpty()) append("; the password is in the encrypted slot now")
            }
            when {
                stored != null -> Unit
                // 刚存进去的那一份不用再说一遍"里面放着什么"
                password.isNotEmpty() -> Unit
                !needsSecret -> Unit
                secret == null -> append(
                    "; the script has a text step and the password slot is empty, so that step" +
                        " will say it has nothing to send",
                )

                else -> append("; the password slot already holds ${secret.describe}")
            }
            secretProblem?.let { append("; the password slot could not be read ($it)") }
            append(" (whatever was recorded before is replaced, and the failure count is back to zero)")
        }
        return buildJsonObject {
            put("imported", true)
            put("steps", parsed.steps.size)
            put("stepList", shape)
            put("secret", secret?.describe ?: "nothing")
            put("passwordStored", password.isNotEmpty() && stored == null)
            put("text", sentence)
        }
    }

    /**
     * 立刻重放一次 (手动)
     *
     * 先点亮: 熄屏的机器上注入的触摸唤不醒屏, 而这一条既要从设置页点, 也可能从 `lw_lock` 调 ——
     * 两种情况的屏幕都可能灭着
     */
    private fun unlock(context: Context): JsonObject {
        val app = context.applicationContext
        // 这条路上允许为了点亮去连一次通道: 它不是唤醒词那条路, 不在采集线程上, 等得起
        val wake = LockReplay.wakeScreen(app, allowConnecting = true)
        val report = LockReplay.replayNow(app, "lw_lock op=unlock")
        return buildJsonObject {
            put("woke", wake)
            put("unlocked", report.ok)
            put("steps", report.done)
            put("total", report.total)
            put("detail", report.detail)
            put("text", "$wake. Then ${report.detail}")
        }
    }

    /** 看录了什么: 每一步一句人话, 而**密码那一步只说"the password"** */
    private fun steps(context: Context): JsonObject {
        val app = context.applicationContext
        val steps = LockSetting.steps(app)
        val (secret, secretError) = LockSecret.load(app)
        return buildJsonObject {
            put("count", steps.size)
            put("steps", LockSetting.describe(steps).joinToString(" | "))
            put("recordedAt", LockSetting.recordedSentence(LockSetting.recordedAt(app)))
            put("secret", secret?.describe ?: "nothing")
            if (secretError != null) put("secretProblem", secretError)
            put(
                "text",
                if (steps.isEmpty() && secret == null) {
                    "no unlock has been recorded yet: record one on the phone's settings page" +
                        " (锁屏 -> 录制解锁)"
                } else {
                    "${steps.size} step(s), recorded ${LockSetting.recordedSentence(LockSetting.recordedAt(app))}:" +
                        " " + LockSetting.describe(steps).joinToString(" then ") +
                        "; the password slot holds ${secret?.describe ?: "nothing"}" +
                        (secretError?.let { " ($it)" } ?: "")
                },
            )
        }
    }

    /** 清掉: 步骤序列、明文副本、加密那一份、失败计数, 一起走 */
    private fun clear(context: Context): JsonObject {
        val app = context.applicationContext
        val had = LockSetting.steps(app).size
        LockSetting.clearSteps(app)
        LockReplay.forgetWarnings()
        return buildJsonObject {
            put("cleared", true)
            put("steps", had)
            put(
                "text",
                "the recording is gone ($had step(s)), the encrypted password was deleted with its" +
                    " Keystore key, and the failure count is back to zero; automatic unlock is now" +
                    " ${if (LockSetting.autoUnlock(app)) "still on, but there is nothing to replay" else "off"}",
            )
        }
    }

    /**
     * 录一段 (设置页那个「录制解锁」走的也是这里)
     *
     * `action` 两个值: `start` 起录 (顺手把屏锁上), `stop` 手动收工
     */
    private fun record(context: Context, request: JsonObject): JsonObject {
        val app = context.applicationContext
        val action = request.string("action", "start").trim().lowercase()
        return when (action) {
            "stop" -> {
                if (!LockRecord.recording) {
                    return text("nothing is being recorded right now")
                }
                LockRecord.stop()
                text("the recording is stopping: whatever was walked through gets saved")
            }

            "start" -> {
                if (LockRecord.recording) {
                    return text("a recording is already running")
                }
                val password = request.stringOrNull("password").orEmpty()
                // 连通道那一步留给 LockRecord.start 自己去做: 它会把"连不上"的原话回出来
                val problem = LockRecord.start(app, password)
                if (problem != null) {
                    text(problem)
                } else {
                    text(
                        "the screen is locked now: walk through the unlock on the phone." +
                            " It stops by itself once the phone is open, and says so in a" +
                            " notification. With a password handed over here, everything from the" +
                            " first tap on is dropped from the recording and the password is sent" +
                            " instead - the taps on a keypad are the password, so they are never" +
                            " written down",
                    )
                }
            }

            else -> throw IllegalArgumentException("record takes action=start or action=stop, not \"$action\"")
        }
    }

}
