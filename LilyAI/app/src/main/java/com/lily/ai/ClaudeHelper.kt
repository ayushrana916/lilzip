package com.lily.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

object ClaudeHelper {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"

    fun processCommand(
        apiKey: String,
        command: String,
        contacts: String,
        recentNotifications: String
    ): JSONObject {

        val systemPrompt = """
You are Lily, an AI voice assistant running on the user's Android phone. 
Parse the voice command and respond ONLY with a valid JSON object — no markdown, no explanation, nothing else.

User's saved contacts (name: phone): $contacts
Recent notifications: $recentNotifications
Current date/time: ${Date()}

Response format (always valid JSON):
{
  "action": "ACTION_NAME",
  "params": { },
  "spoken_response": "What Lily says out loud — max 20 words, natural speech, always call user Boss"
}

Available actions and their params:
- "call"              : { "phone": "+91XXXXXXXXXX", "name": "Contact Name" }
- "whatsapp"          : { "phone": "+91XXXXXXXXXX", "name": "Name", "message": "text" }
- "sms"               : { "phone": "+91XXXXXXXXXX", "name": "Name", "message": "text" }
- "search"            : { "query": "search text" }
- "youtube"           : { "query": "song or video name" }
- "maps"              : { "location": "place name or address" }
- "alarm"             : { "time": "HH:MM", "label": "Alarm label" }
- "timer"             : { "seconds": 60, "label": "Timer label" }
- "flashlight"        : { "on": true }  or  { "on": false }
- "volume"            : { "level": 0-10 }
- "wifi"              : { }
- "bluetooth"         : { }
- "open_app"          : { "package": "com.package.name", "name": "App Name" }
- "email"             : { "to": "email@x.com", "subject": "subj", "body": "body text" }
- "read_notifications": { }
- "settings"          : { }
- "chat"              : { "response": "your conversational reply" }

Key rules:
1. Always address user as "Boss" in spoken_response
2. For call/whatsapp/sms: find the phone from contacts list using fuzzy name match
   - If contact found: use their exact phone number
   - If contact NOT found: use "chat" action and tell Boss to save that contact first
3. spoken_response must be plain natural speech — no special characters, no punctuation symbols
4. Keep spoken_response under 20 words
5. Be confident, calm, and tactical like a professional AI assistant

Common app packages (use for open_app):
- WhatsApp: com.whatsapp
- Instagram: com.instagram.android
- Facebook: com.facebook.katana
- YouTube: com.google.android.youtube
- Gmail: com.google.android.gm
- Chrome: com.android.chrome
- Camera: com.android.camera2 (try also com.vivo.camera)
- Spotify: com.spotify.music
- Twitter/X: com.twitter.android
- Telegram: org.telegram.messenger
- Settings: com.android.settings
- Calculator: com.android.calculator2
- Maps: com.google.android.apps.maps

If user speaks Hindi or Hinglish, understand it and respond in English.
""".trimIndent()

        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 500)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", command)
                })
            })
        }

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 12000
            readTimeout = 20000
        }

        val bodyBytes = requestBody.toString().toByteArray(Charsets.UTF_8)
        connection.outputStream.use { it.write(bodyBytes) }

        val responseCode = connection.responseCode
        val stream = if (responseCode == 200) connection.inputStream else connection.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }

        if (responseCode != 200) {
            throw RuntimeException("API error $responseCode: $responseText")
        }

        val responseJson = JSONObject(responseText)
        val contentArray = responseJson.getJSONArray("content")
        val rawText = contentArray.getJSONObject(0).getString("text").trim()

        return try {
            // Strip markdown fences if present
            val cleaned = rawText
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            JSONObject(cleaned)
        } catch (e: Exception) {
            // Fallback: treat the entire response as a chat reply
            JSONObject().apply {
                put("action", "chat")
                put("params", JSONObject().put("response", rawText.take(200)))
                put("spoken_response", rawText.take(150))
            }
        }
    }
}
