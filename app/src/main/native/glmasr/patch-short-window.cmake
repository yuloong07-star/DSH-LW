# 给拉下来的那一份 llama.cpp 打上"窗口可调"的改动
#
# 上游的 mtmd 找 whisper 那套做法: 不管录音多长, 都先补 30 秒静音 (480000 个样本), 于是
# 一句"打开设置"与一段 30 秒的话在编码器那里一样贵 —— 实测天玑 9300 上都是 12 秒上下。
# 这里的改动把那个常数变成一个环境变量 (由 libglmasr.so 的 --pad-seconds 放进环境),
# 顺带修掉两处只在"整窗"下才成立的假设:
#
#   1. mel 长度要对齐到 8 帧的倍数 —— 图里按向上取整算, 而 clip_n_output_tokens 按向下取整,
#      两者只在 8 的倍数上相等, 否则 clip_encode 直接 GGML_ABORT("Invalid number of output tokens")
#   2. 最后那一小段也要发出去 (同样补齐到 8 帧), 上游在这里是整段丢掉, 于是超过一个窗口的
#      录音会缺尾巴
#
# 被 pin 的那一版源码对不上就**直接失败**: 安静地用回 30 秒会让 APK 里那份慢得没有理由,
# 而那种错只有拿在手里才看得出来

if (NOT DEFINED LW_SOURCE_DIR)
    message(FATAL_ERROR "patch-short-window: LW_SOURCE_DIR is not set")
endif()

set(LW_AUDIO "${LW_SOURCE_DIR}/tools/mtmd/mtmd-audio.cpp")
if (NOT EXISTS "${LW_AUDIO}")
    message(FATAL_ERROR "patch-short-window: ${LW_AUDIO} is missing")
endif()

file(READ "${LW_AUDIO}" LW_SOURCE)

if (LW_SOURCE MATCHES "LW_ASR_PAD_SECONDS")
    message(STATUS "patch-short-window: already applied")
    return()
endif()

function(lw_replace needle replacement label)
    string(FIND "${LW_SOURCE}" "${needle}" at)
    if (at EQUAL -1)
        message(FATAL_ERROR
                "patch-short-window: ${label} does not look like the pinned upstream any more")
    endif()
    string(REPLACE "${needle}" "${replacement}" LW_SOURCE "${LW_SOURCE}")
    set(LW_SOURCE "${LW_SOURCE}" PARENT_SCOPE)
endfunction()

lw_replace(
        [[#include <cmath>
#include <cstdint>]]
        [[#include <cmath>
#include <cstdint>
#include <cstdlib>]]
        "the include block")

lw_replace(
        [[        int64_t stage_1_pad = params.sample_rate * 30;]]
        [[        // LittleWhale: 30 秒静音垫是"每句话都按 30 秒算"的根源, 调用方可以要一个短的
        int pad_seconds = 30;
        if (const char * asked = std::getenv("LW_ASR_PAD_SECONDS")) {
            const int value = std::atoi(asked);
            if (value >= 1 && value <= 30) {
                pad_seconds = value;
            }
        }
        int64_t stage_1_pad = (int64_t) params.sample_rate * pad_seconds;]]
        "the 30 second pad")

lw_replace(
        [[    const size_t frames_per_chunk = 3000;
    GGML_ASSERT((size_t) out_full.n_len > frames_per_chunk);
    for (size_t off = 0; off < (size_t) out_full.n_len; off += frames_per_chunk) {
        int64_t n_len = std::min((int64_t)frames_per_chunk, out_full.n_len - (int64_t)off);
        if (n_len < (int64_t)frames_per_chunk) {
            break;  // last incomplete chunk will always be a padded chunk, safe to ignore
        }

        mtmd_audio_mel out_chunk;
        out_chunk.n_len     = n_len;
        out_chunk.n_mel     = out_full.n_mel;
        out_chunk.n_len_org = out_full.n_mel;  // unused
        out_chunk.data.reserve((size_t)out_chunk.n_mel * (size_t)out_chunk.n_len);

        for (int64_t i = 0; i < out_full.n_mel; i++) {
            auto src = out_full.data.begin() + (size_t)i * out_full.n_len + off;
            out_chunk.data.insert(out_chunk.data.end(), src, src + frames_per_chunk);
        }

        output.push_back(std::move(out_chunk));
    }]]
        [[    // LittleWhale: 图里向上取整而 token 数预估向下取整, 两边只在 8 的倍数上相等,
    // 所以短窗口要先对齐, 否则 clip_encode 会以 "Invalid number of output tokens" 中止
    const int64_t align = 8;
    const int64_t n_len_aligned = (out_full.n_len + align - 1) / align * align;
    if (n_len_aligned != out_full.n_len) {
        mtmd_audio_mel aligned;
        aligned.n_mel     = out_full.n_mel;
        aligned.n_len     = n_len_aligned;
        aligned.n_len_org = out_full.n_len_org;
        aligned.data.assign((size_t) n_len_aligned * out_full.n_mel, 0.0f);
        for (int64_t i = 0; i < out_full.n_mel; i++) {
            std::copy(out_full.data.begin() + (size_t) i * out_full.n_len,
                      out_full.data.begin() + (size_t) (i + 1) * out_full.n_len,
                      aligned.data.begin() + (size_t) i * n_len_aligned);
        }
        out_full = std::move(aligned);
    }
    const size_t frames_per_chunk = 3000;
    for (size_t off = 0; off < (size_t) out_full.n_len; off += frames_per_chunk) {
        int64_t n_len = std::min((int64_t)frames_per_chunk, out_full.n_len - (int64_t)off);

        mtmd_audio_mel out_chunk;
        // 最后那一小段也发出去, 但要补齐到 8 帧的倍数 —— 上游在这里整段丢掉
        out_chunk.n_len     = (n_len + align - 1) / align * align;
        out_chunk.n_mel     = out_full.n_mel;
        out_chunk.n_len_org = out_full.n_mel;  // unused
        out_chunk.data.reserve((size_t)out_chunk.n_mel * (size_t)out_chunk.n_len);

        for (int64_t i = 0; i < out_full.n_mel; i++) {
            auto src = out_full.data.begin() + (size_t)i * out_full.n_len + off;
            out_chunk.data.insert(out_chunk.data.end(), src, src + n_len);
            out_chunk.data.insert(out_chunk.data.end(), (size_t)(out_chunk.n_len - n_len), 0.0f);
        }

        output.push_back(std::move(out_chunk));
    }]]
        "the mel chunking")

file(WRITE "${LW_AUDIO}" "${LW_SOURCE}")
message(STATUS "patch-short-window: mtmd-audio.cpp now takes LW_ASR_PAD_SECONDS")
