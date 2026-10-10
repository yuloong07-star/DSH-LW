package io.github.yuloong07star.luwi.plugin

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * 把一份包收进来 (协议第 7 节的"装入"那几格)
 *
 * 顺序一步都不许省: **先解包到暂存 → 解 `plugin.json` → 验签名与逐文件哈希 → 查版本与前缀冲突 →
 * 才搬进 `<id>/<version>`**。中途任何一条不过, 暂存目录一删, 什么都没落地
 *
 * 签名那一条有个容易漏的地方: `files` 那份清单必须**覆盖包里除 `plugin.json` 之外的每一个文件** ——
 * 签名覆盖不到的文件等于没被签, 那是一个洞
 */
internal object PluginInstaller {

    /** 一份装进来的包 */
    data class Outcome(
        val id: String,
        val name: String,
        val version: String,
        val signed: Boolean,
        val fingerprint: String,
        /** 第一次见到这把公钥时为 true, 界面要单独提示一句"这是一个新发布者" */
        val newPublisher: Boolean,
        val directory: File,
    )

    /**
     * 装一份包
     *
     * @param source 一个解开的目录, 或者一个 `.lwp` (zip)
     * @param developerMode 关着的时候未签名的包一律拒
     */
    fun install(context: Context, source: File, developerMode: Boolean): Outcome {
        if (!source.exists()) throw IllegalArgumentException("没有这个东西: ${source.absolutePath}")
        val staging = PluginStore.staging(context, "install-${System.nanoTime()}")
        try {
            val prepared = when {
                source.isDirectory -> source
                source.isFile -> unzip(source, staging)
                else -> throw IllegalArgumentException("${source.absolutePath} 既不是目录也不是文件")
            }
            val manifest = read(context, prepared)
            checkSignature(context, prepared, manifest, developerMode)
            checkUpgrade(context, manifest)

            val target = PluginStore.versionDir(context, manifest.id, manifest.version)
            target.parentFile?.mkdirs()
            if (target.exists()) target.deleteRecursively()
            if (prepared == source) copyInto(source, target) else moveInto(prepared, target)
            PluginStore.setCurrent(context, manifest.id, manifest.version)

            val firstTime = if (manifest.signed) {
                PluginStore.rememberPublisher(
                    context,
                    PluginStore.Publisher(
                        name = manifest.publisherName,
                        key = manifest.publisherKey,
                        firstSeen = System.currentTimeMillis(),
                    ),
                )
            } else {
                false
            }
            // 换了一份包就要让宿主重注册一次 (清单里的工具名与摘要可能都变了), 再让内存里那一份失效
            PluginStore.bumpRevision(context)
            PluginManager.invalidate()
            return Outcome(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                signed = manifest.signed,
                fingerprint = if (manifest.signed) PluginSignature.fingerprint(manifest.publisherKey) else "",
                newPublisher = firstTime,
                directory = target,
            )
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** 解包到暂存: 路径不许带 `..` 或从 `/` 起头, 条目数与总字节数都按上限卡 */
    private fun unzip(source: File, staging: File): File {
        var files = 0
        var bytes = 0L
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith("/") || name.contains("..")) {
                    throw IllegalArgumentException("包里的路径 \"$name\" 不合法")
                }
                val target = File(staging, name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                files += 1
                bytes += entry.size
                if (files > PluginStore.MAX_FILES) {
                    throw IllegalArgumentException("包里的文件超过 ${PluginStore.MAX_FILES} 个")
                }
                if (bytes > PluginStore.MAX_BYTES) {
                    throw IllegalArgumentException("包解开之后超过 ${PluginStore.MAX_BYTES / 1024 / 1024} MB")
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        if (files == 0) throw IllegalArgumentException("包里一个文件都没有")
        return staging
    }

    /** 解清单: 目录里必须有 `plugin.json` */
    private fun read(context: Context, directory: File): PluginManifest {
        val file = File(directory, "plugin.json")
        if (!file.isFile) throw IllegalArgumentException("包里没有 plugin.json")
        return PluginManifest.parse(file.readText(), PluginStore.lwVersion(context))
    }

    /** 签名、哈希覆盖、开发者模式这三条 */
    private fun checkSignature(context: Context, directory: File, manifest: PluginManifest, developerMode: Boolean) {
        val present = filesIn(directory)
        if (!manifest.signed) {
            if (!developerMode) {
                throw IllegalArgumentException(
                    "这一份没有发布者签名。未签名的包只接受本地手工放入, 而且要先去设置页打开「开发者模式」",
                )
            }
            return
        }
        if (present != manifest.files.keys) {
            val missing = manifest.files.keys - present
            val extra = present - manifest.files.keys
            val why = buildList {
                if (missing.isNotEmpty()) add("清单里有 ${missing.joinToString(", ")} 而包里没有")
                if (extra.isNotEmpty()) add("包里有 ${extra.joinToString(", ")} 而清单里没列 (签名覆盖不到的东西等于没被签)")
            }
            throw IllegalArgumentException("签名与包对不上: ${why.joinToString("; ")}")
        }
        manifest.files.forEach { (path, digest) ->
            val actual = PluginSignature.digestOf(File(directory, path))
            if (actual != digest) {
                throw IllegalArgumentException("$path 的内容与签名清单对不上 (清单 $digest, 实际 $actual)")
            }
        }
        val ok = PluginSignature.verify(manifest.raw, manifest.files, manifest.publisherKey, manifest.publisherSignature)
        if (!ok) throw IllegalArgumentException("发布者签名验不过 —— 这一份要么被改过, 要么不是这把私钥签的")
    }

    /** 版本与前缀这两条 (同前缀的第二个插件直接拒, 见协议第 6 节) */
    private fun checkUpgrade(context: Context, manifest: PluginManifest) {
        PluginStore.installedIds(context).filter { it != manifest.id }.forEach { other ->
            val theirs = PluginStore.manifest(context, other)
            if (theirs != null && theirs.toolPrefix == manifest.toolPrefix) {
                throw IllegalArgumentException(
                    "前缀 \"${manifest.toolPrefix}_\" 已经被 ${theirs.id} 占着, 换一个 (前缀是全局唯一的)",
                )
            }
        }
        val current = PluginStore.currentVersion(context, manifest.id)
        if (current != null && PluginVersions.compare(manifest.version, current) < 0) {
            throw IllegalArgumentException("装着的已经是 $current, 这一份是 ${manifest.version} —— 降级要显式确认, 这一版不做")
        }
    }

    /** 包里除 `plugin.json` 之外的每个文件 (相对路径), 与签名清单比的就是这一份 */
    private fun filesIn(directory: File): Set<String> = directory.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(directory).invariantSeparatorsPath }
        .filter { it != "plugin.json" }
        .toSet()

    private fun moveInto(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { child -> child.renameTo(File(to, child.name)) }
            ?: throw IllegalStateException("暂存目录读不出来: ${from.absolutePath}")
    }

    /** 源是一份已经解开的目录时用拷的 —— 它可能就是仓库里那一份, 不能动它 */
    private fun copyInto(from: File, to: File) {
        to.mkdirs()
        from.copyRecursively(to, overwrite = true)
    }
}
