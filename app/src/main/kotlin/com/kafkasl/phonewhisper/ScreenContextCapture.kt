package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenContext(
    val appLabel: String = "",
    val screenText: String = "",
    val textBeforeCursor: String = "",
    val recentDictations: List<String> = emptyList()
) {
    fun isEmpty(): Boolean =
        appLabel.isBlank() && screenText.isBlank() && textBeforeCursor.isBlank() && recentDictations.isEmpty()
}

object ScreenContextCapture {
    private const val MAX_NODES = 250
    private const val MAX_DEPTH = 24
    private const val MAX_BEFORE_CURSOR_CHARS = 500
    private const val MAX_SCREEN_CHARS = 1400
    private val NOISE_TIME_RE = Regex("^\\d{1,2}:\\d{2}(\\s?[AP]M)?$", RegexOption.IGNORE_CASE)

    fun capture(
        service: AccessibilityService,
        focusedNode: AccessibilityNodeInfo?,
        recentHistory: List<String> = emptyList()
    ): ScreenContext {
        return try {
            val textBefore = extractTextBeforeCursor(focusedNode)
            val root = service.rootInActiveWindow
            val pkg = focusedNode?.packageName?.toString()
                ?: root?.packageName?.toString()
                ?: ""
            val appLabel = resolveAppLabel(service, pkg)
            val snippets = mutableListOf<String>()
            var visited = 0

            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > MAX_DEPTH || visited >= MAX_NODES) return
                visited++
                if (!node.isVisibleToUser || node.isPassword) return
                if (focusedNode != null && node == focusedNode) return

                val txt = node.text?.toString()?.trim()
                if (!txt.isNullOrEmpty() && !isNoiseText(txt)) {
                    if (snippets.lastOrNull() != txt) snippets.add(txt)
                }
                for (i in 0 until node.childCount) {
                    if (visited >= MAX_NODES) break
                    walk(node.getChild(i), depth + 1)
                }
            }

            if (root != null) walk(root, 0)
            val joined = snippets.joinToString("\n")
            val screenText = if (joined.length > MAX_SCREEN_CHARS) {
                joined.takeLast(MAX_SCREEN_CHARS)
            } else {
                joined
            }

            ScreenContext(
                appLabel = appLabel,
                screenText = screenText,
                textBeforeCursor = textBefore,
                recentDictations = recentHistory.takeLast(3)
            )
        } catch (_: Exception) {
            ScreenContext(recentDictations = recentHistory.takeLast(3))
        }
    }

    internal fun extractTextBeforeCursor(focusedNode: AccessibilityNodeInfo?): String {
        if (focusedNode == null || focusedNode.isPassword) return ""
        val full = focusedNode.text?.toString() ?: return ""
        if (full.isEmpty()) return ""
        val selStart = focusedNode.textSelectionStart
        val end = if (selStart in 0..full.length) selStart else full.length
        return full.substring(0, end).takeLast(MAX_BEFORE_CURSOR_CHARS)
    }

    private fun isNoiseText(s: String): Boolean {
        if (s.length <= 1) return true
        if (NOISE_TIME_RE.matches(s)) return true
        return false
    }

    private fun resolveAppLabel(service: AccessibilityService, pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val pm = service.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().trim()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }

    fun formatUserMessage(
        rawDictation: String,
        context: ScreenContext?,
        languageCode: String
    ): String {
        val sb = StringBuilder()
        if (context != null) {
            if (context.appLabel.isNotBlank()) {
                sb.append("<app>").append(context.appLabel.trim()).append("</app>\n")
            }
            if (context.screenText.isNotBlank()) {
                sb.append("<screen_context>\n").append(context.screenText.trim()).append("\n</screen_context>\n")
            }
            if (context.textBeforeCursor.isNotBlank()) {
                sb.append("<text_before_cursor>").append(context.textBeforeCursor).append("</text_before_cursor>\n")
            }
            if (context.recentDictations.isNotEmpty()) {
                sb.append("<recent_dictations>\n")
                for (item in context.recentDictations) {
                    if (item.isNotBlank()) sb.append("- ").append(item.trim()).append("\n")
                }
                sb.append("</recent_dictations>\n")
            }
        }
        val langLabel = when (languageCode.lowercase()) {
            "en" -> "English"
            "es" -> "Spanish"
            "ca" -> "Catalan"
            else -> ""
        }
        if (langLabel.isNotEmpty()) {
            sb.append("<language>").append(langLabel).append("</language>\n")
        }
        sb.append("<raw_dictation>\n").append(rawDictation.trim()).append("\n</raw_dictation>")
        return sb.toString()
    }

    fun validateAndCleanOutput(
        cleaned: String,
        rawDictation: String,
        textBeforeCursor: String = ""
    ): String? {
        var out = cleaned.trim()
        if (out.startsWith("<raw_dictation>") && out.endsWith("</raw_dictation>")) {
            out = out.removePrefix("<raw_dictation>").removeSuffix("</raw_dictation>").trim()
        }
        if (out.isEmpty()) return null

        val prefixTrimmed = textBeforeCursor.trim()
        if (prefixTrimmed.length >= 4 && out.startsWith(prefixTrimmed, ignoreCase = true)) {
            val remainder = out.substring(prefixTrimmed.length).trimStart()
            if (remainder.isNotEmpty()) out = remainder
        }

        val rawLen = rawDictation.trim().length
        val maxAllowed = maxOf(rawLen * 2 + 80, 300)
        if (out.length > maxAllowed) return null

        return out
    }
}
