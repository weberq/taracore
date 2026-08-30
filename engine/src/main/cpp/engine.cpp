// Tara Core -- native inference engine.
// Copyright 2026 The Tara Core Authors. Licensed under the Apache License 2.0.
#include "engine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <mutex>

#include "ggml-backend.h"
#include "ggml.h"

#define TAG "TaraCore/Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace taracore {
namespace {

int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

/** Route ggml/llama's internal logging into logcat under our tag. */
void ggmlLogBridge(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) return;
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_print(prio, "TaraCore/ggml", "%s", text);
}

/**
 * Largest index <= `limit` at which `s` can be cut without splitting a UTF-8
 * codepoint. A single codepoint (an emoji, a CJK glyph) is routinely spread across
 * two or three tokens, so a naive per-token emit hands the JVM invalid bytes and
 * produces mojibake. Holding the incomplete tail back for one iteration fixes it.
 */
size_t utf8SafeEnd(const std::string &s, size_t limit) {
    if (limit > s.size()) limit = s.size();
    // Walk back over continuation bytes (10xxxxxx) to the lead byte of the last
    // sequence; keep it only if the whole sequence is present.
    size_t i = limit;
    size_t back = 0;
    while (i > 0 && back < 4) {
        const auto c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) { --i; ++back; continue; }  // continuation byte
        size_t need;
        if      ((c & 0x80) == 0x00) need = 1;
        else if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        else return i - 1;                    // invalid lead byte: drop it
        return (back + 1 >= need) ? limit : i - 1;
    }
    return limit;
}

std::once_flag g_initOnce;

/**
 * Name of the best non-CPU device ggml found, or "CPU". Used to tell the user which
 * backend actually initialised -- a `gpu` build on a device with no usable Vulkan
 * driver silently falls back, and the dashboard has to be able to show that.
 */
std::string detectBackend() {
    std::string best = "CPU";
    const size_t n = ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (dev == nullptr) continue;
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_GPU) {
            const char *name = ggml_backend_dev_name(dev);
            return name != nullptr ? std::string(name) : std::string("GPU");
        }
    }
    return best;
}

/**
 * Minimal ChatML rendering, used only when the GGUF carries no chat template.
 * Deliberately not configurable: a model without a template is already a fallback
 * path, and ChatML is the most widely understood of the ad-hoc formats.
 */
std::string renderChatMl(const std::vector<ChatMsg> &messages) {
    std::string out;
    for (const auto &m : messages) {
        out += "<|im_start|>";
        out += m.role;
        out += "\n";
        out += m.content;
        out += "<|im_end|>\n";
    }
    out += "<|im_start|>assistant\n";
    return out;
}

}  // namespace

void global_init() {
    std::call_once(g_initOnce, [] {
        ggml_log_set(ggmlLogBridge, nullptr);
        llama_log_set(ggmlLogBridge, nullptr);
        llama_backend_init();
        LOGI("llama.cpp backend initialised, version=%s", llama_version());
    });
}

Engine::~Engine() {
    unload();
}

