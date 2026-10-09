/**
 * 伴侣插件 (companion) 那一份样例 —— 批次 9 的 P1
 *
 * 它是一整个 APK, 与 LittleWhale 只共三样东西: 那份接口 (`:lwplugin-api`)、一条自定义权限、
 * 以及包里那份 `plugin.json` (在 `plugin/` 下, 装进 LW 的就是那个目录, 与这个 APK 分开走)
 *
 * 签名用的是与主 APK 同一把 debug keystore, 于是它是"同签名伴侣"那一档 (`signature` 级权限自动拿到);
 * 第三方伴侣要用户点一次系统弹窗 (`dangerous` 那一档, 见 docs/LW-软件插件协议.md 第 11 节)
 */
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.miuzarte.littlewhale.sample.companion"
    compileSdk {
        version = release(37)
    }

    val debugKeystore = providers.environmentVariable("LW_DEBUG_KEYSTORE")
        .orElse(providers.environmentVariable("ANDROID_SDK_HOME").map { "$it/.android/debug.keystore" })
        .getOrElse(rootProject.file("../.android/debug.keystore").absolutePath)
    signingConfigs {
        getByName("debug") {
            storeFile = file(debugKeystore)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "io.github.miuzarte.littlewhale.sample.companion"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}

dependencies {
    implementation(project(":lwplugin-api"))
}
