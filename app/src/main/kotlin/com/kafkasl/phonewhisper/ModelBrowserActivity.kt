package com.kafkasl.phonewhisper

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Full-catalog model browser: search, filter (language / architecture / size),
 * sort (downloads / size / name), download and select any app-loadable
 * sherpa-onnx offline model. Sizes are fetched lazily in the background.
 */
class ModelBrowserActivity : AppCompatActivity() {

    private enum class Sort(val label: String) { DOWNLOADS("Downloads"), SIZE("Size"), NAME("Name") }

    private val LANGUAGES = listOf(
        "All" to "", "English" to "en", "French" to "fr", "German" to "de",
        "Spanish" to "es", "Italian" to "it", "Portuguese" to "pt", "Dutch" to "nl",
        "Russian" to "ru", "Chinese" to "zh", "Japanese" to "ja", "Korean" to "ko",
    )
    private val LANG_LABELS = LANGUAGES.associate { it.second to it.first.lowercase() }

    private var all: List<HuggingFaceModelBrowser.HFModel> = emptyList()
    private val sizes = HashMap<String, Int>()          // repoId -> MB (present = known)
    private val sizeRequested = HashSet<String>()
    private val inProgress = mutableSetOf<String>()

    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private val rows = HashMap<String, Row>()           // repoId -> row views
    private val sizePool = Executors.newFixedThreadPool(6)

    // Filters
    private var query = ""
    private var langFilter = ""
    private var archFilter: ModelArch? = null
    private var maxSizeMb = 0
    private var sortMode = Sort.DOWNLOADS

