package io.github.miuzarte.littlewhale.host

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 随包发的那两份"首启内容": 四份技能与两条样例快捷指令
 *
 * 技能落在 `$DSH_HOME/skills/`, 快捷指令落在 `$DSH_HOME/quick-commands/`, 两份都落在 dsh 自己的家
 * 里而不是 assets 里 —— 与模式正文同一条道理 (见 [io.github.miuzarte.littlewhale.channel.LwModes]):
 * 主人可以自己改, 改完下一次就用新的那份
 *
 * **两份的规矩不一样, 这是刻意的**:
 *
 * - 技能: **缺什么补什么**。与 `LwModes.ensureDefault` 同一条 —— 新装的机器上没有, 补上; 主人改过的
 *   那一份已经在了, 一个字节都不动
 * - 样例快捷指令: **只发一次**。成功之后落一个 [STAMP] 记号, 之后无论主人删掉还是改掉都不再管 ——
 *   "我删掉的样例又回来了"是这类功能最容易招人烦的地方
 *
 * 落位失败 (assets 缺 / 磁盘满) 只记日志: 这一件事不该拦住 host 起来, 而缺哪一份在设置页与技能
 * 目录里一眼看得见
 *
 * 随包的那几项由构建拷进 assets (见 `app/build.gradle.kts` 的 `copySeedAssets`), 源在仓库的
 * `skills/` 与 `quick-commands/` 两处, 只有那四份技能与两条样例进包
 */
internal object LwSeed {

    private const val TAG = "LwSeed"

    /** assets 里技能那一层: 每个技能一个目录, 目录名就是技能名 */
    private const val SKILLS = "skills"

    /** 技能正文的文件名, dsh 的 skill-filesystem 认的就是它 */
    private const val SKILL_FILE = "SKILL.md"

    /** assets 里样例快捷指令那一层 */
    private const val COMMANDS = "quick-commands"

    /** 样例发过一次的记号 (放在 `$DSH_HOME` 根上, 不放技能目录里 —— 那里多一个点文件会被扫成一条坏技能) */
    private const val STAMP = ".lw-seed"

    /** 随包的技能: 目录名 = frontmatter 里的 `name`, 两份都要对得上 */
    private val skills = listOf("web-search", "weather", "calendar", "photo-edit")

    /** 随包的样例快捷指令: 文件名去掉 `.md` 就是它在设置页里的名字 */
    private val commands = listOf("制定旅游计划", "今天要做什么")

    /** 首启把这两份落到 `$DSH_HOME` 里, 已经有的一律不动 */
    fun ensure(context: Context) {
        val home = File(context.filesDir, DshHost.HOME_DIR)
        var written = 0
        var failed = 0
        skills.forEach { name ->
            val target = File(File(home, SKILLS), name).resolve(SKILL_FILE)
            if (target.isFile) return@forEach
            if (copy(context, "$SKILLS/$name/$SKILL_FILE", target)) written += 1 else failed += 1
        }
        // 样例那一档看记号: 没发过就补 (只补缺的那几条), 发过就再也不看
        val stamp = File(home, STAMP)
        var seeded = 0
        if (!stamp.isFile) {
            commands.forEach { name ->
                val target = File(File(home, COMMANDS), "$name.md")
                if (target.isFile) return@forEach
                if (copy(context, "$COMMANDS/$name.md", target)) {
                    written += 1
                    seeded += 1
                } else {
                    failed += 1
                }
            }
            // 一条都没发成时不落记号: 下一次启动还有机会 (磁盘满这种事是暂时的)
            if (failed == 0) {
                runCatching { stamp.writeText("${skills.size + commands.size}\n") }
                    .onFailure { Log.w(TAG, "the seed stamp could not be written", it) }
            }
        }
        Log.i(TAG, "seeds: $written written ($seeded of them sample quick commands), $failed failed")
    }

    /** 从 assets 拷一份出来; 回"写进去了没有" */
    private fun copy(context: Context, asset: String, target: File): Boolean = try {
        target.parentFile?.mkdirs()
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        true
    } catch (problem: Throwable) {
        Log.w(TAG, "$asset could not be seeded to ${target.absolutePath}", problem)
        false
    }
}
