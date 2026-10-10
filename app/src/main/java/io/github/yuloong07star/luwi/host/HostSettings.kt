package io.github.yuloong07star.luwi.host

import android.content.Context
import android.content.SharedPreferences

/**
 * The choices that outlive one host process
 *
 * The LAN switch is read when the host spawns, so a change reaches the running host only through
 * a restart - which is what the panel that owns the switch says
 */
object HostSettings {
    /** Preference file name, one file for the whole app */
    private const val STORE = "luwi"

    /** Key of the switch that serves the GUI to the local network */
    private const val LAN_ACCESS = "lan-access"

    /** LW 插件的开发者模式 (批次 9): 开着才收未签名的本地包 */
    private const val DEVELOPER_PLUGINS = "plugin-developer"

    /**
     * 未签名的插件包让不让进来
     *
     * 默认关: 装一个插件就是信任它, 而未签名的包连"这是谁发的"都答不上来。开着的时候界面上
     * 常驻一句红字, 它只该在本地开发时开
     */
    fun developerPlugins(context: Context): Boolean =
        preferences(context).getBoolean(DEVELOPER_PLUGINS, false)

    fun setDeveloperPlugins(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(DEVELOPER_PLUGINS, enabled).apply()
    }

    /**
     * Whether the host binds every interface so another device can open the GUI
     * @param context context whose preferences are read.
     */
    fun lanAccess(context: Context): Boolean = preferences(context).getBoolean(LAN_ACCESS, false)

    /**
     * Persist the LAN choice for the next host start
     * @param context context whose preferences are written.
     * @param enabled the choice to remember.
     */
    fun setLanAccess(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(LAN_ACCESS, enabled).apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
