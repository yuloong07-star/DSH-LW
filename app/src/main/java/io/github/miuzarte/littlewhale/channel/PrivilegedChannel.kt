package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.os.IBinder
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the channel can say about itself, whether or not a privileged process is up right now */
data class ChannelState(
    val connected: Boolean,
    val backend: String?,
    val uid: Int?,
    val pid: Int?,
    val version: String?,
    val error: String?,
)

/** A probe: what the channel is, and what only the privileged side could read */
data class ChannelProbe(
    val state: ChannelState,
    val inputDevices: String?,
    /** The device's Android users, as the platform lists them, for telling a clone from its original */
    val users: String? = null,
)

/**
 * The app's side of the privileged channel
 *
 * It owns no mechanism of its own: a route is a connector plus the permission backend that says
 * whether the connector is worth trying, and the first route that produces a live binder wins.
 * root is tried first because this device has it and because it needs nothing installed
 *
 * Connecting is blocking by design. Every caller is a worker thread - the bridge's request thread
 * or a diagnostic - and the alternative, an asynchronous state machine with listeners, is exactly
 * the availability machinery step 3 leaves out
 */
object PrivilegedChannel {

    private val TAG = "LwChannel"

    private val monitor = Any()

    private var routes: List<Route> = emptyList()

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var service: LwServiceProxy? = null

    @Volatile
    private var backend: RemoteBackend? = null

    @Volatile
    private var activeRoute: Route? = null

    @Volatile
    private var lastError: String? = null

    /**
     * 应用级的 context, 给那些"不需要特权、但需要一个 Context"的工具用
     *
     * 通知、剪贴板、电池、系统设置这些都在 app 进程里自己做, 走这条通道只是为了把它们的结果按
     * 同一套协议回给 host。所以这里留一个只读的口子, 免得每个工具各自再持一份
     */
    @Volatile
    private var application: Context? = null

    /** 这个应用的 context, 没有就说明 [initialize] 还没被调过 */
    fun context(): Context? = application

    /** Build the routes once, from an application context */
    fun initialize(context: Context) {
        synchronized(monitor) {
            application = context.applicationContext
            if (routes.isNotEmpty()) return
            val app = context.applicationContext
            routes = listOf(
                Route(RootServiceConnector(app), RootAccessBackend),
                Route(ShizukuServiceConnector(app), ShizukuAccessBackend),
            )
        }
    }

    /**
     * Hold a live service, connecting one if there is none
     *
     * Blocking, and serialized: a second caller waits for the first attempt instead of starting a
     * competing privileged process
     */
    fun ensure(): LwServiceProxy? = synchronized(monitor) {
        live()?.let { return it }
        if (routes.isEmpty()) {
            lastError = "the channel was never initialized"
            return null
        }
        // 两条路各说各的为什么不行, 而且失败时**不留下最后一条的理由**: 以前 lastError 是 Shizuku
        // 那句, root 那半就丢了, 于是一台既没给 root 又没跑 Shizuku 的设备只会被告知"Shizuku 没授权"
        val refused = mutableListOf<String>()
        for (route in routes) {
            connect(route)?.let { return it }
            lastError?.let { refused += it }
        }
        lastError = NOTHING_TO_CONNECT + (if (refused.isEmpty()) "" else " (${refused.joinToString("; ")})")
        Log.w(TAG, lastError ?: NOTHING_TO_CONNECT)
        null
    }

    /** Read the current state without connecting anything */
    fun state(): ChannelState {
        val current = live()
        if (current == null) {
            return ChannelState(false, null, null, null, null, lastError ?: "no privileged process")
        }
        return try {
            ChannelState(
                connected = true,
                backend = backend?.label,
                uid = current.uid(),
                pid = current.pid(),
                version = current.version(),
                error = null,
            )
        } catch (error: Throwable) {
            // The process died between the liveness check and the calls
            drop("the privileged process went away while it was being asked", error)
            ChannelState(false, null, null, null, null, lastError)
        }
    }

