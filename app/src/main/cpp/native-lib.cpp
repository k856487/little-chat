#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <algorithm>
#include <atomic>
#include <android/log.h>
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LittleChat", __VA_ARGS__)
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::mutex g_mutex;
static int g_context = 2048;
static std::atomic<bool> g_cancel_generation(false);

static void reset_context(int context_size) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    llama_context_params p = llama_context_default_params();
    p.n_ctx = (uint32_t)context_size;
    p.n_batch = (uint32_t)std::min(context_size, 512);
    g_ctx = llama_init_from_model(g_model, p);
    g_context = context_size;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_k856487_littlechat_NativeLlm_loadModel(JNIEnv* env, jobject, jstring path, jint contextSize) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_init();
    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(cpath, mp);
    env->ReleaseStringUTFChars(path, cpath);
    if (!g_model) { LOGE("Failed to load model"); return JNI_FALSE; }
    reset_context(contextSize);
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_k856487_littlechat_NativeLlm_generate(JNIEnv* env, jobject, jstring prompt, jint maxTokens, jboolean thinkingMode, jobject progressCallback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel_generation.store(false);
    if (!g_model || !g_ctx) return env->NewStringUTF("模型尚未加载，请检查模型文件后重试。");
    jclass callbackClass = env->GetObjectClass(progressCallback);
    jmethodID progressMethod = env->GetMethodID(callbackClass, "onProgress", "(Ljava/lang/String;)V");
    auto report = [&](const std::string& stage) {
        if (progressMethod) {
            jstring value = env->NewStringUTF(stage.c_str());
            env->CallVoidMethod(progressCallback, progressMethod, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    };
    report("正在整理输入并进行分词…");
    const char* cprompt = env->GetStringUTFChars(prompt, nullptr);
    std::string raw(cprompt);
    env->ReleaseStringUTFChars(prompt, cprompt);
    if (thinkingMode == JNI_TRUE) {
        raw += "\n请先充分分析问题，再给出准确的最终回答。";
    } else {
        raw += "\n/no_think\n请直接给出最终答案，不要输出思考过程。";
    }
    // Use the chat template embedded in the GGUF when available.
    llama_chat_message message = {"user", raw.c_str()};
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    int needed = llama_chat_apply_template(tmpl, &message, 1, true, nullptr, 0);
    std::string formatted;
    if (needed > 0) {
        std::vector<char> buf((size_t)needed + 1);
        llama_chat_apply_template(tmpl, &message, 1, true, buf.data(), (int32_t)buf.size());
        formatted.assign(buf.data());
    } else {
        formatted = "<|im_start|>user\n" + raw + "<|im_end|>\n<|im_start|>assistant\n";
    }
    // Qwen3-style templates expect /no_think in the assistant prefix, not in user text.
    if (thinkingMode != JNI_TRUE) {
        const std::string assistantPrefix = "<|im_start|>assistant\n";
        const size_t assistantPos = formatted.rfind(assistantPrefix);
        if (assistantPos != std::string::npos) {
            formatted.insert(assistantPos + assistantPrefix.size(), "/no_think\n");
        }
    }
    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    int n = -llama_tokenize(vocab, formatted.c_str(), (int32_t)formatted.size(), nullptr, 0, true, true);
    if (n <= 0) return env->NewStringUTF("无法对输入进行分词。");
    std::vector<llama_token> tokens((size_t)n);
    n = llama_tokenize(vocab, formatted.c_str(), (int32_t)formatted.size(), tokens.data(), (int32_t)tokens.size(), true, true);
    if (n < 0) return env->NewStringUTF("输入过长，请减少对话内容或增大上下文设置。");
    tokens.resize((size_t)n);
    if (tokens.size() + 8 >= (size_t)g_context) return env->NewStringUTF("输入内容超过当前上下文长度，请缩短问题或调大上下文设置。");
    llama_memory_clear(llama_get_memory(g_ctx), true);
    report("输入已完成分词，正在执行模型推理…");
    // Decode the prompt in chunks no larger than the context's batch limit.
    const int32_t batchLimit = std::max(1, std::min(g_context, 512));
    for (size_t offset = 0; offset < tokens.size() && !g_cancel_generation.load();) {
        const size_t count = std::min((size_t)batchLimit, tokens.size() - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, (int32_t)count);
        if (offset != 0) {
            // Rebuild a batch with absolute token positions for chunks after the first.
            batch = llama_batch_init((int32_t)count, 0, 1);
            batch.n_tokens = (int32_t)count;
            for (int32_t j = 0; j < batch.n_tokens; ++j) {
                batch.token[j] = tokens[offset + (size_t)j];
                batch.pos[j] = (llama_pos)(offset + (size_t)j);
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = 0;
                batch.logits[j] = (offset + (size_t)j == tokens.size() - 1) ? 1 : 0;
            }
        }
        const int decodeResult = llama_decode(g_ctx, batch);
        if (offset != 0) llama_batch_free(batch);
        if (decodeResult != 0) {
            report("模型处理输入失败（llama_decode 错误），请降低上下文或检查内存。");
            return env->NewStringUTF("推理失败：模型无法处理输入，可能是内存不足或上下文过长。");
        }
        offset += count;
    }
    if (g_cancel_generation.load()) { report("已中断生成。"); return env->NewStringUTF(""); }
    report("输入处理完成，开始逐 token 生成…");
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    std::string output;
    for (int i = 0; i < maxTokens && tokens.size() + (size_t)i + 1 < (size_t)g_context && !g_cancel_generation.load(); ++i) {
        llama_token id = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[8192];
        int len = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (len > 0) output.append(piece, (size_t)len);
        if ((i + 1) % 8 == 0) report("模型正在生成 token：" + std::to_string(i + 1) + "/" + std::to_string(maxTokens));
        llama_sampler_accept(sampler, id);
        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }
    const bool wasCancelled = g_cancel_generation.load();
    llama_sampler_free(sampler);
    if (thinkingMode != JNI_TRUE) {
        // Defensive output filter: never expose a hidden reasoning block when thinking is off.
        size_t open = output.find("<think>");
        while (open != std::string::npos) {
            const size_t close = output.find("</think>", open + 7);
            if (close == std::string::npos) {
                output.erase(open);
                break;
            }
            output.erase(open, close + 8 - open);
            open = output.find("<think>");
        }
        // Remove any stray closing marker from malformed model output.
        size_t stray;
        while ((stray = output.find("</think>")) != std::string::npos) output.erase(stray, 8);
    }
    report(wasCancelled ? "已中断生成。" : (output.empty() ? "生成结束，但模型未输出可见文本。" : "生成完成。"));
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_k856487_littlechat_NativeLlm_cancelGeneration(JNIEnv*, jobject) {
    g_cancel_generation.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_k856487_littlechat_NativeLlm_unload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}
