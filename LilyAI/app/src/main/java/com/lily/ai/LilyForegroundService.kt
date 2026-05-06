package com.lily.ai

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.*
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.*

class LilyForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "LilyChannel"
        const val NOTIF_ID = 1
        const val ACTION_ACTIVATE = "com.lily.ai.ACTIVATE"
        const val TAG = "LilyService"
        var isRunning = false
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningForWakeWord = false
    private var isProcessingCommand = false
    private var contacts: List<Pair<String, String>> = emptyList()

    // ─── Lifecycle ───────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForegroundCompat()
        initTTS()
        loadContacts()
        // Start wake word loop after TTS init delay
        mainHandler.postDelayed({ startWakeWordLoop() }, 3000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ACTIVATE) {
            mainHandler.post { activateCommandListening() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        destroyRecognizer()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Foreground Notification ──────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("Lily is active — Say 'Hey Lily'")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Lily AI Assistant", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Lily voice assistant running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val tapToSpeak = PendingIntent.getService(
            this, 1,
            Intent(this, LilyForegroundService::class.java).apply { action = ACTION_ACTIVATE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lily AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_btn_speak_now, "Tap to Speak", tapToSpeak)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ─── TTS ─────────────────────────────────────────────────────

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.92f)
                tts?.setPitch(1.05f)
                ttsReady = true
                speak("Lily is online, Boss. Say hey Lily anytime.")
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lily_${System.currentTimeMillis()}")
    }

    // ─── Contacts ────────────────────────────────────────────────

    private fun loadContacts() {
        Thread { contacts = ContactsHelper.getAllContacts(this) }.start()
    }

    // ─── Wake Word Loop ───────────────────────────────────────────

    private fun startWakeWordLoop() {
        if (isProcessingCommand || isListeningForWakeWord) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            mainHandler.postDelayed({ startWakeWordLoop() }, 5000)
            return
        }

        destroyRecognizer()
        isListeningForWakeWord = true

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : SimpleRecognitionListener() {
            override fun onResults(results: Bundle?) {
                isListeningForWakeWord = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.lowercase() ?: ""
                Log.d(TAG, "Wake word check: $text")
                if (isWakeWord(text)) {
                    // Strip wake word from command if it has one
                    val command = stripWakeWord(text)
                    if (command.isNotBlank()) {
                        // Command given with wake word in one breath
                        processCommand(command)
                    } else {
                        activateCommandListening()
                    }
                } else {
                    mainHandler.postDelayed({ startWakeWordLoop() }, 400)
                }
            }

            override fun onError(error: Int) {
                isListeningForWakeWord = false
                val delay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2000L
                    else -> 1500L
                }
                mainHandler.postDelayed({ startWakeWordLoop() }, delay)
            }
        })

        try {
            speechRecognizer?.startListening(buildRecognitionIntent())
        } catch (e: Exception) {
            isListeningForWakeWord = false
            mainHandler.postDelayed({ startWakeWordLoop() }, 2000)
        }
    }

    private fun isWakeWord(text: String): Boolean {
        return text.contains("lily") || text.contains("hey lily") || text.contains("ok lily") || text.contains("aye lily")
    }

    private fun stripWakeWord(text: String): String {
        return text.replace(Regex("^(hey lily|ok lily|aye lily|lily)[,\\s]*", RegexOption.IGNORE_CASE), "").trim()
    }

    // ─── Command Listening ────────────────────────────────────────

    private fun activateCommandListening() {
        if (isProcessingCommand) return
        destroyRecognizer()
        updateNotif("Listening for your command...")
        speak("Yes Boss?")

        mainHandler.postDelayed({
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : SimpleRecognitionListener() {
                override fun onResults(results: Bundle?) {
                    val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (command.isNotBlank()) {
                        processCommand(command)
                    } else {
                        speak("I didn't catch that, Boss. Say Hey Lily to try again.")
                        mainHandler.postDelayed({ startWakeWordLoop() }, 2000)
                    }
                }

                override fun onError(error: Int) {
                    speak("Sorry Boss, try again.")
                    updateNotif("Lily is active — Say 'Hey Lily'")
                    mainHandler.postDelayed({ startWakeWordLoop() }, 2000)
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    updateNotif("Listening...")
                }

                override fun onEndOfSpeech() {
                    updateNotif("Processing...")
                }
            })
            try {
                speechRecognizer?.startListening(buildRecognitionIntent())
            } catch (e: Exception) {
                mainHandler.postDelayed({ startWakeWordLoop() }, 2000)
            }
        }, 1300)
    }

    private fun buildRecognitionIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListeningForWakeWord = false
    }

    // ─── Command Processing ───────────────────────────────────────

    private fun processCommand(command: String) {
        if (isProcessingCommand) return
        isProcessingCommand = true
        updateNotif("Processing: $command")
        Log.d(TAG, "Command: $command")

        // Handle offline commands instantly
        val offlineResponse = handleOffline(command)
        if (offlineResponse != null) {
            speak(offlineResponse)
            isProcessingCommand = false
            updateNotif("Lily is active — Say 'Hey Lily'")
            mainHandler.postDelayed({ startWakeWordLoop() }, 2500)
            return
        }

        // Send to Claude AI
        Thread {
            try {
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val apiKey = prefs.getString(MainActivity.KEY_API_KEY, "") ?: ""
                val contactsStr = contacts.joinToString(" | ") { "${it.first}: ${it.second}" }
                val notifs = LilyNotificationService.getRecentNotifications()

                val result = ClaudeHelper.processCommand(apiKey, command, contactsStr, notifs)

                mainHandler.post {
                    executeAction(result)
                    isProcessingCommand = false
                    updateNotif("Lily is active — Say 'Hey Lily'")
                    mainHandler.postDelayed({ startWakeWordLoop() }, 4000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Claude error: ${e.message}")
                mainHandler.post {
                    speak("Sorry Boss, I had a connection issue. Please check your internet.")
                    isProcessingCommand = false
                    mainHandler.postDelayed({ startWakeWordLoop() }, 2000)
                }
            }
        }.start()
    }

    // ─── Offline Commands (no internet needed) ────────────────────

    private fun handleOffline(command: String): String? {
        val lower = command.lowercase()
        return when {
            lower.contains("torch on") || lower.contains("flashlight on") || lower.contains("turn on torch") || lower.contains("turn on flashlight") -> {
                toggleTorch(true); "Flashlight on, Boss."
            }
            lower.contains("torch off") || lower.contains("flashlight off") || lower.contains("turn off torch") -> {
                toggleTorch(false); "Flashlight off, Boss."
            }
            (lower.contains("what") && lower.contains("time")) || lower.contains("time is it") || lower.contains("kitna baje") -> {
                val t = java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                "It is $t, Boss."
            }
            (lower.contains("what") && lower.contains("date")) || lower.contains("today's date") || lower.contains("aaj kya date") -> {
                val d = java.text.SimpleDateFormat("EEEE, MMMM d yyyy", Locale.getDefault()).format(Date())
                "Today is $d, Boss."
            }
            lower.contains("volume up") || lower.contains("increase volume") -> {
                adjustVolume(AudioManager.ADJUST_RAISE); "Volume increased, Boss."
            }
            lower.contains("volume down") || lower.contains("decrease volume") -> {
                adjustVolume(AudioManager.ADJUST_LOWER); "Volume decreased, Boss."
            }
            lower.contains("mute") && !lower.contains("unmute") -> {
                adjustVolume(AudioManager.ADJUST_MUTE); "Muted, Boss."
            }
            lower.contains("unmute") || lower.contains("sound on") -> {
                adjustVolume(AudioManager.ADJUST_UNMUTE); "Unmuted, Boss."
            }
            lower.contains("stop") && lower.contains("lily") -> {
                speak("Going offline, Boss."); mainHandler.postDelayed({ stopSelf() }, 2000); null
            }
            else -> null
        }
    }

    private fun toggleTorch(on: Boolean) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList[0], on)
        } catch (e: Exception) {
            Log.e(TAG, "Torch error: ${e.message}")
        }
    }

    private fun adjustVolume(direction: Int) {
        (getSystemService(AUDIO_SERVICE) as AudioManager)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    // ─── Action Execution ─────────────────────────────────────────

    private fun executeAction(result: JSONObject) {
        val action = result.optString("action", "chat")
        val params = result.optJSONObject("params") ?: JSONObject()
        val spoken = result.optString("spoken_response", "Done, Boss.")

        speak(spoken)

        when (action) {

            "call" -> {
                val phone = params.optString("phone").replace("\\s".toRegex(), "")
                if (phone.isNotEmpty()) {
                    mainHandler.postDelayed({
                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }, 2200)
                }
            }

            "whatsapp" -> {
                val phone = params.optString("phone").replace("[^0-9+]".toRegex(), "")
                val message = params.optString("message", "")
                if (phone.isNotEmpty()) {
                    mainHandler.postDelayed({
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")
                            setPackage("com.whatsapp")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            // WhatsApp not installed, open in browser
                            startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        }
                    }, 2500)
                }
            }

            "sms" -> {
                val phone = params.optString("phone")
                val message = params.optString("message", "")
                mainHandler.postDelayed({
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                        putExtra("sms_body", message)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "search" -> {
                val query = params.optString("query", "")
                mainHandler.postDelayed({
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "youtube" -> {
                val query = params.optString("query", "")
                mainHandler.postDelayed({
                    // Try YouTube app first
                    val ytApp = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", query)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        startActivity(ytApp)
                    } catch (e: Exception) {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                    }
                }, 2000)
            }

            "maps" -> {
                val location = params.optString("location", "")
                mainHandler.postDelayed({
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("geo:0,0?q=${Uri.encode(location)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "alarm" -> {
                val time = params.optString("time", "07:00")
                val label = params.optString("label", "Lily Alarm")
                val parts = time.split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: 7
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                mainHandler.postDelayed({
                    startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "timer" -> {
                val seconds = params.optInt("seconds", 60)
                val label = params.optString("label", "Lily Timer")
                mainHandler.postDelayed({
                    startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "flashlight" -> {
                toggleTorch(params.optBoolean("on", true))
            }

            "volume" -> {
                val level = params.optInt("level", -1)
                if (level >= 0) {
                    val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (level.coerceIn(0, 10) * maxVol / 10.0).toInt()
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
                }
            }

            "wifi" -> {
                mainHandler.postDelayed({
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "bluetooth" -> {
                mainHandler.postDelayed({
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "open_app" -> {
                val pkg = params.optString("package", "")
                if (pkg.isNotEmpty()) {
                    mainHandler.postDelayed({
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            speak("I couldn't find that app, Boss.")
                        }
                    }, 2000)
                }
            }

            "email" -> {
                val to = params.optString("to", "")
                val subject = params.optString("subject", "")
                val body = params.optString("body", "")
                mainHandler.postDelayed({
                    startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            "read_notifications" -> {
                val notifs = LilyNotificationService.getRecentNotifications()
                speak(notifs)
            }

            "settings" -> {
                mainHandler.postDelayed({
                    startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }, 2000)
            }

            // "chat" or anything else — just speak the response
            else -> { /* spoken response already called above */ }
        }
    }
}

// ─── Helper: Silent recognition listener ──────────────────────────
abstract class SimpleRecognitionListener : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
