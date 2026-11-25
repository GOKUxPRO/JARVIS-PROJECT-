package com.jarvis.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {

    companion object { var instance: JarvisAccessibilityService? = null }
    private var torchOn = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun executeAction(action: String, target: String?): String {
        return try {
            when (action) {
                "click" -> clickText(target)
                "type" -> typeText(target)
                "scroll_down" -> swipeVertical(true)
                "scroll_up" -> swipeVertical(false)
                "back" -> { performGlobalAction(GLOBAL_ACTION_BACK); "Back" }
                "home" -> { performGlobalAction(GLOBAL_ACTION_HOME); "Home" }
                "recents" -> { performGlobalAction(GLOBAL_ACTION_RECENTS); "Recents" }
                "open_app" -> openApp(target)
                "search_web" -> searchWeb(target)
                "torch" -> setTorch(target == "on" || (target == null && !torchOn))
                "volume_up" -> changeVolume(true)
                "volume_down" -> changeVolume(false)
                "volume_mute" -> muteVolume()
                "call" -> dialNumber(target)
                else -> "Unknown action: $action"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun clickText(text: String?): String {
        if (text.isNullOrBlank()) return "No target text"
        val root = rootInActiveWindow ?: return "No screen"
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) return "Not found: $text"
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) {
                    cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return "Clicked '$text'"
                }
                cur = cur.parent
            }
        }
        return "Found '$text' but not clickable"
    }

    private fun typeText(text: String?): String {
        if (text.isNullOrBlank()) return "No text"
        val root = rootInActiveWindow ?: return "No screen"
        var focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus == null) focus = findEditableNode(root)
        if (focus == null) return "No input field found"
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return "Typed '$text'"
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isFocused) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun swipeVertical(down: Boolean): String {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        val startY = if (down) h * 0.75f else h * 0.25f
        val endY = if (down) h * 0.25f else h * 0.75f
        path.moveTo(w / 2, startY)
        path.lineTo(w / 2, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
        return if (down) "Scrolled down" else "Scrolled up"
    }

    private fun openApp(name: String?): String {
        if (name.isNullOrBlank()) return "No app name"
        val pm = packageManager
        val query = name.lowercase().trim()
        val apps = pm.getInstalledApplications(0)
        var best: android.content.pm.ApplicationInfo? = null
        var bestScore = 0
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val score = when {
                label == query -> 100
                label.startsWith(query) -> 80
                label.contains(query) -> 60
                query.contains(label) -> 50
                else -> 0
            }
            if (score > bestScore) { bestScore = score; best = app }
        }
        val app = best ?: return "App not found: $name"
        val intent = pm.getLaunchIntentForPackage(app.packageName) ?: return "Cannot launch $name"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Opened ${pm.getApplicationLabel(app)}"
    }

    private fun searchWeb(query: String?): String {
        if (query.isNullOrBlank()) return "No query"
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Searching '$query'"
    }

    private fun setTorch(on: Boolean): String {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flash available"
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Torch on" else "Torch off"
        } catch (e: Exception) { "Torch error: ${e.message}" }
    }

    private fun changeVolume(up: Boolean): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        return if (up) "Volume up" else "Volume down"
    }

    private fun muteVolume(): String {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        return "Muted"
    }

    private fun dialNumber(number: String?): String {
        if (number.isNullOrBlank()) return "No number"
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return "Dialing $number"
    }
}
