package io.github.yuloong07star.luwi.plugin.api

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/**
 * LW 让插件够到设备的那一侧 —— 插件**只能**从这里够, 没有别的路
 *
 * 主进程在 `attach` 那一次把它的 binder 交给插件 (见 [LwPluginApi.Attach]), 插件的每一次能力调用都走
 * [call]: 声明与授权都在主进程那一侧逐次对照, 参数与结果都进审计
 */
abstract class ILwPluginContext : Binder() {
    /** 一个能力一次调用; 没声明、没授权、或这一版还没接通的, 都回一句点名的话 */
    abstract fun call(capability: String, args: Bundle?): Bundle

    /** 读插件自己那一段设置 (没有的键用 [defaults] 里那份填) */
    abstract fun settings(defaults: Bundle?): Bundle

    /** 插件自己的一小块存储: `op` 是 `get` / `put` / `list` / `remove` */
    abstract fun store(op: String, args: Bundle?): Bundle

    /** 往 LW 的日志里写一行 (它会带插件 id 落进 `DshHost` 那份日志) */
    abstract fun log(level: Int, message: String)

    /** API 版本, 与 `plugin.json` 的 `api` 对照 */
    abstract fun api(): Int

    protected override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            CODE_CALL, CODE_SETTINGS, CODE_STORE, CODE_LOG, CODE_API -> {
                data.enforceInterface(LwPluginApi.DESCRIPTOR_CONTEXT)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        val loader = javaClass.classLoader
        when (code) {
            CODE_CALL -> write(reply) { call(data.readString().orEmpty(), data.readBundle(loader)) }
            CODE_SETTINGS -> write(reply) { settings(data.readBundle(loader)) }
            CODE_STORE -> write(reply) { store(data.readString().orEmpty(), data.readBundle(loader)) }
            CODE_API -> {
                reply?.writeNoException()
                reply?.writeInt(api())
            }
            else -> {
                log(data.readInt(), data.readString().orEmpty())
                reply?.writeNoException()
            }
        }
        return true
    }

    private inline fun write(reply: Parcel?, result: () -> Bundle) {
        reply?.writeNoException()
        reply?.writeBundle(result())
    }

    companion object {
        const val CODE_CALL = 1
        const val CODE_SETTINGS = 2
        const val CODE_STORE = 3
        const val CODE_LOG = 4
        const val CODE_API = 5

        fun asInterface(binder: IBinder?): ILwPluginContext? = when {
            binder == null -> null
            binder is ILwPluginContext -> binder
            binder.queryLocalInterface(LwPluginApi.DESCRIPTOR_CONTEXT) is ILwPluginContext ->
                binder.queryLocalInterface(LwPluginApi.DESCRIPTOR_CONTEXT) as ILwPluginContext
            else -> Proxy(binder)
        }
    }

    private class Proxy(private val remote: IBinder) : ILwPluginContext() {
        override fun call(capability: String, args: Bundle?): Bundle =
            transactBundle(CODE_CALL) { writeString(capability); writeBundle(args) }
                ?: LwPluginResults.fail("LW 那一侧没有回东西")

        override fun settings(defaults: Bundle?): Bundle =
            transactBundle(CODE_SETTINGS) { writeBundle(defaults) } ?: Bundle()

        override fun store(op: String, args: Bundle?): Bundle =
            transactBundle(CODE_STORE) { writeString(op); writeBundle(args) }
                ?: LwPluginResults.fail("LW 那一侧没有回东西")

        override fun log(level: Int, message: String) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(LwPluginApi.DESCRIPTOR_CONTEXT)
                data.writeInt(level)
                data.writeString(message)
                remote.transact(CODE_LOG, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun api(): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(LwPluginApi.DESCRIPTOR_CONTEXT)
                remote.transact(CODE_API, data, reply, 0)
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        private inline fun transactBundle(code: Int, write: Parcel.() -> Unit): Bundle? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(LwPluginApi.DESCRIPTOR_CONTEXT)
                data.write()
                remote.transact(code, data, reply, 0)
                reply.readException()
                reply.readBundle(javaClass.classLoader)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
