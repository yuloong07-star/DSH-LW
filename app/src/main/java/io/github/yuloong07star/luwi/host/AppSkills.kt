package io.github.yuloong07star.luwi.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * 「装了哪个应用, 就装哪个技能」
 *
 * 随包带一份**应用 → 技能**的目录 (`assets/app-skills/catalog.json`, 源在仓库的 `app-skills/`),
 * 这一份做三件事: 读目录 / 看这台机器上装了哪些应用 / 把命中那些技能抄进 `$DSH_HOME/skills/`
 *
 * 三条写在这里的规矩:
 *
 * - **只加不改**: 目标技能已经在了就一个字节都不动 (与 [LwSeed] 同一条) —— 主人改过的技能不该
 *   被"一键"覆盖掉
 * - **"装了哪些"问的是启动器看得见的那一份** (`MAIN` + `LAUNCHER`), 与 `lw_apps` 同一个口径:
 *   系统组件不算"主人装的应用"
 * - 抄进去的是**技能正文**, 不是插件: 它落在 `$DSH_HOME/skills/<名字>/SKILL.md`, dsh 下一轮就看得见
 */
internal object AppSkills {

    private const val TAG = "AppSkills"

    /** assets 里那份目录 */
    private const val CATALOG = "app-skills/catalog.json"

    /** 每个技能正文放在自己那个目录下, 名字与 dsh 认的一致 */
    private const val SKILL_FILE = "SKILL.md"

    /** dsh 的技能目录, 与 [LwSeed] 同一个落点 */
    private const val SKILLS = "skills"

    /** 目录里的一条: 一个技能, 与它会命中的那几个包名 */
    data class Entry(val skill: String, val title: String, val apps: List<String>)

    /** 一条技能在这台机器上的现状 */
    data class Match(
        val skill: String,
        val title: String,
        /** 目录里列的那几个包名里, 这台机器上真装了的 */
        val hits: List<String>,
        /** 技能正文是不是已经在 `$DSH_HOME/skills/` 里了 */
        val present: Boolean,
    )

    /** 一次扫描或一次安装的结论 */
    data class Report(val matches: List<Match>, val wrote: List<String>, val failed: List<String>, val error: String)

