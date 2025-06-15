package com.example.cortana

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.*

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {

    private lateinit var recognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        startForegroundService()
        startListening()
    }

    private fun startForegroundService() {
        val channelId = "VoiceAssistantChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Voice Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Voice Assistant Running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1, notification)
    }

    private fun startListening() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                startListening() // restart on error
            }
            override fun onResults(results: Bundle?) {
                val spokenText =
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
                spokenText?.let { askChatGPT(it) }
                startListening() // restart after result
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    private fun askChatGPT(prompt: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject()
                json.put("model", "llama3") // ✅ Local Ollama model
                json.put(
                    "messages",
                    org.json.JSONArray().put(
                        JSONObject().put("role", "user").put("content", prompt)
                    )
                )

                val requestBody = json.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("http://localhost:11434/v1/chat/completions") // ✅ Ollama local server
                    // NO Authorization header needed
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                Log.d("VoiceAssistantService", "Ollama raw response: $responseBody")

                val reply = if (JSONObject(responseBody).has("choices")) {
                    JSONObject(responseBody)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } else {
                    "Hmm, I couldn't get a reply. Please check your Ollama server."
                }

                speak(reply)

            } catch (e: Exception) {
                Log.e("VoiceAssistantService", "Error: ${e.message}")
            }
        }
    }



    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.destroy()
        tts.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
