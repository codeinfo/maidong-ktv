package com.local.ktv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * 圆形音量盘：270° 弧线进度 + 圆心数值。
 *
 * 交互：
 *  - 遥控器 ←/→：按 step（默认 1）增减，↑/↓ 交回框架用于换焦点；
 *  - 触摸：按住圆弧拖动，按角度直接换算数值；
 *  - 焦点态画一圈橙色外发光，方便大屏看清当前选中的是哪个盘。
 */
class CircularVolumeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var label: String = "音量"
        set(value) {
            field = value
            invalidate()
        }

    var unit: String = "%"
        set(value) {
            field = value
            invalidate()
        }

    var minValue: Int = 0
        set(value) {
            field = value
            clampAndEmit(field, false)
        }

    var maxValue: Int = 100
        set(value) {
            field = value
            clampAndEmit(field, false)
        }

    var step: Int = 1

    private var current: Int = 50

    var onValueChanged: ((Int) -> Unit)? = null

    private val startAngle = 135f
    private val sweepAngle = 270f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#33FFFFFF")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#66FF8A3C")
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFFFD08A")
    }
    private val focusDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1AFFB43C")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textAlign = Paint.Align.LEFT
    }

    private val ringRect = RectF()
    private var gradient: SweepGradient? = null
    private var strokeWidth = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun getValue(): Int = current

    fun setValue(next: Int) = clampAndEmit(next, false)

    fun setValueFromUser(next: Int) = clampAndEmit(next, true)

    private fun clampAndEmit(next: Int, fromUser: Boolean) {
        val lower = Math.min(minValue, maxValue)
        val upper = Math.max(minValue, maxValue)
        var wanted = next.coerceIn(lower, upper)
        if (step > 1) {
            val offset = wanted - lower
            wanted = lower + Math.round(offset.toFloat() / step) * step
            wanted = wanted.coerceIn(lower, upper)
        }
        val changed = wanted != current
        current = wanted
        invalidate()
        if (changed || fromUser) onValueChanged?.invoke(wanted)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val edge = Math.min(w, h).toFloat()
        strokeWidth = edge * 0.085f
        val inset = strokeWidth / 2f + edge * 0.035f
        ringRect.set(inset, inset, w - inset, h - inset)
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        glowPaint.strokeWidth = strokeWidth * 1.5f
        focusPaint.strokeWidth = strokeWidth * 0.9f
        valuePaint.textSize = edge * 0.29f
        labelPaint.textSize = edge * 0.135f
        unitPaint.textSize = edge * 0.115f
        val shader = SweepGradient(
            w / 2f,
            h / 2f,
            intArrayOf(
                Color.parseColor("#FF27CCA4"),
                Color.parseColor("#FFFFB43C"),
                Color.parseColor("#FFFF6E4A"),
                Color.parseColor("#FF27CCA4")
            ),
            floatArrayOf(0f, 0.33f, 0.66f, 1f)
        )
        val matrix = Matrix()
        matrix.setRotate(startAngle, w / 2f, h / 2f)
        shader.setLocalMatrix(matrix)
        progressPaint.shader = shader
        gradient = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(ringRect, startAngle, sweepAngle, false, trackPaint)
        val span = (maxValue - minValue).coerceAtLeast(1)
        val ratio = ((current - minValue).toFloat() / span).coerceIn(0f, 1f)
        if (ratio > 0f) {
            canvas.drawArc(ringRect, startAngle, sweepAngle * ratio, false, progressPaint)
        }
        if (isFocused || isPressed) {
            canvas.drawArc(ringRect, startAngle, sweepAngle, false, glowPaint)
            canvas.drawCircle(width / 2f, height / 2f, Math.min(width, height) * 0.36f, focusDiscPaint)
            canvas.drawArc(ringRect, startAngle, sweepAngle, false, focusPaint)
        }

        val cx = width / 2f
        val cy = height / 2f
        val metrics = valuePaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2f - Math.min(width, height) * 0.02f
        val number = current.toString()
        canvas.drawText(number, cx, baseline, valuePaint)
        if (unit.isNotEmpty()) {
            val numberWidth = valuePaint.measureText(number)
            val unitBaseline = baseline - valuePaint.textSize * 0.05f
            canvas.drawText(unit, cx + numberWidth / 2f + strokeWidth * 0.3f, unitBaseline, unitPaint)
        }
        if (label.isNotEmpty()) {
            canvas.drawText(label, cx, cy + Math.min(width, height) * 0.235f, labelPaint)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        AppLog.i("Tuning", "dial $label focus=$gainFocus value=$current")
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                clampAndEmit(current - step, true)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                clampAndEmit(current + step, true)
                true
            }
            else -> false
        }
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isFocused) requestFocus()
                isPressed = true
                adjustFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                adjustFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun adjustFromTouch(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val radius = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        val outer = Math.min(width, height) / 2f
        if (radius < outer * 0.3f || radius > outer) return
        var degrees = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (degrees < 0f) degrees += 360f
        var relative = degrees - startAngle
        if (relative < 0f) relative += 360f
        if (relative > sweepAngle) return
        val value = minValue + Math.round(relative / sweepAngle * (maxValue - minValue))
        clampAndEmit(value, true)
    }
}
