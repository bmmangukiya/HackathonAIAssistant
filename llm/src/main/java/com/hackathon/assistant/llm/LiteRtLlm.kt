package com.hackathon.assistant.llm

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.hackathon.assistant.core.LlmChat
import com.hackathon.assistant.core.LocalLlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device model via LiteRT-LM. Models are read from /sdcard/Download/models/<name>.litertlm
 * (needs "All files access"; tools/install.sh grants it). Files that adb writes into the app's
 * own Android/data dir end up owned by `shell` and are unreadable by the app.
 */
class LiteRtLlm(
    private val context: Context,
    var modelName: String = DEFAULT_MODEL,
) : LocalLlm {
    private var engine: Engine? = null
    @Volatile private var active: com.google.ai.edge.litertlm.Conversation? = null

    /** Aborts the in-flight generation (the card's ✕). The waiting generate() returns at once. */
    fun cancelCurrent() { cancel(); android.util.Log.i(TAG, "generation cancelled") }
    private val lock = Mutex()

    override val isLoaded get() = engine != null

    val modelsDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "models")

    override suspend fun load(): Unit = lock.withLock {
        if (engine != null) return@withLock
        withContext(Dispatchers.IO) {
            val file = File(modelsDir, modelName)
            require(file.canRead()) {
                "Can't read model ${file.path}. Pushed it? Granted All files access (tools/install.sh)?"
            }
            val start = System.currentTimeMillis()
            val config = EngineConfig(
                modelPath = file.path,
                backend = Backend.GPU(),
                maxNumTokens = contextTokens(),
                cacheDir = context.cacheDir.path,
            )
            engine = Engine(config).also { it.initialize() }
            Log.i(TAG, "loaded $modelName in ${System.currentTimeMillis() - start} ms")
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int, jsonSchema: String?): String {
        if (engine == null) load()
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                // A fresh conversation per call: planner calls are independent and must not
                // leak earlier screens into the KV cache.
                val conversation = engine!!.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                        maxOutputToken = maxTokens,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        enableResponseFormat = jsonSchema != null,
                    ),
                )
                active = conversation
                conversation.use {
                    active = it
                    val reply = try { it.sendMessage(
                        prompt,
                        responseFormat = jsonSchema?.let(ResponseFormat::json),
                    ) } finally { active = null }
                    val text = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { c -> c.text }
                    Log.i(TAG, "generate ${System.currentTimeMillis() - start} ms, prompt ${prompt.length} chars")
                    text
                }
            }
        }
    }

    override suspend fun openChat(system: String, maxTokens: Int, jsonSchema: String?): LlmChat {
        if (engine == null) load()
        // Created without cancellation, then closed if the caller was cancelled meanwhile (Stop):
        // otherwise the native conversation (and its GPU KV cache) would be created and lost.
        val chat = lock.withLock {
            withContext(Dispatchers.IO + NonCancellable) {
                val start = System.currentTimeMillis()
                val conversation = engine!!.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                        maxOutputToken = maxTokens,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        enableResponseFormat = jsonSchema != null,
                        prefillPrefaceOnInit = true,
                    ),
                )
                Log.i(TAG, "chat opened, system prompt (${system.length} chars) prefilled in ${System.currentTimeMillis() - start} ms")
                Chat(conversation, jsonSchema?.let(ResponseFormat::json))
            }
        }
        if (!currentCoroutineContext().isActive) { chat.close(); throw CancellationException("cancelled while opening a chat") }
        return chat
    }

    private inner class Chat(
        private val conversation: com.google.ai.edge.litertlm.Conversation,
        private val format: ResponseFormat?,
    ) : LlmChat {
        override val tokenCount get() = runCatching { conversation.getTokenCount() }.getOrDefault(0)

        override suspend fun send(text: String, jsonSchema: String?): String = lock.withLock {
            withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                active = conversation
                val reply = conversation.sendMessage(text, responseFormat = jsonSchema?.let(ResponseFormat::json) ?: format)
                val out = reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                Log.i(TAG, "chat turn ${System.currentTimeMillis() - start} ms, +${text.length} chars, context $tokenCount tokens")
                out
            }
        }

        override fun close() { runCatching { conversation.close() } }
    }

    /** Big models get a smaller KV cache so they fit in phone memory. */
    private fun contextTokens() = if (modelName.contains("12B") || modelName.contains("26B")) BIG_MODEL_CONTEXT_TOKENS else MAX_CONTEXT_TOKENS

    /** Interrupts a generation in progress (Stop button). */
    fun cancel() {
        runCatching { active?.cancelProcess() }
    }

    override fun close() {
        engine?.close()
        engine = null
    }

    companion object {
        // Default on-device model. Alternatives (push the .litertlm to /sdcard/Download/models/ and
        // switch over adb with --es model <file>, see DebugCommandReceiver):
        // "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm" (~2x faster, less capable),
        // "qwen3_4b_instruct_2507_mixed_int4.litertlm". See docs/MODELS.md.
        const val DEFAULT_MODEL = "gemma-4-E4B-it-gpu.litertlm"
        private const val MAX_CONTEXT_TOKENS = 8192
        private const val BIG_MODEL_CONTEXT_TOKENS = 4096
        private const val TAG = "LiteRtLlm"
    }
}
