package io.github.miuzarte.littlewhale.plugin.api

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/**
 * 插件让 LW 调的那一侧
 *
 * **手写 Binder, 不是 AIDL**: app 模块里出现 `.aidl` 就会生成 Java, 而 Java 编译会把 AGP 那条坏掉的
 * 资源管线拉回构建 (`channel/LwPrivilegedService.kt` 就是为这件事手写的)。这里与它同一个体例:
 * 描述符与 transaction code 写死, 信封一律是 `Bundle`
 *
 * 伴侣插件这么用: `class MyPlugin : ILwPlugin() { … }`, Service 的 `onBind` 回它;
 * LW 主进程这么用: `ILwPlugin.asInterface(binder)`
 */
abstract class ILwPlugin : Binder() {
    /** 自述, 必须与包里那份 `plugin.json` 一致, 不一致即拒绝启用 */
    abstract fun describe(): Bundle

    /** 事件: `attach` / `enable` / `disable` / `detach`, 回一句结果 */
    abstract fun lifecycle(event: String, args: Bundle?): Bundle

    /** 调一个工具, 回的 [`Bundle`] 见 [LwPluginResults] */
    abstract fun invoke(tool: String, args: Bundle?): Bundle

    /**
     * 收请求这一侧
     *
     * 不认识的 code 交回 [Binder.onTransact] (它会回 false, 于是调用方拿到
     * `TransactionTooLargeException` 之外的那条 `onTransact` 失败, 比静默成功好)
     */
    protected override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            CODE_DESCRIBE, CODE_LIFECYCLE, CODE_INVOKE -> {
                data.enforceInterface(LwPluginApi.DESCRIPTOR_PLUGIN)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        val result = when (code) {
            CODE_DESCRIBE -> describe()
            CODE_LIFECYCLE -> lifecycle(data.readString().orEmpty(), data.readBundle(javaClass.classLoader))
            else -> invoke(data.readString().orEmpty(), data.readBundle(javaClass.classLoader))
        }
        if (reply != null) {
            reply.writeNoException()
            reply.writeBundle(result)
        }
        return true
    }

    companion object {
        const val CODE_DESCRIBE = 1
        const val CODE_LIFECYCLE = 2
        const val CODE_INVOKE = 3

        /** 把一个远端 binder 包成本类的样子, 拿到的就是同一套方法 */
        fun asInterface(binder: IBinder?): ILwPlugin? = when {
            binder == null -> null
            binder is ILwPlugin -> binder
            binder.queryLocalInterface(LwPluginApi.DESCRIPTOR_PLUGIN) is ILwPlugin ->
                binder.queryLocalInterface(LwPluginApi.DESCRIPTOR_PLUGIN) as ILwPlugin
            else -> Proxy(binder)
        }
    }

    /** 本进程这一个是自己, 跨进程那一个是 [Proxy] */
    private class Proxy(private val remote: IBinder) : ILwPlugin() {
        override fun describe(): Bundle = transact(CODE_DESCRIBE) { }

        override fun lifecycle(event: String, args: Bundle?): Bundle =
            transact(CODE_LIFECYCLE) { writeString(event); writeBundle(args) }

        override fun invoke(tool: String, args: Bundle?): Bundle =
            transact(CODE_INVOKE) { writeString(tool); writeBundle(args) }

        private inline fun transact(code: Int, write: Parcel.() -> Unit): Bundle {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(LwPluginApi.DESCRIPTOR_PLUGIN)
                data.write()
                remote.transact(code, data, reply, 0)
                reply.readException()
                reply.readBundle(javaClass.classLoader)
                    ?: LwPluginResults.fail("插件那一侧没有回东西 (code $code)")
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
