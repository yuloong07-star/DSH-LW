package io.github.yuloong07star.luwi.plugin.api

import android.os.Bundle

/**
 * 协议里那些写死的字符串
 *
 * 这一份是**两侧共用的唯一一份**: LW 主进程按它校验与分发, 伴侣插件按它回话, 而
 * `tools/check-plugins.mjs` 拿它跟 `docs/LW-软件插件协议.md` 对齐 —— 手写 Binder 没有 AIDL 编译器
 * 帮着对描述符与编号, 所以这几个常量一处写错就会变成"调不通而看不出原因"
 */
object LwPluginApi {
    /** 协议版本, `plugin.json` 的 `protocol` 只认这一个 */
    const val PROTOCOL = "lw-plugin/1"

    /** API 版本, `plugin.json` 的 `api` 与 [ILwPluginContext.api] 比的就是它 */
    const val API_VERSION = 1

    /** 护住伴侣 Service 的那条自定义权限 (LW 自己也 uses-permission 它, 否则绑不上) */
    const val PLUGIN_PERMISSION = "io.github.yuloong07star.luwi.permission.PLUGIN"

    /** 伴侣 Service 的 intent action */
    const val BIND_ACTION = "io.github.yuloong07star.luwi.plugin.BIND"

    /** [ILwPlugin] 的 Binder 描述符 */
    const val DESCRIPTOR_PLUGIN = "io.github.yuloong07star.luwi.plugin.ILwPlugin"

    /** [ILwPluginContext] 的 Binder 描述符 */
    const val DESCRIPTOR_CONTEXT = "io.github.yuloong07star.luwi.plugin.ILwPluginContext"

    /** `describe()` 回的 [Bundle] 里那几个键 */
    object Plugin {
        const val ID = "id"
        const val NAME = "name"
        const val VERSION = "version"
        const val API = "api"
        const val TOOLS = "tools"
        const val CAPABILITIES = "capabilities"
        const val DANGEROUS = "dangerous"
    }

    /** `attach` 那一次传下去的 [Bundle] 里那几个键 */
    object Attach {
        const val CONTEXT = "context"
        const val DIR = "dir"
        const val LW = "lw"
        const val LANGUAGE = "language"
        const val MODE = "mode"
        const val GRANTS = "grants"
    }

    /** `lifecycle` 认得的事件名 */
    object Event {
        const val ATTACH = "attach"
        const val ENABLE = "enable"
        const val DISABLE = "disable"
        const val DETACH = "detach"
    }
}

/**
 * 一次工具调用或一次能力调用回的那一份 `Bundle`
 *
 * 形状与 `lw_*` 的返回对齐: 一句话给人看, 外加几张图的**路径** (工作区或插件自己目录里的),
 * 宿主读文件再挂进上下文 —— 图片本身不走 binder, 那边传二进制既慢又容易撞上 1 MB 的交易上限
 */
object LwPluginResults {
    const val OK = "ok"
    const val TEXT = "text"
    const val IMAGES = "images"
    const val DATA = "data"

    /** 成功: [text] 是给人看的那一句, [images] 是要挂进上下文的图 (绝对路径) */
    fun ok(text: String, images: List<String> = emptyList(), data: Bundle? = null): Bundle =
        Bundle().apply {
            putBoolean(OK, true)
            putString(TEXT, text)
            putStringArray(IMAGES, images.toTypedArray())
            if (data != null) putBundle(DATA, data)
        }

    /** 失败: 一样要有一句话, 而且那句话要能说清是哪一条不过 */
    fun fail(text: String): Bundle = Bundle().apply {
        putBoolean(OK, false)
        putString(TEXT, text)
        putStringArray(IMAGES, emptyArray())
    }

    fun isOk(result: Bundle?): Boolean = result?.getBoolean(OK) == true

    fun text(result: Bundle?): String = result?.getString(TEXT).orEmpty()

    fun images(result: Bundle?): List<String> =
        result?.getStringArray(IMAGES)?.filter { it.isNotBlank() }.orEmpty()

    fun data(result: Bundle?): Bundle? = result?.getBundle(DATA)
}
