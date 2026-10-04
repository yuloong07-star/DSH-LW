plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The dsh host tree is a build product of the submodule rather than of this repository, and the
// APK ships it as one archive the app extracts on first run, so the build grows two steps here
// instead of a checked-in directory. Both are keyed on the submodule pin and on its sources, so
// only a dsh change pays for the rebuild: a plain :app:assembleDebug reuses what it already has
// Edits to submodule files outside the globs below do not invalidate the tree; rerun with
// `--rerun-tasks` (or run tools/pack-host.mjs by hand and push it, see tools/push-host.mjs)
val dshCheckout = rootProject.layout.projectDirectory.dir("third_party/deepseek-harness")
val hostTree = layout.buildDirectory.dir("host-tree")
val hostAssets = layout.buildDirectory.dir("generated/host-assets")


// 语音转写要的那套 sherpa-onnx 不在 Maven 上: 上游只把它发成 GitHub Release 里的 AAR, 所以这
// 一个依赖由构建自己取。app/libs/sherpa-onnx.aar 放了本地文件就用那一份 (离线构建、或用自己
// 编的版本), 否则按下面的版本与 sha256 下载到 build/ 里, 校验过才算数
// 取 static-link-onnxruntime 那一版是有原因的: 本应用已经带了 onnxruntime-android-qnn (端侧
// OCR 用), 普通版 AAR 会再带一份同名的 libonnxruntime.so, 打成 APK 时会撞名
val sherpaVersion = "1.13.8"
val sherpaAar = layout.buildDirectory.file("sherpa-onnx/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar")
val sherpaAarLocal = rootProject.file("app/libs/sherpa-onnx.aar")
val sherpaAarSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"

