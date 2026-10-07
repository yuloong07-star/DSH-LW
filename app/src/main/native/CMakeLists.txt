cmake_minimum_required(VERSION 3.22.1)

# C++ 是给 glmasr 那一份: 它把 llama.cpp 与 mtmd 静态链进来 (见 glmasr/CMakeLists.txt)
project(littlewhale_native C CXX)

# The launcher is an executable that ships as a native library: Android 10 refuses to execute a
# file in the app's data directory, while anything unpacked into nativeLibraryDir is executable
# by every app domain, so the output is named liblauncher.so and the app execs that path
add_executable(launcher launcher.c)
set_target_properties(launcher PROPERTIES
        OUTPUT_NAME "liblauncher"
        SUFFIX ".so"
        LINKER_LANGUAGE C)

# Only android_log_print is needed; nothing here touches the NDK's own libraries
target_link_libraries(launcher log)

# **这一条不是优化, 是让它能用**: AGP 给 externalNativeBuild 的 Debug 变体传的是
# CMAKE_BUILD_TYPE=Debug, 也就是一个 -O 都没有, 而 llama.cpp 那套推理内核在 -O0 下慢几十倍
# (2026-10-06 实测, 同一台设备同一份权重, 1.5 秒的音频: -O0 要 137 秒, -O3 要 3.7 秒 —— 装机
# 之后那条"app 里认一段要等 225 秒"的账全在这儿)。所以这一份不跟 app 的变体走: 它是个黑盒,
# 调它不需要断点
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    string(APPEND CMAKE_C_FLAGS_DEBUG " -O3")
    string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O3")
endif()

# 语音识别的那一份常驻进程: 出来的 libglmasr.so 同样是"程序当 native lib 发"
add_subdirectory(glmasr)
