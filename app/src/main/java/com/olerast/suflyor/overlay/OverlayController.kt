package com.olerast.suflyor.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.session.SessionEngine

/**
 * The floating prompter. In portrait it hangs from the top edge: text right under the front camera, controls below.
 * Held sideways, it moves next to the lens and turns its text (camera apps keep their screen in portrait).
 * "Lock" lets touches pass through to the camera app; a small button next to the window unlocks it.
 * Hosted by the accessibility service (TYPE_ACCESSIBILITY_OVERLAY) or by the app (TYPE_APPLICATION_OVERLAY).
 */
class OverlayController(private val context: Context, private val windowType: Int) {
    /** Where the lens is and how the content must be turned. */
    private enum class Placement {
        PORTRAIT,

        /** Screen stays portrait, phone turned counter-clockwise: lens on the left, content turned +90°. */
        TURNED_LEFT,

        /** Screen stays portrait, phone turned clockwise: lens on the right, content turned −90°. */
        TURNED_RIGHT,

        /** The screen itself rotated to landscape, lens on the left / right edge. */
        SIDE_LEFT,
        SIDE_RIGHT,
    }

    private val app = App.instance
    private val wm = context.getSystemService(WindowManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var frame: RotatedFrame? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var topSpacer: View
    private lateinit var prompter: PrompterView
    private lateinit var countdown: TextView
    private lateinit var controls: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var level: LevelBar
    private lateinit var diag: TextView
    private lateinit var grabber: FrameLayout
    private lateinit var pauseBtn: ImageView
    private lateinit var modeBtn: ImageView
    private lateinit var background: GradientDrawable

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var locked = false

    private var placement = Placement.PORTRAIT
    private var physical = 0
    private var pendingPhysical = 0
    private var pendingSince = 0L
    private var orientationListener: OrientationEventListener? = null
    private var fontSp = app.settings.fontSp
    private var portraitY = app.settings.overlayY.coerceAtLeast(0)
    private var safeTop = 0
    private var lastScript: Any? = null

    val isShowing: Boolean get() = frame != null

    private val listener: (SessionEngine.State) -> Unit = { render(it) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) relayoutIfNeeded()
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (frame != null) return
        safeTop = portraitSafeTop()

        topSpacer = View(context)
        prompter = PrompterView(context).apply {
            setTextSizeSp(fontSp.toFloat())
            linesAbove = 0f
            wordHighlight = app.settings.wordHighlight
            onWordTap = { app.engine.jumpToToken(it) }
        }
        countdown = TextView(context).apply {
            setTextColor(PrompterView.ACCENT)
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val textBox = FrameLayout(context).apply {
            addView(prompter, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(countdown, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        statusDot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        statusText = TextView(context).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 11f
            maxLines = 1
            setPadding(dp(6), 0, dp(4), 0)
        }
        level = LevelBar(context)
        pauseBtn = icon(R.drawable.ic_pause, "Пауза") { app.engine.togglePause() }
        modeBtn = icon(R.drawable.ic_mic, "Голос или скорость") {
            val s = app.engine.state
            app.engine.setScroll(if (s.scroll == SessionEngine.Scroll.VOICE) SessionEngine.Scroll.AUTO else SessionEngine.Scroll.VOICE)
        }
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
                addView(statusText)
            })
            addView(level, LinearLayout.LayoutParams(dp(56), dp(3)).apply { topMargin = dp(4) })
        }
        controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(6), 0)
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.ic_up, "Строка назад") { app.engine.stepLine(-1) })
            addView(pauseBtn)
            addView(icon(R.drawable.ic_down, "Строка вперёд") { app.engine.stepLine(1) })
            addView(modeBtn)
            addView(textButton("A−") { changeFont(-2) })
            addView(textButton("A+") { changeFont(2) })
            addView(icon(R.drawable.ic_lock, "Закрепить: нажатия пойдут в камеру") { setLocked(true) })
            addView(icon(R.drawable.ic_close, "Закрыть") { OverlayHost.stopSession(context) })
        }
        diag = TextView(context).apply {
            setTextColor(Color.argb(190, 170, 240, 190))
            textSize = 10f
            maxLines = 3
            setPadding(dp(16), 0, dp(12), dp(2))
            visibility = if (app.settings.showDiagnostics) View.VISIBLE else View.GONE
        }
        grabber = FrameLayout(context).apply {
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(Color.argb(110, 255, 255, 255))
                }
            }, FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER))
        }

        background = GradientDrawable().apply { setColor(Color.argb(app.settings.overlayAlpha * 255 / 100, 8, 8, 10)) }
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            this.background = this@OverlayController.background
            addView(topSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
            addView(textBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, prompterHeight()))
            addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            addView(diag, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(grabber, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
        }
        val root = RotatedFrame(context).apply {
            addView(panel, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        frame = root
        placement = computePlacement()
        applyPlacement()

        var downY = 0f
        var startY = 0
        grabber.setOnTouchListener { _, e ->
            if (placement != Placement.PORTRAIT) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startY = portraitY
                }
                MotionEvent.ACTION_MOVE -> {
                    portraitY = (startY + (e.rawY - downY)).toInt().coerceIn(0, context.resources.displayMetrics.heightPixels / 2)
                    applyPlacement()
                    frame?.let { wm.updateViewLayout(it, params) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> app.settings.overlayY = portraitY
            }
            true
        }

        wm.addView(root, params)
        prompter.setScript(app.scripts.model)
        lastScript = app.scripts.model
        app.engine.addListener(listener)
        displays.registerDisplayListener(displayListener, handler)
        if (app.settings.autoRotate) startOrientationTracking()
    }

    fun hide() {
        app.engine.removeListener(listener)
        orientationListener?.disable()
        orientationListener = null
        runCatching { displays.unregisterDisplayListener(displayListener) }
        hideBubble()
        frame?.let { runCatching { wm.removeView(it) } }
        frame = null
    }

    // ---- placement ----------------------------------------------------------------------------------------------

    private fun startOrientationTracking() {
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val snapped = when {
                    deg >= 330 || deg <= 30 -> 0
                    deg in 60..120 -> 90
                    deg in 240..300 -> 270
                    deg in 150..210 -> 180
                    else -> return
                }
                val now = SystemClock.uptimeMillis()
                if (snapped != pendingPhysical) {
                    pendingPhysical = snapped
                    pendingSince = now
                }
                if (snapped != physical && now - pendingSince >= 700) {
                    physical = snapped
                    relayoutIfNeeded()
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun computePlacement(): Placement {
        val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        return when {
            rotation == Surface.ROTATION_90 -> Placement.SIDE_LEFT
            rotation == Surface.ROTATION_270 -> Placement.SIDE_RIGHT
            physical == 270 -> Placement.TURNED_LEFT
            physical == 90 -> Placement.TURNED_RIGHT
            else -> Placement.PORTRAIT
        }
    }

    private fun relayoutIfNeeded() {
        val root = frame ?: return
        val next = computePlacement()
        if (next == placement) return
        placement = next
        DiagLog.i("Окно: ${describe(next)}")
        applyPlacement()
        wm.updateViewLayout(root, params)
        if (locked) positionBubble()
    }

    private fun describe(p: Placement) = when (p) {
        Placement.PORTRAIT -> "вертикально, текст под камерой"
        Placement.TURNED_LEFT, Placement.TURNED_RIGHT -> "телефон горизонтально, экран камеры вертикальный — текст повёрнут к объективу"
        Placement.SIDE_LEFT, Placement.SIDE_RIGHT -> "экран горизонтальный — окно у объектива"
    }

    /** Window size and position, content rotation, paddings that keep text clear of the camera cut-out. */
    private fun applyPlacement() {
        val root = frame ?: return
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else null
        val screenW = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val screenH = bounds?.height() ?: context.resources.displayMetrics.heightPixels
        val r = dp(22).toFloat()
        val textBox = prompter.parent as View
        val portrait = placement == Placement.PORTRAIT
        val basePadStart = dp(18)
        val basePadEnd = dp(14)

        root.angle = when (placement) {
            Placement.TURNED_LEFT -> 90
            Placement.TURNED_RIGHT -> -90
            else -> 0
        }
        panel.layoutParams = panel.layoutParams.apply {
            width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            height = if (portrait) android.view.ViewGroup.LayoutParams.WRAP_CONTENT else android.view.ViewGroup.LayoutParams.MATCH_PARENT
        }
        textBox.layoutParams = (textBox.layoutParams as LinearLayout.LayoutParams).apply {
            if (portrait) {
                height = prompterHeight()
                weight = 0f
            } else {
                height = 0
                weight = 1f
            }
        }
        grabber.visibility = if (portrait && !locked) View.VISIBLE else View.GONE
        panel.setPadding(0, 0, 0, 0)
        prompter.setPadding(basePadStart, dp(4), basePadEnd, dp(2))
        topSpacer.layoutParams = topSpacer.layoutParams.apply { height = 0 }

        when (placement) {
            Placement.PORTRAIT -> {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = portraitY
                topSpacer.layoutParams = topSpacer.layoutParams.apply { height = (safeTop - params.y).coerceAtLeast(0) }
                val top = if (params.y == 0) 0f else r
                background.cornerRadii = floatArrayOf(top, top, top, top, r, r, r, r)
            }
            Placement.TURNED_LEFT, Placement.TURNED_RIGHT -> {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = (screenH * 0.46f).toInt()
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = 0
                // The lens is at the screen's top edge = the start (left) or the end (right) of every text line.
                if (placement == Placement.TURNED_LEFT) {
                    prompter.setPadding(basePadStart + safeTop, dp(6), basePadEnd, dp(4))
                    background.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                } else {
                    prompter.setPadding(basePadStart, dp(6), basePadEnd + safeTop, dp(4))
                    background.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                }
            }
            Placement.SIDE_LEFT, Placement.SIDE_RIGHT -> {
                params.width = (screenW * 0.46f).toInt()
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
                val cut = sideCutout()
                if (placement == Placement.SIDE_LEFT) {
                    params.gravity = Gravity.TOP or Gravity.LEFT
                    panel.setPadding(cut, 0, 0, 0)
                    background.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                } else {
                    params.gravity = Gravity.TOP or Gravity.RIGHT
                    panel.setPadding(0, 0, cut, 0)
                    background.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                }
            }
        }
        panel.requestLayout()
    }

    private fun portraitSafeTop(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.statusBars())
            val v = maxOf(insets.top, insets.left, insets.right)
            if (v > 0) return v
        }
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun sideCutout(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.displayCutout())
            return maxOf(insets.left, insets.right)
        }
        return 0
    }

    // ---- lock ---------------------------------------------------------------------------------------------------

    private fun setLocked(value: Boolean) {
        val root = frame ?: return
        locked = value
        controls.visibility = if (value) View.GONE else View.VISIBLE
        grabber.visibility = if (!value && placement == Placement.PORTRAIT) View.VISIBLE else View.GONE
        params.flags = if (value) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        // Plain overlays let touches through only when at most 80% opaque (Android 12+); accessibility ones always.
        if (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) params.alpha = if (value) 0.8f else 1f
        wm.updateViewLayout(root, params)
        if (value) showBubble() else hideBubble()
        DiagLog.i(if (value) "Окно закреплено: нажатия проходят в приложение камеры" else "Окно снова принимает нажатия")
    }

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(44)
        val view = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 8, 8, 10))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_lock_open)
                imageTintList = ColorStateList.valueOf(PrompterView.ACCENT)
                contentDescription = "Открепить окно"
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { setLocked(false) }
        }
        val p = WindowManager.LayoutParams(
            size, size, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        bubble = view
        bubbleParams = p
        runCatching { wm.addView(view, p) }
        positionBubble()
    }

    private fun positionBubble() {
        val root = frame ?: return
        root.post {
            val view = bubble ?: return@post
            val p = bubbleParams ?: return@post
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            val size = p.width
            when (placement) {
                Placement.SIDE_LEFT -> {
                    p.x = loc[0] + root.width + dp(6)
                    p.y = loc[1] + root.height - size - dp(24)
                }
                Placement.SIDE_RIGHT -> {
                    p.x = loc[0] - size - dp(6)
                    p.y = loc[1] + root.height - size - dp(24)
                }
                else -> {
                    p.x = loc[0] + root.width - size - dp(12)
                    p.y = loc[1] + root.height + dp(6)
                }
            }
            runCatching { wm.updateViewLayout(view, p) }
        }
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        bubbleParams = null
    }

    // ---- state --------------------------------------------------------------------------------------------------

    private fun render(s: SessionEngine.State) {
        if (frame == null) return
        if (lastScript !== app.scripts.model) {
            lastScript = app.scripts.model
            prompter.setScript(app.scripts.model)
        }
        prompter.setProgress(s.nextToken)
        if (s.countdown > 0) {
            countdown.text = s.countdown.toString()
            countdown.visibility = View.VISIBLE
        } else {
            countdown.visibility = View.GONE
        }
        val muted = s.silencedBySystem == true || s.digitalSilence
        level.setLevel(s.levelDb, muted)
        pauseBtn.setImageResource(if (s.paused) R.drawable.ic_play else R.drawable.ic_pause)
        modeBtn.setImageResource(if (s.scroll == SessionEngine.Scroll.VOICE) R.drawable.ic_mic else R.drawable.ic_speed)
        val (color, label) = when {
            s.starting -> Color.GRAY to "запуск…"
            !s.listening -> Color.GRAY to "стоп"
            s.paused -> PrompterView.ACCENT to "пауза"
            s.scroll == SessionEngine.Scroll.AUTO -> PrompterView.ACCENT to "по скорости"
            s.silencedBySystem == true -> Color.rgb(255, 80, 80) to "микрофон занят"
            s.autoFallback -> Color.rgb(255, 80, 80) to "не слышу"
            else -> Color.rgb(70, 215, 120) to "слушаю"
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.text = label
        if (diag.visibility == View.VISIBLE) {
            val others = s.recordings.filter { !it.ours }
            diag.text = buildString {
                append(s.partial.ifEmpty { s.lastFinal }.takeLast(70))
                if (others.isNotEmpty()) append("\n").append(others.joinToString("; ") { it.describe() })
                s.error?.let { append("\n⚠ ").append(it) }
            }
        }
    }

    private fun changeFont(delta: Int) {
        fontSp = (fontSp + delta).coerceIn(14, 48)
        app.settings.fontSp = fontSp
        prompter.setTextSizeSp(fontSp.toFloat())
        applyPlacement()
        frame?.let { wm.updateViewLayout(it, params) }
    }

    private fun prompterHeight(): Int {
        val probe = PrompterView(context).apply { setTextSizeSp(fontSp.toFloat()) }
        return probe.lineHeightPx * app.settings.overlayLines + dp(8)
    }

    private fun ripple() = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let {
        val d = it.getDrawable(0)
        it.recycle()
        d
    }

    private fun icon(res: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        background = ripple()
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(40))
        setOnClickListener { onClick() }
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = ripple()
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(40))
        setOnClickListener { onClick() }
    }
}

/** Thin level meter; red when Android is feeding us silence. */
class LevelBar(context: Context) : View(context) {
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val fg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var muted = false

    fun setLevel(db: Float, muted: Boolean) {
        fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
        this.muted = muted
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
        fg.color = if (muted) Color.rgb(255, 80, 80) else Color.rgb(70, 215, 120)
        val w = if (muted) width.toFloat() else width * fraction
        if (w > 0f) canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fg)
    }
}
