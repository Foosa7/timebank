package com.timebank.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.timebank.app.util.formatMoney

/**
 * The gate shown when an app is brought to the foreground and its cover charge hasn't
 * been paid for this visit. Like [LockOverlay] it is a hand-built view rather than
 * Compose, and needs SYSTEM_ALERT_WINDOW.
 *
 * The whole point is that it *blocks*: the app underneath stays unusable until the user
 * either pays in or backs out, so the charge lands on a deliberate choice rather than on
 * a reflex. Nothing is deducted here — [onEnter] is what spends the money, and backing
 * out costs nothing.
 */
class CoverChargeOverlay(private val context: Context) {

    /** Everything the gate needs to draw itself for one package. */
    data class Offer(
        val packageName: String,
        val label: String,
        val cover: Double,
        val ratePerMin: Double,
        val balance: Double,
        /** e.g. "🍺 Happy hour pricing", or null when the normal price applies. */
        val pricingNote: String? = null
    ) {
        val affordable: Boolean get() = balance >= cover
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    /** The offer currently on screen, so an unchanged gate isn't rebuilt every tick. */
    private var shown: Offer? = null

    fun show(offer: Offer, onEnter: () -> Unit, onDecline: () -> Unit) = main.post {
        if (shown == offer) return@post
        if (!Settings.canDrawOverlays(context)) return@post
        removeView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val v = buildView(offer, onEnter, onDecline)
        try {
            wm.addView(v, params)
            view = v
            shown = offer
        } catch (_: Exception) {
        }
    }

    fun hide() = main.post {
        removeView()
        shown = null
    }

    private fun removeView() {
        val v = view ?: return
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        view = null
    }

    private fun buildView(offer: Offer, onEnter: () -> Unit, onDecline: () -> Unit): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F2102A1E"))
            setPadding(72, 72, 72, 72)
            isClickable = true
        }
        val title = TextView(context).apply {
            text = "🎟  Cover charge"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val app = TextView(context).apply {
            text = offer.label
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 40)
        }
        val terms = TextView(context).apply {
            text = "Entry     " + formatMoney(offer.cover) +
                "\nThen      " + formatMoney(offer.ratePerMin) + "/min" +
                (offer.pricingNote?.let { "\n\n" + it } ?: "")
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        val after = TextView(context).apply {
            text = if (offer.affordable) {
                "Balance " + formatMoney(offer.balance) +
                    "  →  " + formatMoney(offer.balance - offer.cover)
            } else {
                "Balance " + formatMoney(offer.balance) + " — not enough to get in."
            }
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 56)
        }
        root.addView(title)
        root.addView(app)
        root.addView(terms)
        root.addView(after)

        if (offer.affordable) {
            root.addView(Button(context).apply {
                text = "Enter (" + formatMoney(offer.cover) + ")"
                setOnClickListener { onEnter() }
            })
        }
        root.addView(Button(context).apply {
            // The only way out when broke, so it can't just say "No thanks".
            text = if (offer.affordable) "No thanks" else "Go to home screen"
            setOnClickListener {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onDecline()
            }
        })
        return root
    }
}
