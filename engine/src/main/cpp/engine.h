// Tara Core -- native inference engine.
// Copyright 2026 The Tara Core Authors. Licensed under the Apache License 2.0.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace taracore {

/** One-time process-wide backend/logging init. Idempotent. */
void global_init();

struct LoadResult {
    bool        ok             = false;
    std::string error;
    int64_t     modelSizeBytes = 0;
    int32_t     vocabSize      = 0;
    int32_t     nCtx           = 0;
    std::string backendName;
    std::string description;
};

struct GenParams {
    int32_t                  maxTokens     = 512;
    float                    temperature   = 0.8f;
    float                    topP          = 0.95f;
    int32_t                  topK          = 40;
    float                    repeatPenalty = 1.1f;
    int32_t                  repeatLastN   = 64;
    uint32_t                 seed          = LLAMA_DEFAULT_SEED;
    std::vector<std::string> stop;
};

struct GenStats {
    int32_t promptTokens = 0;
    int32_t genTokens    = 0;
    int64_t promptMs     = 0;
    int64_t genMs        = 0;
    bool    cancelled    = false;
    bool    stopped      = false;   // terminated by a stop string
    bool    ok           = true;
    std::string error;
};

struct ChatMsg {
    std::string role;
    std::string content;
};

/** Receives decoded UTF-8 pieces as they are produced. Returns false to abort. */
using TokenSink = std::function<bool(const std::string &)>;

/**
 * Owns one llama model + context. Not copyable. All public methods are safe to call
 * from any thread, but `generate` serialises on `mutex_` so only one generation runs
 * at a time -- llama contexts are not re-entrant.
 */
class Engine {
public:
    Engine() = default;
    ~Engine();

    Engine(const Engine &)             = delete;
    Engine &operator=(const Engine &)  = delete;

    LoadResult load(const std::string &path,
                    int32_t            nCtx,
                    int32_t            nThreads,
                    int32_t            nGpuLayers,
                    int32_t            nBatch,
                    bool               useMmap,
                    bool               useMlock);

    void unload();

    bool isLoaded() const { return model_ != nullptr && ctx_ != nullptr; }

    /**
     * Render chat messages with the model's built-in chat template, falling back to
     * ChatML when the GGUF carries none. Always ends with the assistant turn opener.
     */
    std::string formatChat(const std::vector<ChatMsg> &messages) const;

    /** Run a completion, streaming pieces to `sink`. Blocks until done. */
    GenStats generate(const std::string &prompt, const GenParams &params, const TokenSink &sink);

    /** Ask the running generation to stop. Takes effect within one token. */
    void requestCancel() { cancel_.store(true, std::memory_order_relaxed); }

    int64_t modelSizeBytes() const { return modelSizeBytes_; }
    const std::string &backendName() const { return backendName_; }

private:
    std::vector<llama_token> tokenize(const std::string &text, bool addSpecial) const;
    std::string              tokenToPiece(llama_token tok) const;
    llama_sampler           *buildSampler(const GenParams &params) const;

    /**
     * Keep the longest common prefix of `tokens` and the previous prompt in the KV
     * cache and drop the divergent tail. Returns the number of tokens already
     * present (i.e. the index at which decoding should resume).
     */
    int32_t reuseKvPrefix(const std::vector<llama_token> &tokens);

    mutable std::mutex       mutex_;
    std::atomic<bool>        cancel_{false};

    llama_model             *model_ = nullptr;
    llama_context           *ctx_   = nullptr;
    const llama_vocab       *vocab_ = nullptr;

    int32_t                  nBatch_        = 512;
    int64_t                  modelSizeBytes_ = 0;
    std::string              backendName_   = "CPU";

    // Token sequence currently resident in the KV cache of sequence 0.
    std::vector<llama_token> kvTokens_;
};

}  // namespace taracore
