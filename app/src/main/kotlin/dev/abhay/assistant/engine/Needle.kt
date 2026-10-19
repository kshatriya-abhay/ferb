package dev.abhay.assistant.engine

import android.content.res.AssetManager
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Kotlin side of the JNI bridge (`assistant_jni.cpp`) to Cactus's Needle engine, running Needle 3
 * and, for Phone-mode voice, Whistle.
 *
 * The engine holds one process-global model of each kind (text, speech) and isn't thread-safe, so every call runs on one
 * dedicated thread (which also keeps the engine's `thread_local` state on the same thread).
 */
object Needle {
    private val engine = Executors.newSingleThreadExecutor { Thread(it, "needle-engine") }.asCoroutineDispatcher()

    init {
        System.loadLibrary("assistantjni")
    }

    /** Loads the model bundled in the APK at [path] (an asset), beside a loaded model of the other kind. Call once per model and process. */
    suspend fun loadAsset(assets: AssetManager, path: String) = withContext(engine) { nativeLoadAsset(assets, path) }

    /**
     * Gives the model its system facts (e.g. "date: …") and tools (a JSON array of schemas),
     * replacing any it had. Returns the length of that prefix in tokens.
     */
    suspend fun init(systemFacts: String, toolsJson: String): Int = withContext(engine) { nativeInit(systemFacts, toolsJson) }

    /** Answers [request] against the tools, as a fresh conversation; returns the engine's JSON. */
    suspend fun complete(request: String, maxNewTokens: Int = 256): String = withContext(engine) {
        nativeReset()
        nativeComplete(request, maxNewTokens).toString(Charsets.UTF_8)
    }

    /** Whistle's JSON for 16 kHz mono float [pcm] (at most 30 s), in [language] (null: detect). */
    suspend fun transcribe(pcm: FloatArray, language: String?): String = withContext(engine) {
        nativeTranscribe(pcm, language).toString(Charsets.UTF_8)
    }

    private external fun nativeLoadAsset(assets: AssetManager, path: String): Int
    private external fun nativeInit(systemPrompt: String, toolsJson: String): Int
    private external fun nativeReset()
    private external fun nativeComplete(input: String, maxNewTokens: Int): ByteArray
    private external fun nativeTranscribe(pcm: FloatArray, language: String?): ByteArray
}
