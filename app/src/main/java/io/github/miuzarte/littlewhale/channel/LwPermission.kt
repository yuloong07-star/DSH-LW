package io.github.miuzarte.littlewhale.channel

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * The settings only a privileged uid may write
 *
 * The app cannot turn its own accessibility service on: that switch is a secure setting, and
 * writing one takes a permission this app does not hold and cannot ask for at runtime. Both uids
 * the channel can produce do hold it, so this is one more thing the privileged process is for -
 * and the reason the user never has to find the page in system settings that hides it
 *
 * The write goes through the `settings` command rather than through this process's own
 * ContentResolver, and that is not a shortcut. A process started by app_process has no
 * IApplicationThread, so asking for the settings provider from here is refused with "Unable to
 * find app for caller" - the same wall the app's own provider had to be reached around. The
 * command works because it reaches the provider the other way, through an external token
 *
 * Writing the setting is not quite the whole job: Android 13 lets an app that no store installed
 * hold its own accessibility service back, which is this app's case, and a component that is
 * already in the list but not bound does not come back from a write that changes nothing. Both of
 * those are handled here rather than left for the user to work out
 */
/**
 * 无障碍现在到底怎么样
 *
 * 几件事实放在一起才算回答完了那个问题: `componentListed` 与 `running` 是"设置里写着"与"系统真的
 * 绑上了"的区别, `writeChannel` 是"我们还能不能写"的区别, 另外两个是 Android 13 那道闸的两半。
 * 设置页显示它, `lw_probe` 回它, 所以判据只有一份
 *
 * @property component 我们那个无障碍组件的完整名字 (写进列表里的就是它)
 * @property componentListed 设备当前的 `enabled_accessibility_services` 里有没有我们
 * @property otherServices 设备上除此之外还有几个别人的无障碍服务 (写入要读出来改, 不能整串覆盖)
 * @property masterSwitch `accessibility_enabled` 是不是 1
 * @property running 系统有没有把服务实例绑起来 —— "开没开"最终是它说了算
 * @property installer 设备记的 `installerPackageName`, 空串表示 null (侧载)
 * @property restrictedSettings `ACCESS_RESTRICTED_SETTINGS` 这道 op 的值, 空串表示这台设备问不到
 * @property writeChannel 应用自己写的 secure settings 会怎样, 见 [WriteChannel]
 * @property advancedProtection 用户开着高级保护模式吗 (Android 17 的 AAPM), null 表示这台设备问不到
 * @property readAt 这一份是什么时候读的 (毫秒时间戳), 见 [reason] 里为什么需要它
 */

/**
 * 应用自己写一条 secure setting 会怎样
 *
 * 三种情形要分开, 因为它们要的处置完全不同:
 *
 * - [OPEN] 写进去、读回来就是那个值 —— 应用自己就能开
 * - [DISCARDED] 命令跑过了 (退出码 0) 而值没变 —— 这台设备把写入丢了, 只能赶在重装之后那段窗口里写
 * - [REFUSED] 命令本身被拒 —— Android 14 起非 shell uid 跑 `settings put` 会 `SecurityException`,
 *   于是"设备会不会留下"这件事**根本没被问到**。这不是设备的毛病, 写入本来就该由特权进程去做
 */
