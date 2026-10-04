package io.github.miuzarte.littlewhale.host

import android.content.Context
import java.io.File

/**
 * The dsh patch overlay that loads LittleWhale's tools into the host's profile
 *
 * The tools ship inside the host tree the APK carries, but nothing in that tree mentions them:
 * a profile composes its plugins from the profile directory plus whatever patch overlays the
 * command line names, and this app is what composes that command line. Writing the overlay here
 * rather than into the profile keeps the profile the user's own
 *
 * Two plugins are named, and they got into the tree in two different ways - this app's own channel
 * plugin is copied in by the packaging step, while `dsh-web-mobile` is an ordinary dependency of
 * that tree. Either way the row has to be written here, because a package that is merely installed
 * is not part of any composition
 */
object PluginOverlay {

    /**
     * One row of the overlay: the id it gets, the file that carries it, and any config it needs
     *
     * @property id the row id the loader reports in diagnostics
     * @property entry path inside the host tree
     * @property config YAML lines written verbatim under `config:`, empty for rows that take none
     */
    private data class Row(val id: String, val entry: String, val config: List<String> = emptyList())

    /**
     * What this app adds, as the row id to the path that carries it, relative to the host tree root
     *
     * `dsh-web-mobile` points at the host half (`lib/index.js`); its browser half is a separate
     * `lib/client.js`, which the tree's `modules` row finds by scanning for packages that declare
     * `dsh.client` - so naming the host half here is what puts both halves in play
     *
     * 语音输入那三个包同理: 装了不等于挂了, 而**不挂就没有草稿框旁边那个麦克风按钮**。这里只挂
     * 这三个, 不挂官方那个 voice-input bundle —— bundle 会把 `defaultProvider` 设成它自带的本地
     * SenseVoice provider, 而那个 provider 靠 `sherpa-onnx-node` 的原生 addon, 安卓上没有
     * (npm 上也没有 `sherpa-onnx-android-arm64`)。本机那份转写走 app 进程, 由
     * `littlewhale-channel` 注册成 `lw-native`
     */
    private val PLUGINS = listOf(
        Row("littlewhale-channel", "node_modules/littlewhale-channel/index.mjs"),
        Row("dsh-web-mobile", "node_modules/dsh-web-mobile/lib/index.js"),
        Row(
            "speech-to-text",
            "node_modules/@deepseek-ai/dsh-experimental-speech-to-text/lib/index.js",
            config = listOf("defaultProvider: lw-native", "language: auto"),
        ),
        Row("speech-to-text-api", "node_modules/@deepseek-ai/dsh-experimental-api-speech-to-text/lib/index.js"),
        Row("voice-input", "node_modules/@deepseek-ai/dsh-experimental-client-ui-voice-input/lib/index.js"),
    )

    private const val DIRECTORY = "lw"
    private const val FILE = "tool-plugin.yml"

    /**
     * Write the overlay the host should boot with
     *
     * Rewritten on every start so a stale file cannot outlive the tree it points into
     * @param context context whose sandbox holds both the tree and the overlay.
     * @returns the overlay to pass on the command line, or null when the tree carries none of them.
     */
    fun write(context: Context): File? {
        val root = DshHost.hostRoot(context)
        val present = PLUGINS.mapNotNull { row ->
            File(root, row.entry).takeIf { it.isFile }?.let { row to it.absolutePath }
        }
        if (present.isEmpty()) return null
        val file = File(File(context.filesDir, DIRECTORY).apply { mkdirs() }, FILE)
        file.writeText(
            buildString {
                appendLine("# Written by LittleWhale on every host start, edits are overwritten")
                appendLine("- insert:")
                present.forEach { (row, path) ->
                    appendLine("    - id: ${row.id}")
                    appendLine("      name: '$path'")
                    if (row.config.isNotEmpty()) {
                        appendLine("      config:")
                        row.config.forEach { appendLine("        $it") }
                    }
                }
            },
        )
        return file
    }
}
