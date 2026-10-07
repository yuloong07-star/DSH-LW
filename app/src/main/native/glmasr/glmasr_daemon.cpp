// 常驻的 GLM-ASR-Nano 转写进程
//
// 这一个可执行文件当 native lib 发 (libglmasr.so), app 侧 fork + exec 它, 模型只在启动时
// 加载一次, 之后每段录音是一次 stdin/stdout 上的一问一答 (都是单行 JSON):
//
//   daemon -> app    {"ready":true,"padSeconds":4,"threads":6,...}   起好了
//   app -> daemon    {"wav":"/absolute/path/to/a.wav"}
//   daemon -> app    {"ok":true,"text":"...","ms":1234,"tokens":42}
//   app -> daemon    {"quit":true}                                   收工, 把那 1.7 GB 还回去
//
// 为什么不是一个 JNI 库: 与 liblauncher.so 同一个理由 —— 静态链接 llama.cpp 与 mtmd 之后
// 它就是普通 Linux 程序, app 侧只要会开管道; 走 JNI 要把同一套 C++ 再包一层, 还要操心
// 加载路径, 而这里要的只是"喂一段 wav, 拿一行字"
//
// 为什么窗口可以不是 30 秒: llama.cpp 那套 whisper 预处理会给每段音频补 30 秒静音, 于是
// 一句话与一段 30 秒的话一样贵 (实测天玑 9300 上都是 12 秒上下), 详见 --pad-seconds

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>

