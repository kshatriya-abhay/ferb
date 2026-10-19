// JNI bridge between dev.abhay.assistant.engine.Needle and the prebuilt Needle engine (needle.h),
// which runs Cactus's Needle 3 (requests in, tool calls out) and Whistle (speech in, text out).
//
// The engine holds one process-global model of each kind (text, speech), is not thread-safe and has no way to unload it. The
// Kotlin side calls these functions from one dedicated thread. The model stays loaded while the app
// runs; changing the tools re-runs needle_init, which replaces the previous tool set.

#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <cstring>
#include <string>
#include <vector>

#include "needle.h"

namespace {

constexpr const char* kTag = "Needle";

// Room for the JSON the engine writes; the same size the official Python wrapper uses.
constexpr int kOutputCapacity = 1 << 18;

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string lastError() {
    const char* error = needle_last_error();
    return error != nullptr && *error != '\0' ? error : "unknown engine error";
}

// A Java string as UTF-8 for the length of a call; null stays null.
class Utf8 {
public:
    Utf8(JNIEnv* env, jstring value) : env_(env), value_(value),
        chars_(value != nullptr ? env->GetStringUTFChars(value, nullptr) : nullptr) {}
    ~Utf8() { if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_); }
    Utf8(const Utf8&) = delete;
    Utf8& operator=(const Utf8&) = delete;
    const char* get() const { return chars_; }

private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_;
};

}  // namespace

// Loads the .cact model bundled in the APK's assets. It's stored uncompressed, so the buffer is a
// mapping of the APK itself, with no copy on the Java heap; needle_load keeps its own copy.
// Returns the kinds now loaded (NEEDLE_TEXT | NEEDLE_SPEECH bits). Throws IllegalStateException.
extern "C" JNIEXPORT jint JNICALL
Java_dev_abhay_assistant_engine_Needle_nativeLoadAsset(JNIEnv* env, jobject, jobject assetManager, jstring path) {
    Utf8 name(env, path);
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    AAsset* asset = manager != nullptr ? AAssetManager_open(manager, name.get(), AASSET_MODE_BUFFER) : nullptr;
    if (asset == nullptr) {
        throwIllegalState(env, std::string("asset not found: ") + name.get());
        return 0;
    }
    const void* data = AAsset_getBuffer(asset);
    const off64_t size = AAsset_getLength64(asset);
    if (data == nullptr || size <= 0) {
        AAsset_close(asset);
        throwIllegalState(env, std::string("cannot read asset ") + name.get());
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "loading %s: %lld bytes", name.get(), static_cast<long long>(size));
    const int result = needle_load(static_cast<const unsigned char*>(data), static_cast<unsigned long long>(size));
    AAsset_close(asset);
    if (result < 0) {
        throwIllegalState(env, "needle_load failed: " + lastError());
        return 0;
    }
    return needle_models();
}

// Sets the system facts and tools (a JSON array of schemas), replacing any set before. Returns the
// length in tokens of that fixed prefix. Throws IllegalStateException if it doesn't fit the context.
extern "C" JNIEXPORT jint JNICALL
Java_dev_abhay_assistant_engine_Needle_nativeInit(JNIEnv* env, jobject, jstring systemPrompt, jstring toolsJson) {
    Utf8 system(env, systemPrompt);
    Utf8 tools(env, toolsJson);
    const int prefix = needle_init(system.get(), tools.get(), /* tool_index_path= */ nullptr);
    if (prefix < 0) throwIllegalState(env, "needle_init failed: " + lastError());
    return prefix;
}

// Forgets the conversation so far, keeping the system facts and tools.
extern "C" JNIEXPORT void JNICALL
Java_dev_abhay_assistant_engine_Needle_nativeReset(JNIEnv*, jobject) {
    needle_reset();
}

// Answers a text request against the tools from nativeInit; returns the engine's JSON
// (function_calls, suppressed_calls, reasoning, confidence, ...) as UTF-8 bytes. Not NewStringUTF:
// JNI's "modified UTF-8" differs from real UTF-8 for some characters.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_abhay_assistant_engine_Needle_nativeComplete(JNIEnv* env, jobject, jstring input, jint maxNewTokens) {
    Utf8 text(env, input);
    std::vector<char> out(kOutputCapacity, '\0');
    const int result = needle_complete(text.get(), /* pcm= */ nullptr, /* samples= */ 0, maxNewTokens,
                                       out.data(), kOutputCapacity);
    if (result < 0) {
        throwIllegalState(env, "needle_complete failed: " + lastError());
        return nullptr;
    }
    const jsize length = static_cast<jsize>(strnlen(out.data(), out.size()));
    jbyteArray json = env->NewByteArray(length);
    if (json != nullptr) env->SetByteArrayRegion(json, 0, length, reinterpret_cast<const jbyte*>(out.data()));
    return json;
}

// Transcribes 16 kHz mono float PCM in [-1, 1] (at most 30 s) with the speech model (Whistle) and
// returns the engine's JSON ({"text", "language", "ttft_ms", "decode_tps"}) as UTF-8 bytes; silence
// gives empty text. language may be null (detect). Throws IllegalStateException on failure.
// Adapted from Local Models Demo's needle_jni.cpp.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_abhay_assistant_engine_Needle_nativeTranscribe(JNIEnv* env, jobject, jfloatArray pcm, jstring language) {
    const jsize count = env->GetArrayLength(pcm);
    // A copy rather than a pinned array: transcription takes a while, and pinning would hold up the
    // garbage collector that long. 30 s of audio is under 2 MB.
    std::vector<float> samples(static_cast<size_t>(count));
    env->GetFloatArrayRegion(pcm, 0, count, samples.data());
    Utf8 lang(env, language);
    std::vector<char> out(kOutputCapacity, '\0');
    const int result = needle_transcribe(samples.data(), count, lang.get(), /* keywords= */ nullptr,
                                         /* word_timestamps= */ 0, out.data(), kOutputCapacity);
    if (result < 0) {
        throwIllegalState(env, "needle_transcribe failed: " + lastError());
        return nullptr;
    }
    const jsize length = static_cast<jsize>(strnlen(out.data(), out.size()));
    jbyteArray json = env->NewByteArray(length);
    if (json != nullptr) env->SetByteArrayRegion(json, 0, length, reinterpret_cast<const jbyte*>(out.data()));
    return json;
}
