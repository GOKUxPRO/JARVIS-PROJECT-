package com.jarvis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // તમારું વેરિફાઈ થયેલું એન્ડપોઈન્ટ
    private val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val systemInstruction = """
        You are Jarvis, a voice-controlled Android phone assistant.
        Use the control_phone tool to perform actions on the device.
        Available actions:
        - click (target: exact text on screen to click)
        - type (target: text to type into focused input field)
        - scroll_down, scroll_up, back, home, recents (no target needed)
        - open_app (target: app name like "YouTube", "WhatsApp")
        - search_web (target: search query)
        - torch (target: "on" or "off")
        - volume_up, volume_down, volume_mute (no target)
        - call (target: phone number)
        Keep spoken responses SHORT (1-2 sentences).
        Reply in the same language as the user (English, Hindi, or Gujarati).
        If the action succeeded, confirm briefly. If just chatting, reply concisely.
    """.trimIndent()

    suspend fun processCommand(
        userCommand: String,
        history: MutableList<JSONObject>,
        executor: (String, String?) -> String
    ): String = withContext(Dispatchers.IO) {
        try {
            history.add(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userCommand)))
            })
            trimHistory(history)

            var response = callGemini(history)
            var content = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?: return@withContext "No response"

            var functionCall = extractFunctionCall(content)
            var textResponse = extractText(content)

            if (functionCall == null) {
                history.add(JSONObject().apply {
                    put("role", "model")
                    put("parts", JSONArray().put(JSONObject().put("text", textResponse ?: "")))
                })
                return@withContext textResponse ?: "Done."
            }

            var iterations = 0
            while (functionCall != null && iterations < 3) {
                iterations++
                val args = functionCall.optJSONObject("args")
                val action = args?.optString("action") ?: ""
                val target = args?.optString("target")?.takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
                val result = executor(action, target)

                history.add(content)
                history.add(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", "control_phone")
                            put("response", JSONObject().put("result", result))
                        })
                    }))
                })

                response = callGemini(history)
                content = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                    ?: return@withContext result
                functionCall = extractFunctionCall(content)
                textResponse = extractText(content)
            }

            val finalText = textResponse ?: "Done."
            history.add(JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().put(JSONObject().put("text", finalText)))
            })
            trimHistory(history)
            return@withContext finalText
        } catch (e: Exception) {
            return@withContext "Error: ${e.message}"
        }
    }

    private fun extractFunctionCall(content: JSONObject): JSONObject? {
        val parts = content.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.has("functionCall")) return part.optJSONObject("functionCall")
        }
        return null
    }

    private fun extractText(content: JSONObject): String? {
        val parts = content.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.has("text")) sb.append(part.optString("text"))
        }
        return sb.toString().ifEmpty { null }
    }

    private fun trimHistory(history: MutableList<JSONObject>) {
        while (history.size > 12) history.removeAt(0)
    }

    private fun callGemini(history: List<JSONObject>): JSONObject {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            })
            put("contents", JSONArray(history))
            put("tools", JSONArray().put(JSONObject().apply {
                put("function_declarations", JSONArray().put(JSONObject().apply {
                    put("name", "control_phone")
                    put("description", "Control the Android phone")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("action", JSONObject().apply {
                                put("type", "string")
                                put("description", "Action: click, type, scroll_down, scroll_up, back, home, recents, open_app, search_web, torch, volume_up, volume_down, volume_mute, call")
                            })
                            put("target", JSONObject().apply {
                                put("type", "string")
                                put("description", "Target text, app name, search query, phone number, or on/off for torch")
                            })
                        })
                        put("required", JSONArray().put("action"))
                    })
                }))
            }))
            put("tool_config", JSONObject().apply {
                put("function_calling_config", JSONObject().put("mode", "AUTO"))
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 400)
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-goog-api-key", apiKey) // વેરિફાઈ થયેલી કી માટે હેડર
            .post(body.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                when (response.code) {
                    400 -> throw Exception("Bad Request. Check your command.")
                    401 -> throw Exception("API Key invalid or expired.")
                    403 -> throw Exception("Permission denied. Check API settings.")
                    429 -> throw Exception("Too many requests. Wait a moment.")
                    else -> throw Exception("API ${response.code}: ${bodyStr.take(100)}")
                }
            }
            return JSONObject(bodyStr)
        }
    }
}
