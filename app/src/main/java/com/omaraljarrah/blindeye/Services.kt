package com.omaraljarrah.blindeye

import android.content.ContentValues.TAG
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.ImagePart
import com.google.ai.client.generativeai.type.TextPart
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global reference to the app's cache directory.
 * Set in MainActivity or Application class: CACHE_DIR = cacheDir
 */
var CACHE_DIR: File? = null

/**
 * An executor for blocking I/O tasks (network, file reads/writes).
 * You can customize thread count or use a different Executor as needed.
 */
private val IO_EXECUTOR: ExecutorService = Executors.newCachedThreadPool()

/**
 * GeminiService calls a generative AI model to describe an image (Bitmap),
 * using a blocking network call. Migrated to CompletableFuture.
 */
private object GeminiService {

    private val model: GenerativeModel = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = "key" // Replace with your real API key
    )

    private val PROMPT = """
        Describe the photo you see. Be short and prioritize important things.
        Your results will be transformed into speech and played for a blind man.
        Categorize things in the least amount of words possible.
        You can use at least 30 words.
    """.trimIndent()

    /**
     * Returns a CompletableFuture that runs the blocking call on IO_EXECUTOR.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    fun prompt(bitmap: Bitmap): CompletableFuture<String?> {
        return CompletableFuture.supplyAsync({
            // Blocking call to the generative model
            val result = runBlocking {
                try {
                    model.generateContent(
                        Content(
                            parts = listOf(
                                ImagePart(bitmap),
                                TextPart(PROMPT)
                            )
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            Log.d(TAG, "Gemini response: ${result}")
            if (result != null) {
                result.text
            } else {
                "response is null"
            }
        })
    }
}

/**
 * TextToSpeechService calls ElevenLabs to transform text into an MP3 file,
 * then plays it. Migrated to CompletableFuture.
 */
object TextToSpeechService {

    val client: Retrofit = Retrofit.Builder()
        .baseUrl("https://api.elevenlabs.io/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL/")
        .build()

    // Shared MediaPlayer, set in your Activity (e.g. onCreate)
    var mediaPlayer: MediaPlayer? = null

    /**
     * Transform text -> MP3 -> playback, all in the background using a CompletableFuture.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun speak(text: String): CompletableFuture<Void> {
        return CompletableFuture.supplyAsync({
            val mp = mediaPlayer
            if (mp == null) {
                Log.w(TAG, "MediaPlayer is null; cannot speak.")
                return@supplyAsync null
            }

            // 1) Transform text to MP3
            val audioFile = transform(text)
            if (!audioFile.exists() || audioFile.length() <= 0) {
                Log.e(TAG, "TTS audio file is invalid; cannot proceed.")
                return@supplyAsync null
            }

            // 2) Wait if a current playback is in progress
            while (mp.isPlaying) {
                Thread.sleep(1000)
            }

            // 3) Play the new MP3
            mp.reset()
            mp.setDataSource(audioFile.path)
            mp.prepare()
            mp.start()

            mp.setOnCompletionListener {
                if (audioFile.exists()) {
                    audioFile.delete()
                    Log.d(TAG, "Temporary MP3 file deleted.")
                }
                // If you want a fresh MediaPlayer each time, you could do mp.release()
            }

            null
        }, IO_EXECUTOR).thenAccept { /* nothing to return */ }
    }

    /**
     * Blocking method to call ElevenLabs to produce an MP3 file. Returns the File.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun transform(text: String): File {
        val json = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_flash_v2_5")
        }

        val requestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull()!!)
        val request = Request.Builder()
            .addHeader("Content-Type", "application/json")
            .addHeader("xi-api-key", "key") // Replace with real API key
            .addHeader("Accept", "audio/mp3")
            .url("https://api.elevenlabs.io/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL")
            .post(requestBody)
            .build()

        // Blocking network call
        val response = client.callFactory().newCall(request).execute()

        val file = File(CACHE_DIR, "${UUID.randomUUID()}.mp3").apply {
            createNewFile()
            Log.d(TAG, "ElevenLabs response code: ${response.code}")
            response.body?.source()?.readByteArray()?.inputStream()?.use { input ->
                outputStream().use { output ->
                    input.transferTo(output)
                }
            }
        }
        response.close()

        return file
    }
}

/**
 * DescriptionScheduler now manages a queue of Bitmaps via a BlockingQueue.
 * A single background thread loops forever, taking items off the queue, then
 * calling GeminiService and TextToSpeechService in sequence with CompletableFutures.
 */
object DescriptionScheduler {

    private val bitmapQueue = LinkedBlockingQueue<Bitmap>(10)  // capacity=10, adjust as needed
    private val running = AtomicBoolean(false)
    private var consumerThread: Thread? = null

    /**
     * Submit a Bitmap to the queue. (No coroutines)
     */
    fun submit(bitmap: Bitmap) {
        // Blocks if the queue is full
        bitmapQueue.put(bitmap)
    }

    /**
     * Start a single consumer thread that loops indefinitely,
     * reading from the queue and processing each bitmap.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun start() {
        if (running.get()) return  // Already started
        running.set(true)

        consumerThread = Thread {
            Log.i(TAG, "DescriptionScheduler consumer loop started.")
            try {
                while (running.get()) {
                    // Take next bitmap (blocks until available)
                    val bitmap = bitmapQueue.take()

                    // 1) Describe using GeminiService
                    GeminiService.prompt(bitmap)
                        // 2) Then speak (if text is not null or empty)
                        .thenCompose { description ->
                            if (!description.isNullOrEmpty()) {
                                TextToSpeechService.speak(description)
                            } else {
                                Log.w(TAG, "Received empty description from GeminiService.")
                                CompletableFuture.completedFuture(null)
                            }
                        }
                        // 3) Handle exceptions
                        .exceptionally { ex ->
                            Log.e(TAG, "Error in DescriptionScheduler chain: ${ex.message}", ex)
                            null
                        }
                }
            } catch (ex: InterruptedException) {
                Log.i(TAG, "DescriptionScheduler was interrupted.")
            } finally {
                Log.i(TAG, "DescriptionScheduler consumer loop ending.")
            }
        }
        consumerThread?.start()
    }

    /**
     * Stop the consumer loop. Clears the queue if you want to discard pending bitmaps.
     */
    fun stop() {
        running.set(false)
        consumerThread?.interrupt()
        consumerThread = null

        // Optionally clear the queue
        // bitmapQueue.clear()
    }
}

/**
 * A simple facade that enqueues bitmaps into DescriptionScheduler.
 */
object PhotoDescriptionSpeakService {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun describe(bitmap: Bitmap) {
        // Just submit the bitmap to the queue
        DescriptionScheduler.submit(bitmap)
    }
}
