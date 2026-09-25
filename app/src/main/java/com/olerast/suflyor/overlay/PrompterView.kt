package com.olerast.suflyor.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.olerast.suflyor.script.ScriptModel
import kotlin.math.abs
import kotlin.math.exp

/**
 * Teleprompter text. Progress is shown by lines, not words: lines already read are dimmed, the current line stays
 * bright with an accent mark and sits on the reading line (smooth scrolling). Word-level highlighting is optional —
 * with ~0.5 s of recognition latency it tends to look "behind" the voice.
 * Tap a word to jump there; drag to look around (following resumes a few seconds later).
 */
class PrompterView(context: Context) : View(context) {
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val markRect = RectF()
    private var model: ScriptModel = ScriptModel.EMPTY
    private var text = SpannableString("")
    private var layout: StaticLayout? = null

    private val spokenSpan = ForegroundColorSpan(Color.argb(95, 255, 255, 255))
    private val nextSpan = BackgroundColorSpan(Color.argb(120, 255, 176, 32))
    private val noteColor = Color.argb(170, 140, 200, 255)

    /** Lines of already-read text kept visible above the reading line. */
    var linesAbove: Float = 0f
        set(v) {
            if (field == v) return
            field = v
            applyProgress(false)
        }

    var wordHighlight = false
        set(v) {
            if (field == v) return
            field = v
            applyProgress(false)
        }

    var onWordTap: ((tokenIndex: Int) -> Unit)? = null

    private var nextToken = 0
    private var currentLine = 0
    private var posY = 0f
    private var targetY = 0f
    private var lastFrame = 0L
    private var manualUntil = 0L

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    fun setTextSizeSp(sp: Float) {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        if (px == paint.textSize && layout != null) return
        paint.textSize = px
        rebuildLayout()
    }

    val lineHeightPx: Int get() = (paint.fontMetricsInt.let { it.descent - it.ascent } * LINE_SPACING).toInt()

    fun setScript(m: ScriptModel) {
        model = m
        text = SpannableString(m.displayText)
        for (r in m.emphasis) {
            if (r.first < 0 || r.last >= text.length) continue
            text.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (r in m.notes) {
            if (r.first < 0 || r.last >= text.length) continue
            text.setSpan(ForegroundColorSpan(noteColor), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.ITALIC), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        nextToken = 0
        rebuildLayout()
        posY = targetY
    }

    fun setProgress(nextTokenIndex: Int, animate: Boolean = true) {
        if (nextTokenIndex == nextToken && layout != null) return
        nextToken = nextTokenIndex
        applyProgress(animate)
    }

    private fun applyProgress(animate: Boolean) {
        val l = layout ?: return
        val tokens = model.tokens
        text.removeSpan(spokenSpan)
        text.removeSpan(nextSpan)
        val atEnd = nextToken >= tokens.size
        currentLine = if (atEnd) l.lineCount - 1 else l.getLineForOffset(tokens[nextToken].start)
        val dimEnd = if (atEnd) text.length else l.getLineStart(currentLine)
        if (dimEnd > 0) text.setSpan(spokenSpan, 0, dimEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (wordHighlight && !atEnd) {
            val t = tokens[nextToken]
            text.setSpan(nextSpan, t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        targetY = (l.getLineTop(currentLine.coerceAtLeast(0)) - linesAbove * lineHeightPx).coerceAtLeast(0f)
        if (!animate) posY = targetY
        invalidate()
    }

    private fun rebuildLayout() {
        val w = width - paddingLeft - paddingRight
        if (w <= 0) {
            layout = null
            return
        }
        layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()
        applyProgress(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
        lastFrame = now
        if (now >= manualUntil) {
            val k = 1f - exp(-dt / SMOOTH_MS)
            posY += (targetY - posY) * k
            if (abs(targetY - posY) < 0.5f) posY = targetY
        }
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(paddingLeft.toFloat(), paddingTop - posY)
        if (model.tokens.isNotEmpty() && nextToken < model.tokens.size) {
            val top = l.getLineTop(currentLine).toFloat()
            val bottom = l.getLineBottom(currentLine).toFloat() - (LINE_SPACING - 1f) * lineHeightPx / LINE_SPACING
            val x = -dp(9f)
            markRect.set(x, top + dp(3f), x + dp(3.5f), bottom - dp(1f))
            canvas.drawRoundRect(markRect, dp(2f), dp(2f), markPaint)
        }
        l.draw(canvas)
        canvas.restore()
        if (posY != targetY || now < manualUntil) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val l = layout ?: return false
            manualUntil = SystemClock.uptimeMillis() + MANUAL_HOLD_MS
            posY = (posY + dy).coerceIn(0f, l.height.toFloat())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val l = layout ?: return false
            val y = (e.y - paddingTop + posY).toInt()
            val line = l.getLineForVertical(y)
            val offset = l.getOffsetForHorizontal(line, e.x - paddingLeft)
            // The word under the finger, staying on the tapped line: the end of a word or the empty space to the
            // right of a line must not select the next word or line.
            val ls = l.getLineStart(line)
            val le = l.getLineEnd(line)
            val tokens = model.tokens
            val onLine = tokens.indices.filter { tokens[it].start in ls until le }
            val idx = onLine.lastOrNull { tokens[it].start <= offset } ?: onLine.firstOrNull()
                ?: tokens.indexOfFirst { it.end > offset }
            if (idx >= 0) {
                manualUntil = 0L
                onWordTap?.invoke(idx)
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    companion object {
        const val LINE_SPACING = 1.12f
        const val SMOOTH_MS = 150f
        const val MANUAL_HOLD_MS = 3000L
        val ACCENT: Int = Color.rgb(255, 176, 32)
    }
}