    /** Connect if needed, then read what only the privileged side can see */
    fun probe(): ChannelProbe {
        val current = ensure()
        if (current == null) return ChannelProbe(state(), null)
        val devices = try {
            current.inputDevices()
        } catch (error: Throwable) {
            drop("reading the input devices failed", error)
            null
        }
        // A device with a cloned app has more than one user, and that is the only thing that
        // distinguishes the clone from what it was cloned from
        val users = try {
            current.users()
        } catch (error: Throwable) {
            Log.w(TAG, "reading the device's users failed", error)
            null
        }
        return ChannelProbe(state(), devices, users)
    }

    /** Release the privileged process and forget it */
    fun close() {
        synchronized(monitor) {
            val current = binder
            val route = activeRoute
            drop("closed on request", null)
            if (current != null && route != null) route.connector.disconnect(current)
        }
    }

    /** Try one route end to end, returning the service when it produced a live binder */
    private fun connect(route: Route): LwServiceProxy? {
        val label = route.connector.backend.label
        if (!route.permission.isAvailable()) {
            val reason = "$label is not installed, or it is not running"
            lastError = reason
            Log.i(TAG, reason)
            return null
        }
        if (!route.permission.isGranted() && !route.permission.requestPermission()) {
            val reason = "$label was not granted"
            lastError = reason
            Log.w(TAG, reason)
            return null
        }

        val arrived = CountDownLatch(1)
        var received: IBinder? = null
        var failure: Throwable? = null
        val callbacks = object : RemoteServiceConnectorBackend.Callbacks {
            override fun onConnected(from: RemoteBackend, remote: IBinder) {
                received = remote
                arrived.countDown()
            }

            override fun onDisconnected(from: RemoteBackend) {
                drop("the $label process disconnected", null)
            }

            override fun onError(from: RemoteBackend, error: Throwable) {
                failure = error
                arrived.countDown()
            }
        }

        route.connector.connect(callbacks)
        val budget = route.connector.worstCaseConnectMs + CONNECT_SLACK_MS
        if (!arrived.await(budget, TimeUnit.MILLISECONDS)) {
            val reason = "$label did not produce a binder within ${budget}ms"
            lastError = reason
            Log.w(TAG, reason)
            return null
        }
        val remote = received
        if (remote == null) {
            val reason = "$label failed: ${failure?.message ?: "no reason reported"}"
            lastError = reason
            Log.w(TAG, reason, failure)
            return null
        }

        val connected = LwServiceProtocol.proxy(remote)
        binder = remote
        service = connected
        backend = route.connector.backend
        activeRoute = route
        lastError = null
        Log.i(TAG, "$label connected")
        return connected
    }

    /** The service, but only while its process is still there */
    private fun live(): LwServiceProxy? {
        val current = service ?: return null
        val remote = binder ?: return null
        if (remote.pingBinder()) return current
        drop("the privileged process is gone", null)
        return null
    }

    /** Forget the connection, keeping the reason for the last failure */
    private fun drop(reason: String, error: Throwable?) {
        if (service != null) Log.w(TAG, reason, error)
        service = null
        binder = null
        backend = null
        activeRoute = null
        lastError = reason
    }

    /** One way of reaching a privileged process, with the check that says whether to try it */
    private data class Route(
        val connector: RemoteServiceConnectorBackend,
        val permission: RemoteAccessPermissionBackend,
    )

    /** Room for the spawn call and the cleanup that happens outside the connector's own wait */
    private const val CONNECT_SLACK_MS = 3_000L

    /**
     * 两条路都不通时给的那一句话
     *
     * 它要说的是"下一步做什么", 而不是"哪里不对": 屏幕那一整套工具全靠这条通道, 而一台设备上没有
     * root 也没有 Shizuku 时, 以前的答案只会说"Shizuku 没授权", 让人去装一个本来就没装的东西
     */
    private const val NOTHING_TO_CONNECT =
        "the screen side needs root (KernelSU / Magisk) or Shizuku, and neither is usable right now:" +
            " for root, allow this app in the root manager by hand - nothing pops up on its own to ask," +
            " and the app cannot even tell whether root is there until you do; for Shizuku, install it," +
            " start it, and allow this app when it asks"
}
