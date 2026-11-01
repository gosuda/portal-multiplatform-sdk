#include "PortalSampleLlm.h"
#include "llama.h"
#include "utf8.h"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {
constexpr int kContextTokens = 1024;
constexpr int kMaxTokens = 128;
constexpr size_t kMaxPromptBytes = 16 * 1024;
constexpr size_t kMaxTemplateBytes = 64 * 1024;

struct Failure : std::runtime_error {
    int code;
    Failure(int code, const char* message) : std::runtime_error(message), code(code) {}
};

// Returns the complete UTF-8 prefix. An unfinished final scalar is permitted
// only for generated output: a hard token cap can split a byte-fallback token.
size_t utf8_prefix(const char* data, size_t length, bool permit_incomplete) {
    size_t complete = 0;
    if (!portal_sample_llm::utf8_prefix(data, length, complete)) {
        throw Failure(permit_incomplete ? 2 : 1, "Text is not valid UTF-8.");
    }
    if (!permit_incomplete && complete != length) throw Failure(1, "Text ends with incomplete UTF-8.");
    return complete;
}

char* copy_bytes(const char* data, size_t length) noexcept {
    auto* result = static_cast<char*>(std::malloc(length + 1));
    if (result) {
        if (length) std::memcpy(result, data, length);
        result[length] = '\0';
    }
    return result;
}

void set_error(char** error, const char* message) noexcept {
    if (error) *error = copy_bytes(message, std::strlen(message));
}

// No prompts, paths, generated text, or model metadata enter platform logs.
void discard_log(ggml_log_level, const char*, void*) {}
std::once_flag backend_once;
} // namespace

struct PortalSampleLlm {
    std::atomic<bool> cancelled{false};
    std::mutex generation;
    llama_model* model = nullptr;
    llama_context* context = nullptr;
    llama_sampler* sampler = nullptr;
    llama_batch_ext* batch = nullptr;
    const llama_vocab* vocab = nullptr;
    const char* chat_template = nullptr;
    std::vector<llama_token> prompt_tokens;
    std::string user;
    std::string formatted;
    std::string output;
    std::vector<char> large_piece;

    PortalSampleLlm() : prompt_tokens(kContextTokens) {
        output.reserve(4096);
    }

    void release() noexcept {
        if (batch) llama_batch_ext_free(batch);
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        batch = nullptr;
        sampler = nullptr;
        context = nullptr;
        model = nullptr;
    }

    ~PortalSampleLlm() { release(); }

    void check_cancelled() const {
        if (cancelled.load(std::memory_order_relaxed)) throw Failure(3, "Generation was cancelled.");
    }

    void reset() noexcept {
        if (context) {
            llama_synchronize(context);
            llama_memory_clear(llama_get_memory(context), true);
        }
        if (sampler) llama_sampler_reset(sampler);
        if (batch) llama_batch_ext_clear(batch);
    }
};

namespace {
// A retained registry entry makes Cancel/Destroy/Generate races safe without
// dereferencing a possibly destroyed C/JNI handle. It is not request history.
std::mutex registry_mutex;
std::unordered_map<PortalSampleLlm*, std::shared_ptr<PortalSampleLlm>> registry;

std::shared_ptr<PortalSampleLlm> retain(PortalSampleLlm* handle) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto entry = registry.find(handle);
    return entry == registry.end() ? nullptr : entry->second;
}

bool should_abort(void* data) {
    return static_cast<PortalSampleLlm*>(data)->cancelled.load(std::memory_order_relaxed);
}

struct RequestReset {
    PortalSampleLlm& engine;
    explicit RequestReset(PortalSampleLlm& engine) : engine(engine) { engine.reset(); }
    ~RequestReset() { engine.reset(); }
};

void fill_batch(PortalSampleLlm& engine, const llama_token* tokens, int count, llama_pos position) {
    llama_batch_ext_clear(engine.batch);
    for (int i = 0; i < count; ++i) {
        const int32_t index = llama_batch_ext_add_token(engine.batch, 0, tokens[i]);
        const llama_pos pos = position + i;
        if (index < 0 || !llama_batch_ext_set_pos(engine.batch, index, &pos)) {
            throw Failure(2, "Unable to prepare inference batch.");
        }
    }
    if (!llama_batch_ext_set_output_logits(engine.batch, count - 1, true)) {
        throw Failure(2, "Unable to prepare inference output.");
    }
}

