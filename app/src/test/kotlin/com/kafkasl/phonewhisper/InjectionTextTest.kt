package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InjectionTextTest {
    @Test fun `treats matching hint as empty`() {
        val result = InjectionText.resolveEditableText(
            rawText = "Message",
            hintText = "Message",
            contentDescription = "",
            className = "android.widget.EditText",
            packageName = "com.example",
            isFocused = true,
            selectionStart = 0,
            selectionEnd = 0
        )

        assertEquals("", result.text)
        assertEquals("matches_hint", result.ignoredReason)
    }

    @Test fun `treats focused web accessible name as empty placeholder`() {
        val placeholder = "\u041d\u0430\u043f\u0438\u0448\u0438\u0442\u0435, \u043a\u0430\u043a \u0432\u044b \u0431\u0443\u0434\u0435\u0442\u0435 \u0440\u0435\u0448\u0430\u0442\u044c \u0437\u0430\u0434\u0430\u0447\u0443 \u043a\u043b\u0438\u0435\u043d\u0442\u0430"
        val result = InjectionText.resolveEditableText(
            rawText = placeholder,
            hintText = "",
            contentDescription = placeholder,
            className = "android.view.View",
            packageName = "com.android.chrome",
            isFocused = true,
            selectionStart = 0,
            selectionEnd = 0
        )

        assertEquals("", result.text)
        assertEquals("matches_accessible_name", result.ignoredReason)
    }

    @Test fun `treats focused web placeholder-like text as empty`() {
        val result = InjectionText.resolveEditableText(
            rawText = "\u041d\u0430\u043f\u0438\u0448\u0438\u0442\u0435, \u043a\u0430\u043a \u0432\u044b \u0431\u0443\u0434\u0435\u0442\u0435 \u0440\u0435\u0448\u0430\u0442\u044c \u0437\u0430\u0434\u0430\u0447\u0443 \u043a\u043b\u0438\u0435\u043d\u0442\u0430\n",
            hintText = "",
            contentDescription = "",
            className = "android.view.View",
            packageName = "com.android.chrome",
            isFocused = true,
            selectionStart = 43,
            selectionEnd = 43
        )

        assertEquals("", result.text)
        assertEquals("web_placeholder_like", result.ignoredReason)
    }

    @Test fun `keeps real web text when caret is not at placeholder start`() {
        val text = "\u041d\u0430\u043f\u0438\u0448\u0438\u0442\u0435 \u043a\u043b\u0438\u0435\u043d\u0442\u0443 \u043a\u043e\u0440\u043e\u0442\u043a\u0438\u0439 \u043f\u043b\u0430\u043d"
        val result = InjectionText.resolveEditableText(
            rawText = text,
            hintText = "",
            contentDescription = "",
            className = "android.view.View",
            packageName = "com.android.chrome",
            isFocused = true,
            selectionStart = text.length,
            selectionEnd = text.length
        )

        assertEquals(text, result.text)
        assertNull(result.ignoredReason)
    }

    @Test fun `treats trumbowyg empty html sentinel as empty`() {
        val result = InjectionText.resolveEditableText(
            rawText = "<div><br></div>",
            hintText = "",
            contentDescription = "",
            className = "android.widget.EditText",
            packageName = "com.android.chrome",
            isFocused = true,
            selectionStart = 0,
            selectionEnd = 0
        )

        assertEquals("", result.text)
        assertNull(result.ignoredReason)
    }
}