enum class WriteChannel { OPEN, DISCARDED, REFUSED }
data class AccessibilityState(
    val component: String,
    val componentListed: Boolean,
    val otherServices: Int,
    val masterSwitch: Boolean,
    val running: Boolean,
    val installer: String,
    val restrictedSettings: String,
    val writeChannel: WriteChannel,
    val advancedProtection: Boolean?,
    val readAt: Long,
) {

    /** 健康的样子: 在列表里且真的绑着 */
    val healthy: Boolean get() = componentListed && running

    /** 应用自己能写, 这条通路才算通 —— 另外两种都不是"通" */
    val writeChannelOpen: Boolean get() = writeChannel == WriteChannel.OPEN

    /**
     * 一句能直接给用户/日志看的话, 说清现在卡在哪一层
     *
     * **末尾那个时间不是装饰**: 设置页那一行点一下就是重新读一次, 而读出来的内容常常与上一次逐字相同
     * (状态没变时它就该相同) —— 于是"点了没反应"与"点了、结果一样"在屏幕上看不出区别。把读取时刻带上,
     * 每一次点击都会让那一行真的变一下, 而它同时说明了这份状态有多新
     */
    fun reason(): String = when {
        running && componentListed -> "服务在跑, 组件在设备的列表里$AT" +
            advancedNote()

        running && !componentListed -> "服务在跑, 但它不在设备的列表里: 那个页面多半是这么显示的," +
            " 而实例还活着 (去掉条目不会杀已经绑好的服务)$AT" + advancedNote()

        advancedProtection == true -> "用户开着高级保护模式: 这个模式下系统只让带 isAccessibilityTool" +
            " 标志的服务拿到无障碍, 本应用带了那个标志, 所以卡住的应该是下面某一条 —— 先看写入通路" +
            "$AT$ADVANCED_NOTE"

        writeChannel == WriteChannel.REFUSED -> "应用自己写不了 secure settings: 从 Android 14 起" +
            " `/system/bin/settings` 对非 shell 的 uid 一律拒 (SecurityException), 所以这不是设备" +
            "不接受写入, 而是这条写入本该由特权进程去做 —— 设置页那个开关走的就是它$AT"

        writeChannel == WriteChannel.DISCARDED -> "这台设备不接受写入: `settings put` 返回 0 而值不变," +
            " 所以应用自己开不了它。用电脑跑 $INSTALL_SCRIPT, 它会赶在重装之后那段窗口里写完并读回$AT"

        installer.isEmpty() -> "装的时候没带安装者身份 (installerPackageName 是 null), 而 Android 13" +
            " 起这样的应用不许开无障碍。用带 -i 的方式重装: $INSTALL_SCRIPT$AT"

        restrictedSettings.isNotEmpty() && restrictedSettings != "allow" ->
            "受限设置那道 op 还是 \"$restrictedSettings\" (要 allow);" +
                " 这台设备上它只有带安装者身份重装之后才设得动 —— $INSTALL_SCRIPT$AT"

        restrictedSettings.isEmpty() -> "问不到受限设置那道 op 的值 (`cmd appops get` 没答上来)," +
            " 所以这一条不知道: 侧载的应用在 Android 13 起要它变成 allow 才开得了无障碍 ——" +
            " $INSTALL_SCRIPT$AT"

        !componentListed -> "组件不在设备的列表里, 写一次就能放回去 (重装会把我们踢出去):" +
            " $INSTALL_SCRIPT$AT"

        else -> "组件在列表里但系统没有把它绑起来: 开关会先摘掉、停一下再放回, 那一下是让系统重新评估$AT"
    }

    /**
     * 高级保护模式开着的话, 补一句
     *
     * 它是**背景**而不是结论: 本应用已经声明了 isAccessibilityTool, 在这个模式下本来就该放行, 所以
     * 服务没跑起来时原因在别处。但用户开了那个模式这件事本身要说出来 —— 否则他会去翻系统设置里的
     * 无障碍页, 而那里在模式开着时根本不给这个应用授权
     */
    private fun advancedNote(): String = if (advancedProtection == true) ADVANCED_NOTE else ""

    /** 高级保护模式那句背景说明, [reason] 的两条分支与它共用一份措辞 */
    private val ADVANCED_NOTE: String
        get() = " (这台设备开着高级保护模式: 它只把无障碍交给带 isAccessibilityTool 标志的服务," +
            "本应用带了那个标志。模式开着时系统设置里的无障碍页不会给这个应用授权, 别在那里找开关)"

    /**
     * 这台设备上把无障碍打开的唯一办法, 连命令一起给
     *
     * 说"跑装机脚本"是不够的: 脚本在电脑上, 要带设备序列号, 而人在屏幕上看到的这句话就是他手里
     * 仅有的线索 —— 给一行能直接粘的命令, 比给一个文件名有用
     */
    private val INSTALL_SCRIPT: String
        get() = "pwsh -File tools/lw-install.ps1 -Serial <设备序列号>"

    private val AT: String
        get() = " (读取 " +
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(readAt) + ")"
}

