package com.local.ktv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 歌手头像加载器（P1）。
 *
 * 顺序：内存 LRU → 本地库 `SingerPortraitStore` → 在线拉取（i歌霸 image_url）→ 落盘。
 * 全部在后台线程做，主线程只负责在「tag 仍然匹配」时贴图，避免列表滚动后错位。
 */
internal object SingerAvatarLoader {
    private const val TAG = "SingerAvatar"
    private const val MEMORY_BYTES = 8 * 1024 * 1024
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 15_000

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private val inflight = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 清空内存缓存（设置页清头像库时一并调用，避免「清了还显示」）。 */
    fun clearMemory() {
        memory.evictAll()
    }

    /** @param targetSizePx 期望边长（像素），按 view 尺寸传入即可。 */
    fun load(number: String?, url: String?, target: ImageView, fallback: Drawable, targetSizePx: Int = 128) {
        val key = number?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() } ?: return
        target.tag = key
        memory.get(key)?.let { bitmap ->
            target.setImageDrawable(circle(target, bitmap))
            return
        }
        target.setImageDrawable(fallback)
        if (!inflight.add(key)) return
        executor.execute {
            val bitmap = runCatching { resolve(key, number, url, targetSizePx) }.getOrNull()
            inflight.remove(key)
            if (bitmap == null) return@execute
            memory.put(key, bitmap)
            main.post {
                if (target.tag == key) target.setImageDrawable(circle(target, bitmap))
            }
        }
    }

    /**
     * 预热：启动后把歌星页首页那批头像先拉到本地，进页面就不用等。
     * 已经缓存过的直接跳过，最多 [limit] 张。
     */
    fun warmUp(singers: List<Array<String?>>, limit: Int = 40) {
        executor.execute {
            var fetched = 0
            for (singer in singers) {
                if (fetched >= limit) break
                val number = singer.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
                if (SingerPortraitStore.cached(number) != null) continue
                val url = singer.getOrNull(5)?.takeIf { it.isNotBlank() } ?: continue
                if (fetchToStore(number, url) != null) fetched++
            }
            if (fetched > 0) {
                val (count, bytes) = SingerPortraitStore.stats()
                AppLog.i(TAG, "头像预热 $fetched 张，本地库现有 $count 张 / ${bytes / 1024} KB")
            }
        }
    }

    private fun resolve(key: String, number: String?, url: String?, targetSizePx: Int): Bitmap? {
        SingerPortraitStore.cached(number)?.let { file ->
            decode(file.readBytes(), targetSizePx)?.let { return it }
        }
        if (number == null || url == null) return null
        val bytes = download(url) ?: return null
        SingerPortraitStore.save(number, bytes)
        return decode(bytes, targetSizePx)
    }

    private fun fetchToStore(number: String, url: String): File? {
        if (!inflight.add(number)) return null
        return try {
            val bytes = download(url) ?: return null
            SingerPortraitStore.save(number, bytes)
        } finally {
            inflight.remove(number)
        }
    }

    private fun download(url: String): ByteArray? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", IgebaApiClient.USER_AGENT)
            val code = connection.responseCode
            if (code !in 200..299) {
                AppLog.w(TAG, "头像下载 HTTP $code ${AppLog.maskUrl(url)}")
                return null
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > 3 * 1024 * 1024) break
                }
            }
            output.toByteArray().takeIf(SingerPortraitStore::looksLikeImage)
        } finally {
            connection.disconnect()
        }
    }.getOrElse {
        AppLog.w(TAG, "头像下载失败 ${AppLog.maskUrl(url)}：${it.message}")
        null
    }

    /** 按目标边长算 inSampleSize，避免 1280 大图直接解码进 88dp 的圆里。 */
    private fun decode(bytes: ByteArray, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val side = minOf(bounds.outWidth, bounds.outHeight)
        while (side / sample > targetSizePx * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun circle(target: ImageView, bitmap: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(target.resources, bitmap).apply { isCircular = true }
}
