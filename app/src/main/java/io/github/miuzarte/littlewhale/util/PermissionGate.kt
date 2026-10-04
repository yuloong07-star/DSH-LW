package io.github.miuzarte.littlewhale.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 能力要权限时先过的那道闸
 *
 * 它只做一件事: 把"这个能力现在能不能用"变成一个**可以说出口的答案**。没拿到权限时, 工具回给模型
 * 的必须是"缺哪一条、怎么给", 而不是装作成功 —— 假装成功在这台设备上已经害过一次了 (无障碍那个
 * 写 secure settings 的假成功), 所以这一版所有新能力都走同一道闸
 *
 * 三种回答对应三种动作: [Grant.GRANTED] 直接用, [Grant.DENIED] 能点名要, [Grant.MISSING] 是构建
 * 出了问题 (清单里没声明), 那要修的是清单而不是权限
 */
object PermissionGate {

    /**
     * 一个能力现在能不能用
     *
     * @return null 表示能用, 否则是一句可以直接交给模型的话
     */
    fun refusal(context: Context, capability: Capability): String? {
        if (capability.permissions.isEmpty()) return null
        return when (PermissionCatalog.state(context, capability)) {
            Grant.GRANTED -> null
            Grant.MISSING -> "$FIX_MANIFEST: ${capability.name}"
            Grant.DENIED -> {
                val names = capability.permissions.joinToString(", ") {
                    PermissionCatalog.shortName(it)
                }
                if (capability.special) {
                    "${capability.name} needs ${names}, which this device only hands out from a " +
                        "system settings page: open it on the phone (Settings -> " +
                        "${capability.name}), or run tools/lw-install.ps1 -Perms on a computer"
                } else {
                    "${capability.name} needs ${names}, which has not been granted: tap it in the " +
                        "app's own Settings -> Permissions, or run tools/lw-install.ps1 -Perms on a " +
                        "computer"
                }
            }
        }
    }

    /** 一个能力现在能不能用, 只回答是与否 */
    fun allows(context: Context, capability: Capability): Boolean = refusal(context, capability) == null

    /**
     * 请用户在应用里点一下授权
     *
     * 只有 [Capability.special] 为 false 的能力能走这条路: 特殊访问那些系统不接受运行时申请, 只能
     * 打开它那一页
     *
     * **不去看 `shouldShowRequestPermissionRationale`**: 曾经用"不该再问"当闸, 而它在真机上会
     * 因为设备或版本不同而给出不同的答案 —— 结果是点一下什么都不发生 (实测: 相机那一条点了连系统框
     * 都不弹)。这里改成只要没授权就直接申请: 系统自己会在"拒绝过且不再问"时静默回调, 而那条路至少
     * 会走完一次请求, 应用那一侧也就知道该把状态重算一遍
     */
    fun ask(activity: Activity, capability: Capability): Boolean {
        if (capability.special || capability.permissions.isEmpty()) return false
        if (PermissionCatalog.state(activity, capability) == Grant.GRANTED) return false
        PermissionRequests.request(capability.permissions)
        return true
    }

    /**
     * 打开这个能力要的那一页
     *
     * **三级回退**: 它自己那页 → 应用详情页 → 都没有就报 false 让人给一句提示。第二级是必需的:
     * `ACTION_MANAGE_WRITE_SETTINGS` 这类页面在个别 ROM 上没有接收者, 而"这一次点击没有下文"是
     * 用户唯一看得见的东西, 所以宁可退到一个一定存在、而且能看权限状态的地方
     */
    fun openSettings(context: Context, capability: Capability): Boolean {
        capability.settings?.let { if (start(context, it)) return true }
        return start(context, appDetails(capability.context))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: Throwable) {
        false
    }

    /** 应用详情页: 一定存在的那一页, 权限开关都在上面 */
    private fun appDetails(owner: String?): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${owner ?: APP_PACKAGE}".toUri(),
    )

    /** 本应用自己的包名, 给"没有携带包名"的能力当回退目标 */
    private const val APP_PACKAGE = "io.github.miuzarte.littlewhale"

    private const val FIX_MANIFEST = "this build does not declare the permission this capability needs"
}