/**
 * 通知使用权现在到底怎么样
 *
 * 与 [AccessibilityState] 是同一套问法, 只是少了几件事实 (通知这边没有主开关, 也没有
 * `isAccessibilityTool` 那个标志): `componentListed` 是"设置里写着", `running` 是"系统真的把服务
 * 绑上了", 而 `granted` 是系统自己对这道授权的回答 —— 三者不一致时, **说话的是后两个**
 *
 * @property component 本应用那个监听服务的完整组件名
 * @property componentListed `enabled_notification_listeners` 里有没有它
 * @property otherListeners 设备上除此之外还有几个别人的监听 (写入要读出来改)
 * @property running 系统有没有把服务实例绑起来
 * @property granted `NotificationManager.isNotificationListenerAccessGranted` 的回答, null 表示问不到
 * @property banners 全屏通知 (横幅) 现在给不给, null 表示这台设备问不到 (Android 14 以下)
 * @property readAt 这一份是什么时候读的
 */
data class NotificationState(
    val component: String,
    val componentListed: Boolean,
    val otherListeners: Int,
    val running: Boolean,
    val granted: Boolean?,
    val banners: Boolean?,
    val readAt: Long,
) {

    /** 健康的样子: 在名单里且真的绑着 */
    val healthy: Boolean get() = componentListed && running

    /**
     * 一句能直接看的话, 说清现在卡在哪一层
     *
     * 末尾那个时间与无障碍那条同理: 每次重读都让这一行真的变一下, 不然"点了没反应"与"点了、结果
     * 一样"在屏幕上看不出区别
     */
    fun reason(): String = when {
        running && componentListed -> "服务在跑, 组件在设备的名单里$AT" + bannerNote()
        running && !componentListed -> "服务在跑, 但它不在设备的名单里: 条目被摘掉不会杀已经绑好的" +
            " 服务$AT" + bannerNote()
        !componentListed -> "组件不在设备的名单里 (重装会把我们踢出去): 那个开关走系统自己的" +
            " `cmd notification allow_listener`, 由特权进程执行; 它办不到时去这一段那个入口打开" +
            " DSH-LW (系统会弹一个确认框)$AT"
        else -> "组件在名单里但系统没有把它绑起来: 开关会先摘掉、停一下再放回; 若还是不动, 用这一段" +
            " 那个入口去系统那页打开它 —— 有些设备只认系统自己那个确认框$AT"
    }

    /** 横幅那件事是另一道授权, 所以单独说一句 */
    private fun bannerNote(): String = when (banners) {
        true -> " (通知可以越过锁屏弹成横幅)"
        false -> " (但横幅不给: 从 Android 14 起全屏通知要人去 设置 -> 应用 -> 特殊应用权限 里手动开," +
            " 在那之前 lw_notify 的 banner 只是一个普通的高重要度通知)"
        null -> ""
    }

    private val AT: String
        get() = " (读取 " +
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(readAt) + ")"

    private val INSTALL_SCRIPT: String
        get() = "pwsh -File tools/lw-install.ps1 -Serial <设备序列号>"
}

internal class LwPermission(private val context: Context?) {

