package io.github.miuzarte.littlewhale.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

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
     * 打开它那一页. 所以这里返回 false 时 caller 应该去开 [Capability.settings]
     *
     * 已经"拒绝且不再问"时不重复申请 (Android 11 起系统会静默拒绝, 白弹一次), 直接返回 false
     */
    fun ask(activity: Activity, capability: Capability): Boolean {
        if (capability.special || capability.permissions.isEmpty()) return false
        if (PermissionCatalog.state(activity, capability) == Grant.GRANTED) return false
        val permanently = capability.permissions.any { permission ->
            activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED &&
                !activity.shouldShowRequestPermissionRationale(permission)
        }
        if (permanently) return false
        PermissionRequests.request(capability.permissions)
        return true
    }

    /** 打开这个能力要的那一页系统设置 */
    fun openSettings(context: Context, capability: Capability): Boolean {
        val intent = capability.settings ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (error: Throwable) {
            false
        }
    }

    private const val FIX_MANIFEST = "this build does not declare the permission this capability needs"
}