    /** 技能目录: 读出随包那份清单, 条数错了就只记一行日志, 不让它拦住别的事 */
    fun catalog(context: Context): List<Entry> {
        val text = try {
            context.assets.open(CATALOG).bufferedReader().use { it.readText() }
        } catch (problem: Throwable) {
            Log.w(TAG, "no $CATALOG in this package", problem)
            return emptyList()
        }
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (problem: Throwable) {
            Log.w(TAG, "$CATALOG is not a JSON object", problem)
            return emptyList()
        }
        val entries = root["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val skill = item["skill"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val apps = (item["apps"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            if (skill.isEmpty() || apps.isEmpty()) return@mapNotNull null
            Entry(skill, item["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { skill }, apps)
        }
    }

    /**
     * 这台机器上"看得见"的应用
     *
     * 用启动器那一份而不是 `pm list packages`: 后者会把系统组件与后台服务也算进来, 而"主人装了
     * 什么"这件事的判据一直是"桌面上有没有它的图标" (与 `lw_apps` 同一个取法)
     */
    private fun launcherPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (problem: Throwable) {
            Log.w(TAG, "could not ask the package manager for launcher apps", problem)
            emptySet()
        }
    }

    /** 技能目录里现在有哪些技能 (`$DSH_HOME/skills/` 下每一个带 SKILL.md 的目录) */
    fun present(context: Context): Set<String> {
        val directory = File(File(context.filesDir, DshHost.HOME_DIR), SKILLS)
        val found = directory.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, SKILL_FILE).isFile }
            .map { it.name }
            .toSet()
        return found
    }

    /** 扫一遍: 目录里有哪几条在这台机器上命中, 各自装了没有 */
    fun scan(context: Context): Report {
        val apps = launcherPackages(context)
        val have = present(context)
        val matches = catalog(context).map { entry ->
            Match(entry.skill, entry.title, entry.apps.filter { it in apps }, entry.skill in have)
        }
        return Report(matches.filter { it.hits.isNotEmpty() }, emptyList(), emptyList(), "")
    }

    /**
     * 一键: 把技能抄进 `$DSH_HOME/skills/`
     *
     * @param only 点名装这几个技能 (**不要求**那个应用在这台机器上 —— 点名就是点名, 给桥上那条
     *   `op=install` 用); 空表示"按已安装的应用来", 那才是设置页那颗按钮走的路
     */
    fun install(context: Context, only: List<String> = emptyList()): Report {
        val apps = launcherPackages(context)
        val have = present(context)
        val entries = catalog(context).filter { entry ->
            if (only.isEmpty()) entry.apps.any { it in apps } else entry.skill in only
        }
        val target = File(File(context.filesDir, DshHost.HOME_DIR), SKILLS)
        val wrote = mutableListOf<String>()
        val failed = mutableListOf<String>()
        entries.forEach { entry ->
            // 已经在的不动: "一键"不覆盖主人改过的技能
            if (entry.skill in have) return@forEach
            val destination = File(File(target, entry.skill), SKILL_FILE)
            val copied = try {
                destination.parentFile?.mkdirs()
                context.assets.open("app-skills/${entry.skill}/$SKILL_FILE").use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (problem: Throwable) {
                Log.w(TAG, "could not install the ${entry.skill} skill", problem)
                false
            }
            if (copied) wrote += entry.skill else failed += entry.skill
        }
        // 重新看一遍账, 好让调用方拿到"现在到底装了哪些"
        val after = scan(context)
        return Report(after.matches, wrote, failed, "")
    }

    /** 那一个技能正文还在不在包里 (点名装一个不存在的名字时要能说清) */
    private fun packaged(context: Context, skill: String): Boolean = try {
        context.assets.open("app-skills/$skill/$SKILL_FILE").use { it.read() }
        true
    } catch (problem: Throwable) {
        false
    }

    /**
     * 桥上那两个 op
     *
     * `scan` 只读, `install` 才落地; 两个都回同一张表, 于是"装之前能看见会装什么"
     */
    fun dispatch(context: Context, request: JsonObject): JsonObject {
        val op = request["op"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val only = (request["skills"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val report = when (op) {
            "scan" -> scan(context)
            "install" -> {
                // 点名的那几个得真的在包里: 拼错一个名字就什么也不装, 而不是"装了另外几个"
                val unknown = only.filterNot { packaged(context, it) }
                if (unknown.isNotEmpty()) {
                    throw IllegalArgumentException("包里没有这几个技能: ${unknown.joinToString(", ")}")
                }
                install(context, only)
            }
            else -> return buildJsonObject {
                put("ok", false)
                put("error", "skill 这个桥方法只有两个 op: scan (看会装什么) 与 install (装)")
            }
        }
        return buildJsonObject {
            put("op", if (op == "install") "install" else "scan")
            put("count", report.matches.size)
            put("wrote", buildJsonArray { report.wrote.forEach { add(it) } })
            put("failed", buildJsonArray { report.failed.forEach { add(it) } })
            putJsonArray("matches") {
                report.matches.forEach { match ->
                    add(
                        buildJsonObject {
                            put("skill", match.skill)
                            put("title", match.title)
                            put("installed", match.present)
                            put("apps", buildJsonArray { match.hits.forEach { add(it) } })
                        },
                    )
                }
            }
            put("text", sentence(report, op == "install"))
        }
    }

    /** 那句给人看的话: 命中了哪几条、刚装了几条; 点名装的那一路不看"命中" */
    private fun sentence(report: Report, installing: Boolean): String {
        if (report.error.isNotEmpty()) return report.error
        val lines = report.matches.map { match ->
            val state = if (match.present) "已经在" else "缺着"
            "${match.skill} (${match.title}) $state —— 命中 ${match.hits.joinToString(", ")}"
        }
        val head = when {
            !installing -> "命中 ${report.matches.size} 条, 其中 ${report.matches.count { !it.present }} 条还没装"
            report.wrote.isEmpty() && report.failed.isEmpty() -> "一条都不用装: 该在的都在了"
            else -> "装了 ${report.wrote.size} 条" + (if (report.wrote.isEmpty()) "" else " (${report.wrote.joinToString(", ")})") +
                (if (report.failed.isEmpty()) "" else ", 有 ${report.failed.size} 条没写成 (${report.failed.joinToString(", ")})")
        }
        // 一句结论 + 命中的那几条明细; 一条都没命中时不摆空表, 只把那句结论说出来
        return if (lines.isEmpty()) head else head + "\n" + lines.joinToString("\n")
    }
}
