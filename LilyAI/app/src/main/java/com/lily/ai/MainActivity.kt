package com.lily.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "LilyPrefs"
        const val KEY_API_KEY = "claude_api_key"
        const val PERM_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val apiKeyInput = findViewById<EditText>(R.id.etApiKey)
        apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))

        // Step 1: Save API key + request permissions
        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter your Claude API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_API_KEY, key).apply()
            Toast.makeText(this, "✓ API key saved! Requesting permissions...", Toast.LENGTH_SHORT).show()
            requestAllPermissions()
        }

        // Step 2a: Notification access (reads WhatsApp messages)
        findViewById<Button>(R.id.btnNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "Find 'Lily AI' and enable it", Toast.LENGTH_LONG).show()
        }

        // Step 2b: Accessibility access (controls apps)
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Find 'Lily Voice Control' under Installed Apps and enable it", Toast.LENGTH_LONG).show()
        }

        // Step 3: Start Lily
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            val key = prefs.getString(KEY_API_KEY, "")
            if (key.isNullOrEmpty()) {
                Toast.makeText(this, "Please enter and save your API key first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startLilyService()
            Toast.makeText(this, "Lily is now active! Say 'Hey Lily' anytime.", Toast.LENGTH_LONG).show()
        }

        // Stop Lily
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, LilyForegroundService::class.java))
            Toast.makeText(this, "Lily stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val notifGranted = isNotificationListenerEnabled()
        val accessGranted = isAccessibilityEnabled()

        findViewById<TextView>(R.id.tvNotifStatus).apply {
            text = if (notifGranted) "✓ Notification Access: Enabled" else "✗ Notification Access: Not enabled (tap button below)"
            setTextColor(if (notifGranted) 0xFF1B8A5A.toInt() else 0xFFE24B4A.toInt())
        }

        findViewById<TextView>(R.id.tvAccessStatus).apply {
            text = if (accessGranted) "✓ Accessibility: Enabled" else "✗ Accessibility: Not enabled (tap button below)"
            setTextColor(if (accessGranted) 0xFF1B8A5A.toInt() else 0xFFE24B4A.toInt())
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${LilyAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(service, ignoreCase = true)) return true
        }
        return false
    }

    private fun requestAllPermissions() {
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest, PERM_REQUEST)
        } else {
            Toast.makeText(this, "✓ All permissions already granted!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "✓ All permissions granted! Now enable Notification & Accessibility access.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Some permissions denied — Lily may not work fully.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLilyService() {
        val intent = Intent(this, LilyForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