    private class Row(
        val subtitle: TextView,
        val progress: LinearProgressIndicator,
        val radio: MaterialRadioButton,
        val dlBtn: MaterialButton,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Browse models"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Search box
        val search = EditText(this).apply {
            hint = "Search models…"
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)

        // Filter/sort/help buttons (horizontally scrollable)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), 0) }
        bar.addView(chip("Language") { pickLanguage() })
        bar.addView(chip("Type") { pickArch() })
        bar.addView(chip("Size") { pickSize() })
        bar.addView(chip("Sort") { pickSort() })
        bar.addView(chip("?") { showHelp() })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) })

        statusText = TextView(this).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12)); textSize = 14f
        }
        root.addView(statusText)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(listContainer)
        })

        setContentView(root)
        load()
    }

    override fun onDestroy() { sizePool.shutdownNow(); super.onDestroy() }

    override fun onResume() {
        super.onResume()
        if (all.isNotEmpty()) render() else refreshStates()
    }

    private fun load() {
        statusText.text = "Loading models from HuggingFace…"
        thread {
            val result = HuggingFaceModelBrowser.fetchModels()
            runOnUiThread {
                when (result) {
                    is HuggingFaceModelBrowser.BrowseResult.Error -> statusText.text = "Failed: ${result.message}"
                    is HuggingFaceModelBrowser.BrowseResult.Success -> { all = result.models; render() }
                }
            }
        }
    }

    // --- Rendering ---

    private fun visibleModels(): List<HuggingFaceModelBrowser.HFModel> {
        val q = query.lowercase()
        val filtered = all.filter { m ->
            (q.isEmpty() || m.displayName.lowercase().contains(q) || m.languages.lowercase().contains(q)) &&
                (archFilter == null || m.arch == archFilter) &&
                matchesLang(m, langFilter) &&
                (maxSizeMb == 0 || (sizes[m.repoId]?.let { it <= maxSizeMb } ?: true))
        }
        return when (sortMode) {
            Sort.DOWNLOADS -> filtered.sortedByDescending { it.downloads }
            Sort.NAME -> filtered.sortedBy { it.displayName }
            Sort.SIZE -> filtered.sortedBy { sizes[it.repoId] ?: Int.MAX_VALUE } // unknown last
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        rows.clear()
        inProgress.clear(); inProgress.addAll(DownloadCenter.active())
        val models = visibleModels()
        statusText.text = "${models.size} of ${all.size} models"
        for (m in models) {
            listContainer.addView(buildRow(m))
            requestSize(m.repoId)
        }
        // Reconnect to downloads still running in the service.
        models.filter { it.archive in inProgress }.forEach { observeDownload(it) }
    }

    private fun buildRow(hf: HuggingFaceModelBrowser.HFModel): View {
        val title = TextView(this).apply { text = hf.displayName; textSize = 16f }
        val subtitle = TextView(this).apply { text = subtitleFor(hf); textSize = 13f; setTextColor(secondary()) }
        val progress = LinearProgressIndicator(this).apply { visibility = View.GONE; isIndeterminate = false }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(title); addView(subtitle); addView(progress)
        }
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(
                attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialIconButtonStyle
        ).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onAction(hf) }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            val slot = dp(48)
            addView(dlBtn, LinearLayout.LayoutParams(slot, slot))
            addView(radio, LinearLayout.LayoutParams(slot, slot))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(12), dp(16), dp(12))
            addView(textCol); addView(right)
            setOnClickListener { onAction(hf) }
        }
        rows[hf.repoId] = Row(subtitle, progress, radio, dlBtn)
        refreshRow(hf)
        return row
    }

    private fun subtitleFor(hf: HuggingFaceModelBrowser.HFModel): String {
        val size = sizes[hf.repoId]?.let { "$it MB" } ?: "… MB"
        val langs = hf.languages.ifBlank { "—" }
        return "${hf.arch.label} · $langs · $size"
    }

    private fun refreshRow(hf: HuggingFaceModelBrowser.HFModel) {
        val r = rows[hf.repoId] ?: return
        if (hf.archive in inProgress) {
            r.progress.visibility = View.VISIBLE; r.radio.visibility = View.GONE; r.dlBtn.visibility = View.GONE
            return
        }
        val active = prefs().getString("model_name", "") == hf.archive
        val installed = ModelDownloader.isInstalled(this, HuggingFaceModelBrowser.toModel(hf))
        r.radio.isChecked = active
        r.radio.visibility = if (installed) View.VISIBLE else View.GONE
        r.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        if (r.progress.visibility == View.GONE) r.subtitle.text = subtitleFor(hf)
    }

    private fun refreshStates() { all.forEach { refreshRow(it) } }

    // --- Size lazy loading ---

    private fun requestSize(repoId: String) {
        if (repoId in sizeRequested || repoId in sizes) return
        sizeRequested.add(repoId)
        sizePool.submit {
            val mb = HuggingFaceModelBrowser.fetchSizeMb(repoId)
            runOnUiThread {
                sizes[repoId] = mb
                all.firstOrNull { it.repoId == repoId }?.let { refreshRow(it) }
            }
        }
    }

    // --- Actions ---

    private fun onAction(hf: HuggingFaceModelBrowser.HFModel) {
        val model = HuggingFaceModelBrowser.toModel(hf).copy(sizeMb = sizes[hf.repoId] ?: 0)
        if (ModelDownloader.isInstalled(this, model)) { select(hf); return }
        inProgress.add(hf.archive); refreshRow(hf)
        observeDownload(hf)
        DownloadCenter.start(this, model) // foreground service; survives screen-off
    }

    private fun observeDownload(hf: HuggingFaceModelBrowser.HFModel) {
        DownloadCenter.observe(hf.archive) { state -> runOnUiThread { onDownloadState(hf, state) } }
    }

    private fun onDownloadState(hf: HuggingFaceModelBrowser.HFModel, state: DownloadState) {
        val r = rows[hf.repoId]
        when (state) {
            is DownloadState.Downloading -> {
                r?.progress?.visibility = View.VISIBLE
                r?.progress?.isIndeterminate = false
                r?.progress?.progress = (state.progress * 100).toInt()
                r?.subtitle?.text = "Downloading ${(state.progress * 100).toInt()}%"
            }
            is DownloadState.Extracting -> {
                r?.progress?.isIndeterminate = true
                r?.subtitle?.text = "Extracting…"
            }
            is DownloadState.Done -> {
                inProgress.remove(hf.archive)
                r?.progress?.visibility = View.GONE
                select(hf)
                toast("${hf.displayName} ready")
            }
            is DownloadState.Error -> {
                inProgress.remove(hf.archive)
                r?.progress?.visibility = View.GONE
                r?.subtitle?.text = "Error: ${state.message}"
                refreshRow(hf)
            }
        }
    }

    private fun select(hf: HuggingFaceModelBrowser.HFModel) {
        prefs().edit().putString("model_name", hf.archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshStates()
    }

    // --- Filter/sort/help dialogs ---

    private fun pickLanguage() {
        val labels = LANGUAGES.map { it.first }.toTypedArray()
        val cur = LANGUAGES.indexOfFirst { it.second == langFilter }.coerceAtLeast(0)
        single("Language", labels, cur) { langFilter = LANGUAGES[it].second; render() }
    }

    private fun pickArch() {
        val archs = listOf<ModelArch?>(null) + ModelArch.values().filter { it.isSupported }
        val labels = archs.map { it?.label ?: "All" }.toTypedArray()
        val cur = archs.indexOf(archFilter).coerceAtLeast(0)
        single("Architecture", labels, cur) { archFilter = archs[it]; render() }
    }

    private fun pickSize() {
        val opts = listOf("All" to 0, "≤ 150 MB" to 150, "≤ 300 MB" to 300, "≤ 600 MB" to 600, "≤ 1000 MB" to 1000)
        val cur = opts.indexOfFirst { it.second == maxSizeMb }.coerceAtLeast(0)
        single("Max size", opts.map { it.first }.toTypedArray(), cur) { maxSizeMb = opts[it].second; render() }
    }

    private fun pickSort() {
        val opts = Sort.values()
        val cur = opts.indexOf(sortMode)
        single("Sort by", opts.map { it.label }.toTypedArray(), cur) { sortMode = opts[it]; render() }
    }

    private fun showHelp() {
        val msg = ModelArch.values().filter { it.isSupported }
            .joinToString("\n\n") { "• ${it.label}\n${it.help}" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Model architectures")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun single(title: String, items: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(items, checked) { d, which -> onPick(which); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun matchesLang(hf: HuggingFaceModelBrowser.HFModel, code: String): Boolean {
        if (code.isBlank()) return true
        val langs = hf.languages.lowercase()
        if ("multilingual" in langs || "1600" in langs) return true
        val tokens = langs.split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        if (code in tokens) return true
        return LANG_LABELS[code]?.let { it in tokens } ?: false
    }

    // --- helpers ---
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun secondary() = attrColor(android.R.attr.textColorSecondary)
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0); ta.recycle(); return c
    }
    private fun chip(label: String, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
    }
}
