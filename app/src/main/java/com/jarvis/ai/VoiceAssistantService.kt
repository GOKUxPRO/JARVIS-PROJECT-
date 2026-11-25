package com.jarvis.ai

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.*

class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {
    private lateinit var speech: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var gemini: GeminiClient
    private val history = mutableListOf<JSONObject>()
    private var ttsReady = false
    private var isRunning = true
    private var isProcessing = false
    private var activated = false
    private var errorCount = 0
    private var language = "en-IN"

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val key = prefs.getString("api_key", "") ?: ""
        language = prefs.getString("language", "en-IN") ?: "en-IN"
        gemini = GeminiClient(key)
        tts = TextToSpeech(this, this)
        startForeground(1, buildNotification())
        setupSpeech()
    }

    private fun buildNotification(): Notification {
        val ch = "jarvis_ch"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(ch, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, ch)
            .setContentTitle("Jarvis Active")
            .setContentText("Say 'Jarvis ...' to give a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
    }

    private fun setupSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("Jarvis", "Speech recognition not available")
            return
        }
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                errorCount = 0
                handleResult(text)
            }
            override fun onError(error: Int) { if (!isProcessing) scheduleRestart() }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        startListening()
    }

    private fun startListening() {
        if (!isRunning) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try { speech.startListening(intent) } catch (e: Exception) { Log.e("Jarvis", "start err", e) }
    }

    private fun scheduleRestart() {
        if (!isRunning) return
        scope.launch {
            errorCount++
            val backoff = minOf(400L * errorCount, 8000L)
            delay(backoff)
            if (isRunning && !isProcessing) startListening()
        }
    }

    private fun handleResult(text: String) {
        if (!isRunning || isProcessing) return
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) { scheduleRestart(); return }

        if (!activated) {
            if (lower.contains("jarvis")) {
                val cmd = lower.substringAfter("jarvis").trim().trimStart(',', '.', ':', ' ').trim()
                if (cmd.isNotEmpty()) runCommand(cmd)
                else { speak("Yes?"); activated = true; startListening() }
            } else scheduleRestart()
            return
        }

        if (lower.contains("stop") || lower.contains("bye jarvis") || lower.contains("bandh")) {
            speak("Okay"); activated = false; scheduleRestart(); return
        }
        runCommand(lower)
    }

    private fun runCommand(cmd: String) {
        isProcessing = true
        scope.launch {
            try {
                val resp = gemini.processCommand(cmd, history) { action, target ->
                    JarvisAccessibilityService.instance?.executeAction(action, target)
                        ?: "Accessibility service not enabled"
                }
                speak(resp)
            } catch (e: Exception) { speak("Error occurred") }
            finally {
                activated = false
                isProcessing = false
                scheduleRestart()
            }
        }
    }

    private fun speak(text: String) {
        if (!text.isNullOrBlank() && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "j${System.currentTimeMillis()}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try { tts.language = Locale.forLanguageTag(language); ttsReady = true }
            catch (e: Exception) { ttsReady = true }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try { speech.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
