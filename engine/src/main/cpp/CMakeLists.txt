cmake_minimum_required(VERSION 3.22.1)
project(taracore_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(CheckCXXCompilerFlag)

# ----------------------------------------------------------------------------
# Options forwarded from Gradle (see engine/build.gradle.kts). Default OFF: the
# `cpu` product flavour builds a pure-CPU engine, the `gpu` flavour turns Vulkan on.
# ----------------------------------------------------------------------------
option(TARACORE_VULKAN "Tara Core: build the ggml Vulkan backend" OFF)
option(TARACORE_OPENCL "Tara Core: build the ggml OpenCL (Adreno) backend" OFF)

# ----------------------------------------------------------------------------
# llama.cpp configuration. Every one of these MUST be set before add_subdirectory,
# because llama.cpp reads them at configure time via option()/CACHE defaults.
# ----------------------------------------------------------------------------
set(LLAMA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
get_filename_component(LLAMA_ROOT "${LLAMA_ROOT}" ABSOLUTE)

if(NOT EXISTS "${LLAMA_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR
        "third_party/llama.cpp is empty. Run:  git submodule update --init --recursive\n"
        "Looked in: ${LLAMA_ROOT}")
endif()

# Cross-compiling: never probe the *host* CPU for -march flags.
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)

# arm64 baseline. Every Android arm64 SoC since ~2018 (Cortex-A75/A55 and newer)
# implements v8.2 dotprod + fp16 arithmetic, which roughly doubles Q4_K throughput.
# Probe the flag rather than assuming, so an older NDK degrades instead of failing.
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(TARACORE_ARM_ARCH "armv8.2-a+dotprod+fp16")
    set(CMAKE_REQUIRED_FLAGS "-march=${TARACORE_ARM_ARCH}")
    check_cxx_compiler_flag("" TARACORE_HAS_ARMV82)
    unset(CMAKE_REQUIRED_FLAGS)
    if(NOT TARACORE_HAS_ARMV82)
        message(WARNING "Toolchain rejected -march=${TARACORE_ARM_ARCH}; falling back to armv8-a")
        set(TARACORE_ARM_ARCH "armv8-a")
    endif()
    set(GGML_CPU_ARM_ARCH "${TARACORE_ARM_ARCH}" CACHE STRING "" FORCE)
    message(STATUS "Tara Core: GGML_CPU_ARM_ARCH=${TARACORE_ARM_ARCH}")
endif()

# We only want the libraries. No CLI tools, no tests, no curl dependency.
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)

# Static llama/ggml linked into our single .so: one artifact to ship, and
# -Wl,--gc-sections can then drop everything we never call.
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  OFF CACHE BOOL "" FORCE)

# The NDK ships no libomp against which we can link statically without pulling
# libomp.so into the APK; ggml's own threadpool is used instead.
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

if(TARACORE_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    message(STATUS "Tara Core: Vulkan backend ENABLED (needs a host vulkan-shaders-gen, see docs/SETUP.md)")
endif()

if(TARACORE_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS      ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    message(STATUS "Tara Core: OpenCL backend ENABLED (targets Adreno)")
endif()

add_subdirectory("${LLAMA_ROOT}" llama_build EXCLUDE_FROM_ALL)

# ----------------------------------------------------------------------------
# Our JNI library
# ----------------------------------------------------------------------------
add_library(taracore_jni SHARED
    engine.cpp
    jni.cpp
)

target_include_directories(taracore_jni PRIVATE
    "${LLAMA_ROOT}/include"
    "${LLAMA_ROOT}/ggml/include"
    "${CMAKE_CURRENT_SOURCE_DIR}"
)

target_compile_options(taracore_jni PRIVATE
    -O2
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -Wno-unused-parameter
)
# NOTE: exceptions stay enabled -- llama.cpp throws std::runtime_error on load
# failure and we catch it at the JNI boundary to turn it into a LoadResult.

target_link_options(taracore_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # 16 KB page compliance: required for Android 15+ devices that boot with a
    # 16 KB page size. Harmless on 4 KB devices.
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

target_link_libraries(taracore_jni PRIVATE
    llama
    ggml
    log
    android
)

if(TARACORE_VULKAN)
    target_compile_definitions(taracore_jni PRIVATE TARACORE_VULKAN=1)
endif()
if(TARACORE_OPENCL)
    target_compile_definitions(taracore_jni PRIVATE TARACORE_OPENCL=1)
endif()

# Apply the 16 KB page flag to every shared library produced in this build tree,
# not just ours, in case a backend ends up shared.
foreach(tgt llama ggml ggml-base ggml-cpu)
    if(TARGET ${tgt})
        target_link_options(${tgt} PRIVATE -Wl,-z,max-page-size=16384)
    endif()
endforeach()
