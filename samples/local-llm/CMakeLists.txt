cmake_minimum_required(VERSION 3.22)
project(PortalSampleLlm VERSION 1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

include(cmake/llama-source.cmake)

# A single statically linked CPU backend: no model downloader, daemon, GPU,
# host-specific instruction probes, OpenMP runtime, or network dependency.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Static upstream dependencies" FORCE)
set(LLAMA_BUILD_IS_DEV OFF CACHE BOOL "Pinned release" FORCE)
foreach(flag LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES
             LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_BUILD_UI LLAMA_USE_PREBUILT_UI
             LLAMA_CONNECT LLAMA_OPENSSL LLAMA_SUBPROCESS LLAMA_LLGUIDANCE
             LLAMA_BUILD_MTMD LLAMA_TOOLS_INSTALL LLAMA_TESTS_INSTALL
             GGML_NATIVE GGML_BACKEND_DL GGML_CPU_ALL_VARIANTS GGML_LTO
             GGML_ACCELERATE GGML_BLAS GGML_LLAMAFILE GGML_OPENMP GGML_OPENMP_FETCH
             GGML_CPU_KLEIDIAI GGML_CPU_HBM GGML_METAL GGML_CUDA GGML_CANN GGML_MUSA GGML_HIP
             GGML_VULKAN GGML_WEBGPU GGML_ZDNN GGML_VIRTGPU GGML_VIRTGPU_BACKEND
             GGML_RPC GGML_SYCL GGML_OPENVINO GGML_ET GGML_ET_SYSEMU GGML_OPENCL
             GGML_HEXAGON GGML_ZENDNN GGML_BUILD_TESTS GGML_BUILD_EXAMPLES
             GGML_SSE42 GGML_AVX GGML_AVX2 GGML_AVX_VNNI GGML_BMI2 GGML_FMA GGML_F16C
             GGML_AVX512 GGML_AVX512_VBMI GGML_AVX512_VNNI GGML_AVX512_BF16
             GGML_AMX_TILE GGML_AMX_INT8 GGML_AMX_BF16)
    set(${flag} OFF CACHE BOOL "Disabled in portable sample CPU build" FORCE)
endforeach()
set(GGML_CPU ON CACHE BOOL "CPU inference" FORCE)
# ARMv8-A is the Android arm64 baseline. Apple target toolchains retain their
# own ABI baseline; neither path compiles for the machine running CMake.
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Portable Android ARM baseline" FORCE)
endif()
set(LLAMA_BUILD_COMMIT "${PORTAL_SAMPLE_LLAMA_COMMIT}" CACHE STRING "Pinned source commit" FORCE)
add_subdirectory("${PORTAL_SAMPLE_LLAMA_SOURCE}" "${CMAKE_CURRENT_BINARY_DIR}/llama")

if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    add_library(portal_sample_llm STATIC src/portal_sample_llm.cpp)
    target_compile_definitions(portal_sample_llm PUBLIC PORTAL_SAMPLE_LLM_STATIC)
else()
    add_library(portal_sample_llm SHARED src/portal_sample_llm.cpp src/portal_sample_llm_jni.cpp)
    target_compile_definitions(portal_sample_llm PRIVATE PORTAL_SAMPLE_LLM_BUILD)
    if(NOT ANDROID)
        # JNI is a header-only boundary here; do not link a host JVM/AWT library.
        find_package(JNI REQUIRED)
        target_include_directories(portal_sample_llm PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
endif()

target_include_directories(portal_sample_llm PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include")
target_link_libraries(portal_sample_llm PRIVATE llama)
set_target_properties(portal_sample_llm PROPERTIES OUTPUT_NAME portal_sample_llm)
if(WIN32)
    set_target_properties(portal_sample_llm PROPERTIES PREFIX "")
endif()

if(ANDROID)
    # The legacy NDK toolchain deliberately sets CMAKE_SYSTEM_VERSION to 1.
    if(ANDROID_PLATFORM_LEVEL LESS 26)
        message(FATAL_ERROR "The local inference sample requires Android API 26 or newer.")
    endif()
    # NDK29 already supports flexible pages; make both ELF segment alignment
    # and linker common-page assumptions explicit for every sample ABI.
    target_link_options(portal_sample_llm PRIVATE
        "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384" "-Wl,--exclude-libs,ALL")
elseif(UNIX AND NOT APPLE)
    target_link_options(portal_sample_llm PRIVATE "-Wl,--exclude-libs,ALL")
endif()

set(PORTAL_SAMPLE_LLM_OUTPUT_DIR "" CACHE PATH "Optional final host JNI library directory")
if(PORTAL_SAMPLE_LLM_OUTPUT_DIR)
    set_target_properties(portal_sample_llm PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${PORTAL_SAMPLE_LLM_OUTPUT_DIR}"
        RUNTIME_OUTPUT_DIRECTORY "${PORTAL_SAMPLE_LLM_OUTPUT_DIR}")
    foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set_target_properties(portal_sample_llm PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY_${config} "${PORTAL_SAMPLE_LLM_OUTPUT_DIR}"
            RUNTIME_OUTPUT_DIRECTORY_${config} "${PORTAL_SAMPLE_LLM_OUTPUT_DIR}")
    endforeach()
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    # The packaging script combines these static dependencies into each slice.
    # Generator expressions avoid assuming upstream output directory layouts.
    file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/portal-sample-llm-archives-$<CONFIG>.txt"
        CONTENT "$<TARGET_FILE:portal_sample_llm>\n$<TARGET_FILE:llama>\n$<TARGET_FILE:ggml>\n$<TARGET_FILE:ggml-base>\n$<TARGET_FILE:ggml-cpu>\n")
endif()
