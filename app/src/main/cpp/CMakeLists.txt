cmake_minimum_required(VERSION 3.18)
project(trascrittore C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- whisper.cpp (sorgenti inclusi nel progetto, versione 1.8.5, licenza MIT) ---
# Librerie condivise + backend CPU caricati a runtime: ggml compila una variante
# per ogni famiglia di processori (ARMv8.0, dotprod, fp16, i8mm, SVE2, SME) e
# all'avvio sceglie la piu veloce supportata dal telefono. Niente piu
# ricompilazione per i telefoni vecchi, e i telefoni recenti usano i8mm.
set(BUILD_SHARED_LIBS        ON  CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS         OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES      OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH        ""  CACHE STRING "" FORCE)

add_subdirectory(whisper.cpp)

# --- libreria JNI dell'app ---
add_library(trascrittore SHARED jni_bridge.cpp)
# ggml dipende gia da tutte le varianti ggml-cpu-*, che finiscono nell'APK.
target_link_libraries(trascrittore PRIVATE whisper ggml)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(trascrittore PRIVATE ${LOG_LIB} android)
endif()

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(trascrittore PRIVATE -O3)
endif()
