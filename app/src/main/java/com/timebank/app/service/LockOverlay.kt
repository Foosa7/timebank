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

/**
 * A full-screen overlay shown when the balance hits $0 while an app is open.
 * It captures touches so the app underneath can't be used, and offers a way
 * back to the home screen (which stops the drain). Requires the
 * "Display over other apps" permission (SYSTEM_ALERT_WINDOW).
 */
class LockOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun show() = main.post {
        if (view != null) return@post
        if (!Settings.canDrawOverlays(context)) return@post
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val v = buildView()
        try {
            wm.addView(v, params)
            view = v
        } catch (_: Exception) {
        }
    }

    fun hide() = main.post {
        val v = view ?: return@post
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
        view = null
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20F3D2E"))
            setPadding(72, 72, 72, 72)
            isClickable = true
        }
        val title = TextView(context).apply {
            text = "💸  You're broke"
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val sub = TextView(context).apply {
            text = "Balance is \$0.\nTurn the screen off to earn, or go home."
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 56)
        }
        val home = Button(context).apply {
            text = "Go to home screen"
            setOnClickListener {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                hide()
            }
        }
        root.addView(title)
        root.addView(sub)
        root.addView(home)
        return root
    }
}
