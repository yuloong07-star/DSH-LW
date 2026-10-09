package io.github.miuzarte.littlewhale.plugin

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 审计 (协议第 12 节)
 *
 * 每次 `context.call` 记一条: 时间 / 能力 / 参数摘要 / 结果摘要。**被拒的也要记** —— "它想干什么"
 * 比"它干成了什么"更有信息量, 而这一版那道机器闸已经撤掉了 (决定 10), 事后就只剩这一份账查得到
 *
 * 上限自己滚: 512 KiB 之上留尾部 500 行 (与自动指令那份历史同一个做法)
 */
internal object PluginAudit {

    private const val CAP_BYTES = 512L * 1024
    private const val KEEP_LINES = 500

    /** 参数摘要的长度上限: 账里要的是"它要了什么", 不是整份参数 */
    private const val MAX_ARGUMENTS = 120

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 记一条; 参数里的换行压成空格, 免得一行账被拆成两行 */
    fun record(context: Context, id: String, capability: String, arguments: String, outcome: String) {
        val line = buildString {
            append(stamp.format(Date()))
            append("  ").append(capability)
            append("  ").append(arguments.replace('\n', ' ').take(MAX_ARGUMENTS))
            append("  ->  ").append(outcome.replace('\n', ' ').take(MAX_ARGUMENTS))
        }
        runCatching {
            val file = PluginStore.auditFile(context, id)
            file.parentFile?.mkdirs()
            if (file.length() > CAP_BYTES) {
                val kept = file.readLines().takeLast(KEEP_LINES)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            file.appendText("$line\n")
        }
    }

    /** 最近几条 (设置页那一段与 `lw_plugin op=audit` 读的是同一份) */
    fun tail(context: Context, id: String, lines: Int): List<String> {
        val file: File = PluginStore.auditFile(context, id)
        if (!file.isFile) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.takeLast(lines.coerceIn(1, 500))
    }
}
