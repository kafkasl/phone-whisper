package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import java.util.ArrayDeque

class EqualizerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val history = ArrayDeque<Sample>()
    private val rendered = FloatArray(3)
    private val delaysMs = longArrayOf(0L, 50L, 100L)
    private val rect = RectF()

    fun setLevel(level: Float) {
        val now = SystemClock.uptimeMillis()
        history.addLast(Sample(now, level.coerceIn(0f, 1f)))
        while (history.isNotEmpty() && now - history.first().timeMs > 900L) {
            history.removeFirst()
        }
        if (visibility == VISIBLE) invalidate()
    }

    fun reset() {
        history.clear()
        for (i in rendered.indices) rendered[i] = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        val centerY = height / 2f
        val barWidth = width * 0.16f
        val gap = width * 0.12f
        val totalWidth = barWidth * 3f + gap * 2f
        val startX = (width - totalWidth) / 2f
        val minHeight = height * 0.14f
        val maxHeight = height * 0.88f

        paint.color = 0xFFFFFFFF.toInt()
        paint.style = Paint.Style.FILL

        for (i in 0..2) {
            val target = delayedLevel(now - delaysMs[i])
            rendered[i] += (target - rendered[i]) * 0.42f
            val h = minHeight + (maxHeight - minHeight) * rendered[i]
            val left = startX + i * (barWidth + gap)
            rect.set(left, centerY - h / 2f, left + barWidth, centerY + h / 2f)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }

        if (visibility == VISIBLE && (history.isNotEmpty() || rendered.any { it > 0.01f })) {
            postInvalidateOnAnimation()
        }
    }

    private fun delayedLevel(targetTimeMs: Long): Float {
        var best = 0f
        for (sample in history) {
            if (sample.timeMs <= targetTimeMs) best = sample.level else break
        }
        return best
    }

    private data class Sample(val timeMs: Long, val level: Float)
}