    /**
     * Add or take this app's accessibility service out of the enabled list
     *
     * The list is a colon separated set of flattened component names shared with every other app on
     * the device, so this edits it rather than replacing it, and it compares components rather than
     * strings: the same component can be spelled two ways, and something else may have written
     * either spelling
     */
    fun setAccessibility(enabled: Boolean): Boolean {
        val context = context ?: run {
            Log.w(TAG, "no context, so the app's own component name is unknown")
            return false
        }
        val ours = ComponentName(context, LwAccessibility::class.java)
        val stored = get(ENABLED_SERVICES) ?: return false
        val listed = stored.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
        val others = listed.filter { it != ours }
        val alreadyListed = listed.any { it == ours }

        // The master switch is what the system reads first; enabling the component without it
        // leaves the service off with no sign of why, so it goes in before the list does.
        // 读回校验: 这台设备上"写了但没留下"是常态, 而退出码看不出来
        if (enabled && !putVerified(ACCESSIBILITY_ENABLED, "1")) return false
        if (enabled) allowRestrictedSettings(context.packageName)

        // A component that is in the list but not bound - which is what a force stop leaves behind -
        // is not brought back by writing the same list again, since the value did not change and the
        // observer has nothing to act on. Taking it out first makes the change real, and the pause
        // is for the system to see it; this is the "restart the service" gkd does before enabling
        if (enabled && alreadyListed) {
            if (!put(ENABLED_SERVICES, others.joinToString(":") { it.flattenToString() })) return false
            Thread.sleep(REBIND_GAP_MS)
        }

        val next = (if (enabled) others + ours else others)
            .joinToString(":") { it.flattenToString() }
        if (!put(ENABLED_SERVICES, next)) return false

        // Read it back, because an exit code says the command ran rather than that the device kept
        // the value, and a listener that rewrites the list is exactly the case that has to be seen
        val written = get(ENABLED_SERVICES) ?: return false
        val kept = written.split(':').any { ComponentName.unflattenFromString(it) == ours }
        if (kept != enabled) {
            Log.w(TAG, "the device kept $written, which is not what was written")
            return false
        }
        Log.i(TAG, "accessibility ${if (enabled) "enabled" else "disabled"}: $written")
        return true
    }

    /**
     * Let this app past Android 13's restricted settings
     *
     * An accessibility service of an app no store installed cannot be turned on, and this app is
     * installed by adb or by `pm install` - its installer package is null, which is exactly the
     * restricted case. What comes between the setting and the service binding is an app op, and
     * either uid this process can run as may set one. Best effort in both directions: the op does
     * not exist before Android 13, and nothing here is what makes the write above fail
     *
     * **The value is read back rather than trusted to the exit code.** Measured on a vivo device
     * running Android 16: `cmd appops set ... ACCESS_RESTRICTED_SETTINGS allow` exits 0 and changes
     * nothing at all - the ROM does not honour that op. Logging the exit code would write "allowed"
     * while nothing was allowed, and every later diagnosis would be led astray by that line, so what
     * is reported here is what the device actually kept
     */
    private fun allowRestrictedSettings(packageName: String) {
        val result = run(CMD, "appops", "set", packageName, RESTRICTED_SETTINGS, "allow") ?: return
        val (code, output) = result
        if (code != 0) {
            Log.d(TAG, "$RESTRICTED_SETTINGS was not allowed: $output")
            return
        }
        val kept = restrictedSettings(packageName)
        if (kept == ALLOW) {
            Log.i(TAG, "allowed $RESTRICTED_SETTINGS for $packageName")
            return
        }
        Log.w(
            TAG,
            "$RESTRICTED_SETTINGS is still \"$kept\" after being set to allow: this device does not" +
                " honour that op, so the accessibility service cannot be turned on while this app has" +
                " no installer - installing it through a package installer is what lifts this",
        )
    }

    /** What the device says this app's restricted settings op is set to, empty when it will not say */
    private fun restrictedSettings(packageName: String): String {
        val (code, output) = run(CMD, "appops", "get", packageName, RESTRICTED_SETTINGS) ?: return ""
        if (code != 0) return ""
        // "ACCESS_RESTRICTED_SETTINGS: allow; time=+1m5s ago" -> "allow"
        return output.substringAfter(':', "").substringBefore(';').trim()
    }

    /**
     * One secure setting
     *
     * **两条路, 顺序要紧**: 应用进程里先用 `ContentResolver` (普通应用读这些键是天经地义的), 因为
     * `/system/bin/settings get` 是一条 **shell 命令** —— 实测 Android 16 上它对非 shell 的 uid 直接
     * 抛 `SecurityException: getCurrentUser() ... requires INTERACT_ACROSS_USERS`, 于是应用自己读回来
     * 永远是 null: 设置页与 `lw_probe` 会把"组件在列表里"说成"不在", 把主开关说成关着
     *
     * `ContentResolver` 在特权进程里**走不通** (`app_process` 没有 `IApplicationThread`), 所以那条命令
     * 留着当兜底 —— 同一个类在两个进程里都用, 各自走各自能走的那条
     */
    private fun get(key: String): String? {
        context?.let { ctx ->
            runCatching { Settings.Secure.getString(ctx.contentResolver, key) }
                .onFailure { Log.d(TAG, "could not read $key through the ContentResolver", it) }
                .getOrNull()
                ?.let { return it }
        }
        val (code, output) = run(SETTINGS, "get", "secure", key) ?: return null
        if (code != 0) return null
        val value = output.trim()
        return if (value == "null") "" else value
    }

