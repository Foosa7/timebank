package com.timebank.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

/**
 * Draws the balance into the notification's *small* icon, so the number sits in the status
 * bar strip and is readable without pulling the shade down. A notification can't put text
 * up there, but it can put an arbitrary bitmap, so we render the digits ourselves.
 *
 * The system tints small icons using only their alpha channel, which is why the glyphs are
 * drawn opaque white on a transparent square — they come out the right colour on both a
 * light and a dark status bar without us knowing which one we're on.
 */
object BalanceIcon {

    /** Drawn well above the 24dp it lands at, so the system's downscale stays crisp. */
    private const val SIZE_PX = 96

    /**
     * Glyphs stop short of the edge. The status bar packs icons tightly, so a balance
     * rendered truly edge-to-edge runs into its neighbours — but the old margin was
     * generous enough to leave the number looking shrunken next to the clock, so this
     * keeps only what it takes to stay separated.
     */
    private const val MAX_GLYPH_PX = 92f

    private var cachedText: String? = null
    private var cachedIcon: IconCompat? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    /**
     * Cached on the rendered string rather than the balance, so the bitmap is only redrawn
     * when the visible number changes — not on every 3s tick.
     */
    fun forText(text: String): IconCompat {
        cachedIcon?.let { if (cachedText == text) return it }
        val icon = IconCompat.createWithBitmap(render(text))
        cachedText = text
        cachedIcon = icon
        return icon
    }

    private fun render(text: String): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Shrink to fit rather than clip: "7" and "128k" both have to stay legible. Glyph
        // size scales linearly with text size, so one measure-and-correct pass is exact.
        paint.textSize = SIZE_PX.toFloat()
        paint.getTextBounds(text, 0, text.length, bounds)
        val scale = minOf(
            MAX_GLYPH_PX / bounds.width().coerceAtLeast(1),
            MAX_GLYPH_PX / bounds.height().coerceAtLeast(1),
            1f
        )
        if (scale < 1f) {
            paint.textSize = SIZE_PX * scale
            paint.getTextBounds(text, 0, text.length, bounds)
        }

        canvas.drawText(text, SIZE_PX / 2f, SIZE_PX / 2f - bounds.exactCenterY(), paint)
        return bmp
    }
}
