package io.github.miuzarte.littlewhale.plugin

import android.content.Context
import android.os.Bundle
import io.github.miuzarte.littlewhale.plugin.api.LwPluginResults
import io.github.miuzarte.littlewhale.tool.LwNotify
import io.github.miuzarte.littlewhale.tool.LwSpeak
import io.github.miuzarte.littlewhale.tool.LwSystem
import io.github.miuzarte.littlewhale.voice.VoiceInbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 能力真正干活的地方 (协议第 5 节里"接通"的那七条)
 *
 * 一条能力底下是**已经做过的那件事**: `device.read` 就是电池那个读数, `notify.post` 就是 `lw_notify`,
 * `session.post` 就是语音那条投递队列。不重写一遍的理由与 `lw_files` 那一条同一个: 同一件事有两份
 * 实现, 迟早会漂开
 *
 * 没接通的那几条由 [LwPluginContextBinder] 在更外一层拦住 (它先说"这一版还没接通"), 到不了这里
 */
internal object PluginCapabilityRuntime {

    /** 读插件自己目录里的文件时一次最多读多少 */
    private const val MAX_OWN_READ = 256 * 1024

    fun call(context: Context, manifest: PluginManifest, capability: String, args: Bundle): Bundle =
        when (capability) {
            "device.read" -> sentence(LwSystem.battery(context))
            "sensor.read" -> sentence(
                LwSystem.sensor(
                    context,
                    buildJsonObject {
                        args.getString("name")?.let { put("name", it) }
                        args.getInt("timeoutMs", 0).takeIf { it > 0 }?.let { put("timeoutMs", it) }
                    },
                ),
            )
            "notify.post" -> {
                val text = args.getString("text").orEmpty()
                if (text.isBlank()) {
                    LwPluginResults.fail("notify.post 得给一句 text")
                } else {
                    sentence(
                        LwNotify.notify(
                            context,
                            buildJsonObject {
                                put("title", args.getString("title")?.takeIf { it.isNotBlank() } ?: manifest.name)
                                put("text", text)
                            },
                        ),
                    )
                }
            }
            "speech.speak" -> {
                val text = args.getString("text").orEmpty()
                if (text.isBlank()) {
                    LwPluginResults.fail("speech.speak 得给一句 text")
                } else {
                    sentence(LwSpeak.dispatch(context, buildJsonObject { put("op", "speak"); put("text", text) }))
                }
            }
            "clipboard" -> {
                val op = args.getString("op") ?: "get"
                if (op != "get" && op != "set") {
                    LwPluginResults.fail("clipboard 的 op 只能是 get 或 set")
                } else {
                    sentence(
                        LwNotify.clipboard(
                            context,
                            buildJsonObject {
                                put("op", op)
                                args.getString("text")?.let { put("text", it) }
                            },
                        ),
                    )
                }
            }
            "files.own" -> own(context, manifest, args)
            "session.post" -> {
                val text = args.getString("text").orEmpty()
                if (text.isBlank()) {
                    LwPluginResults.fail("session.post 得给一句 text")
                } else {
                    val seq = VoiceInbox.append(context, text, source = "plugin:${manifest.id}")
                    if (seq == null) {
                        LwPluginResults.fail("投递队列写不进去 (目录建不出来或者磁盘满了)")
                    } else {
                        LwPluginResults.ok("已经把「${text.take(40)}」投进会话 (队列第 $seq 条)")
                    }
                }
            }
            else -> LwPluginResults.fail("\"$capability\" 这一版还没接通")
        }

    /** 只读自己那个插件目录: 路径不许跑出去, 单次读取有上限 */
    private fun own(context: Context, manifest: PluginManifest, args: Bundle): Bundle {
        val directory = PluginStore.packageDir(context, manifest.id)
            ?: return LwPluginResults.fail("插件目录现在不在了")
        val op = args.getString("op") ?: "list"
        val requested = args.getString("path").orEmpty().trim('/')
        if (requested.contains("..")) return LwPluginResults.fail("路径不许带 ..")
        val target = if (requested.isEmpty()) directory else File(directory, requested)
        if (!target.canonicalPath.startsWith(directory.canonicalPath)) {
            return LwPluginResults.fail("只能读自己目录里的东西")
        }
        return when (op) {
            "list" -> {
                if (!target.isDirectory) return LwPluginResults.fail("$requested 不是一个目录")
                val rows = target.listFiles()?.sortedBy { it.name }?.map {
                    if (it.isDirectory) "${it.name}/" else "${it.name} (${it.length()} 字节)"
                }.orEmpty()
                LwPluginResults.ok(rows.joinToString("\n").ifBlank { "(空)" })
            }
            "read" -> {
                if (!target.isFile) return LwPluginResults.fail("$requested 不是一个文件")
                if (target.length() > MAX_OWN_READ) {
                    return LwPluginResults.fail("$requested 有 ${target.length()} 字节, 超过一次能读的 $MAX_OWN_READ")
                }
                LwPluginResults.ok(target.readText())
            }
            else -> LwPluginResults.fail("files.own 的 op 只能是 list 或 read")
        }
    }

    /** `lw_*` 那套工具回的都是 `{"text": …}`; 能力这一侧只要那一句话 */
    private fun sentence(json: JsonObject): Bundle =
        LwPluginResults.ok(json["text"]?.jsonPrimitive?.contentOrNull ?: json.toString())
}
