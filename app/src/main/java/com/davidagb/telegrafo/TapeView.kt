package com.davidagb.telegrafo

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/** Cinta de telégrafo: cada letra se dibuja como puntos y rayas con su carácter debajo. */
class TapeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var tokens: List<Morse.Token> = emptyList()
    private var cells: List<RectF> = emptyList()
    private var hot = -1
    private var scale = 1f

    private val dp get() = resources.displayMetrics.density * scale
    private val dot get() = 9 * dp
    private val dash get() = 25 * dp
    private val markH get() = 9 * dp
    private val symGap get() = 4 * dp
    private val padH get() = 6 * dp
    private val padTop get() = 7 * dp
    private val padBottom get() = 5 * dp
    private val gapV get() = 6 * dp
    private val rowGap get() = 8 * dp
    private val cellGap get() = 4 * dp
    private val divider get() = 18 * dp

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.ink) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val hotBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent_soft) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.line) }
    private val cap = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        color = context.getColor(R.color.danger)
    }
    private val mutedColor = context.getColor(R.color.muted)
    private val dangerColor = context.getColor(R.color.danger)
    private var captionColor = mutedColor

    init {
        captionStyle(13f, mutedColor)
    }

    fun captionStyle(sizeSp: Float, color: Int, markScale: Float = 1f) {
        scale = markScale
        captionColor = color
        cap.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        small.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        requestLayout()
        invalidate()
    }

    fun setTokens(list: List<Morse.Token>) {
        tokens = list
        hot = -1
        requestLayout()
        invalidate()
    }

    fun setHot(index: Int) {
        if (hot == index) return
        hot = index
        invalidate()
    }

    fun cellRect(index: Int): Rect? {
        val r = cells.getOrNull(index) ?: return null
        return Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    private fun marksWidth(code: String): Float =
        code.sumOf { (if (it == '.') dot else dash).toDouble() }.toFloat() + symGap * (code.length - 1)

    private fun cellWidth(t: Morse.Token): Float {
        if (t.gap) return divider
        val marks = t.code?.let { marksWidth(it) } ?: small.measureText("sin código")
        return max(marks, cap.measureText(t.ch ?: "?")) + padH * 2
    }

    private val cellHeight get() = padTop + markH + gapV + (cap.descent() - cap.ascent()) + padBottom

    private fun layoutCells(maxWidth: Float): Float {
        val out = ArrayList<RectF>(tokens.size)
        var x = 0f
        var y = 0f
        var widest = 0f
        val h = cellHeight
        for (t in tokens) {
            val w = cellWidth(t)
            if (x > 0 && x + w > maxWidth) {
                x = 0f
                y += h + rowGap
            }
            out += RectF(x, y, x + w, y + h)
            widest = max(widest, x + w)
            x += w + cellGap
        }
        cells = out
        return widest
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val avail = if (mode == MeasureSpec.UNSPECIFIED) Float.MAX_VALUE else (size - paddingLeft - paddingRight).toFloat()
        val widest = layoutCells(avail)
        val w = if (mode == MeasureSpec.EXACTLY) size else (widest + paddingLeft + paddingRight).roundToInt()
        val h = ((cells.lastOrNull()?.bottom ?: 0f) + paddingTop + paddingBottom).roundToInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        val radius = 8 * dp
        tokens.forEachIndexed { i, t ->
            val r = cells.getOrNull(i) ?: return@forEachIndexed
            if (t.gap) {
                val cx = r.centerX()
                canvas.drawRoundRect(cx - dp, r.top + 4 * dp, cx + dp, r.bottom - 4 * dp, dp, dp, line)
                return@forEachIndexed
            }
            val isHot = i == hot
            if (isHot) canvas.drawRoundRect(r, radius, radius, hotBg)
            val markTop = r.top + padTop
            val code = t.code
            if (code != null) {
                val paint = if (isHot) accent else ink
                var x = r.centerX() - marksWidth(code) / 2
                for (s in code) {
                    if (s == '.') {
                        canvas.drawCircle(x + dot / 2, markTop + markH / 2, dot / 2, paint)
                        x += dot + symGap
                    } else {
                        canvas.drawRoundRect(x, markTop, x + dash, markTop + markH, markH / 2, markH / 2, paint)
                        x += dash + symGap
                    }
                }
            } else {
                canvas.drawText("sin código", r.centerX(), markTop + markH, small)
            }
            cap.color = when {
                code == null -> dangerColor
                isHot -> accent.color
                else -> captionColor
            }
            canvas.drawText(t.ch ?: "?", r.centerX(), markTop + markH + gapV - cap.ascent(), cap)
        }
        canvas.restore()
    }
}
