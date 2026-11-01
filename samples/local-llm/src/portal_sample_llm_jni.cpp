#include "PortalSampleLlm.h"
#include "utf8.h"
#include <jni.h>

#include <cstdint>
#include <memory>
#include <new>
#include <string>

namespace {
using NativeText = std::unique_ptr<char, decltype(&PortalSampleLlmFree)>;

void throw_java(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (exception) {
        env->ThrowNew(exception, message);
        env->DeleteLocalRef(exception);
    }
}

bool read_bytes(JNIEnv* env, jbyteArray bytes, size_t maximum, std::string& result) {
    if (!bytes) {
        throw_java(env, "java/lang/IllegalArgumentException", "UTF-8 bytes are required.");
        return false;
    }
    const jsize length = env->GetArrayLength(bytes);
    if (length <= 0 || static_cast<size_t>(length) > maximum) {
        throw_java(env, "java/lang/IllegalArgumentException", "UTF-8 input is empty or too large.");
        return false;
    }
    result.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result.data()));
    return !env->ExceptionCheck();
}

PortalSampleLlm* native_handle(jlong handle) {
    return reinterpret_cast<PortalSampleLlm*>(static_cast<uintptr_t>(handle));
}

void unexpected_failure(JNIEnv* env) {
    throw_java(env, "java/lang/IllegalStateException", "Local inference could not complete.");
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_gosuda_portal_sample_llm_NativeLlm_open(JNIEnv* env, jobject, jbyteArray model_path_utf8) {
    try {
        std::string path;
        if (!read_bytes(env, model_path_utf8, 32768, path)) return 0;
        if (path.find('\0') != std::string::npos) {
            throw_java(env, "java/lang/IllegalArgumentException", "The model path must not contain NUL characters.");
            return 0;
        }
        size_t complete = 0;
        if (!portal_sample_llm::utf8_prefix(path.data(), path.size(), complete) || complete != path.size()) {
            throw_java(env, "java/lang/IllegalArgumentException", "The model path must be valid UTF-8.");
            return 0;
        }
        char* error = nullptr;
        auto* handle = PortalSampleLlmCreate(path.c_str(), &error);
        NativeText owned_error(error, PortalSampleLlmFree);
        if (!handle) {
            throw_java(env, "java/lang/IllegalStateException", error ? error : "Unable to load the local model.");
            return 0;
        }
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(handle));
    } catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Not enough memory to open the local model.");
    } catch (...) {
        unexpected_failure(env);
    }
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_gosuda_portal_sample_llm_NativeLlm_generate(JNIEnv* env, jobject, jlong handle,
                                                  jbyteArray prompt_utf8, jint max_tokens) {
    try {
        std::string prompt;
        if (!read_bytes(env, prompt_utf8, 16 * 1024, prompt)) return nullptr;
        size_t output_length = 0;
        int error_code = 0;
        char* error = nullptr;
        NativeText output(PortalSampleLlmGenerate(native_handle(handle), prompt.data(), prompt.size(),
                                                  max_tokens, &output_length, nullptr, &error_code, &error),
                          PortalSampleLlmFree);
        NativeText owned_error(error, PortalSampleLlmFree);
        if (!output) {
            throw_java(env, error_code == 1 ? "java/lang/IllegalArgumentException" : "java/lang/IllegalStateException",
                       error ? error : "Local inference failed.");
            return nullptr;
        }
        // The native output is standard UTF-8, including four-byte scalars.
        // NewStringUTF/GetStringUTFChars would corrupt supplementary characters.
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output_length));
        if (result && output_length) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(output_length),
                                    reinterpret_cast<const jbyte*>(output.get()));
        }
        return result;
    } catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Not enough memory for local inference.");
    } catch (...) {
        unexpected_failure(env);
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_org_gosuda_portal_sample_llm_NativeLlm_cancel(JNIEnv*, jobject, jlong handle) {
    PortalSampleLlmCancel(native_handle(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_org_gosuda_portal_sample_llm_NativeLlm_close(JNIEnv*, jobject, jlong handle) {
    PortalSampleLlmDestroy(native_handle(handle));
}
