package com.local.ktv

import android.os.StatFs
import java.io.File

/**
 * 下载磁盘预算（P0）。
 *
 * 4.4 盒子普遍只有 4–8 GB，一首 MKV 常在 40–80 MB，多首并发很容易把空间写穿，
 * 结果是留下半截文件又反复重试。这里在「开始下载前」先算一次可用空间：
 * - 已知预期大小（拿到 Content-Range 时）就按「预期 + 底线余量」判断；
 * - 未知大小（首包之前）按单曲上限 + 底线余量判断。
 */
internal object DownloadSpaceBudget {
    /** 单曲按 120 MB 估算（i歌霸 MKV 实测多为 40–70 MB，留一倍余量）。 */
    private const val SINGLE_SONG_ESTIMATE = 120L * 1024L * 1024L
    /** 不管怎样都要给系统留的余量，避免整盘写满导致应用自己写不了 state.json。 */
    private const val SAFETY_RESERVE = 200L * 1024L * 1024L

    class Verdict(val allowed: Boolean, val available: Long, val required: Long, val message: String?)

    fun check(target: File, expectedBytes: Long = 0L): Verdict {
        val available = runCatching {
            val stat = StatFs(target.parentFile?.absolutePath ?: AppPaths.ROOT_PATH)
            @Suppress("DEPRECATION")
            stat.availableBlocks.toLong() * stat.blockSize.toLong()
        }.getOrDefault(-1L)
        if (available < 0L) {
            // 拿不到空间信息就不要拦下载，交给写盘阶段的异常兜底
            return Verdict(true, -1L, 0L, null)
        }
        val need = if (expectedBytes > 0) expectedBytes + SAFETY_RESERVE
        else SINGLE_SONG_ESTIMATE + SAFETY_RESERVE
        if (available >= need) return Verdict(true, available, need, null)
        return Verdict(
            false,
            available,
            need,
            "存储空间不足：剩余 ${mb(available)}，本次需要约 ${mb(need)}，请先清理下载缓存",
        )
    }

    fun describeAvailable(): String = runCatching {
        val stat = StatFs(AppPaths.cloudSongsDir.absolutePath)
        @Suppress("DEPRECATION")
        val available = stat.availableBlocks.toLong() * stat.blockSize.toLong()
        "剩余 ${mb(available)}"
    }.getOrDefault("未知")

    private fun mb(bytes: Long): String = "${bytes / (1024L * 1024L)} MB"
}
