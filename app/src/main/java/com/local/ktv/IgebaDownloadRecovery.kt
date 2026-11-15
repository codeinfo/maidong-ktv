package com.local.ktv

import java.util.concurrent.Executors

/**
 * 断点恢复（P0）。
 *
 * 之前应用重启后，state.json / KtvStateDatabase 里「queued / downloading」的下载只会被标成
 * 「应用重启，等待重试」，然后就一直躺着等用户手动点——现场很常见的是下到一半断电或被切走，
 * 再进来就发现歌没下完、还得重新点一遍。
 *
 * 这里在启动后把这类任务捡回来，逐首串行重跑（间隔 2 秒，避免同时抢带宽和空间）。
 */
internal object IgebaDownloadRecovery {
    private const val MAX_PER_BOOT = 3
    private val runner = Executors.newSingleThreadExecutor()
    private var scheduled = false

    /**
     * @param candidates 上次没下完的歌（按进队顺序）
     * @param onEach 每恢复一首前回调，宿主用它更新列表状态与数据库
     */
    fun resume(candidates: List<Song>, onEach: (Song) -> Unit) {
        if (candidates.isEmpty()) return
        synchronized(this) {
            if (scheduled) return
            scheduled = true
        }
        runner.execute {
            var count = 0
            for (song in candidates) {
                if (count >= MAX_PER_BOOT) break
                if (SongOkDownloadManager.isDownloaded(song)) {
                    AppLog.i(TAG, "已有完整文件，跳过：${song.title}")
                    continue
                }
                if (SongOkDownloadManager.isDownloading(song)) continue
                runCatching {
                    onEach(song)
                    AppLog.i(TAG, "恢复下载：${song.title}（${count + 1}/$MAX_PER_BOOT）")
                    SongOkDownloadManager.download(song, null)
                    count++
                }.onFailure { AppLog.e(TAG, "恢复下载失败：${song.title}", it) }
                Thread.sleep(2_000L)
            }
            AppLog.i(TAG, "本轮恢复 $count 首")
        }
    }

    private const val TAG = "DownloadRecovery"
}
