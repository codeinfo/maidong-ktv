package com.local.ktv

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * i歌霸媒体文件下载器。
 *
 * 为什么不用 okdownload：
 * 实测（2026-11-15）i歌霸媒体机（218.95.37.159:1882）对 `Range: bytes=<start>-`（开放结束位）
 * 直接回 **403**，只接受 `bytes=<start>-<end>` 这种带结束位的区间（回 206）。
 * okdownload 首个请求就是开放区间，于是每次都在 offset 0 拿到
 * `ServerCanceledException: Response code can't handled on internal 403`，
 * 表现为「点歌后一直下载不下来」。这里改成自己按 4 MB 显式区间拉取，支持续传与取消。
 */
internal object IgebaMediaDownloader {
    private const val TAG = "IgebaMedia"
    private const val CHUNK = 4L * 1024 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    class Outcome(
        val ok: Boolean,
        val bytes: Long,
        val total: Long,
        val message: String? = null,
    )

    /**
     * 阻塞下载，必须在后台线程调用。
     * @param partial 目标文件旁的 `.download` 半成品，存在即按已有长度续传。
     */
    fun run(
        url: String,
        partial: File,
        stallTimeoutMs: Long,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): Outcome {
        var total = -1L
        val existed = partial.exists()
        var written = if (existed) partial.length() else 0L
        var lastActivity = System.currentTimeMillis()
        try {
            partial.parentFile?.mkdirs()
            while (true) {
                if (cancelled()) return Outcome(false, written, total, "已取消")
                if (total > 0 && written >= total) break
                val start = if (partial.exists()) partial.length() else 0L
                val end = start + CHUNK - 1
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT
                    connection.readTimeout = READ_TIMEOUT
                    connection.setRequestProperty("User-Agent", IgebaApiClient.USER_AGENT)
                    // 结束位必须给：开放区间会被媒体机判 403
                    connection.setRequestProperty("Range", "bytes=$start-$end")
                    val code = connection.responseCode
                    if (code == 416) break
                    if (code != 206 && code != 200) {
                        AppLog.w(TAG, "媒体机拒绝请求 HTTP $code offset=$start")
                        return Outcome(false, written, total, "HTTP $code")
                    }
                    if (total < 0) total = totalLength(connection.getHeaderField("Content-Range"))
                    // 200 表示服务端忽略了区间：从头写，避免把整文件追加到半成品后面
                    val append = code == 206 && start == written && partial.exists()
                    FileOutputStream(partial, append).use { output ->
                        val input: InputStream = connection.inputStream
                        val buffer = ByteArray(64 * 1024)
                        var thisChunk = 0L
                        while (true) {
                            if (cancelled()) return Outcome(false, written, total, "已取消")
                            if (System.currentTimeMillis() - lastActivity > stallTimeoutMs) {
                                AppLog.w(TAG, "下载停滞 ${stallTimeoutMs}ms，已中断")
                                return Outcome(false, written, total, "下载停滞")
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            thisChunk += read
                            written += read
                            lastActivity = System.currentTimeMillis()
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt().coerceIn(0, 99)
                                progress(percent)
                            }
                        }
                        output.flush()
                        if (thisChunk <= 0L && total > 0 && written < total) {
                            return Outcome(false, written, total, "连接未返回数据")
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (total <= 0) {
                    // 服务端没给总长（极少数情况）：一次拉完就收工
                    break
                }
            }
            if (total > 0 && written < total) {
                return Outcome(false, written, total, "只下到 $written/$total 字节")
            }
            AppLog.i(TAG, "下载完成 bytes=$written total=$total")
            return Outcome(true, written, total, null)
        } catch (error: Throwable) {
            AppLog.e(TAG, "下载异常 written=$written total=$total", error)
            return Outcome(false, written, total, error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Content-Range 形如 `bytes 0-4194303/49090742`，`Content-Length` 只是本段长度。
     * 手动解析而不用 getContentLengthLong()，后者在 API 19 上不存在（会 NoSuchMethodError）。
     */
    private fun totalLength(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1L
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0) return -1L
        return contentRange.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }
}
