#include <jni.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

static std::mutex g_mu;
static llama_model * g_model = nullptr;
static std::atomic<bool> g_cancel{false};

struct AbortState {
    std::chrono::steady_clock::time_point deadline;
};

static bool should_abort(void * data) {
    const auto * state = static_cast<const AbortState *>(data);
    return g_cancel.load() || std::chrono::steady_clock::now() >= state->deadline;
}

static std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_futo_inputmethod_latin_xlm_correction_CorrectionNative_nativeLoad(
        JNIEnv * env, jclass, jstring jpath) {
    const std::string path = jstring_to_utf8(env, jpath);
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_model) return JNI_TRUE;
    ggml_backend_load_all();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    return g_model ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_futo_inputmethod_latin_xlm_correction_CorrectionNative_nativeUnload(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mu);
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_futo_inputmethod_latin_xlm_correction_CorrectionNative_nativeCancel(
        JNIEnv *, jclass) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_futo_inputmethod_latin_xlm_correction_CorrectionNative_nativeGenerate(
        JNIEnv * env, jclass, jstring jprompt, jint n_predict, jlong timeout_ms) {
    const std::string prompt = jstring_to_utf8(env, jprompt);
    g_cancel.store(false);
    std::lock_guard<std::mutex> lock(g_mu);
    if (!g_model) return env->NewStringUTF("");

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, false, true);
    if (n_prompt <= 0) return env->NewStringUTF("");
    std::vector<llama_token> prompt_tokens((size_t) n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), prompt_tokens.data(),
                       (int) prompt_tokens.size(), false, true) < 0) {
        return env->NewStringUTF("");
    }

    const auto deadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(std::max<jlong>(1, timeout_ms));
    AbortState abort_state{deadline};

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_prompt + n_predict + 8;
    ctx_params.n_batch = std::max(n_prompt, 512);
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    ctx_params.abort_callback = should_abort;
    ctx_params.abort_callback_data = &abort_state;
    llama_context * ctx = llama_init_from_model(g_model, ctx_params);
    if (!ctx) return env->NewStringUTF("");

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t) prompt_tokens.size());
    std::string out;
    int n_pos = 0;
    llama_token new_token_id;
    const int budget = n_prompt + n_predict;
    bool timed_out = false;
    while (n_pos + batch.n_tokens < budget) {
        if (g_cancel.load()) break;
        if (std::chrono::steady_clock::now() >= deadline) {
            timed_out = true;
            break;
        }
        if (llama_decode(ctx, batch)) {
            timed_out = std::chrono::steady_clock::now() >= deadline;
            break;
        }
        n_pos += batch.n_tokens;
        new_token_id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token_id)) break;
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token_id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        out.append(buf, n);
        batch = llama_batch_get_one(&new_token_id, 1);
    }

    llama_sampler_free(smpl);
    llama_free(ctx);
    if (timed_out) return env->NewStringUTF("__CORNERSTONE_TIMEOUT__");
    return env->NewStringUTF(out.c_str());
}
