/**
 * LW 插件的对外接口那一份 (批次 9)
 *
 * 里面只有两个手写 Binder 抽象, 一份常量, 与几个把结果装进 `Bundle` 的小工具 —— **零依赖, 零资源**,
 * 所以 app 与第三方伴侣都能引它。对外发布的那份 `lwplugin-api-1.jar` 就是本模块 AAR 里的 `classes.jar`
 * (见 `docs/LW-软件插件协议.md` 第 9 节与第 0 节第 11 条)
 */
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.yuloong07star.luwi.plugin.api"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
}
