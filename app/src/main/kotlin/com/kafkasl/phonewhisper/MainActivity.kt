package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(this).apply { text = "🎤 Phone Whisper"; textSize = 24f })

        status = TextView(this).apply { textSize = 14f }
        layout.addView(status)

        // --- Engine toggle ---
        val useLocal = prefs().getBoolean("use_local", true)
        val toggle = Switch(this).apply {
            text = "Use local model (offline)"
            isChecked = useLocal
            setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean("use_local", checked).apply()
                updateStatus()
            }
        }
        layout.addView(toggle)

        // --- API key ---
        layout.addView(TextView(this).apply {
            text = "\nOpenAI API Key (for cloud mode):"
            textSize = 12f
        })
        val apiInput = EditText(this).apply {
            hint = "sk-..."
            isSingleLine = true
            setText(prefs().getString("api_key", ""))
        }
        layout.addView(apiInput)
        layout.addView(Button(this).apply {
            text = "Save API Key"
            setOnClickListener {
                prefs().edit().putString("api_key", apiInput.text.toString().trim()).apply()
                toast("Saved"); updateStatus()
            }
        })

        // --- Accessibility ---
        layout.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onResume() { super.onResume(); updateStatus() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        updateStatus()
    }

    private fun updateStatus() {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val key = (prefs().getString("api_key", "") ?: "").isNotBlank()
        val models = LocalTranscriber.availableModels(this)
        val hasLocal = models.isNotEmpty()
        val localReady = WhisperAccessibilityService.instance?.let {
            // Check if local transcriber is loaded via reflection-free check
            true // We just check if models exist on disk
        } ?: false

        status.text = buildString {
            appendLine()
            appendLine("Audio permission: ${if (audio) "✅" else "❌"}")
            appendLine("Accessibility service: ${if (acc) "✅" else "❌"}")
            appendLine()
            appendLine("── Engine ──")
            if (useLocal) {
                appendLine("Mode: 🏠 Local (offline)")
                if (hasLocal) {
                    appendLine("Models found: ${models.joinToString(", ")}")
                } else {
                    appendLine("⚠️ No models found!")
                    appendLine("Push models: make push-model MODEL=/path/to/model")
                }
            } else {
                appendLine("Mode: ☁️ Cloud (OpenAI API)")
                appendLine("API key: ${if (key) "✅" else "❌"}")
            }
            appendLine()
            val ready = audio && acc && (if (useLocal) hasLocal else key)
            if (ready) appendLine("✅ Ready! Tap the overlay dot to dictate.")
            else appendLine("Complete the setup above.")
        }
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