    /** Write one secure setting, answering whether the command succeeded */
    private fun put(key: String, value: String): Boolean {
        val result = run(SETTINGS, "put", "secure", key, value) ?: return false
        val (code, output) = result
        if (code != 0) {
            Log.w(TAG, "settings put secure $key was refused: $output")
            return false
        }
        return true
    }

    /**
     * 写一条 secure setting, 但**读回来**才算成功
     *
     * 退出码只说命令跑过了, 不说设备留下了这个值。实测过的两种"假成功"都在这条路上: 这台 vivo 上
     * `settings put` 对任何 key 都返回 0 而值不变, 而侧载应用那道 app op 也是同一个毛病。所以凡是要
     * 相信结果的写入都走这里
     */
    private fun putVerified(key: String, value: String): Boolean {
        if (!put(key, value)) return false
        val kept = get(key)
        if (kept != value) {
            Log.w(TAG, "the device kept \"$kept\" after being asked for \"$value\" ($key)")
            return false
        }
        return true
    }

    /**
     * 这台设备的写入通路现在是什么样
     *
     * 用一个**自己的**探针 key 试一次: 写进去、读回来、再删掉。key 是本应用的名字, 一眼看得出是谁
     * 留的, 而且它不参与任何别的判断 —— 它只回答一个问题: "我现在写的值, 设备会不会留下"
     *
     * 为什么值得单独做一件事: 这台 vivo 上的丢弃是整个 SettingsProvider 层面的 (三个命名空间、任何
     * key 都一样), 所以"无障碍写不进"与"这道 op 没设上"是两件事, 而它们要的处置完全不同。分不清就
     * 只能给一句含糊的失败, 那种提示已经误导过一次了
     *
     * **第三种情形是 1.0.3 才分出来的**: 命令本身被拒 (非 shell uid 跑 `settings put` 会
     * `SecurityException`)。那时"设备会不会留下"这个问题根本没被问到, 说成"这台设备不接受写入"是
     * 错的 —— 写入本来就该由特权进程去做
     */
    fun writeChannel(): WriteChannel {
        val refused = !put(PROBE_KEY, PROBE_VALUE)
        val kept = !refused && get(PROBE_KEY) == PROBE_VALUE
        // 收尾要删掉: 探针留着就成了设备上一条没人认领的设置
        if (!refused) run(SETTINGS, "delete", "secure", PROBE_KEY)
        if (refused) {
            Log.i(
                TAG,
                "the app cannot write secure settings at all on this device: only the privileged" +
                    " process can, which is what the settings page's switch goes through",
            )
            return WriteChannel.REFUSED
        }
        if (!kept) {
            Log.w(
                TAG,
                "this device discards writes to secure settings: the probe was written and read" +
                    " back as something else, so nothing the app writes here survives on its own",
            )
            return WriteChannel.DISCARDED
        }
        return WriteChannel.OPEN
    }

