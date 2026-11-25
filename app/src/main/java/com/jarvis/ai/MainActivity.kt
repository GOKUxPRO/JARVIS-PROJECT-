package com.jarvis.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.ai.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        Toast.makeText(
            this,
            if (allGranted) "Permissions granted!" else "Some permissions denied",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        b.apiKeyInput.setText(prefs.getString("api_key", ""))

        val languages = arrayOf("English", "Hindi", "Gujarati")
        val langCodes = arrayOf("en-IN", "hi-IN", "gu-IN")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages)
        b.languageSpinner.adapter = adapter
        val savedLang = prefs.getString("language", "en-IN") ?: "en-IN"
        b.languageSpinner.setSelection(langCodes.indexOf(savedLang).coerceAtLeast(0))
        b.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("language", langCodes[pos]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.saveKeyBtn.setOnClickListener {
            val key = b.apiKeyInput.text.toString().trim()
            if (key.length < 30 || !key.startsWith("AIza")) {
                Toast.makeText(this, "Invalid API key (AIza thi shuru thavu joie)", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).apply()
            Toast.makeText(this, "API Key saved!", Toast.LENGTH_SHORT).show()
        }

        b.permissionBtn.setOnClickListener { requestPermissions() }
        b.accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        b.startBtn.setOnClickListener {
            val key = prefs.getString("api_key", "")
            if (key.isNullOrEmpty()) {
                Toast.makeText(this, "Pehla API Key nakho", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Pehla mic permission aapo", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (JarvisAccessibilityService.instance == null) {
                Toast.makeText(this, "Pehla Accessibility Service enable karo", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val intent = Intent(this, VoiceAssistantService::class.java)
            ContextCompat.startForegroundService(this, intent)
            b.statusText.text = "Status: Jarvis Listening 👂 (Say 'Jarvis ...')"
        }

        b.stopBtn.setOnClickListener {
            stopService(Intent(this, VoiceAssistantService::class.java))
            b.statusText.text = "Status: Stopped"
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }
}
