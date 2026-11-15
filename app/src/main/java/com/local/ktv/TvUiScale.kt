package com.local.ktv

import android.content.Context
import android.content.res.Configuration
import java.io.File

/**
 * TV 尺寸缩放：把界面按「1280×720 dp 基准」等比映射到实际屏幕。
 *
 * 这台设备（1920×1080 / density 1.5）本来就等于基准，所以此处因子恒等于 1，排版一个像素都不变；
 * 换到 1080p@160dpi 或 4K 盒子上，density 不是「屏宽/1280」，界面会整体偏小或错位，这里统一拉回基准。
 *
 * 注意：只改 density（dp→px 换算），不改字号单位、不改布局 rank，等比例缩放最安全。
 */
object TvUiScale {

    /** 设计基准：宽 1280dp、高 720dp（1280×720 的盒子按 density=1 出图）。 */
    const val REFERENCE_WIDTH_DP = 1280f
    const val REFERENCE_HEIGHT_DP = 720f

    private const val MIN_FACTOR = 0.6f
    private const val MAX_FACTOR = 3.5f

    /** debug 覆盖：`/sdcard/MaidongKTV/qa_scale.txt` 写一个数字（如 0.8）即可强制缩放，用来验证生效。 */
    private const val QA_SCALE_FILE = "qa_scale.txt"

    @Volatile
    var lastFactor: Float = 1f
        private set

    @Volatile
    var lastNote: String = ""
        private set

    private fun readQaOverride(): Float {
        if (!BuildConfig.DEBUG) return 0f
        return try {
            val file = File(AppPaths.ROOT_PATH, QA_SCALE_FILE)
            if (!file.exists()) return 0f
            file.readText().trim().toFloatOrNull() ?: 0f
        } catch (t: Throwable) {
            AppLog.w("TvUiScale", "读取 qa_scale 失败：" + t.message)
            0f
        }
    }

    /**
     * 目标 density（px per dp）：长边对宽基准、短边对高基准，保证整屏都能装下。
     *
     * 不能直接用 widthPixels/heightPixels —— `attachBaseContext` 阶段还没应用横屏方向，
     * 拿到的可能是竖屏的 800×1280，会把基准算反（实测就是 0.625 × 0.8 被夹成 0.6）。
     */
    private fun targetDensity(widthPx: Int, heightPx: Int): Float {
        if (widthPx <= 0 || heightPx <= 0) return 0f
        val longSide = Math.max(widthPx, heightPx).toFloat()
        val shortSide = Math.min(widthPx, heightPx).toFloat()
        val byWidth = longSide / REFERENCE_WIDTH_DP
        val byHeight = shortSide / REFERENCE_HEIGHT_DP
        return Math.min(byWidth, byHeight)
    }

    /**
     * 把缩放落到传入 Activity 的 Resources 上。
     *
     * 试过两种在 4.4 上不生效的做法：`createConfigurationContext(densityDpi)` 和改 `baseContext.resources`
     * —— 前者布局不认，后者 metrics 变了但 Activity 拿不到同一份对象（实测两帧像素零差异）。
     * 只有直接改 `activity.resources.displayMetrics`，且必须在 `setContentView` 之前。
     */
    fun applyTo(activity: Context) {
        val metrics = activity.resources.displayMetrics
        val target = targetDensity(metrics.widthPixels, metrics.heightPixels)
        if (target <= 0f) return
        val current = metrics.density
        if (current <= 0f) return

        var factor = (target / current).coerceIn(MIN_FACTOR, MAX_FACTOR)
        val qa = readQaOverride()
        if (qa > 0f) {
            factor = (factor * qa).coerceIn(MIN_FACTOR, MAX_FACTOR)
        }
        lastFactor = factor
        lastNote = "屏幕 " + metrics.widthPixels + "x" + metrics.heightPixels +
            " density=" + current + " 目标density=" + target + " 缩放=" + factor +
            (if (qa > 0f) "（qa 覆盖 " + qa + "）" else "")
        AppLog.i("TvUiScale", lastNote)

        if (Math.abs(factor - 1f) < 0.02f) return
        try {
            metrics.density = current * factor
            metrics.densityDpi = Math.round(160f * current * factor)
            metrics.scaledDensity = metrics.density
            AppLog.i(
                "TvUiScale",
                "已应用 density=" + metrics.density + " dpi=" + metrics.densityDpi +
                    "，dp 宽度=" + Math.round(metrics.widthPixels / metrics.density)
            )
        } catch (t: Throwable) {
            AppLog.w("TvUiScale", "缩放失败，保持原样：" + t.message)
        }
    }
}
