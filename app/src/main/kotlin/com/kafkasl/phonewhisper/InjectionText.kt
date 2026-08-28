package com.kafkasl.phonewhisper

object InjectionText {
    data class ResolvedText(
        val text: String,
        val ignoredReason: String? = null
    )

    fun resolveEditableText(
        rawText: String,
        hintText: String,
        contentDescription: String,
        className: String,
        packageName: String,
        isFocused: Boolean,
        selectionStart: Int,
        selectionEnd: Int
    ): ResolvedText {
        val raw = normalize(rawText)
        if (raw.isBlank() || isEmptyHtmlSentinel(raw)) return ResolvedText("")

        val hint = normalize(hintText)
        if (hint.isNotBlank() && raw.equals(hint, ignoreCase = true)) {
            return ResolvedText("", "matches_hint")
        }

        val description = normalize(contentDescription)
        val caretAtStart = selectionStart <= 1 && selectionEnd <= 1
        val webLike = isWebLike(packageName, className)
        if (description.isNotBlank() &&
            raw.equals(description, ignoreCase = true) &&
            isFocused &&
            (caretAtStart || webLike)
        ) {
            return ResolvedText("", "matches_accessible_name")
        }

        if (webLike &&
            isFocused &&
            looksLikePlaceholder(raw, strict = !caretAtStart)
        ) {
            return ResolvedText("", "web_placeholder_like")
        }

        return ResolvedText(rawText)
    }

    private fun normalize(value: String): String =
        value
            .replace('\u00A0', ' ')
            .replace("\u200B", "")
            .replace("\uFEFF", "")
            .trim()

    private fun isEmptyHtmlSentinel(value: String): Boolean {
        val compact = value.lowercase().replace("\\s+".toRegex(), "")
        return compact == "<br>" ||
            compact == "<br/>" ||
            compact == "<div><br></div>" ||
            compact == "<p><br></p>"
    }

    private fun isWebLike(packageName: String, className: String): Boolean {
        val pkg = packageName.lowercase()
        val cls = className.lowercase()
        return pkg.contains("chrome") ||
            pkg.contains("browser") ||
            pkg.contains("webview") ||
            cls.contains("webview") ||
            cls.contains("webkit")
    }

    private fun looksLikePlaceholder(value: String, strict: Boolean): Boolean {
        if (value.length > 140) return false
        val lowered = value.lowercase()
        if (strict) {
            return strongPlaceholderPrefixes.any { lowered.startsWith(it) } ||
                placeholderExact.any { lowered == it }
        }
        return placeholderPrefixes.any { lowered.startsWith(it) } ||
            placeholderExact.any { lowered == it }
    }

    private val strongPlaceholderPrefixes = listOf(
        "\u043d\u0430\u043f\u0438\u0448\u0438\u0442\u0435, \u043a\u0430\u043a \u0432\u044b \u0431\u0443\u0434\u0435\u0442\u0435", // Kwork offer description
        "\u043d\u0430\u043f\u0438\u0448\u0438\u0442\u0435 \u0441\u0432\u043e\u0435 \u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0435",
        "write your message",
        "enter your message"
    )

    private val placeholderPrefixes = listOf(
        "\u043d\u0430\u043f\u0438\u0448\u0438\u0442\u0435", // napishite
        "\u0432\u0432\u0435\u0434\u0438\u0442\u0435", // vvedite
        "\u0443\u043a\u0430\u0436\u0438\u0442\u0435", // ukazhite
        "\u0432\u044b\u0431\u0435\u0440\u0438\u0442\u0435", // vyberite
        "\u043e\u043f\u0438\u0448\u0438\u0442\u0435", // opishite
        "\u0440\u0430\u0441\u0441\u043a\u0430\u0436\u0438\u0442\u0435", // rasskazhite
        "\u0434\u043e\u0431\u0430\u0432\u044c\u0442\u0435", // dobavte
        "write ",
        "enter ",
        "type ",
        "search "
    )

    private val placeholderExact = listOf(
        "\u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0435", // soobschenie
        "message"
    )
}
