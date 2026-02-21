# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)

project("livetvpro")

add_library(
        native-lib
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

# ── FFmpeg NDK (media3-exoplayer-ffmpeg) ──────────────────────────────────────
# The media3 ffmpeg extension ships a pre-built libffmpeg.so for each ABI.
# Gradle unpacks it into the app's jniLibs before the CMake build runs, so we
# can import it as a pre-built shared library and link against it.
# This is required so that our native-lib is aware of the FFmpeg symbols at
# link time; at runtime the .so is loaded automatically by the extension.
find_library(ffmpeg-lib ffmpeg)

if(ffmpeg-lib)
    target_link_libraries(
            native-lib
            ${log-lib}
            ${ffmpeg-lib}
    )
else()
    # ffmpeg-lib not found in the NDK search paths — link without it.
    # The Java-side DefaultRenderersFactory will still activate the extension
    # because Gradle packages the .so into the APK; this only affects
    # whether native-lib itself can call FFmpeg symbols directly (we don't need that).
    target_link_libraries(
            native-lib
            ${log-lib}
    )
endif()
