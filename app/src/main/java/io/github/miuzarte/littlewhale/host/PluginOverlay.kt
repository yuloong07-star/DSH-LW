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
     * What this app adds, as the row id to the path that carries it, relative to the host tree root
     *
     * `dsh-web-mobile` points at the host half (`lib/index.js`); its browser half is a separate
     * `lib/client.js`, which the tree's `modules` row finds by scanning for packages that declare
     * `dsh.client` - so naming the host half here is what puts both halves in play
     */
    private val PLUGINS = listOf(
        "littlewhale-channel" to "node_modules/littlewhale-channel/index.mjs",
        "dsh-web-mobile" to "node_modules/dsh-web-mobile/lib/index.js",
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
        val present = PLUGINS.mapNotNull { (id, entry) ->
            File(root, entry).takeIf { it.isFile }?.let { id to it.absolutePath }
        }
        if (present.isEmpty()) return null
        val file = File(File(context.filesDir, DIRECTORY).apply { mkdirs() }, FILE)
        file.writeText(
            buildString {
                appendLine("# Written by LittleWhale on every host start, edits are overwritten")
                appendLine("- insert:")
                present.forEach { (id, path) ->
                    appendLine("    - id: $id")
                    appendLine("      name: '$path'")
                }
            },
        )
        return file
    }
}
