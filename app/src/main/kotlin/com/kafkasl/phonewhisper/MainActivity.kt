package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*
import kotlin.math.abs
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()
    private var langRowView: LinearLayout? = null

    /** Archives currently downloading or extracting — not yet usable. */
    private val inProgress = mutableSetOf<String>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One-time: tag pre-existing (pre-marker) model downloads as complete so
        // they still count as installed. Guarded so it never re-marks a future
        // interrupted download.
        if (!prefs().getBoolean("markers_migrated", false)) {
            ModelDownloader.migrateMarkers(this)
            prefs().edit().putBoolean("markers_migrated", true).apply()
        }

        val root = vertical(0, 0)

        // Top large header (like "Connected devices")
        val header = TextView(this).apply {
            text = "Phone Whisper"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

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

        // --- Engine Section ---
        
        val isCloud = !prefs().getBoolean("use_local", true)
        
        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "Requires OpenAI API key", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        root.addView(cloudRow)

        // Recognition language (applies to Whisper/SenseVoice/Canary; "" = auto).
        val langRow = settingsRow("Recognition language",
            languageSummary()) { showLanguagePicker() }
        langRowView = langRow
        root.addView(langRow)

        // Avoid overwriting the clipboard when a keyboard is open (insert directly).
        val avoidClipSwitch = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean("avoid_clipboard_when_keyboard", true)
            isClickable = false
        }
        root.addView(settingsRow(
            "Avoid clipboard when keyboard is open",
            "Insert text directly instead of copy-paste",
            avoidClipSwitch,
        ) {
            val v = !avoidClipSwitch.isChecked
            prefs().edit().putBoolean("avoid_clipboard_when_keyboard", v).apply()
            avoidClipSwitch.isChecked = v
        })

        // Models: "Local models" (installed) + "Suggested models" + Browse button.
        modelContainer = vertical(0)
        root.addView(modelContainer)
        rebuildModelSections()

        // --- Post-Processing Section ---
        root.addView(sectionHeader("Post-Processing"))
        
        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow("Cleanup transcript", "Uses OpenAI Chat API to fix grammar and punctuation", postProcessSwitch) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        root.addView(postProcessRow)

        promptContainer = vertical(0)
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        root.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(promptRow)

        // --- Settings Section ---
        root.addView(sectionHeader("Settings"))
        
        val keyRow = settingsRow("OpenAI API Key", "Tap to set") { promptApiKey() }
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

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
        // Reflect models installed/removed elsewhere (e.g. the browser).
        if (::modelContainer.isInitialized) rebuildModelSections()
        refresh()
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Recognition language ---

    private val LANGS = listOf(
        "English" to "en", "French" to "fr", "German" to "de", "Spanish" to "es",
        "Italian" to "it", "Portuguese" to "pt", "Dutch" to "nl", "Russian" to "ru",
        "Chinese" to "zh", "Japanese" to "ja", "Korean" to "ko",
    )

    private fun currentLangCodes(): List<String> =
        (prefs().getString("language_set", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun labelFor(code: String) = LANGS.firstOrNull { it.second == code }?.first ?: code

    /** Human summary of the language selection for the settings row subtitle. */
    private fun languageSummary(): String {
        val codes = currentLangCodes()
        return when {
            codes.isEmpty() -> "Auto-detect"
            codes.size == 1 -> "${labelFor(codes[0])} (forced)"
            else -> codes.joinToString(", ") { labelFor(it) } + " · auto-detect"
        }
    }

    /**
     * Multi-select language picker. Semantics that match the model APIs:
     * pick exactly one to force that language; pick several (or none) to
     * auto-detect (Whisper/SenseVoice can't restrict to a subset, so any
     * multi-selection means full auto-detect). The effective single code is
     * stored in "language" for the loader; the checked set in "language_set".
     */
    private fun showLanguagePicker() {
        val labels = LANGS.map { it.first }.toTypedArray()
        val cur = currentLangCodes().toMutableSet()
        val checked = BooleanArray(LANGS.size) { LANGS[it].second in cur }
        fun persist(codes: List<String>) {
            prefs().edit()
                .putString("language_set", codes.joinToString(","))
                .putString("language", if (codes.size == 1) codes[0] else "")
                .apply()
            langRowView?.findViewWithTag<TextView>("subtitle")?.text = languageSummary()
            WhisperAccessibilityService.instance?.reloadModel()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Languages (one = forced, several/none = auto-detect)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) cur.add(LANGS[which].second) else cur.remove(LANGS[which].second)
            }
            .setPositiveButton("Save") { _, _ -> persist(LANGS.map { it.second }.filter { it in cur }) }
            .setNeutralButton("Auto-detect") { _, _ -> persist(emptyList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Rebuild the model list: installed models under "Local models" (hidden when
     *  none), then not-yet-installed catalog entries under "Suggested models",
     *  then the Browse button. Called on create, on resume, and after selection. */
    private fun rebuildModelSections() {
        modelContainer.removeAllViews()
        modelRows.clear()
        // Reflect downloads running in the background service.
        inProgress.clear(); inProgress.addAll(DownloadCenter.active())

        val installed = ModelDownloader.installedModels(this)
        if (installed.isNotEmpty()) {
            modelContainer.addView(sectionHeader("Local models"))
            for (a in installed) {
                val model = displayModel(a)
                modelContainer.addView(swipeToDelete(buildModelRow(model)) { confirmDelete(model) })
            }
        }

        val suggested = MODEL_CATALOG.filter { it.archive !in installed }
        if (suggested.isNotEmpty()) {
            modelContainer.addView(sectionHeader("Suggested models"))
            for (m in suggested) modelContainer.addView(buildModelRow(m))
        }

        modelContainer.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = "Browse all models…"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ModelBrowserActivity::class.java))
            }
            layoutParams = LinearLayout.LayoutParams(LP_WRAP, LP_WRAP).apply {
                leftMargin = dp(24); topMargin = dp(8); bottomMargin = dp(4)
            }
        })

        // Reconnect to any downloads still running in the service so rows update live.
        inProgress.forEach { a ->
            observeDownload(a, MODEL_CATALOG.firstOrNull { it.archive == a }?.name ?: a)
        }
    }

    /**
     * Wrap a row so swiping it left reveals a Delete button behind it. Horizontal
     * drags are disambiguated from the ScrollView's vertical scroll; a tap still
     * activates the row (select), and a tap while open just closes it.
     */
    private fun swipeToDelete(rowContent: View, onDelete: () -> Unit): View {
        val revealPx = dp(96)
        val container = FrameLayout(this)
        container.addView(TextView(this).apply {
            text = "Delete"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            layoutParams = FrameLayout.LayoutParams(revealPx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
            setOnClickListener { onDelete() }
        })
        // Opaque background so the Delete button only shows once swiped open.
        rowContent.setBackgroundColor(attrColor(android.R.attr.colorBackground))
        container.addView(rowContent, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startTx = 0f
        var dragging = false; var opened = false
        rowContent.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Use raw (screen) coordinates: translating v would move e.x's
                    // frame and cause the row to jitter as it follows the finger.
                    downX = e.rawX; downY = e.rawY; startTx = v.translationX; dragging = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragging = true
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        v.translationX = (startTx + dx).coerceIn(-revealPx.toFloat(), 0f)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        opened = v.translationX < -revealPx / 2f
                        v.animate().translationX(if (opened) -revealPx.toFloat() else 0f)
                            .setDuration(150).start()
                        true
                    } else if (abs(e.rawX - downX) < slop && abs(e.rawY - downY) < slop) {
                        if (opened) {
                            v.animate().translationX(0f).setDuration(150).start(); opened = false
                        } else v.performClick()
                        true
                    } else false
                }
                else -> false
            }
        }
        return container
    }

    private fun confirmDelete(model: Model) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This removes the downloaded model files (${model.sizeMb} MB).")
            .setPositiveButton("Delete") { _, _ ->
                ModelDownloader.delete(this, model)
                if (prefs().getString("model_name", "") == model.archive) {
                    prefs().edit().remove("model_name").apply()
                    WhisperAccessibilityService.instance?.reloadModel()
                }
                rebuildModelSections(); refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A Model for display of an installed archive: the catalog entry if known,
     *  else a synthesized one (name/arch/size derived from the model dir). */
    private fun displayModel(archive: String): Model {
        MODEL_CATALOG.firstOrNull { it.archive == archive }?.let { return it }
        val arch = LocalTranscriber.archOf(this, archive)
        val mb = (dirSizeBytes(File(filesDir, "models/$archive")) / (1024 * 1024)).toInt()
        return Model(archive.removePrefix("sherpa-onnx-"), archive, mb.coerceAtLeast(1), arch.label, arch = arch)
    }

    private fun dirSizeBytes(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Build a model row for any model, storing views in the provided map. */
    private fun buildModelRow(model: Model, rowMap: MutableMap<String, ModelRowViews> = modelRows): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onModelAction(model) }
        }
        
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val slot = dp(48)
            addView(dlBtn, LinearLayout.LayoutParams(slot, slot).apply {
                gravity = Gravity.CENTER
            })
            addView(radio, LinearLayout.LayoutParams(slot, slot).apply {
                gravity = Gravity.CENTER
            })
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            "${model.quality} · ${model.sizeMb} MB",
            rightContainer
        ) {
            onModelAction(model)
        }
        
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        
        rowMap[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn
        )
        refreshCard(model, rowMap)
        
        return row
    }

    private fun onModelAction(model: Model) {
        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }
        inProgress.add(model.archive)
        modelRows[model.archive]?.let {
            it.dlBtn.isEnabled = false
            it.progress.visibility = View.VISIBLE
            it.progress.isIndeterminate = true
            it.subtitle.text = "Starting download…"
        }
        observeDownload(model.archive, model.name)
        DownloadCenter.start(this, model) // foreground service; survives screen-off
    }

    private fun observeDownload(archive: String, name: String) {
        DownloadCenter.observe(archive) { state -> runOnUiThread { onDownloadState(archive, name, state) } }
    }

    private fun onDownloadState(archive: String, name: String, state: DownloadState) {
        val views = modelRows[archive]
        when (state) {
            is DownloadState.Downloading -> {
                views?.progress?.visibility = View.VISIBLE
                views?.progress?.isIndeterminate = false
                views?.progress?.progress = (state.progress * 100).toInt()
                views?.subtitle?.text = "Downloading ${(state.progress * 100).toInt()}%"
            }
            is DownloadState.Extracting -> {
                views?.progress?.isIndeterminate = true
                views?.subtitle?.text = "Extracting…"
            }
            is DownloadState.Done -> {
                inProgress.remove(archive)
                toast("$name ready!")
                selectModel(archive) // rebuilds sections (moves it into Local)
            }
            is DownloadState.Error -> {
                inProgress.remove(archive)
                views?.progress?.visibility = View.GONE
                views?.subtitle?.text = "Error: ${state.message}"
                views?.dlBtn?.isEnabled = true
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        rebuildModelSections(); refresh()
    }

    private fun refreshCard(model: Model, rowMap: Map<String, ModelRowViews> = modelRows) {
        val views = rowMap[model.archive] ?: return

        // Mid-download/extraction: keep showing progress, hide radio + button.
        if (model.archive in inProgress) {
            views.progress.visibility = View.VISIBLE
            views.radio.visibility = View.GONE
            views.dlBtn.visibility = View.GONE
            return
        }

        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() {
        MODEL_CATALOG.forEach { refreshCard(it, modelRows) }
    }

    // --- Prompt Rows ---

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) {
            selectPrompt(preset.key)
        }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refreshPromptRows(); refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> current != PostProcessor.DEV_PROMPT && current != PostProcessor.SIMPLE_PROMPT
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val hasKey = !prefs().getString("api_key", "").isNullOrBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        promptContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        val apiKey = prefs().getString("api_key", "") ?: ""
        keyRowSub.text = if (apiKey.isBlank()) "Tap to set" 
                         else if (apiKey.length > 7) "sk-...${apiKey.takeLast(4)}" 
                         else "sk-...***"

        val prompt = currentPrompt()
        promptRowSub.text = prompt

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        // Ready logic
        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasKey
        val postReady = !usePostProcessing || hasKey
        val ready = audio && acc && (localReady || cloudReady) && postReady

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required"
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))
        
        refreshAllCards()
        refreshPromptRows()
    }

    private fun promptApiKey() {
        val input = EditText(this).apply {
            hint = "sk-..."
            setText(prefs().getString("api_key", ""))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("OpenAI API Key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
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
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
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
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(
            key = "dev",
            title = "Dev cleanup",
            subtitle = "Best for coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT
        ),
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt()
        )
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