LoadResult Engine::load(const std::string &path,
                        int32_t            nCtx,
                        int32_t            nThreads,
                        int32_t            nGpuLayers,
                        int32_t            nBatch,
                        bool               useMmap,
                        bool               useMlock) {
    std::lock_guard<std::mutex> lock(mutex_);
    LoadResult result;

    if (model_ != nullptr) {
        // Replacing a loaded model: free the old one first so peak RSS stays at
        // max(old, new) rather than old + new.
        if (ctx_ != nullptr) { llama_free(ctx_); ctx_ = nullptr; }
        llama_model_free(model_);
        model_ = nullptr;
        vocab_ = nullptr;
        kvTokens_.clear();
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    if (useMmap && useMlock)       mp.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    else if (useMmap)              mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    else if (useMlock)             mp.load_mode = LLAMA_LOAD_MODE_MLOCK;
    else                           mp.load_mode = LLAMA_LOAD_MODE_NONE;

    LOGI("loading model path=%s nCtx=%d threads=%d gpuLayers=%d loadMode=%s",
         path.c_str(), nCtx, nThreads, nGpuLayers, llama_load_mode_name(mp.load_mode));

    try {
        model_ = llama_model_load_from_file(path.c_str(), mp);
    } catch (const std::exception &e) {
        model_ = nullptr;
        result.error = std::string("exception while loading model: ") + e.what();
        LOGE("%s", result.error.c_str());
        return result;
    }

    if (model_ == nullptr) {
        result.error = "llama_model_load_from_file returned null (corrupt GGUF, "
                       "unsupported architecture, or out of memory)";
        LOGE("%s", result.error.c_str());
        return result;
    }

    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(nCtx);
    cp.n_batch         = static_cast<uint32_t>(nBatch);
    cp.n_ubatch        = static_cast<uint32_t>(std::min(nBatch, 512));
    cp.n_threads       = nThreads;
    cp.n_threads_batch = nThreads;
    cp.n_seq_max       = 1;
    cp.no_perf         = false;

    ctx_ = llama_init_from_model(model_, cp);
    if (ctx_ == nullptr) {
        llama_model_free(model_);
        model_ = nullptr;
        vocab_ = nullptr;
        result.error = "llama_init_from_model returned null (context too large for "
                       "available memory?)";
        LOGE("%s", result.error.c_str());
        return result;
    }

    nBatch_         = nBatch;
    modelSizeBytes_ = static_cast<int64_t>(llama_model_size(model_));
    backendName_    = detectBackend();
    kvTokens_.clear();

    char desc[256] = {0};
    llama_model_desc(model_, desc, sizeof(desc));

    result.ok             = true;
    result.modelSizeBytes = modelSizeBytes_;
    result.vocabSize      = llama_vocab_n_tokens(vocab_);
    result.nCtx           = static_cast<int32_t>(llama_n_ctx(ctx_));
    result.backendName    = backendName_;
    result.description    = desc;

    LOGI("model loaded: %s | %lld bytes | vocab %d | nCtx %d | backend %s",
         desc, static_cast<long long>(modelSizeBytes_), result.vocabSize,
         result.nCtx, backendName_.c_str());
    return result;
}

void Engine::unload() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (ctx_ != nullptr)   { llama_free(ctx_);        ctx_ = nullptr; }
    if (model_ != nullptr) { llama_model_free(model_); model_ = nullptr; }
    vocab_ = nullptr;
    kvTokens_.clear();
    kvTokens_.shrink_to_fit();
    LOGI("model unloaded");
}

std::vector<llama_token> Engine::tokenize(const std::string &text, bool addSpecial) const {
    if (vocab_ == nullptr) return {};
    // Negative return = required capacity; one retry is always enough.
    int32_t need = -llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()),
                                   nullptr, 0, addSpecial, /*parse_special=*/true);
    if (need <= 0) return {};
    std::vector<llama_token> out(static_cast<size_t>(need));
    int32_t n = llama_tokenize(vocab_, text.data(), static_cast<int32_t>(text.size()),
                               out.data(), need, addSpecial, /*parse_special=*/true);
    if (n < 0) return {};
    out.resize(static_cast<size_t>(n));
    return out;
}