namespace {

/** 一句话的转写结果, 先把 JSON 里必须转义的几个字换掉 */
std::string jsonEscape(const std::string & value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (const unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[8];
                    std::snprintf(escaped, sizeof(escaped), "\\u%04x", c);
                    out += escaped;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

/** 协议那一行, 写完就刷 —— 对面在等这一行 */
void emit(const std::string & line) {
    std::fputs(line.c_str(), stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
}

void emitError(const std::string & detail) {
    emit("{\"ok\":false,\"error\":\"" + jsonEscape(detail) + "\"}");
}

/** llama.cpp 与 mtmd 的日志一律走 stderr: stdout 是协议那一层, 混进一行就等于协议错 */
void logToStderr(ggml_log_level level, const char * text, void * user) {
    (void) level;
    (void) user;
    std::fputs(text, stderr);
    std::fflush(stderr);
}

/** 极简的取值: 输入是 app 自己拼的, 只有 "键": "值" 这一种形状, 不为此拉一个 JSON 库 */
std::string fieldOf(const std::string & line, const std::string & key) {
    const std::string needle = "\"" + key + "\"";
    size_t at = line.find(needle);
    if (at == std::string::npos) return {};
    at = line.find(':', at + needle.size());
    if (at == std::string::npos) return {};
    at = line.find('"', at + 1);
    if (at == std::string::npos) return {};
    std::string value;
    for (size_t i = at + 1; i < line.size(); i++) {
        const char c = line[i];
        if (c == '\\' && i + 1 < line.size()) {
            const char next = line[++i];
            switch (next) {
                case 'n': value += '\n'; break;
                case 't': value += '\t'; break;
                case 'r': value += '\r'; break;
                default: value += next;
            }
            continue;
        }
        if (c == '"') break;
        value += c;
    }
    return value;
}

bool hasFlag(const std::string & line, const std::string & key) {
    const std::string needle = "\"" + key + "\"";
    const size_t at = line.find(needle);
    if (at == std::string::npos) return false;
    const size_t colon = line.find(':', at + needle.size());
    if (colon == std::string::npos) return false;
    size_t i = colon + 1;
    while (i < line.size() && std::isspace(static_cast<unsigned char>(line[i]))) i++;
    return line.compare(i, 4, "true") == 0;
}

std::string trim(const std::string & value) {
    size_t begin = 0;
    size_t end = value.size();
    while (begin < end && std::isspace(static_cast<unsigned char>(value[begin]))) begin++;
    while (end > begin && std::isspace(static_cast<unsigned char>(value[end - 1]))) end--;
    return value.substr(begin, end - begin);
}

std::string tokenToPiece(const llama_vocab * vocab, llama_token token) {
    char small[256];
    int32_t written = llama_token_to_piece(vocab, token, small, sizeof(small), 0, false);
    if (written >= 0) {
        return std::string(small, written);
    }
    std::vector<char> big(static_cast<size_t>(-written));
    written = llama_token_to_piece(vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, false);
    if (written <= 0) return {};
    return std::string(big.data(), written);
}

/** 贪心取一个 token: 转写要的是"最像的那句话", 不是花样, 所以与 CLI 的 --temp 0 同一条路 */
llama_token greedyToken(llama_context * ctx, const llama_vocab * vocab) {
    const float * logits = llama_get_logits_ith(ctx, -1);
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    llama_token best = 0;
    float bestScore = -INFINITY;
    for (int32_t i = 0; i < n_vocab; i++) {
        if (logits[i] > bestScore) {
            bestScore = logits[i];
            best = i;
        }
    }
    return best;
}

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    mtmd_context * vision = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string prompt;
    int n_predict = 256;
    int n_batch = 2048;
    int n_threads = 6;
};

/**
 * 认一段 wav
 *
 * 一次只认一段 (识别器不是线程安全的), 而且每次都把 KV cache 清干净 —— 上一句话不能影响
 * 下一句话, 这跟"聊天"是两回事
 */
void transcribe(Engine & engine, const std::string & wav) {
    const int64_t startedAt = ggml_time_ms();
    llama_memory_clear(llama_get_memory(engine.context), true);

    mtmd_helper_bitmap_wrapper media = mtmd_helper_bitmap_init_from_file(
        engine.vision, wav.c_str(), false, mtmd_helper_init_opt_default());
    if (media.bitmap == nullptr) {
        emitError("cannot read " + wav);
        return;
    }

    // 与模型自带的 chat template 渲染出来的那一串逐字对齐 (对照 CLI 的 -v 输出核过):
    // <|user|>\n<__media__>Please transcribe this audio into text\n<|assistant|>\n
    const std::string marker = mtmd_default_marker();
    const std::string formatted = "<|user|>\n" + marker + engine.prompt + "\n<|assistant|>\n";

    std::vector<std::string> segments;
    size_t from = 0;
    while (true) {
        const size_t hit = formatted.find(marker, from);
        if (hit == std::string::npos) {
            segments.push_back(formatted.substr(from));
            break;
        }
        segments.push_back(formatted.substr(from, hit - from));
        from = hit + marker.size();
    }

    std::vector<mtmd_input_text> texts(segments.size());
    std::vector<mtmd_input_part> parts;
    for (size_t i = 0; i < segments.size(); i++) {
        texts[i] = { segments[i].c_str(), segments[i].size(), false, true };
        parts.push_back({ &texts[i], nullptr });
        if (i == 0 && media.bitmap != nullptr) {
            parts.push_back({ nullptr, media.bitmap });
        }
    }
    std::vector<const mtmd_input_part *> partPointers;
    partPointers.reserve(parts.size());
    for (const mtmd_input_part & part : parts) {
        partPointers.push_back(&part);
    }

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const int32_t tokenized = mtmd_tokenize_from_parts(
        engine.vision, chunks, partPointers.data(), static_cast<int32_t>(partPointers.size()), true);
    if (tokenized != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(media.bitmap);
        emitError("cannot tokenize " + wav);
        return;
    }

    llama_pos n_past = 0;
    const int32_t evaluated = mtmd_helper_eval_chunks(
        engine.vision, engine.context, chunks, 0, 0, engine.n_batch, true, &n_past);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(media.bitmap);
    if (evaluated != 0) {
        emitError("cannot run the audio encoder over " + wav);
        return;
    }

    std::string text;
    int generated = 0;
    for (int i = 0; i < engine.n_predict; i++) {
        const llama_token token = greedyToken(engine.context, engine.vocab);
        if (llama_vocab_is_eog(engine.vocab, token)) break;
        text += tokenToPiece(engine.vocab, token);
        generated++;
        llama_token next = token;
        if (llama_decode(engine.context, llama_batch_get_one(&next, 1)) != 0) {
            emitError("the decoder stopped early on " + wav);
            return;
        }
    }

    const int64_t elapsed = ggml_time_ms() - startedAt;
    emit("{\"ok\":true,\"text\":\"" + jsonEscape(trim(text)) +
         "\",\"ms\":" + std::to_string(elapsed) +
         ",\"tokens\":" + std::to_string(generated) + "}");
}

void usage() {
    std::fputs(
        "usage: glmasr -m <model.gguf> --mmproj <mmproj.gguf> [--pad-seconds N] [--threads N]\n"
        "              [--ctx N] [--max-tokens N] [--prompt TEXT]\n",
        stderr);
}

}  // namespace

int main(int argc, char ** argv) {
    Engine engine;
    std::string modelPath;
    std::string mmprojPath;
    int padSeconds = 4;
    int n_ctx = 4096;

    for (int i = 1; i < argc; i++) {
        const std::string arg = argv[i];
        const auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
        if (arg == "-m" || arg == "--model") {
            modelPath = next();
        } else if (arg == "--mmproj") {
            mmprojPath = next();
        } else if (arg == "-t" || arg == "--threads") {
            engine.n_threads = std::atoi(next().c_str());
        } else if (arg == "--pad-seconds") {
            padSeconds = std::atoi(next().c_str());
        } else if (arg == "--ctx") {
            n_ctx = std::atoi(next().c_str());
        } else if (arg == "--max-tokens") {
            engine.n_predict = std::atoi(next().c_str());
        } else if (arg == "--prompt") {
            engine.prompt = next();
        } else {
            usage();
            return 2;
        }
    }
    if (modelPath.empty() || mmprojPath.empty()) {
        usage();
        return 2;
    }
    if (padSeconds < 1 || padSeconds > 30) padSeconds = 30;
    if (engine.n_threads < 1) engine.n_threads = 1;
    if (engine.prompt.empty()) engine.prompt = "Please transcribe this audio into text";

    // 那 30 秒静音窗口的开关 (见 mtmd-audio.cpp 里读它的地方): 进程起来之前就要放好
    const std::string pad = std::to_string(padSeconds);
    setenv("LW_ASR_PAD_SECONDS", pad.c_str(), 1);

    llama_log_set(logToStderr, nullptr);
    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    engine.model = llama_model_load_from_file(modelPath.c_str(), modelParams);
    if (engine.model == nullptr) {
        emit("{\"ready\":false,\"error\":\"" + jsonEscape("cannot load the model at " + modelPath) + "\"}");
        return 1;
    }
    engine.vocab = llama_model_get_vocab(engine.model);

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = n_ctx;
    contextParams.n_batch = engine.n_batch;
    contextParams.n_ubatch = 512;
    contextParams.n_threads = engine.n_threads;
    contextParams.n_threads_batch = engine.n_threads;
    contextParams.no_perf = true;
    engine.context = llama_init_from_model(engine.model, contextParams);
    if (engine.context == nullptr) {
        emit("{\"ready\":false,\"error\":\"cannot create the decoder context\"}");
        return 1;
    }

    mtmd_context_params visionParams = mtmd_context_params_default();
    visionParams.use_gpu = false;
    visionParams.n_threads = engine.n_threads;
    visionParams.print_timings = false;
    visionParams.warmup = false;
    engine.vision = mtmd_init_from_file(mmprojPath.c_str(), engine.model, visionParams);
    if (engine.vision == nullptr) {
        emit("{\"ready\":false,\"error\":\"" + jsonEscape("cannot load the audio encoder at " + mmprojPath) + "\"}");
        return 1;
    }

    emit("{\"ready\":true,\"padSeconds\":" + pad +
         ",\"threads\":" + std::to_string(engine.n_threads) +
         ",\"ctx\":" + std::to_string(n_ctx) +
         ",\"maxTokens\":" + std::to_string(engine.n_predict) + "}");

    std::string line;
    while (std::getline(std::cin, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (trim(line).empty()) continue;
        if (hasFlag(line, "quit")) break;
        if (hasFlag(line, "ping")) {
            emit("{\"ok\":true,\"pong\":true}");
            continue;
        }
        const std::string wav = fieldOf(line, "wav");
        if (wav.empty()) {
            emitError("the request names no wav");
            continue;
        }
        transcribe(engine, wav);
    }

    mtmd_free(engine.vision);
    llama_free(engine.context);
    llama_model_free(engine.model);
    llama_backend_free();
    return 0;
}
