cmake_minimum_required(VERSION 3.22.1)
project(assistantjni CXX)

# The prebuilt Needle engine, downloaded (pinned and checksummed) by `mise run fetch`.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is set up.")
endif()
set(NEEDLE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/needle/android-arm64")
if(NOT EXISTS "${NEEDLE_DIR}/libneedle.a")
    message(FATAL_ERROR "Needle engine not found in ${NEEDLE_DIR}. Run `mise run fetch` first.")
endif()

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES
    IMPORTED_LOCATION "${NEEDLE_DIR}/libneedle.a"
    INTERFACE_INCLUDE_DIRECTORIES "${NEEDLE_DIR}")

add_library(assistantjni SHARED assistant_jni.cpp)
target_compile_features(assistantjni PRIVATE cxx_std_17)
target_compile_options(assistantjni PRIVATE -Wall -Wextra -Werror -fvisibility=hidden -fvisibility-inlines-hidden)
# Export only the JNI entry points (JNIEXPORT), not the engine's needle_* symbols.
target_link_options(assistantjni PRIVATE "-Wl,--exclude-libs,ALL")
target_link_libraries(assistantjni PRIVATE needle android log)