std::string Engine::tokenToPiece(llama_token tok) const {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab_, tok, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab_, tok, big.data(), -n, 0, false);
        if (n < 0) return {};
        big.resize(static_cast<size_t>(n));
        return big;
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::string Engine::formatChat(const std::vector<ChatMsg> &messages) const {
    if (model_ == nullptr) return renderChatMl(messages);

    const char *tmpl = llama_model_chat_template(model_, /*name=*/nullptr);
    if (tmpl == nullptr) {
        LOGW("model carries no chat template; falling back to ChatML");
        return renderChatMl(messages);
    }

    std::vector<llama_chat_message> raw;
    raw.reserve(messages.size());
    size_t approx = 0;
    for (const auto &m : messages) {
        raw.push_back({m.role.c_str(), m.content.c_str()});
        approx += m.role.size() + m.content.size();
    }

    std::vector<char> buf(std::max<size_t>(approx * 2 + 512, 1024));
    int32_t n = llama_chat_apply_template(tmpl, raw.data(), raw.size(),
                                          /*add_ass=*/true, buf.data(),
                                          static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(tmpl, raw.data(), raw.size(), true, buf.data(),
                                      static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        LOGW("llama_chat_apply_template failed (%d); falling back to ChatML", n);
        return renderChatMl(messages);
    }
    return std::string(buf.data(), static_cast<size_t>(n));
}

llama_sampler *Engine::buildSampler(const GenParams &p) const {
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = false;
    llama_sampler *chain = llama_sampler_chain_init(scp);

    // Penalties first: the header warns that scanning the full vocabulary is slow,
    // but the repeat window is small (64) so this is cheap and it must see the
    // untruncated distribution to be meaningful.
    if (p.repeatPenalty != 1.0f && p.repeatLastN != 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            llama_vocab_n_tokens(vocab_), p.repeatLastN, p.repeatPenalty,
            /*penalty_freq=*/0.0f, /*penalty_present=*/0.0f));
    }

    if (p.temperature <= 0.0f) {
        // Greedy decoding: temperature 0 means "the most likely token", and the
        // truncation samplers below would be meaningless.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }

    if (p.topK > 0)    llama_sampler_chain_add(chain, llama_sampler_init_top_k(p.topK));
    if (p.topP < 1.0f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(p.topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(p.temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(p.seed));
    return chain;
}

int32_t Engine::reuseKvPrefix(const std::vector<llama_token> &tokens) {
    const size_t common = [&] {
        size_t i = 0;
        const size_t n = std::min(kvTokens_.size(), tokens.size());
        while (i < n && kvTokens_[i] == tokens[i]) ++i;
        return i;
    }();

    llama_memory_t mem = llama_get_memory(ctx_);

    // Always re-decode at least the final prompt token: llama_decode needs logits
    // for the token we are about to sample from, and a cached token's logits are
    // long gone.
    size_t keep = common;
    if (keep >= tokens.size()) keep = tokens.size() - 1;

    if (keep == 0) {
        llama_memory_clear(mem, /*data=*/true);
        kvTokens_.clear();
        return 0;
    }

    // Drop the divergent tail of sequence 0, keeping [0, keep).
    llama_memory_seq_rm(mem, /*seq_id=*/0, static_cast<llama_pos>(keep), -1);
    kvTokens_.resize(keep);
    LOGI("KV prefix reuse: %zu of %zu prompt tokens already resident", keep, tokens.size());
    return static_cast<int32_t>(keep);
}

GenStats Engine::generate(const std::string &prompt,
                          const GenParams   &params,
                          const TokenSink   &sink) {
    std::lock_guard<std::mutex> lock(mutex_);
    GenStats stats;
    cancel_.store(false, std::memory_order_relaxed);

    if (model_ == nullptr || ctx_ == nullptr) {
        stats.ok = false;
        stats.error = "no model loaded";
        return stats;
    }

    std::vector<llama_token> tokens = tokenize(prompt, /*addSpecial=*/true);
    if (tokens.empty()) {
        stats.ok = false;
        stats.error = "prompt tokenised to zero tokens";
        return stats;
    }

    const int32_t nCtx = static_cast<int32_t>(llama_n_ctx(ctx_));
    if (static_cast<int32_t>(tokens.size()) >= nCtx) {
        stats.ok = false;
        stats.error = "prompt of " + std::to_string(tokens.size()) +
                      " tokens exceeds the " + std::to_string(nCtx) + "-token context";
        return stats;
    }

    llama_sampler *smpl = buildSampler(params);
    if (smpl == nullptr) {
        stats.ok = false;
        stats.error = "failed to build sampler chain";
        return stats;
    }

    const int64_t tPromptStart = nowMs();
    int32_t       start        = reuseKvPrefix(tokens);

    // Feed the sampler the reused prefix so repeat penalties see the whole context.
    for (int32_t i = 0; i < start; ++i) llama_sampler_accept(smpl, tokens[i]);

    llama_batch batch = llama_batch_init(nBatch_, /*embd=*/0, /*n_seq_max=*/1);
    auto pushToken = [&](llama_token tok, llama_pos pos, bool wantLogits) {
        const int32_t i          = batch.n_tokens;
        batch.token[i]           = tok;
        batch.pos[i]             = pos;
        batch.n_seq_id[i]        = 1;
        batch.seq_id[i][0]       = 0;
        batch.logits[i]          = wantLogits ? 1 : 0;
        batch.n_tokens           = i + 1;
    };

    bool failed = false;
    for (int32_t i = start; i < static_cast<int32_t>(tokens.size()) && !failed; ) {
        batch.n_tokens = 0;
        const int32_t chunkEnd = std::min<int32_t>(i + nBatch_, static_cast<int32_t>(tokens.size()));
        for (int32_t j = i; j < chunkEnd; ++j) {
            // Only the very last prompt token needs logits -- that is where sampling starts.
            pushToken(tokens[j], j, j == static_cast<int32_t>(tokens.size()) - 1);
            llama_sampler_accept(smpl, tokens[j]);
        }
        const int32_t rc = llama_decode(ctx_, batch);
        if (rc != 0) {
            failed = true;
            stats.ok = false;
            stats.error = "llama_decode failed on prompt with code " + std::to_string(rc);
            LOGE("%s", stats.error.c_str());
            break;
        }
        i = chunkEnd;
        if (cancel_.load(std::memory_order_relaxed)) {
            stats.cancelled = true;
            break;
        }
    }

    stats.promptTokens = static_cast<int32_t>(tokens.size()) - start;
    stats.promptMs     = nowMs() - tPromptStart;

    if (failed) {
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        // The KV cache now holds an unknown partial state; drop it rather than
        // letting the next request reuse a prefix that may not be there.
        llama_memory_clear(llama_get_memory(ctx_), true);
        kvTokens_.clear();
        return stats;
    }

    kvTokens_ = tokens;

    const int64_t tGenStart = nowMs();
    std::string   accumulated;
    // Longest stop string we might have to hold back before emitting.
    size_t maxStopLen = 0;
    for (const auto &s : params.stop) maxStopLen = std::max(maxStopLen, s.size());
    size_t emitted = 0;  // bytes of `accumulated` already handed to the sink

    llama_pos pos = static_cast<llama_pos>(tokens.size());

    for (int32_t n = 0; n < params.maxTokens && !stats.cancelled; ++n) {
        if (cancel_.load(std::memory_order_relaxed)) { stats.cancelled = true; break; }
        if (pos >= nCtx) break;  // context exhausted

        const llama_token tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, tok)) break;

        llama_sampler_accept(smpl, tok);
        accumulated += tokenToPiece(tok);
        stats.genTokens++;

        // Stop strings are checked against the decoded text, not tokens, because a
        // stop string rarely aligns with a token boundary.
        bool hitStop = false;
        size_t cutAt = std::string::npos;
        for (const auto &s : params.stop) {
            if (s.empty()) continue;
            const size_t at = accumulated.find(s, emitted > s.size() ? emitted - s.size() : 0);
            if (at != std::string::npos) {
                hitStop = true;
                cutAt = std::min(cutAt, at);
            }
        }

        if (hitStop) {
            const size_t end = utf8SafeEnd(accumulated, cutAt);
            if (end > emitted) {
                if (!sink(accumulated.substr(emitted, end - emitted))) stats.cancelled = true;
            }
            accumulated.resize(cutAt);
            stats.stopped = true;
            break;
        }

        // Hold back the tail that could still turn out to be the head of a stop
        // string, so we never emit text we are about to retract.
        size_t safeEnd = accumulated.size() > maxStopLen
                             ? accumulated.size() - maxStopLen
                             : 0;
        safeEnd = utf8SafeEnd(accumulated, safeEnd);
        if (safeEnd > emitted) {
            if (!sink(accumulated.substr(emitted, safeEnd - emitted))) {
                stats.cancelled = true;
                break;
            }
            emitted = safeEnd;
        }

        batch.n_tokens = 0;
        pushToken(tok, pos, /*wantLogits=*/true);
        const int32_t rc = llama_decode(ctx_, batch);
        if (rc != 0) {
            stats.ok    = false;
            stats.error = "llama_decode failed during generation with code " + std::to_string(rc);
            LOGE("%s", stats.error.c_str());
            break;
        }
        kvTokens_.push_back(tok);
        pos++;
    }

    // Flush whatever was held back for stop-string lookahead.
    if (!stats.stopped && accumulated.size() > emitted) {
        const size_t end = utf8SafeEnd(accumulated, accumulated.size());
        if (end > emitted) sink(accumulated.substr(emitted, end - emitted));
    }

    stats.genMs = nowMs() - tGenStart;

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    LOGI("generate done: prompt=%d tok in %lldms, gen=%d tok in %lldms (%.1f tok/s)%s",
         stats.promptTokens, static_cast<long long>(stats.promptMs),
         stats.genTokens, static_cast<long long>(stats.genMs),
         stats.genMs > 0 ? (1000.0 * stats.genTokens / static_cast<double>(stats.genMs)) : 0.0,
         stats.cancelled ? " [cancelled]" : "");
    return stats;
}

}  // namespace taracore
