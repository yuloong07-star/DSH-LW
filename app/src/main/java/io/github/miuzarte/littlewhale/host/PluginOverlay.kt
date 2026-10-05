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

    /**
     * 官方那个 bundle 一旦被打开 (它的 row id 与上面这几个不一样), 再插一遍就是同一份包挂两次,
     * 而 `api-speech-to-text` 里的 `speechController` 不许重复注册 —— 现象是那一行带着
     * "service speechController has been registered" 变成异常, 而语音模型的下载正好走它
     *
     * 所以 bundle 开着的时候**只留自己那两个包, 再用"按 id 改配置"把 provider 指到本机那份**:
     * `speech-to-text` 这个 id 两边一样, 于是是一次配置覆盖而不是第二次挂载
     */
    private val OWN_PLUGINS = listOf(
        Row("littlewhale-channel", "node_modules/littlewhale-channel/index.mjs"),
        Row("dsh-web-mobile", "node_modules/dsh-web-mobile/lib/index.js"),
    )

    /** 官方 bundle 的名字: 它在 profile 的 package.json 里出现就说明被打开了 */
    private const val VOICE_BUNDLE = "@deepseek-ai/dsh-experimental-voice-input-bundle"

    /** 官方 bundle 里那条 provider 行的 id, 也是我们要覆盖的那一条 */
    private const val VOICE_PROVIDER_ROW = "speech-to-text"

    /**
     * 覆盖行的 `name` 与 profile 自己那条 `locale` 覆盖行同一个形状 (带 id 与 name 的配置覆盖),
     * 那是本机上正在生效的写法, 照它办最稳
     */
    private const val VOICE_PROVIDER_NAME = "@deepseek-ai/dsh-experimental-speech-to-text"

    private val VOICE_PROVIDER_CONFIG = listOf("defaultProvider: lw-native", "language: auto")

    private const val DIRECTORY = "lw"
    private const val FILE = "tool-plugin.yml"

    /** profile 目录在哪: 主机是以 `web` 这个 profile 起来的 */
    private fun profileDirectory(context: Context): File =
        File(File(File(context.filesDir, DshHost.HOME_DIR), "profiles"), "web")

    /**
     * 官方那个 bundle 现在开着吗
     *
     * 看两个地方: profile 的 `package.json` 里那份 bundle 名单 (插件列表上那个开关写的就是它),
     * 以及 profile 自己的 patch (有人手工按 row id 挂过也算)
     */
    private fun voiceBundleEnabled(context: Context): Boolean {
        val profile = profileDirectory(context)
        val declared = File(profile, "package.json").takeIf { it.isFile }?.readText().orEmpty()
        val patched = File(profile, "cordis.patch.yml").takeIf { it.isFile }?.readText().orEmpty()
        return declared.contains(VOICE_BUNDLE) || patched.contains(VOICE_PROVIDER_ROW)
    }

    /**
     * Write the overlay the host should boot with
     *
     * Rewritten on every start so a stale file cannot outlive the tree it points into
     * @param context context whose sandbox holds both the tree and the overlay.
     * @returns the overlay to pass on the command line, or null when the tree carries none of them.
     */
    fun write(context: Context): File? {
        val root = DshHost.hostRoot(context)
        val bundle = voiceBundleEnabled(context)
        val rows = if (bundle) OWN_PLUGINS else PLUGINS
        val present = rows.mapNotNull { row ->
            File(root, row.entry).takeIf { it.isFile }?.let { row to it.absolutePath }
        }
        if (present.isEmpty()) return null
        val file = File(File(context.filesDir, DIRECTORY).apply { mkdirs() }, FILE)
        file.writeText(
            buildString {
                appendLine("# Written by LittleWhale on every host start, edits are overwritten")
                if (bundle) {
                    // 官方 bundle 把 provider 行挂在它自己那份 patch 里, 而 patch 层是**按 id 覆盖**
                    // 的, 所以这里给一条同 id 的配置行就够, 不需要 (也不能) 再 insert 一遍
                    appendLine("# The official voice-input bundle is on, so its rows already exist:")
                    appendLine("# this only points that provider at this app's own engine")
                    appendLine("- id: $VOICE_PROVIDER_ROW")
                    appendLine("  name: $VOICE_PROVIDER_NAME")
                    appendLine("  config:")
                    VOICE_PROVIDER_CONFIG.forEach { appendLine("    $it") }
                }
                appendLine("- insert:")
                present.forEach { (row, path) ->
                    appendLine("    - id: ${row.id}")
                    appendLine("      name: '$path'")
                    if (!bundle && row.config.isNotEmpty()) {
                        appendLine("      config:")
                        row.config.forEach { appendLine("        $it") }
                    }
                }
            },
        )
        return file
    }
}
