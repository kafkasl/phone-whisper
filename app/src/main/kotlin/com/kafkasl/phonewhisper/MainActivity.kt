package com.kafkasl.phonewhisper

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var shortcutsContainer: LinearLayout
    private lateinit var recoverySub: TextView
    private lateinit var recoveryBadge: TextView
    private lateinit var cloudSwitch: MaterialSwitch
    private lateinit var cloudSub: TextView
    private lateinit var cloudContainer: LinearLayout
    private lateinit var localContainer: LinearLayout
    private lateinit var providerSub: TextView
    private lateinit var sttKeyTitle: TextView
    private lateinit var sttKeySub: TextView
    private lateinit var streamModelSub: TextView
    private lateinit var retryModelSub: TextView
    private lateinit var cleanupUrlSub: TextView
    private lateinit var cleanupModelSub: TextView
    private lateinit var cleanupKeySub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView

    private val modelRows = mutableMapOf<String, ModelRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Phone Whisper"
            textSize = 32f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            setPadding(dp(24), dp(56), dp(24), dp(20))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Shortcuts Section (Default tap + Long-press 1..4) ---
        root.addView(sectionHeader("Shortcuts"))
        shortcutsContainer = vertical(0)
        root.addView(shortcutsContainer)

        // --- Recovery Section ---
        root.addView(sectionHeader("Recovery"))
        recoveryBadge = TextView(this).apply {
            text = "Review"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val recoveryRow = settingsRow("Saved recordings", "Checking...", recoveryBadge) {
            showSavedRecordingsDialog()
        }
        recoverySub = recoveryRow.findViewWithTag("subtitle")
        root.addView(recoveryRow)

        // --- Transcription Section ---
        root.addView(sectionHeader("Transcription"))

        val isCloud = !prefs().getBoolean(AppPrefs.KEY_USE_LOCAL, false)
        cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow(
            "Use cloud streaming",
            "Real-time WebSocket transcription with live top bar preview",
            cloudSwitch
        ) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean(AppPrefs.KEY_USE_LOCAL, !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        cloudSub = cloudRow.findViewWithTag("subtitle")
        root.addView(cloudRow)

        // Cloud settings group
        cloudContainer = vertical(0)
        val providerRow = settingsRow("Streaming provider", "ElevenLabs Scribe v3 Live") {
            val next = if (AppPrefs.isElevenLabs(prefs())) AppPrefs.PROVIDER_GEMINI else AppPrefs.PROVIDER_ELEVENLABS
            prefs().edit().putString(AppPrefs.KEY_STT_PROVIDER, next).apply()
            refresh()
        }
        providerSub = providerRow.findViewWithTag("subtitle")
        cloudContainer.addView(providerRow)

        val keyRow = settingsRow("ElevenLabs API key", "Tap to set") {
            val isEl = AppPrefs.isElevenLabs(prefs())
            promptTextSetting(
                title = if (isEl) "ElevenLabs API key" else "Gemini API key",
                hint = if (isEl) "sk_..." else "AIza...",
                current = AppPrefs.activeSttApiKey(prefs()),
                prefKey = if (isEl) AppPrefs.KEY_ELEVENLABS_API_KEY else AppPrefs.KEY_GEMINI_API_KEY
            )
        }
        sttKeyTitle = (keyRow.getChildAt(0) as LinearLayout).getChildAt(0) as TextView
        sttKeySub = keyRow.findViewWithTag("subtitle")
        cloudContainer.addView(keyRow)

        val streamRow = settingsRow("Streaming model", AppPrefs.DEFAULT_ELEVENLABS_STREAM_MODEL) {
            val isEl = AppPrefs.isElevenLabs(prefs())
            promptTextSetting(
                title = "Streaming model",
                hint = if (isEl) AppPrefs.DEFAULT_ELEVENLABS_STREAM_MODEL else AppPrefs.DEFAULT_STREAMING_MODEL,
                current = if (isEl) AppPrefs.elevenLabsStreamModel(prefs()) else AppPrefs.streamingModel(prefs()),
                prefKey = if (isEl) AppPrefs.KEY_ELEVENLABS_STREAM_MODEL else AppPrefs.KEY_STREAMING_MODEL,
                defaultVal = if (isEl) AppPrefs.DEFAULT_ELEVENLABS_STREAM_MODEL else AppPrefs.DEFAULT_STREAMING_MODEL
            )
        }
        streamModelSub = streamRow.findViewWithTag("subtitle")
        cloudContainer.addView(streamRow)

        val retryRow = settingsRow("Offline retry model", AppPrefs.DEFAULT_ELEVENLABS_RETRY_MODEL) {
            val isEl = AppPrefs.isElevenLabs(prefs())
            promptTextSetting(
                title = "Offline retry model",
                hint = if (isEl) AppPrefs.DEFAULT_ELEVENLABS_RETRY_MODEL else AppPrefs.DEFAULT_RETRY_MODEL,
                current = if (isEl) AppPrefs.elevenLabsRetryModel(prefs()) else AppPrefs.retryModel(prefs()),
                prefKey = if (isEl) AppPrefs.KEY_ELEVENLABS_RETRY_MODEL else AppPrefs.KEY_RETRY_MODEL,
                defaultVal = if (isEl) AppPrefs.DEFAULT_ELEVENLABS_RETRY_MODEL else AppPrefs.DEFAULT_RETRY_MODEL
            )
        }
        retryModelSub = retryRow.findViewWithTag("subtitle")
        cloudContainer.addView(retryRow)
        root.addView(cloudContainer)

        // Local models group (hidden when cloud streaming is enabled)
        localContainer = vertical(0)
        localContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) localContainer.addView(buildModelRow(m))
        root.addView(localContainer)

        // --- Cleanup Model Section (OpenAI-Compatible) ---
        root.addView(sectionHeader("Cleanup Model"))

        val cleanupModelRow = settingsRow("Model", AppPrefs.DEFAULT_CLEANUP_MODEL) {
            promptTextSetting(
                title = "Cleanup model",
                hint = AppPrefs.DEFAULT_CLEANUP_MODEL,
                current = AppPrefs.cleanupModel(prefs()),
                prefKey = AppPrefs.KEY_CLEANUP_MODEL,
                defaultVal = AppPrefs.DEFAULT_CLEANUP_MODEL
            )
        }
        cleanupModelSub = cleanupModelRow.findViewWithTag("subtitle")
        root.addView(cleanupModelRow)

        val cleanupUrlRow = settingsRow("Base URL", AppPrefs.DEFAULT_CLEANUP_BASE_URL) {
            promptTextSetting(
                title = "OpenAI-compatible base URL",
                hint = AppPrefs.DEFAULT_CLEANUP_BASE_URL,
                current = AppPrefs.cleanupBaseUrl(prefs()),
                prefKey = AppPrefs.KEY_CLEANUP_BASE_URL,
                defaultVal = AppPrefs.DEFAULT_CLEANUP_BASE_URL
            )
        }
        cleanupUrlSub = cleanupUrlRow.findViewWithTag("subtitle")
        root.addView(cleanupUrlRow)

        val cleanupKeyRow = settingsRow("Separate cleanup API key", "Using Gemini API key") {
            promptTextSetting(
                title = "Separate cleanup API key (optional)",
                hint = "Leave blank to use Gemini API key",
                current = prefs().getString(AppPrefs.KEY_CLEANUP_API_KEY, "") ?: "",
                prefKey = AppPrefs.KEY_CLEANUP_API_KEY
            )
        }
        cleanupKeySub = cleanupKeyRow.findViewWithTag("subtitle")
        root.addView(cleanupKeyRow)

        val promptRow = settingsRow("Cleanup prompt", PostProcessor.DEFAULT_PROMPT) {
            promptCleanupInstructions()
        }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = TextUtils.TruncateAt.END
        root.addView(promptRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))

        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        refresh()
    }

    // --- Shortcuts Rows & Editor ---

    private fun refreshShortcuts() {
        shortcutsContainer.removeAllViews()
        val presets = ShortcutStore.load(prefs())
        val primary = attrColor(com.google.android.material.R.attr.colorPrimary)
        val muted = attrColor(android.R.attr.textColorSecondary)

        for (preset in presets) {
            val badgeView = TextView(this).apply {
                text = if (preset.enabled) preset.badge else "OFF"
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (preset.enabled) primary else muted)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(99).toFloat()
                    setColor(if (preset.enabled) (primary and 0x00FFFFFF) or 0x22000000 else 0x16808080)
                }
            }
            val sub = if (preset.enabled) {
                "${preset.name} · ${preset.summary()}"
            } else {
                "Off · Tap to enable"
            }
            shortcutsContainer.addView(
                settingsRow(preset.slotLabel, sub, badgeView) {
                    showPresetEditorDialog(preset)
                }
            )
        }
    }

    private fun showPresetEditorDialog(preset: ShortcutPreset) {
        val body = vertical(dp(24), dp(8))

        val enabledSwitch = MaterialSwitch(this).apply { isChecked = preset.enabled }
        if (!preset.isDefault) {
            body.addView(dialogToggleRow("Show in long-press menu", "Display this circle in the radial arc", enabledSwitch))
        }

        body.addView(fieldLabel("Circle badge (2 to 4 letters)"))
        val badgeInput = EditText(this).apply {
            setText(preset.badge)
            hint = "ES"
            filters = arrayOf(InputFilter.LengthFilter(4), InputFilter.AllCaps())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        body.addView(badgeInput)

        body.addView(fieldLabel("Display name"))
        val nameInput = EditText(this).apply {
            setText(preset.name)
            hint = "Spanish"
            filters = arrayOf(InputFilter.LengthFilter(24))
        }
        body.addView(nameInput)

        body.addView(fieldLabel("Language"))
        val langGroup = RadioGroup(this)
        val langIds = mutableMapOf<Int, String>()
        for ((code, label) in ShortcutStore.SUPPORTED_LANGUAGES) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 15f
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            langIds[rb.id] = code
            langGroup.addView(rb)
            if (code == preset.languageCode) langGroup.check(rb.id)
        }
        body.addView(langGroup)

        val postSwitch = MaterialSwitch(this).apply { isChecked = preset.postProcess }
        body.addView(dialogToggleRow("Include post-processing", "Fix grammar, jargon, and self-corrections", postSwitch))

        val ctxSwitch = MaterialSwitch(this).apply { isChecked = preset.screenContext }
        body.addView(dialogToggleRow("Include screen context", "Read visible messages and draft text", ctxSwitch))

        AlertDialog.Builder(this)
            .setTitle(preset.slotLabel)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Save") { _, _ ->
                val badge = badgeInput.text.toString().trim().uppercase().take(4).ifEmpty { preset.badge }
                val name = nameInput.text.toString().trim().ifEmpty { preset.name }
                val lang = langIds[langGroup.checkedRadioButtonId] ?: preset.languageCode
                val updated = preset.copy(
                    badge = badge,
                    name = name,
                    languageCode = lang,
                    postProcess = postSwitch.isChecked,
                    screenContext = ctxSwitch.isChecked,
                    enabled = if (preset.isDefault) true else enabledSwitch.isChecked
                )
                val all = ShortcutStore.load(prefs()).map { if (it.id == updated.id) updated else it }
                ShortcutStore.save(prefs(), all)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dialogToggleRow(title: String, sub: String, sw: MaterialSwitch): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val txt = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply { marginEnd = dp(12) }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                setTextColor(attrColor(android.R.attr.textColorPrimary))
            })
            addView(TextView(this@MainActivity).apply {
                text = sub
                textSize = 13f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
            })
        }
        row.addView(txt)
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun fieldLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(0, dp(12), 0, dp(2))
    }

    // --- Saved Recordings Recovery Dialog ---

    private fun showSavedRecordingsDialog() {
        val clips = RecordingStore.listClips(this)
        if (clips.isEmpty()) {
            toast("No saved recordings")
            return
        }

        val container = vertical(dp(20), dp(8))
        var dialog: AlertDialog? = null

        for (clip in clips) {
            val item = vertical(0).apply {
                setPadding(0, dp(10), 0, dp(10))
            }
            val meta = TextView(this).apply {
                val st = when {
                    clip.isFailed && clip.flagged -> "Failed · Flagged"
                    clip.isFailed -> "Failed"
                    clip.flagged -> "Flagged"
                    else -> "Completed"
                }
                text = "$st · ${clip.presetBadge} · ${formatDuration(clip.durationSec)}"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    when {
                        clip.isFailed -> 0xFFD93025.toInt()
                        clip.flagged -> attrColor(com.google.android.material.R.attr.colorPrimary)
                        else -> attrColor(android.R.attr.textColorSecondary)
                    }
                )
            }
            val previewStr = clip.finalText?.takeIf { it.isNotBlank() }
                ?: clip.partialText.takeIf { it.isNotBlank() }?.let { "Partial: $it" }
                ?: "Audio saved (${formatDuration(clip.durationSec)})"
            val preview = TextView(this).apply {
                text = previewStr
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(attrColor(android.R.attr.textColorPrimary))
                setPadding(0, dp(3), 0, dp(4))
            }
            val draftDiff = clip.liveDraftText.trim()
            val showDraft = draftDiff.isNotEmpty() && draftDiff != previewStr.trim()
            val draftView = if (showDraft) {
                TextView(this).apply {
                    text = "Live draft: $draftDiff"
                    textSize = 12.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                    setPadding(0, 0, 0, dp(6))
                }
            } else null

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
            }
            if (File(clip.wavPath).exists()) {
                actions.addView(dialogActionLink("Retry", primary = true) {
                    dialog?.dismiss()
                    retryClipFromActivity(clip)
                })
            }
            actions.addView(dialogActionLink(if (clip.flagged) "Unflag" else "Flag") {
                RecordingStore.setFlagged(this, clip.id, !clip.flagged)
                dialog?.dismiss()
                refresh()
                showSavedRecordingsDialog()
            })
            val copySource = clip.finalText?.takeIf { it.isNotBlank() } ?: clip.partialText.takeIf { it.isNotBlank() }
            if (copySource != null) {
                actions.addView(dialogActionLink("Copy") {
                    copyToClipboard(copySource)
                    toast("Copied to clipboard")
                })
            }
            actions.addView(dialogActionLink("Delete") {
                RecordingStore.deleteClip(this, clip.id)
                dialog?.dismiss()
                refresh()
                if (RecordingStore.listClips(this).isNotEmpty()) showSavedRecordingsDialog()
            })

            item.addView(meta)
            item.addView(preview)
            if (draftView != null) item.addView(draftView)
            item.addView(actions)
            container.addView(item)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Saved recordings")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun dialogActionLink(label: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                if (primary) attrColor(com.google.android.material.R.attr.colorPrimary)
                else attrColor(android.R.attr.textColorSecondary)
            )
            setPadding(0, dp(4), dp(18), dp(4))
            setOnClickListener { onClick() }
        }

    private fun retryClipFromActivity(clip: SavedClip) {
        val useElevenLabs = AppPrefs.isElevenLabs(prefs())
        val apiKey = AppPrefs.activeSttApiKey(prefs())
        if (apiKey.isBlank()) {
            toast(if (useElevenLabs) "Set ElevenLabs API key first" else "Set Gemini API key first")
            return
        }
        val wavFile = File(clip.wavPath)
        if (!wavFile.exists()) {
            toast("Saved audio file missing")
            return
        }
        toast("Retrying saved clip...")
        val preset = ShortcutStore.findById(prefs(), clip.presetId)

        thread {
            val bytes = try { wavFile.readBytes() } catch (e: Exception) {
                runOnUiThread { toast("Read failed: ${e.message}") }
                return@thread
            }
            val cb: (GeminiLiveClient.RestResult) -> Unit = { res ->
                if (res.text.isNullOrBlank()) {
                    runOnUiThread { toast("Retry failed: ${res.error ?: "empty"}") }
                } else {
                    val raw = res.text.trim()
                    val cleanupKey = AppPrefs.effectiveCleanupApiKey(prefs())
                    if (preset.postProcess && cleanupKey.isNotBlank()) {
                        PostProcessor.process(
                            rawText = raw,
                            prompt = AppPrefs.cleanupPrompt(prefs()),
                            apiKey = cleanupKey,
                            baseUrl = AppPrefs.cleanupBaseUrl(prefs()),
                            model = AppPrefs.cleanupModel(prefs()),
                            context = null,
                            languageCode = clip.languageCode
                        ) { pp ->
                            val finalOut = pp.text?.takeIf { it.isNotBlank() } ?: raw
                            RecordingStore.upsertClip(
                                this,
                                clip.copy(finalText = finalOut, status = RecordingStore.STATUS_COMPLETED)
                            )
                            runOnUiThread {
                                copyToClipboard(finalOut)
                                toast("Recovered and copied to clipboard")
                                refresh()
                            }
                        }
                    } else {
                        RecordingStore.upsertClip(
                            this,
                            clip.copy(finalText = raw, status = RecordingStore.STATUS_COMPLETED)
                        )
                        runOnUiThread {
                            copyToClipboard(raw)
                            toast("Recovered and copied to clipboard")
                            refresh()
                        }
                    }
                }
            }
            if (useElevenLabs) {
                ElevenLabsLiveClient.transcribeWav(bytes, apiKey, AppPrefs.elevenLabsRetryModel(prefs()), clip.languageCode, cb)
            } else {
                GeminiLiveClient.transcribeWav(bytes, apiKey, AppPrefs.retryModel(prefs()), clip.languageCode, cb)
            }
        }
    }

    // --- Local Model Rows (Zero Emojis) ---

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "Get"
            textSize = 13f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply { topMargin = dp(8) }
        }
        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(radio)
        }
        val row = settingsRow(model.name, "${model.quality} · ${model.sizeMb} MB", rightContainer) {
            onModelAction(model)
        }
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        modelRows[model.archive] = ModelRowViews(radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn)
        refreshCard(model)
        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }
        views.dlBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = "Starting download..."

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }
                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} ready")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Error: ${state.message}"
                        views.dlBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString(AppPrefs.KEY_LOCAL_MODEL, archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards()
        refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString(AppPrefs.KEY_LOCAL_MODEL, "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- State Refresh ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean(AppPrefs.KEY_USE_LOCAL, false)
        val isEl = AppPrefs.isElevenLabs(prefs())
        val sttKey = AppPrefs.activeSttApiKey(prefs())
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        cloudSwitch.isChecked = !useLocal
        cloudSub.text = if (!useLocal) {
            "Real-time WebSocket transcription with live top bar preview"
        } else {
            "Off — transcribing locally on device"
        }
        cloudContainer.visibility = if (!useLocal) View.VISIBLE else View.GONE
        localContainer.visibility = if (useLocal) View.VISIBLE else View.GONE

        providerSub.text = if (isEl) "ElevenLabs (tap to switch to Gemini)" else "Gemini Live (tap to switch to ElevenLabs)"
        sttKeyTitle.text = if (isEl) "ElevenLabs API key" else "Gemini API key"
        sttKeySub.text = AppPrefs.maskApiKey(sttKey)
        sttKeySub.setTextColor(attrColor(android.R.attr.textColorSecondary))
        if (isEl && sttKey.isNotBlank() && !useLocal) {
            ElevenLabsLiveClient.fetchSubscription(sttKey) { info ->
                if (info != null) {
                    runOnUiThread {
                        val rem = "%,d".format(info.remaining)
                        sttKeySub.text = "${AppPrefs.maskApiKey(sttKey)} · $rem credits left"
                        sttKeySub.setTextColor(
                            if (info.isLow) 0xFFD93025.toInt()
                            else attrColor(android.R.attr.textColorSecondary)
                        )
                    }
                }
            }
        }
        streamModelSub.text = if (isEl) AppPrefs.elevenLabsStreamModel(prefs()) else AppPrefs.streamingModel(prefs())
        retryModelSub.text = if (isEl) AppPrefs.elevenLabsRetryModel(prefs()) else AppPrefs.retryModel(prefs())
        cleanupUrlSub.text = AppPrefs.cleanupBaseUrl(prefs())
        cleanupModelSub.text = AppPrefs.cleanupModel(prefs())
        val separateKey = prefs().getString(AppPrefs.KEY_CLEANUP_API_KEY, "")?.trim() ?: ""
        cleanupKeySub.text = if (separateKey.isEmpty()) "Using Gemini API key" else AppPrefs.maskApiKey(separateKey)
        promptRowSub.text = AppPrefs.cleanupPrompt(prefs())

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        // Saved recordings summary
        val clips = RecordingStore.listClips(this)
        val failed = clips.count { it.isFailed }
        val flagged = clips.count { it.flagged }
        recoverySub.text = when {
            clips.isEmpty() -> "No saved recordings"
            failed > 0 && flagged > 0 -> "$failed failed · $flagged flagged · ${clips.size} total"
            failed > 0 -> "$failed failed ready to retry · ${clips.size} total"
            flagged > 0 -> "$flagged flagged · ${clips.size} total"
            else -> "${clips.size} recent recording${if (clips.size == 1) "" else "s"}"
        }
        recoverySub.setTextColor(
            when {
                failed > 0 -> 0xFFD93025.toInt()
                flagged > 0 -> attrColor(com.google.android.material.R.attr.colorPrimary)
                else -> attrColor(android.R.attr.textColorSecondary)
            }
        )
        recoveryBadge.visibility = if (clips.isNotEmpty()) View.VISIBLE else View.GONE

        val curLocal = prefs().getString(AppPrefs.KEY_LOCAL_MODEL, "") ?: ""
        if (curLocal.isBlank() || !File(filesDir, "models/$curLocal").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        val engineReady = if (useLocal) hasModel else sttKey.isNotBlank()
        val ready = audio && acc && engineReady
        statusSubtitle.text = if (ready) {
            "Ready — tap to dictate or hold for shortcuts"
        } else if (!audio || !acc) {
            "Grant permissions below"
        } else if (!useLocal) {
            if (isEl) "Set ElevenLabs API key below" else "Set Gemini API key below"
        } else {
            "Download a local model below"
        }
        statusSubtitle.setTextColor(
            if (ready) attrColor(com.google.android.material.R.attr.colorPrimary)
            else attrColor(android.R.attr.textColorSecondary)
        )

        refreshShortcuts()
        refreshAllCards()
    }

    // --- Dialogs ---

    private fun promptTextSetting(
        title: String,
        hint: String,
        current: String,
        prefKey: String,
        defaultVal: String? = null
    ) {
        val input = EditText(this).apply {
            this.hint = hint
            setText(current)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString(prefKey, input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
        if (defaultVal != null) {
            builder.setNeutralButton("Reset") { _, _ ->
                prefs().edit().putString(prefKey, defaultVal).apply()
                refresh()
            }
        }
        builder.show()
    }

    private fun promptCleanupInstructions() {
        val input = EditText(this).apply {
            hint = "System instructions"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            setText(AppPrefs.cleanupPrompt(prefs()))
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Cleanup prompt")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim().ifEmpty { PostProcessor.DEFAULT_PROMPT }
                prefs().edit().putString(AppPrefs.KEY_CLEANUP_PROMPT, text).apply()
                refresh()
            }
            .setNeutralButton("Reset default") { _, _ ->
                prefs().edit().putString(AppPrefs.KEY_CLEANUP_PROMPT, PostProcessor.DEFAULT_PROMPT).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UI Helpers (Strict Flat Android Settings Rows) ---

    private fun settingsRow(
        title: String,
        subtitle: String,
        widget: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f).apply { marginEnd = dp(12) }
        }
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textContainer)
        if (widget != null) row.addView(widget)
        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(dp(24), dp(22), dp(24), dp(6))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    private fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences(AppPrefs.PREFS_NAME, MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
