package dev.vela.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The in-game shader ball.
 *
 * A small floating dot in the corner. Tapping it opens the panel: shaders on or
 * off, the pack's own options, importing another pack, render scale, shadows.
 * Everything it changes is written to the same preferences the launcher reads,
 * so the choice survives a restart.
 *
 * Deliberately plain Android views rather than Compose: this is attached to the
 * game's own window, where Compose's lifecycle and recomposition have no host.
 */
class ShaderOverlay(
    private val host: android.app.Activity,
    private val onImportRequested: () -> Unit,
) : FrameLayout(host) {

    private val prefs = host.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private val ball = BallView(host)
    private val panel = buildPanel()

    private var expanded = false
    private var panelRef: View

    init {
        setWillNotDraw(false)
        addView(ball, LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(96)
            rightMargin = dp(12)
        })
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER).apply {
            marginEnd = dp(16)
        })
        panelRef = panel
        panel.visibility = GONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildPanel(): View {
        val column = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.argb(226, 20, 16, 16))
        }

        column.addView(title("Shader Packs"))
        column.addView(row("Shaders: ${if (shadersOn()) "ON" else "OFF"}") { toggleShaders() })
        column.addView(row("Shader Options…") { showOptions() })
        column.addView(row("Import .zip") { onImportRequested() })
        column.addView(row("Render Scale: ${renderScale()}") { cycleRenderScale() })
        column.addView(row("Shadows: ${if (shadowsOn()) "ON" else "OFF"}") { toggleShadows() })
        column.addView(row("Done") { setExpanded(false) })
        return column
    }

    private fun title(text: String) = TextView(host).apply {
        this.text = text
        setTextColor(Color.parseColor("#E3A13C"))
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(4), dp(4), dp(4), dp(10))
    }

    private fun row(label: String, onClick: () -> Unit) = TextView(host).apply {
        text = label
        setTextColor(Color.parseColor("#F0EAE3"))
        textSize = 14f
        setPadding(dp(10), dp(12), dp(10), dp(12))
        setBackgroundColor(Color.argb(40, 255, 255, 255))
        isClickable = true
        setOnClickListener {
            onClick()
            rebuildPanel()
        }
    }

    /** Rebuilds the labels in place after a toggle changes state. */
    private fun rebuildPanel() {
        removeView(panelRef)
        val fresh = buildPanel()
        addView(fresh, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
        panelRef = fresh
        fresh.visibility = if (expanded) VISIBLE else GONE
    }


    private fun setExpanded(value: Boolean) {
        expanded = value
        panelRef.visibility = if (value) VISIBLE else GONE
        ball.alpha = if (value) 0.35f else 1f
    }

    // ---- settings -------------------------------------------------------

    private fun shadersOn() = prefs.getBoolean("shaders_on", true)
    private fun shadowsOn() = prefs.getBoolean("shadows_on", true)
    private fun renderScale() = prefs.getString("render_scale", "Auto") ?: "Auto"

    private fun toggleShaders() {
        prefs.edit().putBoolean("shaders_on", !shadersOn()).apply()
    }

    private fun toggleShadows() {
        prefs.edit().putBoolean("shadows_on", !shadowsOn()).apply()
    }

    private fun cycleRenderScale() {
        val order = listOf("Auto", "1.0x", "0.75x", "0.5x")
        val next = order[(order.indexOf(renderScale()).coerceAtLeast(0) + 1) % order.size]
        prefs.edit().putString("render_scale", next).apply()
    }

    private fun note(text: String) = TextView(host).apply {
        this.text = text
        setTextColor(Color.parseColor("#9A8B80"))
        textSize = 13f
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun showOptions() {
        // The pack's own settings live in shaders.properties; until that reader
        // lands, the options the launcher already exposes are what we can offer.
        val panel = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.argb(235, 20, 16, 16))
        }
        panel.addView(title("Shader Options"))

        // The pack's own settings, read from its shaders.properties.
        val pack = dev.vela.core.ShaderPackOptions.active(host)
        val options = if (pack != null) dev.vela.core.ShaderPackOptions.read(pack) else emptyList()

        if (options.isEmpty()) {
            panel.addView(note("No pack settings found. Import a pack first."))
        } else {
            for (option in options.take(14)) {
                val current = dev.vela.core.ShaderPackOptions.currentValue(host, option.name, "OFF")
                panel.addView(row("${option.name}: $current") {
                    val next = when {
                        option.values.isNotEmpty() -> {
                            val i = option.values.indexOf(current)
                            option.values[(i + 1) % option.values.size]
                        }
                        current == "ON" -> "OFF"
                        else -> "ON"
                    }
                    dev.vela.core.ShaderPackOptions.setValue(host, option.name, next)
                })
            }
        }
        panel.addView(row("Close") {
            removeView(optionsPanel)
            optionsPanel = null
        })

        removeView(optionsPanel)
        optionsPanel = panel
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))
    }

    private var optionsPanel: View? = null

    // ---- the ball -------------------------------------------------------

    @SuppressLint("ViewConstructor")
    private inner class BallView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E3A13C")
        }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = (minOf(width, height) / 2f) - dp(2)
            rect.set(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
            canvas.drawCircle(width / 2f, height / 2f, r * 0.62f, fill)
            canvas.drawCircle(width / 2f, height / 2f, r, ring)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                setExpanded(!expanded)
                performClick()
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    /** Adds the overlay to the game window. */
    fun attach() {
        val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.addView(this, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }
}
