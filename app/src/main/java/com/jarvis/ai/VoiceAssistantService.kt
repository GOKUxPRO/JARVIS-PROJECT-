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
import android.speech.tts.UtteranceProgressListener
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
    private var restartJob: Job? = null
    private var language = "en-IN"

    private val wakeWords = listOf(
        "jarvis", "javis", "jervis", "jarves", "jarvish", "jarviss", "jarwis",
        "जारविस", "जार्विस", "जारवीस", "जारवीस",
        "જારવિસ", "જાર્વિસ", "જારવીસ"
    )

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val key = prefs.getString("api_key", "") ?: ""
        language = prefs.getString("language", "en-IN") ?: "en-IN"
        Log.d("Jarvis", "Service started, lang=$language, keyLen=${key.length}")
        gemini = GeminiClient(key)
        tts = TextToSpeech(this, this)
        startForeground(1, buildNotification("Initializing..."))
        setupSpeech()
    }

    private fun buildNotification(text: String): Notification {
        val ch = "jarvis_ch"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(NotificationChannel(ch, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, ch)
            .setContentTitle("Jarvis Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
    }

    private fun setupSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("Jarvis", "Speech recognition NOT available on this device!")
            speak("Speech recognition not available")
            return
        }
        if (::speech.isInitialized) {
            try { speech.destroy() } catch (_: Exception) {}
        }
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull() ?: ""
                Log.d("Jarvis", "onResults: '$text'")
                errorCount = 0
                if (text.isNotEmpty()) handleResult(text) else scheduleRestart(true)
            }
            override fun onPartialResults(p: Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrEmpty()) Log.d("Jarvis", "partial: '$t'")
            }
            override fun onError(error: Int) {
                Log.e("Jarvis", "onError: code=$error")
                if (isProcessing) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Normal - user didn't speak. Quiet restart.
                        scheduleRestart(true)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        Log.w("Jarvis", "Recognizer busy - recreating after 2s")
                        scope.launch {
                            delay(2000)
                            if (isRunning && !isProcessing) setupSpeech()
                        }
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                        Log.e("Jarvis", "Network error - check internet")
                        speak("Internet check karo")
                        scheduleRestart(false)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Log.e("Jarvis", "Mic permission denied!")
                        speak("Mic permission aapo")
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        Log.w("Jarvis", "Client error - long wait")
                        scope.launch {
                            delay(3000)
                            if (isRunning && !isProcessing) setupSpeech()
                        }
                    }
                    else -> scheduleRestart(false)
                }
            }
            override fun onReadyForSpeech(p: Bundle?) { Log.d("Jarvis", "Ready for speech") }
            override fun onBeginningOfSpeech() { Log.d("Jarvis", "Speech started") }
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { Log.d("Jarvis", "Speech ended") }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        startListening()
    }

    private fun startListening() {
        if (!isRunning || isProcessing) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            Log.d("Jarvis", "startListening lang=$language")
            speech.startListening(intent)
        } catch (e: Exception) {
            Log.e("Jarvis", "startListening exception", e)
        }
    }

    private fun scheduleRestart(quick: Boolean) {
        if (!isRunning) return
        restartJob?.cancel()
        restartJob = scope.launch {
            errorCount++
            val backoff = if (quick) 500L else minOf(1500L * errorCount, 10000L)
            delay(backoff)
            if (isRunning && !isProcessing) {
                if (errorCount >= 6) {
                    Log.w("Jarvis", "Too many errors ($errorCount), recreating recognizer")
                    errorCount = 0
                    setupSpeech()
                } else {
                    startListening()
                }
            }
        }
    }

    private fun findWakeWord(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        for (w in wakeWords) if (lower.contains(w)) return w
        return null
    }

    private fun handleResult(text: String) {
        if (!isRunning || isProcessing) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) { scheduleRestart(true); return }

        Log.d("Jarvis", "handleResult: '$trimmed' activated=$activated")

        if (!activated) {
            val wake = findWakeWord(trimmed)
            if (wake != null) {
                val idx = trimmed.lowercase(Locale.ROOT).indexOf(wake)
                val cmd = trimmed.substring(idx + wake.length)
                    .trim().trimStart(',', '.', ':', ' ', '!', '?', '-').trim()
                if (cmd.isNotEmpty()) {
                    Log.d("Jarvis", "Wake + command: '$cmd'")
                    runCommand(cmd)
                } else {
                    speak("Yes?")
                    activated = true
                    startListening()
                }
            } else {
                Log.d("Jarvis", "No wake word, ignoring")
                scheduleRestart(true)
            }
            return
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.contains("stop") || lower.contains("bye") || lower.contains("bandh")) {
            speak("Okay")
            activated = false
            scheduleRestart(true)
            return
        }
        runCommand(trimmed)
    }

    private fun runCommand(cmd: String) {
        isProcessing = true
        restartJob?.cancel()
        scope.launch {
            try {
                Log.d("Jarvis", "Gemini command: '$cmd'")
                val resp = gemini.processCommand(cmd, history) { action, target ->
                    JarvisAccessibilityService.instance?.executeAction(action, target)
                        ?: "Accessibility service not enabled"
                }
                Log.d("Jarvis", "Gemini response: '$resp'")
                speak(resp)
            } catch (e: Exception) {
                Log.e("Jarvis", "Command error", e)
                speak("Error occurred")
            } finally {
                activated = false
                isProcessing = false
                // Wait a moment for TTS to finish before listening again
                scope.launch { delay(800); scheduleRestart(true) }
            }
        }
    }

    private fun speak(text: String) {
        if (text.isNullOrBlank()) return
        try {
            val id = "j${System.currentTimeMillis()}"
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            Log.d("Jarvis", "speak('$text') -> $result, ttsReady=$ttsReady")
        } catch (e: Exception) {
            Log.e("Jarvis", "speak exception", e)
        }
    }

    override fun onInit(status: Int) {
        Log.d("Jarvis", "TTS onInit status=$status")
        if (status == TextToSpeech.SUCCESS) {
            try {
                val locale = Locale.forLanguageTag(language)
                val langResult = tts.setLanguage(locale)
                Log.d("Jarvis", "setLanguage($language) -> $langResult")
                if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                    langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w("Jarvis", "Language unsupported, falling back to en-US")
                    tts.setLanguage(Locale.US)
                }
                tts.setSpeechRate(0.95f)
                ttsReady = true
            } catch (e: Exception) {
                Log.e("Jarvis", "TTS setup error, using default", e)
                try { tts.setLanguage(Locale.US); ttsReady = true } catch (_: Exception) {}
            }
        } else {
            Log.e("Jarvis", "TTS init FAILED status=$status")
            ttsReady = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        restartJob?.cancel()
        scope.cancel()
        try { speech.destroy() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
