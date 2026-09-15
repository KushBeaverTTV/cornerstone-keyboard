cmake_minimum_required(VERSION 3.22)
project(cornerstone_correction)

add_library(cornerstone_correction SHARED correction_jni.cpp)

set(LLAMA_ROOT "C:/Cornerstone/tools/llama.cpp")
if(ANDROID_ABI STREQUAL "x86_64")
    set(LLAMA_BUILD "${LLAMA_ROOT}/build-android-x64")
else()
    set(LLAMA_BUILD "${LLAMA_ROOT}/build-android-arm64")
endif()

target_include_directories(cornerstone_correction PRIVATE
    ${LLAMA_ROOT}/include
    ${LLAMA_ROOT}/ggml/include
)

add_library(llama STATIC IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${LLAMA_BUILD}/src/libllama.a)
add_library(ggml STATIC IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${LLAMA_BUILD}/ggml/src/libggml.a)
add_library(ggml-cpu STATIC IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${LLAMA_BUILD}/ggml/src/libggml-cpu.a)
add_library(ggml-base STATIC IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${LLAMA_BUILD}/ggml/src/libggml-base.a)

target_link_libraries(cornerstone_correction
    llama
    -Wl,--whole-archive ggml-cpu -Wl,--no-whole-archive
    ggml
    ggml-base
    android
    log
)
