package com.local.ktv

import java.io.File

/**
 * 歌手头像本地库（P1）。
 *
 * i歌霸曲库每行歌手都带 `image_url`（http 直链），但此前歌星页只用内置占位图，
 * 每次滚动都在线拉、断网就一片灰。这里把头像落成本地文件，按歌手编号命名，可复用、可清理：
 * `/sdcard/MaidongKTV/images/singers/<歌手编号>.img`。
 *
 * 用 `.img` 后缀而不是 `.jpg`：服务器给的是 jpg/png/webp 混合，扩展名靠文件头判断，
 * 存成统一后缀反而不会骗人（BitmapFactory 也不需要扩展名）。
 */
internal object SingerPortraitStore {
    private const val MAX_BYTES = 3L * 1024L * 1024L
    private val dir = File(AppPaths.root, "images/singers")

    fun fileFor(number: String): File = File(dir, "$number.img")

    /** 已缓存且文件头像有效时返回，否则 null。 */
    fun cached(number: String?): File? {
        if (number.isNullOrBlank()) return null
        val file = fileFor(number)
        if (!file.isFile || file.length() == 0L) return null
        return if (looksLikeImage(file)) file else null
    }

    /** 原子落盘：先写 .part，校验通过再改名，避免半截图被当成缓存。 */
    fun save(number: String, bytes: ByteArray): File? {
        if (number.isBlank() || bytes.size > MAX_BYTES || !looksLikeImage(bytes)) return null
        return runCatching {
            dir.mkdirs()
            val target = fileFor(number)
            val part = File(dir, "$number.part")
            part.writeBytes(bytes)
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) return null
            target
        }.getOrElse {
            AppLog.w(TAG, "头像落盘失败 number=$number：${it.message}")
            null
        }
    }

    fun stats(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".img")) {
                count++
                bytes += file.length()
            }
        }
        return count to bytes
    }

    fun clear(): Pair<Int, Long> {
        val (count, bytes) = stats()
        dir.listFiles()?.forEach { it.delete() }
        AppLog.i(TAG, "清理头像缓存：$count 个，${bytes / 1024} KB")
        return count to bytes
    }

    private fun looksLikeImage(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(12)
            val read = input.read(head)
            looksLikeImage(if (read > 0) head.copyOf(read) else ByteArray(0))
        }
    }.getOrDefault(false)

    /** JPEG(FFD8FF) / PNG(89504E47) / GIF(GIF8) / WEBP(RIFF....WEBP)。 */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return true
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) return true
        val riff = String(bytes, 0, 4, Charsets.ISO_8859_1)
        val webp = String(bytes, 8, 4, Charsets.ISO_8859_1)
        return riff == "RIFF" && webp == "WEBP"
    }

    private const val TAG = "SingerPortrait"
}
