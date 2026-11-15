package com.local.ktv

import android.util.Log
import com.liulishuo.okdownload.DownloadTask as OkDownloadTask
import com.liulishuo.okdownload.OkDownload
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener2
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object SongOkDownloadManager {
    private const val TAG = "SongDownload"
    const val MIN_VALID_FILE_SIZE = 6L * 1024L * 1024L
    private const val MAX_RETRY_COUNT = 3
    private const val STALL_TIMEOUT_MS = 25_000L

    interface DownloadCallback {
        fun onDownloadStart(song: Song)
        fun onDownloadProgress(song: Song, progress: Int)
        fun onDownloadReadyToPlay(song: Song)
        fun onDownloadComplete(song: Song, localPath: String)
        fun onDownloadFailed(song: Song, error: String)
    }

    private val tasks = ConcurrentHashMap<String, OkDownloadTask>()
    private val pending = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** 正在走自研分段下载的 i歌霸 任务（okdownload 不参与，取消要单独标记）。 */
    private val igeabaRunning = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val callbacks = ConcurrentHashMap<String, DownloadCallback>()
    private val progress = ConcurrentHashMap<String, Int>()
    private val totalLengths = ConcurrentHashMap<String, Long>()
    private val forcedRetryReasons = ConcurrentHashMap<String, String>()
    private val manuallyCanceled = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val io = Executors.newFixedThreadPool(4)
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    @JvmStatic
    fun getLocalFile(song: Song): File {
        val fileName = song.filename?.takeIf(String::isNotEmpty) ?: "${song.id}.ts"
        return File("${IgebaCatalog.VIDEO_ROOT}/${IgebaCatalog.CLOUD_SONG_DIR}", fileName)
    }

    @JvmStatic
    fun isDownloaded(song: Song): Boolean {
        val file = getLocalFile(song)
        if (file.exists()) {
            val requiresTs = SongFileValidator.requiresTransportStream(file)
            val verdict = SongFileValidator.inspect(file, requiresTs)
            if (!verdict.valid) {
                Log.w(
                    TAG,
                    "Discarding invalid song file: ${file.absolutePath}, ${file.length()} bytes, ${verdict.reason}"
                )
                file.delete()
                SongFileValidator.forget(file)
                song.path = null
            }
        }
        val downloaded = file.exists() && file.length() >= MIN_VALID_FILE_SIZE
        if (downloaded) {
            // Catalog rows may still contain their original cloud-songNNNN path.
            // Once the unified app directory has a verified file, it is the only
            // valid playback source regardless of the stale non-null catalog path.
            song.path = file.absolutePath
        }
        return downloaded
    }

    @JvmStatic
    fun isDownloading(song: Song): Boolean {
        val key = songKey(song)
        return tasks.containsKey(key) || pending.contains(key)
    }

    @JvmStatic
    fun download(song: Song, callback: DownloadCallback?) {
        if (isDownloaded(song)) {
            val localFile = getLocalFile(song)
            Log.i(TAG, "Using verified local file: ${localFile.absolutePath}, ${localFile.length()} bytes")
            callback?.onDownloadComplete(song, localFile.absolutePath)
            return
        }
        val key = songKey(song)
        if (tasks.containsKey(key) || !pending.add(key)) {
            callback?.onDownloadStart(song)
            return
        }
        io.execute {
            try {
                val budget = DownloadSpaceBudget.check(getLocalFile(song))
                if (!budget.allowed) {
                    pending.remove(key)
                    AppLog.w(TAG, "空间不足，拒绝下载：${song.title}，${budget.message}")
                    callback?.onDownloadFailed(song, budget.message ?: "存储空间不足")
                    return@execute
                }
                val resolution = resolveFor(song)
                if (resolution == null) {
                    pending.remove(key)
                    callback?.onDownloadFailed(song, "无法生成下载地址")
                } else {
                    startDownload(song, resolution, callback, 0)
                }
            } catch (error: Throwable) {
                pending.remove(key)
                callback?.onDownloadFailed(song, error.message ?: "下载初始化失败")
            }
        }
    }

    @JvmStatic
    fun cancelDownload(song: Song) {
        val key = songKey(song)
        if (igeabaRunning.contains(key)) {
            // 自研下载器靠这个标记中断，收尾逻辑在下载线程里做
            manuallyCanceled.add(key)
        }
        pending.remove(key)
        tasks.remove(key)?.let { task ->
            manuallyCanceled.add(key)
            task.cancel()
            clearBrokenDownload(task, getLocalFile(song))
        }
        callbacks.remove(key)
        progress.remove(key)
        totalLengths.remove(key)
    }

    @JvmStatic
    fun getDownloadProgress(song: Song): Int = progress[songKey(song)] ?: 0

    private fun resolveFor(song: Song, forceRefresh: Boolean = false): SongSourceRouter.Resolution? {
        val resolution = SongSourceRouter.resolve(song, forceRefresh)
        if (resolution == null) {
            AppLog.w(TAG, "所有音源均未取到地址: ${song.title}")
        } else {
            AppLog.i(TAG, "音源=${resolution.source.label} 歌曲=${song.title} 地址=${AppLog.maskUrl(resolution.url)}")
        }
        return resolution
    }

    /** i歌霸轨道走自研分段下载（媒体机拒收开放 Range，okdownload 会在首包 403）；其余来源继续用 okdownload。 */
    private fun startDownload(
        song: Song,
        resolution: SongSourceRouter.Resolution,
        callback: DownloadCallback?,
        attempt: Int,
    ) {
        if (resolution.source == SongSourceRouter.Source.IGEBA) {
            startIgebaDownload(song, resolution.url, callback, attempt)
        } else {
            startDownloadTask(song, resolution.url, callback, attempt)
        }
    }

    private fun startIgebaDownload(
        song: Song,
        url: String,
        callback: DownloadCallback?,
        attempt: Int,
    ) {
        val key = songKey(song)
        val target = getLocalFile(song)
        val partial = File(target.parentFile, "${target.name}.download")
        target.parentFile?.mkdirs()
        tasks.remove(key)?.cancel()
        igeabaRunning.add(key)
        callback?.let { callbacks[key] = it }
        progress[key] = 0
        AppLog.i(
            TAG,
            "i歌霸下载 歌曲=${song.title} 目标=${target.name} 第${attempt + 1}次 地址=${AppLog.maskUrl(url)}",
        )
        callback?.onDownloadStart(song)
        io.execute {
            val outcome = IgebaMediaDownloader.run(
                url,
                partial,
                STALL_TIMEOUT_MS,
                progress = { percent ->
                    progress[key] = percent
                    callbacks[key]?.onDownloadProgress(song, percent)
                },
                cancelled = { manuallyCanceled.contains(key) },
            )
            igeabaRunning.remove(key)
            if (manuallyCanceled.remove(key)) {
                pending.remove(key)
                progress.remove(key)
                callbacks.remove(key)
                return@execute
            }
            if (!outcome.ok) {
                AppLog.w(TAG, "i歌霸下载失败 歌曲=${song.title}：${outcome.message} 已下 ${outcome.bytes}/${outcome.total}")
                retryOrFail(song, callback, attempt, "igeaba: ${outcome.message}", "下载失败：${outcome.message}")
                return@execute
            }
            val verdict = SongFileValidator.inspect(
                partial,
                SongFileValidator.requiresTransportStream(partial),
            )
            if (partial.length() < MIN_VALID_FILE_SIZE || !verdict.valid) {
                partial.delete()
                SongFileValidator.forget(partial)
                val reason = if (partial.exists()) verdict.reason else "file too small: ${outcome.bytes}"
                AppLog.w(TAG, "i歌霸文件校验未通过 歌曲=${song.title}：$reason")
                retryOrFail(song, callback, attempt, "invalid media: $reason", "下载文件不完整，正在重试")
                return@execute
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                retryOrFail(song, callback, attempt, "rename failed", "下载文件落盘失败")
                return@execute
            }
            pending.remove(key)
            progress.remove(key)
            callbacks.remove(key)
            song.path = target.absolutePath
            AppLog.i(TAG, "i歌霸下载完成 歌曲=${song.title} 文件=${target.absolutePath} 大小=${target.length()}")
            callback?.onDownloadProgress(song, 100)
            callback?.onDownloadComplete(song, target.absolutePath)
        }
    }

    private fun startDownloadTask(song: Song, url: String, callback: DownloadCallback?, attempt: Int) {
        val key = songKey(song)
        val target = getLocalFile(song)
        val partial = File(target.parentFile, "${target.name}.download")
        target.parentFile?.mkdirs()
        if (attempt > 0) partial.delete()
        AppLog.i(
            TAG,
            "开始下载 歌曲=${song.title} 目标=${target.name} 第${attempt + 1}次 地址=${AppLog.maskUrl(url)}",
        )
        val task = OkDownloadTask.Builder(url, partial.parentFile!!)
            .setFilename(partial.name)
            .setPassIfAlreadyCompleted(false)
            .setMinIntervalMillisCallbackProcess(250)
            .build()
        tasks[key] = task
        pending.remove(key)
        callback?.let { callbacks[key] = it }
        progress[key] = 0
        totalLengths[key] = 0
        val downloaded = AtomicLong(0)
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        var httpResponseCode = 0

        scheduleStallCheck(song, task, lastActivityAt, attempt)
        task.enqueue(object : DownloadListener2() {
            override fun taskStart(task: OkDownloadTask) {
                lastActivityAt.set(System.currentTimeMillis())
                callbacks[key]?.onDownloadStart(song)
            }

            override fun connectEnd(
                task: OkDownloadTask,
                blockCount: Int,
                responseCode: Int,
                responseHeaderFields: MutableMap<String, MutableList<String>>,
            ) {
                httpResponseCode = responseCode
                lastActivityAt.set(System.currentTimeMillis())
            }

            override fun downloadFromBeginning(task: OkDownloadTask, info: BreakpointInfo, cause: ResumeFailedCause) {
                totalLengths[key] = info.totalLength
            }

            override fun downloadFromBreakpoint(task: OkDownloadTask, info: BreakpointInfo) {
                totalLengths[key] = info.totalLength
                downloaded.set(info.totalOffset)
            }

            override fun fetchProgress(task: OkDownloadTask, blockIndex: Int, increaseBytes: Long) {
                if (increaseBytes > 0) lastActivityAt.set(System.currentTimeMillis())
                val total = totalLengths[key] ?: return
                if (total <= 0) return
                val value = (downloaded.addAndGet(increaseBytes) * 100 / total).toInt().coerceIn(0, 98)
                progress[key] = value
                callbacks[key]?.onDownloadProgress(song, value)
            }

            override fun taskEnd(task: OkDownloadTask, cause: EndCause, realCause: Exception?) {
                tasks.remove(key, task)
                totalLengths.remove(key)
                val cb = callbacks.remove(key)
                val forcedReason = forcedRetryReasons.remove(key)
                if (manuallyCanceled.remove(key)) return
                if (cause != EndCause.COMPLETED) {
                    AppLog.w(
                        TAG,
                        "下载未完成 歌曲=${song.title} cause=$cause http=$httpResponseCode " +
                            "real=${realCause?.javaClass?.simpleName}:${realCause?.message} attempt=${attempt + 1}",
                    )
                    val retryable = forcedReason != null || cause != EndCause.CANCELED
                    if (retryable && attempt < MAX_RETRY_COUNT) {
                        clearBrokenDownload(task, target)
                        val response = if (httpResponseCode > 0) " HTTP $httpResponseCode" else ""
                        retry(song, cb, attempt + 1, forcedReason ?: "${cause.name}$response")
                    } else {
                        pending.remove(key)
                        progress.remove(key)
                        cb?.onDownloadFailed(song, cause.name + (realCause?.message?.let { ": $it" } ?: ""))
                    }
                    return
                }
                pending.add(key)
                progress[key] = 99
                cb?.onDownloadProgress(song, 99)
                io.execute { finalizeDownload(song, task, target, partial, cb, attempt) }
            }
        })
    }

    private fun finalizeDownload(
        song: Song,
        task: OkDownloadTask,
        target: File,
        partial: File,
        callback: DownloadCallback?,
        attempt: Int,
    ) {
        val key = songKey(song)
        try {
            var downloadedFile = task.file ?: partial
            if (!downloadedFile.exists() || downloadedFile.length() < MIN_VALID_FILE_SIZE) {
                val size = downloadedFile.takeIf(File::exists)?.length() ?: 0L
                clearBrokenDownload(task, target)
                retryOrFail(song, callback, attempt, "file too small: $size", "下载文件异常，已自动清理")
                return
            }
            // 大小够了也要能识别容器：截断的 MKV / 预览片同样要重试
            val verdict = SongFileValidator.inspect(
                downloadedFile,
                SongFileValidator.requiresTransportStream(downloadedFile),
            )
            if (!verdict.valid) {
                clearBrokenDownload(task, target)
                SongFileValidator.forget(downloadedFile)
                retryOrFail(song, callback, attempt, "invalid media: ${verdict.reason}", "下载文件不完整，正在重试")
                return
            }
            if (shouldDecrypt(song) && TsDecryptor.isEncrypted(downloadedFile)) {
                val decrypted = File(downloadedFile.parentFile, "${downloadedFile.name}.decrypted")
                decrypted.delete()
                val decryptedOk = TsDecryptor.decryptFile(downloadedFile, decrypted)
                val replaced = decryptedOk && downloadedFile.delete() && decrypted.renameTo(downloadedFile)
                if (!replaced) {
                    clearBrokenDownload(task, target)
                    retryOrFail(song, callback, attempt, "decrypt failed", "歌曲解密失败，异常文件已删除")
                    return
                }
                downloadedFile = task.file ?: partial
            }
            if (target.exists() && !target.delete()) {
                pending.remove(key)
                progress.remove(key)
                callback?.onDownloadFailed(song, "无法替换旧的歌曲文件")
                return
            }
            if (!downloadedFile.renameTo(target)) {
                clearBrokenDownload(task, target)
                retryOrFail(song, callback, attempt, "rename failed", "下载文件落盘失败")
                return
            }
            pending.remove(key)
            progress.remove(key)
            song.path = target.absolutePath
            callback?.onDownloadProgress(song, 100)
            callback?.onDownloadComplete(song, target.absolutePath)
        } catch (error: Throwable) {
            Log.e(TAG, "Finalizing download failed: ${song.title}", error)
            clearBrokenDownload(task, target)
            retryOrFail(song, callback, attempt, error.message ?: "finalize failed", "下载文件处理失败")
        }
    }

    /**
     * i歌霸返回的是 MKV 封装（首字节并非 TS 的 0x47），复用旧的 TS 判定会误判为加密并触发解密，
     * 导致正常文件被判失败删除，因此该来源跳过 TS 解密。
     */
    private fun shouldDecrypt(song: Song): Boolean =
        song.sourceName != SongSourceRouter.Source.IGEBA.label

    private fun retryOrFail(
        song: Song,
        callback: DownloadCallback?,
        attempt: Int,
        reason: String,
        terminalMessage: String,
    ) {
        if (attempt < MAX_RETRY_COUNT) {
            retry(song, callback, attempt + 1, reason)
        } else {
            val key = songKey(song)
            pending.remove(key)
            progress.remove(key)
            callback?.onDownloadFailed(song, terminalMessage)
        }
    }

    private fun scheduleStallCheck(
        song: Song,
        task: OkDownloadTask,
        lastActivityAt: AtomicLong,
        attempt: Int,
    ) {
        watchdog.schedule({
            val key = songKey(song)
            if (tasks[key] !== task) return@schedule
            val idleMs = System.currentTimeMillis() - lastActivityAt.get()
            if (idleMs >= STALL_TIMEOUT_MS) {
                Log.w(TAG, "Download stalled for ${idleMs}ms: ${song.title}, attempt=$attempt")
                forcedRetryReasons[key] = "stalled for ${idleMs}ms"
                task.cancel()
            } else {
                scheduleStallCheck(song, task, lastActivityAt, attempt)
            }
        }, STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun clearBrokenDownload(task: OkDownloadTask, target: File) {
        runCatching { OkDownload.with().breakpointStore().remove(task.id) }
        val partial = task.file ?: File(target.parentFile, "${target.name}.download")
        if (partial.absolutePath != target.absolutePath) partial.delete()
        File(partial.parentFile, "${partial.name}.decrypted").delete()
    }

    private fun retry(song: Song, callback: DownloadCallback?, attempt: Int, reason: String) {
        Log.w(TAG, "Retrying ${song.title}, attempt=$attempt, reason=$reason")
        val key = songKey(song)
        pending.add(key)
        progress[key] = 0
        callback?.onDownloadProgress(song, 0)
        song.downloadUrl = null
        SongSourceRouter.invalidate(song)
        io.execute {
            Thread.sleep((attempt * 750L).coerceAtMost(2_250L))
            val resolution = runCatching { resolveFor(song, forceRefresh = true) }.getOrNull()
            if (resolution == null) {
                if (attempt < MAX_RETRY_COUNT) {
                    retry(song, callback, attempt + 1, "refresh download URL failed")
                } else {
                    pending.remove(key)
                    progress.remove(key)
                    callback?.onDownloadFailed(song, "刷新下载地址失败")
                }
            } else {
                startDownload(song, resolution, callback, attempt)
            }
        }
    }

    private fun songKey(song: Song): String = song.id?.takeIf(String::isNotEmpty)
        ?: song.filename?.takeIf(String::isNotEmpty)
        ?: song.title.orEmpty()
}