fun sha256Of(file: java.io.File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val fetchSherpaOnnxAar = tasks.register("fetchSherpaOnnxAar") {
    group = "littlewhale"
    description = "Fetch the sherpa-onnx AAR the app links for on-device speech recognition"
    val target = sherpaAar.get().asFile
    outputs.file(target)
    doLast {
        if (target.isFile && sha256Of(target) == sherpaAarSha256) return@doLast
        target.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/" +
            "sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
        logger.lifecycle("fetching $url")
        java.net.URI(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256Of(target)
        if (actual != sherpaAarSha256) {
            target.delete()
            throw GradleException("the sherpa-onnx AAR is not the pinned one: sha256 $actual")
        }
    }
}

/** The submodule files whose change must invalidate the packed tree */
val dshSources = fileTree(dshCheckout.asFile) {
    include(
        "packages/*/*/src/**",
        "packages/*/*/package.json",
        "apps/*/src/**",
        "apps/*/package.json",
        "vendor/*/src/**",
        "vendor/*/package.json",
        "native/system/packages/*/src/**",
        "native/system/packages/*/package.json",
    )
}

/** Commit the tree is built from, which the pack step refuses to violate */
val dshPin: Provider<String> = providers.exec {
    commandLine("git", "-C", dshCheckout.asFile.absolutePath, "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val packHostTree = tasks.register<Exec>("packHostTree") {
    group = "littlewhale"
    description = "Rebuild dsh at the pinned commit and install the flat host tree the APK ships"
    workingDir = rootProject.projectDir
    commandLine(
        "node",
        "tools/pack-host.mjs",
        "--dsh", dshCheckout.asFile.absolutePath,
        "--out", hostTree.get().asFile.absolutePath,
    )
    inputs.file(rootProject.file("tools/pack-host.mjs"))
    // The plugin is copied into the tree rather than built, so its sources are an input too
    inputs.dir(rootProject.file("host-plugin"))
    // The image backend is copied over sharp the same way, so it is one as well
    inputs.dir(rootProject.file("image-backend"))
    inputs.property("dshPin", dshPin)
    inputs.files(dshSources)
    outputs.dir(hostTree)
}

val zipHostTree = tasks.register<Exec>("zipHostTree") {
    group = "littlewhale"
    description = "Archive the host tree and stamp its version for the app to compare"
    workingDir = rootProject.projectDir
    commandLine(
        "node",
        "tools/zip-host.mjs",
        "--tree", hostTree.get().asFile.absolutePath,
        "--out", hostAssets.get().asFile.absolutePath,
        "--dsh", dshCheckout.asFile.absolutePath,
    )
    dependsOn(packHostTree)
    inputs.file(rootProject.file("tools/zip-host.mjs"))
    inputs.dir(hostTree)
    outputs.dir(hostAssets)
}

android {
    namespace = "io.github.miuzarte.littlewhale"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.miuzarte.littlewhale"
        // 33 used to be forced by miuix-blur; the blur went away on 2026-09-22, so nothing in the
        // dependency graph demands it any more and this is now a choice. Lowering it is a separate
        // decision: the accessibility tree path needs API 30, and nothing here has been run below 33.
        minSdk = 33
        targetSdk = 37
        // 2.0.0: 虚拟屏在后台可用(保活面)、语音输入与输出、浮窗面板 A、唤醒词
        versionCode = 4
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndkVersion = "29.0.14206865"
        ndk {
            // Everything native in this app is arm64 only, the shipped runtime included, so
            // building the launcher for anything else would only add an ABI nothing can use
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }
    buildFeatures {
        compose = true
    }
    // The privileged launcher is the one native piece this app builds itself: it is a cmake
    // executable named liblauncher.so, because Android only executes what sits in nativeLibraryDir
    externalNativeBuild {
        cmake {
            path = file("src/main/native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            // Node ships as libnode.so in jniLibs and is exec'd from
            // nativeLibraryDir. That requires install-time extraction, otherwise
            // the libs stay compressed in the APK with nothing to exec.
            // QNN needs the same treatment for a different reason: fastrpc loads
            // libQnnHtpV73Skel.so by path, and ADSP_LIBRARY_PATH points at
            // nativeLibraryDir, so it has to be a real file
            useLegacyPackaging = true
            // qnn-runtime ships skels and stubs for six Hexagon generations; this device is v73, so
            // the other five are dead weight. libQnnHtpPrepare.so is the graph compiler and has to
            // stay for now because the `jit` path compiles the graph on the device
            excludes += listOf(
                "**/libQnnHtpV68*.so",
                "**/libQnnHtpV69*.so",
                "**/libQnnHtpV75*.so",
                "**/libQnnHtpV79*.so",
                "**/libQnnHtpV81*.so",
                // GPU / classic-DSP backends are another 11 MB and OCR only ever asks for HTP
                "**/libQnnGpu*.so",
                "**/libQnnDsp*.so",
            )
        }
    }
    androidResources {
        // The host archive is already deflated, so compressing it again buys nothing and the
        // app reads it as a plain stored asset
        noCompress += "zip"
    }
    // AGP 9 refuses Provider-backed source directories in the classic SourceSet API, so the
    // generated directory is named as a plain file and the dependency on the task filling it is
    // declared below
    sourceSets["main"].assets.srcDir(hostAssets.get().asFile)
}

// Every variant's asset merge has to wait for the archive. `tasks.named` fails the build if AGP
// ever renames that task, which is the failure a missing dependency would otherwise hide
// Every variant's asset merge has to wait for the archive. AGP registers the merge tasks after
// onVariants runs, so this matches them as they appear rather than naming one now; the app
// reports a missing or stale archive on its own, and `assembleDebug --dry-run` shows
// `merge<variant>Assets` in the graph if this ever needs rechecking
androidComponents {
    onVariants { variant ->
        tasks.matching { it.name == "merge${variant.name.replaceFirstChar(Char::uppercaseChar)}Assets" }
            .configureEach { dependsOn(zipHostTree) }
    }
}


// 取 AAR 那一步要排在编译之前: 上面用的是普通文件依赖, 它自己带不上 builtBy 这条边, 所以
// 这里把 preBuild 连过去。本地放了 app/libs/sherpa-onnx.aar 就不需要下载了
if (!sherpaAarLocal.isFile) {
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchSherpaOnnxAar) }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    // Miuix UI: theme, icons, nav, preferences, squircle. No miuix-blur: the app has no blur.
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.squircle)

    implementation(libs.kotlinx.serialization.json)

    // The privileged channel: Shizuku's API and provider, plus libsu for the root shell
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.libsu)

    // 端侧 OCR: PP-OCR 的 det + rec 跑在 ONNX Runtime 上, NPU 走 QNN EP
    // 版本是本机与 B:\Git\Inferencer 一起验过的组合 (那台小米 13 上真建起过 QNN session),
    // 不要随手升: onnxruntime-android-qnn 与 qnn-runtime 的版本是配对的
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.46.0")


    // 语音转写: sherpa-onnx 的 Android AAR (见上面的 fetchSherpaOnnxAar)。它自带
    // libsherpa-onnx-jni.so, 里面静态链接了 onnxruntime, 与 OCR 那套 onnxruntime-android
    // 各走各的, 不是同一份
    implementation(files(sherpaAarLocal.takeIf { it.isFile } ?: sherpaAar.get().asFile))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