    /**
     * 把本应用的通知监听放进 (或拿出) 设备的已授权名单
     *
     * **先走系统自己的那条命令** (`cmd notification allow_listener` / `disallow_listener`), 而不是直接
     * 写那条 secure setting —— 这是 2026-10-04 在模拟器上量出来的: **直写 `enabled_notification_listeners`
     * 是不够的**。写进去、读回来也在、设备也留着, 而系统**根本不看它**: 服务不会被绑上, 系统自己那份
     * "用户设过"的名单里也没有它 (通知那一页会把它列在 "Not allowed" 下面)。那条命令正是设置页上
     * "允许"按钮走的同一条路 (它同时把"用户设过"记上), 双向都实测过: disallow 之后服务真的解绑,
     * allow 之后真的重新绑上
     *
     * 直写那份名单留作退路 (没有那条命令的设备, 或者命令被拒), 因为无障碍那条路就是靠它走通的
     *
     * @return 设备的真实状态有没有变成 [enabled] —— 以名单读回为准, 不看退出码
     */
    fun setNotificationListener(enabled: Boolean): Boolean {
        val context = context ?: run {
            Log.w(TAG, "no context, so the app's own component name is unknown")
            return false
        }
        val ours = ComponentName(context, LwNotificationListener::class.java)
        // 侧载安装的应用在 Android 13 起连通知使用权也受限制设置那道闸管, 与无障碍同一条 op
        if (enabled) allowRestrictedSettings(context.packageName)

        val verb = if (enabled) "allow_listener" else "disallow_listener"
        val answer = run(CMD, "notification", verb, ours.flattenToString())
        val asked = answer != null && answer.first == 0
        if (asked && listedIn(ours) == enabled) {
            Log.i(TAG, "notification access ${if (enabled) "enabled" else "disabled"} through cmd notification")
            return true
        }
        Log.i(
            TAG,
            "cmd notification $verb did not settle it (${answer?.second?.trim().orEmpty()}), so the" +
                " list is written instead",
        )
        return setNotificationListenerByList(ours, enabled)
    }

    /** 名单里有没有这个组件, null 表示问不到 */
    private fun listedIn(ours: ComponentName): Boolean? = get(ENABLED_LISTENERS)
        ?.split(':')
        ?.any { ComponentName.unflattenFromString(it) == ours }

    /**
     * 退路: 读出来改那份名单, 写完读回核对
     *
     * 名单是**与别的应用共用**的, 所以不能整串覆盖; 比较用 [ComponentName] 而不是字符串 (同一个组件
     * 有两种拼法); "已在名单里却没被绑上"这件事两边一样 (强停之后就是), 所以也先摘掉、停一下、再放回
     */
    private fun setNotificationListenerByList(ours: ComponentName, enabled: Boolean): Boolean {
        val stored = get(ENABLED_LISTENERS) ?: return false
        val listed = stored.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
        val others = listed.filter { it != ours }
        val alreadyListed = listed.any { it == ours }

        if (enabled && alreadyListed) {
            if (!put(ENABLED_LISTENERS, others.joinToString(":") { it.flattenToString() })) {
                return false
            }
            Thread.sleep(REBIND_GAP_MS)
        }

        val next = (if (enabled) others + ours else others)
            .joinToString(":") { it.flattenToString() }
        if (!put(ENABLED_LISTENERS, next)) return false

        val kept = listedIn(ours) ?: return false
        if (kept != enabled) {
            Log.w(TAG, "the device kept a list where our listener is listed=$kept, not $enabled")
            return false
        }
        Log.i(TAG, "notification access ${if (enabled) "enabled" else "disabled"} by writing the list")
        return true
    }

    /**
     * 通知栏那个服务现在到底有没有被绑上
     *
     * 与 [AccessibilityState] 同一个道理: "设置里写着"不等于"服务活着", 而这里要说的是后者
     */
    fun notificationListeners(): NotificationState {
        val context = context
        val component = context
            ?.let { ComponentName(it, LwNotificationListener::class.java).flattenToString() }
            .orEmpty()
        val listed = get(ENABLED_LISTENERS).orEmpty()
        val listedComponents = listed.split(':').filter { it.isNotBlank() }
        return NotificationState(
            component = component,
            componentListed = listedComponents.any { it.trim() == component },
            otherListeners = listedComponents.count { it.trim() != component },
            running = LwNotificationListener.running,
            granted = context?.let {
                it.getSystemService(android.app.NotificationManager::class.java)
                    ?.isNotificationListenerAccessGranted(
                        ComponentName(it, LwNotificationListener::class.java),
                    )
            },
            banners = context?.let {
                it.getSystemService(android.app.NotificationManager::class.java)
                    ?.canUseFullScreenIntent()
            },
            readAt = System.currentTimeMillis(),
        )
    }

