cmake_minimum_required(VERSION 3.22.1)

# GLM-ASR-Nano 的常驻转写进程: 静态链接 llama.cpp 与它自带的 mtmd, 出来的是一整个
# libglmasr.so (是程序不是库, 与 liblauncher.so 同一个做法), 由 app 侧 fork + exec
#
# llama.cpp 的源码在这一步拉: 版本与 sha256 都钉死, 换版本就换这两行 —— 与 sherpa-onnx
# 那个 AAR 是一样的写法 (见 app/build.gradle.kts 里的 fetchSherpaOnnxAar)
set(LW_LLAMA_TAG "b11435")
set(LW_LLAMA_SHA256 "177f3fdea416e5506599fffd736734d75ecd9d1876008792d06515c9a806cba0")

# 本地已经有一份 checkout 时用那一份 (离线构建、或者正在改那份源码时方便)
set(LW_LLAMA_CPP_DIR "" CACHE PATH "an existing llama.cpp source tree, empty fetches the pinned one")

if (NOT TARGET mtmd)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # OpenMP 会把 libomp.so 拖成一条运行时依赖, 而 APK 里没有那一份 (实测: 起来就
    # CANNOT LINK EXECUTABLE); ggml 自己的线程池够用, 关掉它
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

    # 这台设备是 armv9 (天玑 9300), 上一台是骁龙 8 Gen 2, 两边的核心都吃这几个扩展;
    # 不写这一行 ggml 会退回基线 armv8 内核, 量化矩阵乘直接慢一截
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    endif()

    if (LW_LLAMA_CPP_DIR)
        # 本地那一份要**已经打过 patch-short-window.cmake** 的改动 (自己改源码那条路)
        add_subdirectory(${LW_LLAMA_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)
    else()
        include(FetchContent)
        # 这一版 CMake 是 3.22.1 (AGP 指定的那几个之一), 它不认 DOWNLOAD_EXTRACT_TIMESTAMP,
        # 写了那一行就会被并进 URL_HASH 里报 "must be ALGO=value"
        FetchContent_Declare(llama_cpp
                URL "https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/${LW_LLAMA_TAG}"
                URL_HASH SHA256=${LW_LLAMA_SHA256}
                # 上游那份给每段音频补 30 秒静音, 这一步把窗口变成可调的 (见那个脚本里的长注释)
                PATCH_COMMAND ${CMAKE_COMMAND}
                        "-DLW_SOURCE_DIR=<SOURCE_DIR>"
                        -P "${CMAKE_CURRENT_LIST_DIR}/patch-short-window.cmake")
        FetchContent_MakeAvailable(llama_cpp)
    endif()
endif()

add_executable(glmasr glmasr_daemon.cpp)
set_target_properties(glmasr PROPERTIES
        OUTPUT_NAME "libglmasr"
        SUFFIX ".so"
        LINKER_LANGUAGE CXX)
target_link_libraries(glmasr llama mtmd)

# 这个二进制对 app 是个黑盒 (它只说协议那几行), 66 MB 的符号留在包里只是把 APK 撑大;
# 真要按地址定位崩溃时用 -DLW_GLMASR_KEEP_SYMBOLS=ON 再编一份
option(LW_GLMASR_KEEP_SYMBOLS "keep the GLM-ASR daemon symbols in the packaged binary" OFF)
if (NOT LW_GLMASR_KEEP_SYMBOLS)
    target_link_options(glmasr PRIVATE "-Wl,--strip-all")
endif()