int format_and_tokenize(PortalSampleLlm& engine, const char* prompt, size_t length, int max_tokens) {
    engine.user.assign(prompt, length);
    const llama_chat_message messages[] = {
        {"system", "You are a helpful assistant. Answer clearly and concisely."},
        {"user", engine.user.c_str()},
    };
    engine.formatted.resize(std::min(kMaxTemplateBytes, length * 2 + 1024));
    int32_t needed = llama_chat_apply_template(engine.chat_template, messages, 2, true,
                                             engine.formatted.data(), static_cast<int32_t>(engine.formatted.size()));
    if (needed < 0) throw Failure(2, "The model chat template is not supported.");
    if (static_cast<size_t>(needed) > kMaxTemplateBytes) throw Failure(1, "The formatted prompt is too large.");
    if (static_cast<size_t>(needed) > engine.formatted.size()) {
        engine.formatted.resize(static_cast<size_t>(needed));
        needed = llama_chat_apply_template(engine.chat_template, messages, 2, true,
                                          engine.formatted.data(), static_cast<int32_t>(engine.formatted.size()));
        if (needed < 0 || static_cast<size_t>(needed) > engine.formatted.size()) {
            throw Failure(2, "Unable to apply the model chat template.");
        }
    }
    engine.formatted.resize(static_cast<size_t>(needed));
    const int32_t count = llama_tokenize(engine.vocab, engine.formatted.data(), needed,
                                       engine.prompt_tokens.data(), kContextTokens, true, true);
    if (count <= 0 || count > kContextTokens - max_tokens) {
        throw Failure(1, "Prompt and requested output exceed the 1024-token context. Shorten the prompt or output.");
    }
    return count;
}

void append_piece(PortalSampleLlm& engine, llama_token token) {
    char piece[256];
    int32_t length = llama_token_to_piece(engine.vocab, token, piece, sizeof(piece), 0, false);
    if (length >= 0) {
        engine.output.append(piece, static_cast<size_t>(length));
        return;
    }
    // Some vocabulary entries are larger than the usual short token piece.
    if (length < -static_cast<int32_t>(kMaxTemplateBytes)) throw Failure(2, "Model token piece is too large.");
    engine.large_piece.resize(static_cast<size_t>(-length));
    length = llama_token_to_piece(engine.vocab, token, engine.large_piece.data(),
                                 static_cast<int32_t>(engine.large_piece.size()), 0, false);
    if (length < 0) throw Failure(2, "Unable to decode a generated token.");
    engine.output.append(engine.large_piece.data(), static_cast<size_t>(length));
}
} // namespace

