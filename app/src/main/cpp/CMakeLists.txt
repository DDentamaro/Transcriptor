cmake_minimum_required(VERSION 3.18)
project(trascrittore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- whisper.cpp (sorgenti inclusi nel progetto, versione 1.8.5, licenza MIT) ---
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS         OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)

# Istruzioni fp16 (ARMv8.2, telefoni dal 2018 circa in poi): molto piu veloce.
# Si attiva/disattiva da app/build.gradle.kts (useFp16).
option(TRASCRITTORE_FP16 "Usa istruzioni ARMv8.2 fp16" ON)
if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a" AND TRASCRITTORE_FP16)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)
else()
    set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
endif()

add_subdirectory(whisper.cpp)

# --- libreria JNI dell'app ---
add_library(trascrittore SHARED jni_bridge.cpp)
target_link_libraries(trascrittore PRIVATE whisper)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(trascrittore PRIVATE ${LOG_LIB} android)
endif()

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(trascrittore PRIVATE -O3)
endif()
