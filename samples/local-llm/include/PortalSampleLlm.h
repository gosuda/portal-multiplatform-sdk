#ifndef PORTAL_SAMPLE_LLM_H
#define PORTAL_SAMPLE_LLM_H

#include <stddef.h>

#if defined(_WIN32) && !defined(PORTAL_SAMPLE_LLM_STATIC)
#  if defined(PORTAL_SAMPLE_LLM_BUILD)
#    define PORTAL_SAMPLE_LLM_API __declspec(dllexport)
#  else
#    define PORTAL_SAMPLE_LLM_API __declspec(dllimport)
#  endif
#elif defined(__GNUC__)
#  define PORTAL_SAMPLE_LLM_API __attribute__((visibility("default")))
#else
#  define PORTAL_SAMPLE_LLM_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PortalSampleLlm PortalSampleLlm;

/* All text is standard UTF-8. Errors and successful output are owned by the
 * caller and must be released with PortalSampleLlmFree. NULL error is allowed.
 * Models stay local; this API neither downloads weights nor opens sockets. */
PORTAL_SAMPLE_LLM_API PortalSampleLlm* PortalSampleLlmCreate(const char* model_path, char** error);

/* Independent, single-turn generation using the GGUF's own chat template.
 * Context: 1024 tokens including the prompt and generation; max_tokens: 1..128.
 * output_length excludes the trailing NUL. generated_tokens excludes EOG.
 * NULL output means failure: error_code 1=input/budget, 2=backend/busy,
 * 3=cancelled/closed. Output parameters may be NULL.
 * One generation per handle; competing generations fail instead of queueing. */
PORTAL_SAMPLE_LLM_API char* PortalSampleLlmGenerate(
    PortalSampleLlm* handle,
    const char* prompt,
    size_t prompt_length,
    int max_tokens,
    size_t* output_length,
    int* generated_tokens,
    int* error_code,
    char** error
);

/* Thread-safe, permanent cancellation. Once cancelled, no new request starts. */
PORTAL_SAMPLE_LLM_API void PortalSampleLlmCancel(PortalSampleLlm* handle);

/* Cancels and waits for an in-flight request before freeing model resources.
 * Concurrent cancel/destroy are safe; do not reuse a destroyed handle. */
PORTAL_SAMPLE_LLM_API void PortalSampleLlmDestroy(PortalSampleLlm* handle);
PORTAL_SAMPLE_LLM_API void PortalSampleLlmFree(void* allocation);

#ifdef __cplusplus
}
#endif

#endif
