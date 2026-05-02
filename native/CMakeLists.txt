cmake_minimum_required(VERSION 3.22)
project(kotlin_harfbuzz LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Position-independent code so we can link the static HarfBuzz into a shared JNI lib.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ────────────────────────────────────────────────────────────────────────────
# HarfBuzz build options — keep the binary small and dependency-free.
# Spelled out per design §5 (`plans/2026-04-25-kotlin-harfbuzz-design.md`).
# ────────────────────────────────────────────────────────────────────────────
set(HB_BUILD_SUBSET     OFF CACHE BOOL "Disable hb-subset; v1 doesn't expose it"          FORCE)
set(HB_HAVE_FREETYPE    OFF CACHE BOOL "We don't need FreeType (no rasterization here)"   FORCE)
set(HB_HAVE_GRAPHITE2   OFF CACHE BOOL "Graphite shaper not used in v1"                   FORCE)
set(HB_HAVE_GLIB        OFF CACHE BOOL "Built-in unicode tables, no GLib"                 FORCE)
set(HB_HAVE_ICU         OFF CACHE BOOL "HarfBuzz handles Arabic correctly without ICU"    FORCE)
set(HB_HAVE_GOBJECT     OFF CACHE BOOL "No GObject bindings"                              FORCE)
set(HB_HAVE_INTROSPECTION OFF CACHE BOOL "No GIR/.typelib"                                FORCE)
set(HB_HAVE_CAIRO       OFF CACHE BOOL "No Cairo interop"                                 FORCE)
set(HB_BUILD_UTILS      OFF CACHE BOOL "No CLI utilities"                                 FORCE)
set(HB_BUILD_RASTER     OFF CACHE BOOL "No raster module — we use hb-draw + Compose"      FORCE)
set(HB_BUILD_VECTOR     OFF CACHE BOOL "No vector module"                                 FORCE)
set(HB_BUILD_GPU        OFF CACHE BOOL "No GPU module"                                    FORCE)

# Static HarfBuzz, embedded into our shared JNI library. One distributable artifact
# per platform, no system HarfBuzz dependency at runtime.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Static HarfBuzz" FORCE)

add_subdirectory(harfbuzz EXCLUDE_FROM_ALL)

# ────────────────────────────────────────────────────────────────────────────
# JNI bridge — shared library exported to Kotlin via System.loadLibrary("harfbuzz_jni").
# Built for JVM-family targets (Android NDK + Desktop JVM). iOS builds the static
# HarfBuzz lib separately for cinterop and does not include this bridge.
# ────────────────────────────────────────────────────────────────────────────
if(NOT KH_TARGET_IOS)
    add_library(harfbuzz_jni SHARED
        jni/harfbuzz_jni.cpp
    )

    target_include_directories(harfbuzz_jni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/harfbuzz/src
    )

    # Android NDK provides jni.h on its toolchain include path automatically.
    # Desktop JVM (linux/macos/windows) needs find_package(JNI) so we can pick up
    # the JDK's headers for whichever JDK is configured at build time.
    if(NOT ANDROID)
        find_package(JNI REQUIRED)
        target_include_directories(harfbuzz_jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()

    target_link_libraries(harfbuzz_jni PRIVATE harfbuzz)

    # Link options — strip dead code, no stdc++ runtime dep where avoidable.
    if(CMAKE_SYSTEM_NAME STREQUAL "Linux" OR ANDROID)
        target_link_options(harfbuzz_jni PRIVATE -Wl,--gc-sections -Wl,--strip-all)
    endif()

    if(APPLE)
        target_link_options(harfbuzz_jni PRIVATE -Wl,-dead_strip)
    endif()
endif()

# iOS-only: just the static HarfBuzz, picked up by cinterop downstream.
if(KH_TARGET_IOS)
    # `harfbuzz` static target is created by add_subdirectory above when
    # BUILD_SHARED_LIBS=OFF. Nothing more to do here — the Kotlin/Native cinterop
    # .def file references it directly.
endif()
