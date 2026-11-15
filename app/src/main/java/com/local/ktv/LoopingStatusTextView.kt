package com.local.ktv

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextView

/**
 * 循环滚动的状态文字：长度超过控件宽度时，从右往左缓慢滚出，停顿一下再从头滚一遍。
 *
 * 为什么要自己做：Android 原生 `ellipsize=marquee` 只在“获得焦点 && 单行”时滚，
 * 且没有循环间隔，大屏上歌曲名经常直接被截断成「当前：…」。这里改成不看焦点、
 * 自动带间隔循环，长歌名也能看全。
 *
 * 用法：设置 text 即自动开始；控件不可见/被移除时自动停（避免后台空转 invalidate）。
 */
class LoopingStatusTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    /** 一轮滚完后与下一轮之间的空白宽度（dp）。 */
    var loopGapDp: Float = 56f

    /** 滚动速度（dp/秒），越大越快。 */
    var loopSpeedDpPerSecond: Float = 96f

    /** 滚完一轮后的停顿时间（毫秒）。 */
    var loopPauseMs: Long = 2200L

    private var startedAt: Long = 0L
    private var lastLoggedCycle: Long = -1L

    /** 关掉后立即退回普通 TextView 行为（不滚动）。 */
    var loopEnabled: Boolean = true

    init {
        isSingleLine = true
        setHorizontallyScrolling(false)
        ellipsize = null
    }

    private fun gapPx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, loopGapDp, resources.displayMetrics
    )

    private fun speedPxPerSecond(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, loopSpeedDpPerSecond, resources.displayMetrics
    )

    private fun restartLoop() {
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartLoop()
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        restartLoop()
    }

    override fun onDraw(canvas: Canvas) {
        val content = text?.toString().orEmpty()
        val available = width - paddingLeft - paddingRight
        if (content.isEmpty() || available <= 0 || !loopEnabled) {
            super.onDraw(canvas)
            return
        }
        val paint = getPaint()
        val textWidth = paint.measureText(content)
        if (textWidth <= available.toFloat()) {
            super.onDraw(canvas)
            return
        }

        val travel = textWidth + gapPx()
        val scrollMs = Math.max(1L, (travel / speedPxPerSecond() * 1000f).toLong())
        val cycleMs = scrollMs + loopPauseMs
        if (lastLoggedCycle != startedAt) {
            lastLoggedCycle = startedAt
            AppLog.i(
                "LoopText",
                "开始循环 text宽=" + textWidth + "px 可见宽=" + available + "px 单轮=" + scrollMs + "ms 停=" + loopPauseMs + "ms"
            )
        }
        val elapsed = (SystemClock.uptimeMillis() - startedAt) % cycleMs
        val offset = if (elapsed < scrollMs) {
            travel * (elapsed.toFloat() / scrollMs)
        } else {
            travel
        }

        val metrics = paint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.save()
        canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
        canvas.drawText(content, paddingLeft - offset, baseline, paint)
        canvas.restore()

        postInvalidateDelayed(16L)
    }
}