    /**
     * 无障碍现在到底怎么样
     *
     * 一次把六件事实读齐: 组件在不在设备列表里 / 主开关的值 / 服务实例活没活 / installer 身份 /
     * 设备记的 app op / 写入通路。设置页拿它显示, `lw_probe` 拿它回话 —— **两边同一份**, 就不会出现
     * "页面上说已允许而工具说不支持"
     */
    fun inspect(): AccessibilityState {
        val context = context
        val component = context
            ?.let { ComponentName(it, LwAccessibility::class.java).flattenToString() }
            .orEmpty()
        val listed = get(ENABLED_SERVICES).orEmpty()
        val componentListed = listed.split(':')
            .filter { it.isNotBlank() }
            .any { it.trim() == component }
        val others = listed.split(':').count { it.isNotBlank() && it.trim() != component }
        val installer = run(CMD, "package", "list", "packages", "-i", context?.packageName.orEmpty())
            ?.second
            ?.substringAfter("installer=", "")
            ?.substringBefore(' ')
            ?.trim()
            .orEmpty()
        return AccessibilityState(
            component = component,
            componentListed = componentListed,
            otherServices = others,
            masterSwitch = get(ACCESSIBILITY_ENABLED) == "1",
            running = LwAccessibility.running,
            installer = installer,
            restrictedSettings = context?.let { restrictedSettings(it.packageName) }.orEmpty(),
            writeChannel = writeChannel(),
            advancedProtection = advancedProtection(),
            readAt = System.currentTimeMillis(),
        )
    }

    /**
     * 高级保护模式 (Android 17 的 AAPM) 开着吗
     *
     * `AdvancedProtectionManager` 是 API 37 才有的类, 所以更低的设备上这个问题**没法问**而不是"问
     * 出来是 false" —— 返回 null 就是那个意思。类不存在时抛的是 `NoClassDefFoundError`, 它属于 Error
     * 而不是 Exception, 所以这里接的是 Throwable
     */
    private fun advancedProtection(): Boolean? {
        if (Build.VERSION.SDK_INT < ADVANCED_PROTECTION_API) return null
        val context = context ?: return null
        return try {
            context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled
        } catch (error: Throwable) {
            Log.w(TAG, "could not ask whether advanced protection is on", error)
            null
        }
    }

    /** Run one command, answering with its exit code and what it printed */
    private fun run(vararg command: String): Pair<Int, String>? {
        val process = try {
            ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            Log.w(TAG, "could not start ${command.joinToString(" ")}", error)
            return null
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Throwable) {
            Log.w(TAG, "could not read what ${command.joinToString(" ")} printed", error)
            ""
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "${command.joinToString(" ")} did not finish, killing it")
            process.destroy()
            return null
        }
        return process.exitValue() to output
    }

    private companion object {
        const val TAG = "LwPermission"

        /** The platform's own way of writing a secure setting without an app process */
        const val SETTINGS = "/system/bin/settings"

        /** And its way of setting an app op, which is a different command */
        const val CMD = "/system/bin/cmd"

        /** Android 13's gate on turning on the accessibility service of an app no store installed */
        const val RESTRICTED_SETTINGS = "ACCESS_RESTRICTED_SETTINGS"

        /** 高级保护模式那个类从哪个 API 开始有 (AdvancedProtectionManager) */
        const val ADVANCED_PROTECTION_API = 37

        /** What that op reads as once it has been lifted, which is what the read back compares to */
        const val ALLOW = "allow"

        /**
         * 探针用的那一条设置
         *
         * 名字带着这是谁写的, 而值带一个不会与任何真实配置撞上的记号
         */
        const val PROBE_KEY = "lw_write_probe"
        const val PROBE_VALUE = "lw-probe"

        /** How long the system is given to notice a component left the list before it goes back in */
        const val REBIND_GAP_MS = 800L

        const val ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        const val ACCESSIBILITY_ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED

        /** 通知使用权的名单, 与无障碍那份是同一个形状的另一个 key (`Settings.Secure` 里没有公开常量) */
        const val ENABLED_LISTENERS = "enabled_notification_listeners"

        /** A settings command is quick, but it starts a process and can wait on the provider */
        const val TIMEOUT_SECONDS = 5L
    }
}
