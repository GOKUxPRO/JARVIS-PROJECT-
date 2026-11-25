package com.jarvis.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
    private lateinit var audioManager: AudioManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var gemini: GeminiClient
    private val history = mutableListOf<JSONObject>()
    
    private var ttsReady = false
    private var isRunning = true
    private var isProcessing = false
    private var activated = false
    private var errorCount = 0
    private var restartJob: Job? = null
    private var language = "gu-IN" // ડિફોલ્ટ ગુજરાતી

    private val wakeWords = listOf(
        "jarvis", "javis", "jervis", "jarves", "jarvish", "jarviss", "jarwis",
        "जारविस", "जार्विस", "जारवीस",
        "જારવિસ", "જાર્વિસ", "જારવીસ"
    )

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val key = prefs.getString("api_key", "") ?: ""
        language = prefs.getString("language", "gu-IN") ?: "gu-IN"
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    // માઇકનો અવાજ (બીપ) બંધ કરવા માટે
    private fun muteBeep() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        } catch (e: Exception) {}
    }

    // અવાજ પાછો ચાલુ કરવા માટે
    private fun unmuteBeep() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
        } catch (e: Exception) {}
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(1, buildNotification(text))
    }

    private fun setupSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        
        if (::speech.isInitialized) {
            try { speech.destroy() } catch (_: Exception) {}
        }
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                unmuteBeep()
                updateNotification("Mic: OFF (Processing...)")
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                errorCount = 0
                if (text.isNotEmpty()) handleResult(text) else scheduleRestart(true)
            }
            override fun onError(error: Int) {
                unmuteBeep()
                updateNotification("Mic: OFF (Waiting for Jarvis)")
                if (isProcessing) return
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    scheduleRestart(true)
                } else {
                    scheduleRestart(false)
                }
            }
            override fun onReadyForSpeech(p: Bundle?) { 
                unmuteBeep() 
                updateNotification("Mic: ON (Listening...)")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {
                muteBeep()
                updateNotification("Mic: OFF (Processing...)")
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        startListening()
    }

    private fun startListening() {
        if (!isRunning || isProcessing) return
        muteBeep() 
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speech.startListening(intent)
        } catch (e: Exception) {
            unmuteBeep()
        }
    }

    private fun scheduleRestart(quick: Boolean) {
        if (!isRunning) return
        restartJob?.cancel()
        restartJob = scope.launch {
            errorCount++
            val backoff = if (quick) 1000L else minOf(2000L * errorCount, 10000L)
            delay(backoff)
            if (isRunning && !isProcessing) {
                startListening()
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

        if (!activated) {
            val wake = findWakeWord(trimmed)
            if (wake != null) {
                val idx = trimmed.lowercase(Locale.ROOT).indexOf(wake)
                val cmd = trimmed.substring(idx + wake.length).trim().trimStart(',', '.', ':', ' ', '!', '?', '-').trim()
                
                if (cmd.isNotEmpty()) {
                    runCommand(cmd)
                } else {
                    // જ્યારે ફક્ત "Jarvis" બોલો ત્યારે ગુજરાતીમાં "બોલો બોસ" કહેશે
                    speak("બોલો બોસ.")
                    activated = true
                }
            } else {
                scheduleRestart(true)
            }
            return
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.contains("stop") || lower.contains("bye") || lower.contains("bandh") || lower.contains("બંધ")) {
            speak("ઠીક છે બોસ.")
            activated = false
            return
        }
        runCommand(trimmed)
    }

    private fun runCommand(cmd: String) {
        isProcessing = true
        restartJob?.cancel()
        updateNotification("Mic: OFF (Thinking...)")
        scope.launch {
            try {
                val resp = gemini.processCommand(cmd, history) { action, target ->
                    JarvisAccessibilityService.instance?.executeAction(action, target) ?: "Accessibility off"
                }
                speak(resp)
            } catch (e: Exception) {
                speak("માફ કરશો, એરર આવી છે.")
            } finally {
                activated = false
                isProcessing = false
            }
        }
    }

    private fun speak(text: String) {
        if (text.isNullOrBlank()) return
        val id = "j${System.currentTimeMillis()}"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val locale = Locale.forLanguageTag(language)
                val langResult = tts.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US)
                }
                ttsReady = true
            } catch (e: Exception) {
                tts.setLanguage(Locale.US)
                ttsReady = true
            }
            
            // અવાજ પૂરો થાય પછી જ માઇક ચાલુ કરવા માટે
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        delay(600)
                        if (isRunning && !isProcessing) startListening()
                    }
                }
                override fun onError(utteranceId: String?) {
                    if (isRunning && !isProcessing) startListening()
                }
            })
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
