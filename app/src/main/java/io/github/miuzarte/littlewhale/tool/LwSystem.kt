package io.github.miuzarte.littlewhale.tool

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import io.github.miuzarte.littlewhale.util.Capability
import io.github.miuzarte.littlewhale.util.PermissionGate
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备与系统: 只读的那些信息, 加上音量、媒体、网络、屏幕与系统设置
 *
 * 读的部分几乎零风险, 所以它们的答案尽量给全 (模型拿着"设备是什么"能少猜几次)。写的部分守两条:
 * **改前先读原值**, 而且**写完读回**, 因为这台设备上"命令说成功而值没变"已经见过不止一次
 *
 * 需要特权的几条 (飞行模式、亮度那种受保护的设置) 走 [LwSystemCommand], 其余在这里用 Context 直接做
 */
internal object LwSystem {

    private val location = Capability(
        name = "位置",
        why = "lw_location 读最后已知位置与一次主动定位",
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    )

    /** 这个设备是什么, 以及它现在怎么样 */
    fun device(context: Context): JsonObject {
        val display = context.resources.displayMetrics
        val uptime = SystemClock.elapsedRealtime()
        val rows = listOf(
            "model" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.PRODUCT})",
            "android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.VERSION.CODENAME})",
            "abi" to Build.SUPPORTED_ABIS.joinToString(", "),
            "hardware" to (Build.HARDWARE + " / " + Build.BOARD),
            "screen" to "${display.widthPixels}x${display.heightPixels} at ${display.densityDpi}dpi" +
                " (${display.widthPixels / display.density}x${display.heightPixels / display.density} dp)",
            "cpu cores" to Runtime.getRuntime().availableProcessors().toString(),
            "locale" to (context.resources.configuration.locales[0]?.toString() ?: "unknown"),
            "time zone" to java.util.TimeZone.getDefault().id,
            "uptime" to "${uptime / 86_400_000} days ${uptime % 86_400_000 / 3_600_000} hours",
            "app" to "${context.packageName} ${appVersion(context)}",
            "users" to android.os.Process.myUserHandle().toString(),
        )
        return text(table(rows))
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (error: Throwable) {
        "unknown"
    }

    /** 电池、电源与屏幕 */
    fun battery(context: Context): JsonObject {
        val manager = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val rows = mutableListOf(
            "level" to (if (percent >= 0) "$percent%" else "unknown"),
            "charging" to when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
                else -> "unknown"
            },
            "plug" to when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "not plugged in"
            },
            "screen" to screenState(context),
            "power save" to (power?.isPowerSaveMode ?: false).toString(),
        )
        if (temperature != null && temperature != Int.MIN_VALUE) {
            rows += "temperature" to "%.1f C".format(temperature / 10.0)
        }
        if (voltage > 0) rows += "voltage" to "${voltage}mV"
        if (health >= 0) rows += "health" to when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        // 电流要 API 21+ 的属性; 拿不到就明说, 不要给一个 0 让人以为是测量值
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val now = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val average = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            if (now != null && now != Int.MIN_VALUE) rows += "current now" to "%.1f mA".format(now / 1000.0)
            if (average != null && average != Int.MIN_VALUE) {
                rows += "current average" to "%.1f mA".format(average / 1000.0)
            }
            val charge = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (charge != null && charge != Int.MIN_VALUE) {
                rows += "charge counter" to "${charge / 1000}mAh"
            }
        }
        return text(table(rows))
    }

    /** 屏幕现在什么样, 给几个工具共用 */
    fun screenState(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java) ?: return "unknown"
        val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            power.isInteractive
        } else {
            power.isScreenOn
        }
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        return buildString {
            append(if (interactive) "on" else "off")
            if (keyguard?.isKeyguardLocked == true) append(", locked")
        }
    }

    /** 存储与内存 */
    fun storage(context: Context): JsonObject {
        val rows = mutableListOf<String>()
        val rowsList = buildList {
            add("data" to statOf(Environment.getDataDirectory().absolutePath))
            add("shared" to statOf(Environment.getExternalStorageDirectory().absolutePath))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add("system" to statOf(Environment.getRootDirectory().absolutePath))
            }
        }
        rowsList.forEach { (name, line) -> rows += "$name: $line" }
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        rows += "memory total: ${bytes(info.totalMem)}"
        rows += "memory available: ${bytes(info.availMem)}"
        rows += "memory low: ${info.lowMemory}"
        val runtime = Runtime.getRuntime()
        rows += "heap: ${bytes(runtime.totalMemory() - runtime.freeMemory())} used of ${bytes(runtime.maxMemory())} max"
        return text(rows.joinToString("\n"))
    }

    private fun statOf(path: String): String = try {
        val stat = StatFs(path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        "${bytes(total - free)} used of ${bytes(total)} (${bytes(free)} free)"
    } catch (error: Throwable) {
        "unreadable: ${error.message}"
    }

    /** 负载与进程数: dsh 里 `os.cpus().length` 在这台设备上报 0, 所以这里的数字是它该对照的那一份 */
    fun running(context: Context): JsonObject {
        val rows = mutableListOf<String>()
        rows += "cpu cores: ${Runtime.getRuntime().availableProcessors()}"
        val load = try {
            android.os.Process.getExclusiveCores()
            null
        } catch (error: Throwable) {
            null
        }
        // /proc/loadavg 是内核给的, 读它比算一个自己的数靠谱
        val loadAverage = try {
            java.io.File("/proc/loadavg").readText().trim()
        } catch (error: Throwable) {
            "unreadable: ${error.message}"
        }
        rows += "load average, running/total, last pid: $loadAverage"
        val activity = context.getSystemService(android.app.ActivityManager::class.java)
        val memory = android.app.ActivityManager.MemoryInfo()
        activity?.getMemoryInfo(memory)
        rows += "low memory: ${memory.lowMemory}"
        rows += "process limit: ${activity?.memoryClass ?: -1} MiB per process"
        if (load != null) rows += "exclusive cores: $load"
        return text(rows.joinToString("\n"))
    }

    /** 音量: 五路各自的档位, 以及静音状态 */
    fun volume(context: Context, request: JsonObject): JsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: unavailable("volume control", "this device has no audio manager")
        val streams = mapOf(
            "media" to AudioManager.STREAM_MUSIC,
            "ring" to AudioManager.STREAM_RING,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "alarm" to AudioManager.STREAM_ALARM,
            "call" to AudioManager.STREAM_VOICE_CALL,
        )
        val op = request.string("op", "get")
        if (op == "get") {
            val rows = streams.map { (name, stream) ->
                val max = audio.getStreamMaxVolume(stream)
                val now = audio.getStreamVolume(stream)
                name to "$now of $max${if (now == 0) " (silent)" else ""}"
            } + ("mode" to when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            })
            return text(table(rows))
        }
        val stream = streams[request.string("system", "media")]
            ?: throw IllegalArgumentException(
                "system has to be one of ${streams.keys.joinToString(", ")}",
            )
        when (op) {
            "set" -> {
                val max = audio.getStreamMaxVolume(stream)
                val before = audio.getStreamVolume(stream)
                val level = request.int("level", before).coerceIn(0, max)
                audio.setStreamVolume(stream, level, 0)
                // 这台设备上"命令成功而值没变"见过不止一次, 所以写完读回
                val after = audio.getStreamVolume(stream)
                return text(
                    "volume of ${request.string("system", "media")}: $before -> $after (asked for" +
                        " $level, max $max)${if (after != level) ", which the device did not keep" else ""}",
                )
            }

            "adjust" -> {
                val direction = when (request.string("direction", "up")) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
                    else -> throw IllegalArgumentException("direction has to be up, down or mute")
                }
                val before = audio.getStreamVolume(stream)
                audio.adjustStreamVolume(stream, direction, 0)
                return text("volume $before -> ${audio.getStreamVolume(stream)}")
            }

            "mode" -> {
                val mode = when (request.string("mode", "normal")) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    "normal" -> AudioManager.RINGER_MODE_NORMAL
                    else -> throw IllegalArgumentException("mode has to be silent, vibrate or normal")
                }
                audio.ringerMode = mode
                return text("ringer mode is now ${request.string("mode", "normal")}")
            }

            else -> throw IllegalArgumentException("op has to be get, set, adjust or mode, not \"$op\"")
        }
    }

    /**
     * 媒体控制
     *
     * 走 keycode 广播而不是 MediaController: 后者要拿到某个具体会话, 而"上一首"是系统自己的动作
     */
    fun media(context: Context, request: JsonObject): JsonObject {
        val key = when (val op = request.string("op")) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "playPause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> throw IllegalArgumentException(
                "op has to be play, pause, playPause, next, previous or stop, not \"$op\"",
            )
        }
        val audio = context.getSystemService(AudioManager::class.java)
            ?: unavailable("media control", "this device has no audio manager")
        audio.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key),
        )
        audio.dispatchMediaKeyEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key),
        )
        return text(
            "sent ${request.string("op")} as a media key event; whether anything was playing is" +
                " not reported by the platform, so take a screenshot or read the screen to check",
        )
    }

    /** 网络: 开关状态、当前网络、IP */
    fun net(context: Context, request: JsonObject): JsonObject {
        val op = request.string("op", "get")
        if (op != "get") {
            // 改的那几条都在特权那一侧: 飞行模式与移动数据不是普通应用能写的
            return LwSystemCommand.network(request)
        }
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
        val rows = mutableListOf<Pair<String, String>>()
        rows += "active network" to when {
            capabilities == null -> "none"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        rows += "wifi" to when (connectivity?.isActiveNetworkMetered == true) {
            true -> "metered"
            false -> "unmetered"
        }
        rows += "airplane mode" to (Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1).toString()
        rows += "wifi ssid" to (wifiSsid(context) ?: "unknown")
        val addresses = ipAddresses()
        rows += "ip addresses" to if (addresses.isEmpty()) "none" else addresses.joinToString(", ")
        return text(table(rows))
    }

    /** 当前 WiFi 的名字, 拿不到就说拿不到 (Android 10 起它要位置权限) */
    private fun wifiSsid(context: Context): String? = try {
        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifi?.connectionInfo?.ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    } catch (error: Throwable) {
        null
    }

    /** 网卡上现在有哪些地址 */
    private fun ipAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.inetAddresses.toList().map { "${network.name}: ${it.hostAddress}" } }
            .filter { !it.contains(':') || it.count { c -> c == ':' } < 4 }
    } catch (error: Throwable) {
        emptyList()
    }

    /** 传感器: 列出来, 或者读一次值 */
    fun sensor(context: Context, request: JsonObject): JsonObject {
        val manager = context.getSystemService(SensorManager::class.java)
            ?: unavailable("sensors", "this device has no sensor service")
        val name = request.stringOrNull("name")
        if (name == null) {
            val rows = manager.getSensorList(Sensor.TYPE_ALL).map {
                it.name to "${it.stringType}${if (it.isWakeUpSensor) ", wake-up" else ""}"
            }
            if (rows.isEmpty()) return text("this device reports no sensors at all")
            return text(table(rows))
        }
        val wanted = manager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.name.equals(name, ignoreCase = true) || it.stringType.equals(name, ignoreCase = true)
        } ?: unavailable(
            "the sensor \"$name\"",
            "this device reports no sensor by that name; call lw_sensor with no name to list them",
        )
        val timeoutMs = request.int("timeoutMs", 1_500).coerceIn(100, 5_000)
        val latch = CountDownLatch(1)
        var reading: FloatArray? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                reading = event.values.copyOf()
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = manager.registerListener(listener, wanted, SensorManager.SENSOR_DELAY_UI)
        if (!registered) unavailable("the sensor \"$name\"", "the platform refused to register a listener")
        try {
            latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } finally {
            manager.unregisterListener(listener)
        }
        val values = reading
            ?: return text(
                "the ${wanted.name} sensor reported nothing within ${timeoutMs}ms, which usually" +
                    " means the device is not moving or the sensor is asleep",
            )
        return text(values.mapIndexed { index, value -> "value[$index] = $value" }.joinToString("\n"))
    }

    /** 位置: 最后已知, 或等一次主动定位 */
    fun location(context: Context, request: JsonObject): JsonObject {
        PermissionGate.refusal(context, location)?.let { throw IllegalStateException(it) }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: unavailable("location", "this device has no location service")
        val active = request.bool("fresh", false)
        if (active) {
            // 主动定位要一个 provider 真的在, 而且外面要有信号: 超时就如实说没有
            val provider = when {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            } ?: unavailable("a fresh fix", "location is turned off on this device")
            val timeout = request.int("timeoutMs", 10_000).coerceIn(1_000, 30_000)
            val latch = CountDownLatch(1)
            var fix: android.location.Location? = null
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    fix = location
                    latch.countDown()
                }

                override fun onProviderDisabled(provider: String) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                @Deprecated("the platform's own signature")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            }
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
                latch.await(timeout.toLong(), TimeUnit.MILLISECONDS)
            } catch (error: SecurityException) {
                throw IllegalStateException("the location request was refused: ${error.message}")
            } finally {
                manager.removeUpdates(listener)
            }
            val found = fix ?: return text(
                "no fix within ${timeout}ms from $provider: indoors or with location off this is" +
                    " normal rather than an error",
            )
            return text(describe(found) + " (fresh, from $provider)")
        }
        val last = manager.allProviders.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)?.let { provider to it }
            } catch (error: SecurityException) {
                null
            }
        }.maxByOrNull { it.second.time }
            ?: return text(
                "no last known location: this device has not had a fix since it booted. Pass" +
                    " fresh=true to wait for one (which needs the location permission and, usually," +
                    " being outdoors)",
            )
        return text(describe(last.second) + " (last known, from ${last.first})")
    }

    private fun describe(fix: android.location.Location): String = table(
        listOf(
            "latitude" to fix.latitude.toString(),
            "longitude" to fix.longitude.toString(),
            "accuracy" to "${fix.accuracy}m",
            "age" to "${(System.currentTimeMillis() - fix.time) / 1000}s ago",
        ),
    )

    /** 系统设置: 亮度、超时、旋转锁定、字体缩放 / 蓝牙、NFC */
    fun system(context: Context, request: JsonObject): JsonObject {
        val op = request.string("op")
        val key = request.string("key", "")
        return when (op) {
            "get" -> text(getSetting(context, key))
            "set" -> setSetting(context, request, key)
            else -> throw IllegalArgumentException("op has to be get or set, not \"$op\"")
        }
    }

    /** 这些键是这一版支持的全部, 别的直接说不支持而不是含糊过去 */
    private val readable = mapOf(
        "brightness" to "brightness",
        "screen_off_timeout" to "screen_off_timeout",
        "accelerometer_rotation" to "accelerometer_rotation",
        "font_scale" to "font_scale",
        "bluetooth" to "bluetooth_on",
        "nfc" to "nfc_on",
        "airplane_mode" to "airplane_mode_on",
    )

    private fun getSetting(context: Context, key: String): String {
        if (key.isEmpty()) {
            return readable.keys.joinToString(", ") { name ->
                "$name = ${readSetting(context, name)}"
            }
        }
        if (key !in readable) {
            throw IllegalArgumentException(
                "key has to be one of ${readable.keys.joinToString(", ")}, not \"$key\"",
            )
        }
        return "$key = ${readSetting(context, key)}"
    }

    private fun readSetting(context: Context, name: String): String {
        val resolver = context.contentResolver
        // 每条都读成字符串: 返回给模型的东西是同一个形状, 免得上面拼句子时还要分类型
        return when (name) {
            "brightness" -> Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                .toString()

            "screen_off_timeout" -> Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                -1,
            ).toString()

            "accelerometer_rotation" -> Settings.System.getInt(
                resolver,
                Settings.System.ACCELEROMETER_ROTATION,
                -1,
            ).toString()

            "font_scale" -> Settings.System.getFloat(resolver, Settings.System.FONT_SCALE, -1f)
                .toString()

            "bluetooth" -> Settings.Global.getInt(resolver, Settings.Global.BLUETOOTH_ON, -1)
                .toString()

            "nfc" -> Settings.Global.getInt(resolver, "nfc_on", -1).toString()
            "airplane_mode" -> Settings.Global.getInt(
                resolver,
                Settings.Global.AIRPLANE_MODE_ON,
                -1,
            ).toString()

            else -> "unknown"
        }
    }

    private fun setSetting(context: Context, request: JsonObject, key: String): JsonObject {
        if (key !in readable || key == "bluetooth" || key == "nfc") {
            throw IllegalArgumentException(
                "this call can set " +
                    listOf("brightness", "screen_off_timeout", "accelerometer_rotation", "font_scale")
                        .joinToString(", ") + ", not \"$key\"",
            )
        }
        val value = request.int("value", Int.MIN_VALUE)
        if (value == Int.MIN_VALUE) throw IllegalArgumentException("set has to name value")
        val before = readSetting(context, key)
        // 三条普通设置应用自己写得动; 写不动的会抛 SecurityException, 那时如实说是权限问题
        try {
            when (key) {
                "brightness" -> Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    value.coerceIn(0, 255),
                )

                "screen_off_timeout" -> Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    value,
                )

                "accelerometer_rotation" -> Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    value,
                )

                "font_scale" -> Settings.System.putFloat(
                    context.contentResolver,
                    Settings.System.FONT_SCALE,
                    value / 100f,
                )
            }
        } catch (error: SecurityException) {
            throw IllegalStateException(
                "$key is a protected setting: grant 修改系统设置 in the app's own Settings ->" +
                    " Permissions, or run tools/lw-install.ps1 -Perms (${error.message})",
            )
        }
        val after = readSetting(context, key)
        return text(
            "$key: $before -> $after (asked for $value)" +
                if (before == after) ", which this device did not keep" else "",
        )
    }
}
