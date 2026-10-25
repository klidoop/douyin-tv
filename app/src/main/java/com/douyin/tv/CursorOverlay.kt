package com.douyin.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * A transparent layer that draws a mouse-style pointer. It never receives touches itself;
 * MainActivity moves it with the D-pad and synthesises touch events at (px, py).
 */
class CursorOverlay(context: Context) : View(context) {

    var px = 0f
        private set
    var py = 0f
        private set

    private var dragging = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FE2C55"); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val arrow = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    fun resetToCenter() {
        px = width / 2f
        py = height / 2f
        invalidate()
    }

    /** repeat = KeyEvent.repeatCount; held keys accelerate so crossing the screen is quick. */
    fun move(dx: Float, dy: Float, repeat: Int) {
        val step = when {
            repeat == 0 -> 24f
            repeat < 6 -> 40f
            repeat < 15 -> 70f
            else -> 110f
        }
        px = (px + dx * step).coerceIn(0f, width.toFloat() - 1)
        py = (py + dy * step).coerceIn(0f, height.toFloat() - 1)
        invalidate()
    }

    fun setDragging(on: Boolean) {
        dragging = on
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (px == 0f && py == 0f) resetToCenter()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dragging) canvas.drawCircle(px, py, 28f, ring)
        arrow.reset()
        arrow.moveTo(px, py)
        arrow.lineTo(px, py + 34f)
        arrow.lineTo(px + 9f, py + 26f)
        arrow.lineTo(px + 16f, py + 40f)
        arrow.lineTo(px + 22f, py + 37f)
        arrow.lineTo(px + 15f, py + 23f)
        arrow.lineTo(px + 26f, py + 23f)
        arrow.close()
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
    }
}