extern "C" PortalSampleLlm* PortalSampleLlmCreate(const char* model_path, char** error) {
    if (error) *error = nullptr;
    try {
        if (!model_path || !*model_path) throw Failure(1, "A cached model path is required.");
        utf8_prefix(model_path, std::strlen(model_path), false);
        std::call_once(backend_once, [] {
            llama_log_set(discard_log, nullptr);
            ggml_log_set(discard_log, nullptr);
            llama_backend_init();
        });
        auto engine = std::make_shared<PortalSampleLlm>();
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.split_mode = LLAMA_SPLIT_MODE_NONE;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.lazy_mode = LLAMA_LAZY_MODE_OFF;
        engine->model = llama_model_load_from_file(model_path, model_params);
        if (!engine->model) throw Failure(2, "Unable to load the cached GGUF model.");
        if (llama_model_has_encoder(engine->model) || !llama_model_has_decoder(engine->model)) {
            throw Failure(2, "This sample requires a decoder-only instruction model.");
        }
        engine->vocab = llama_model_get_vocab(engine->model);
        engine->chat_template = llama_model_chat_template(engine->model, nullptr);
        if (!engine->vocab || !engine->chat_template || !*engine->chat_template) {
            throw Failure(2, "The model is missing its vocabulary or chat template.");
        }
        auto context_params = llama_context_default_params();
        context_params.n_ctx = kContextTokens;
        context_params.n_batch = kContextTokens;
        context_params.n_ubatch = 128;
        context_params.n_seq_max = 1;
        context_params.n_outputs_max = 1;
        context_params.n_outputs_max_per_seq = 1;
        const auto threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        context_params.n_threads = static_cast<int32_t>(threads);
        context_params.n_threads_batch = static_cast<int32_t>(threads);
        context_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        context_params.no_perf = true;
        context_params.abort_callback = should_abort;
        context_params.abort_callback_data = engine.get();
        engine->context = llama_init_from_model(engine->model, context_params);
        if (!engine->context || !llama_get_memory(engine->context)) throw Failure(2, "Unable to allocate the inference context.");
        engine->batch = llama_batch_ext_init(engine->context);
        auto sampler_params = llama_sampler_chain_default_params();
        sampler_params.no_perf = true;
        engine->sampler = llama_sampler_chain_init(sampler_params);
        if (!engine->batch || !engine->sampler) throw Failure(2, "Unable to allocate inference state.");
        llama_sampler_chain_add(engine->sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(engine->sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(engine->sampler, llama_sampler_init_temp(0.8f));
        llama_sampler_chain_add(engine->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        auto* handle = engine.get();
        std::lock_guard<std::mutex> lock(registry_mutex);
        registry.emplace(handle, std::move(engine));
        return handle;
    } catch (const std::exception& failure) {
        set_error(error, failure.what());
    } catch (...) {
        set_error(error, "Unable to initialize local inference.");
    }
    return nullptr;
}

extern "C" char* PortalSampleLlmGenerate(PortalSampleLlm* handle, const char* prompt, size_t prompt_length,
                                         int max_tokens, size_t* output_length, int* generated_tokens,
                                         int* error_code, char** error) {
    if (output_length) *output_length = 0;
    if (generated_tokens) *generated_tokens = 0;
    if (error_code) *error_code = 0;
    if (error) *error = nullptr;
    try {
        auto engine = retain(handle);
        if (!engine) throw Failure(3, "The local model is closed.");
        engine->check_cancelled();
        std::unique_lock<std::mutex> lock(engine->generation, std::try_to_lock);
        if (!lock.owns_lock()) throw Failure(2, "A generation is already running.");
        engine->check_cancelled();
        if (!prompt || prompt_length == 0 || prompt_length > kMaxPromptBytes || max_tokens < 1 || max_tokens > kMaxTokens) {
            throw Failure(1, "Use a nonempty prompt up to 16 KiB and an output limit from 1 to 128 tokens.");
        }
        utf8_prefix(prompt, prompt_length, false);
        bool nonblank = false;
        for (size_t i = 0; i < prompt_length; ++i) {
            if (prompt[i] == '\0') throw Failure(1, "The prompt must not contain NUL characters.");
            if (prompt[i] != ' ' && prompt[i] != '\t' && prompt[i] != '\n' && prompt[i] != '\r' && prompt[i] != '\v' && prompt[i] != '\f') nonblank = true;
        }
        if (!nonblank) throw Failure(1, "The prompt must not be blank.");
        RequestReset reset(*engine);
        const int prompt_count = format_and_tokenize(*engine, prompt, prompt_length, max_tokens);
        fill_batch(*engine, engine->prompt_tokens.data(), prompt_count, 0);
        engine->output.clear();
        int generated = 0;
        llama_pos position = prompt_count;
        for (int i = 0; i < max_tokens; ++i) {
            engine->check_cancelled();
            const int32_t result = llama_process(engine->context, LLAMA_PROCESS_TYPE_DECODE, engine->batch);
            engine->check_cancelled();
            if (result != 0) throw Failure(2, "Local model evaluation failed.");
            const llama_token token = llama_sampler_sample(engine->sampler, engine->context, -1);
            if (token == LLAMA_TOKEN_NULL) throw Failure(2, "Local model sampling failed.");
            if (llama_vocab_is_eog(engine->vocab, token)) break;
            append_piece(*engine, token);
            ++generated;
            if (i + 1 < max_tokens) fill_batch(*engine, &token, 1, position++);
        }
        engine->check_cancelled();
        engine->output.resize(utf8_prefix(engine->output.data(), engine->output.size(), true));
        char* result = copy_bytes(engine->output.data(), engine->output.size());
        if (!result) throw std::bad_alloc();
        if (output_length) *output_length = engine->output.size();
        if (generated_tokens) *generated_tokens = generated;
        return result;
    } catch (const Failure& failure) {
        if (error_code) *error_code = failure.code;
        set_error(error, failure.what());
    } catch (const std::bad_alloc&) {
        if (error_code) *error_code = 2;
        set_error(error, "Not enough memory for local inference.");
    } catch (...) {
        if (error_code) *error_code = 2;
        set_error(error, "Local inference failed.");
    }
    return nullptr;
}

extern "C" void PortalSampleLlmCancel(PortalSampleLlm* handle) {
    try {
        auto engine = retain(handle);
        if (engine) engine->cancelled.store(true, std::memory_order_relaxed);
    } catch (...) {
        // C functions never propagate C++ exceptions.
    }
}

extern "C" void PortalSampleLlmDestroy(PortalSampleLlm* handle) {
    try {
        std::shared_ptr<PortalSampleLlm> engine;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto entry = registry.find(handle);
            if (entry == registry.end()) return;
            engine = entry->second;
            engine->cancelled.store(true, std::memory_order_relaxed);
            registry.erase(entry);
        }
        std::lock_guard<std::mutex> lock(engine->generation);
        engine->release();
    } catch (...) {
        // C functions never propagate C++ exceptions.
    }
}

extern "C" void PortalSampleLlmFree(void* allocation) {
    std::free(allocation);
}
