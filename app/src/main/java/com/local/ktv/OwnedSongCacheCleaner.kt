package com.local.ktv

import java.io.File

/**
 * 自有缓存清理（P0）。
 *
 * 只处理「本应用下载并命名」的文件：`<曲库编号>.<mkv|mp4|ts>` 以及它们的半成品
 * `.download` / `.decrypted`。这样即使用户往同一目录里拷了自己的歌（如 `测试歌曲.wav`），
 * 也不会被误删——旧实现只能整目录删，风险太高。
 */
internal object OwnedSongCacheCleaner {
    /** 曲库编号是 7–10 位纯数字，扩展名只认这几种。 */
    private val OWNED_NAME = Regex("^(\\d{7,10})\\.(mkv|mp4|ts)(\\.download|\\.decrypted)?$", RegexOption.IGNORE_CASE)

    class Summary(val files: Int, val bytes: Long) {
        fun describe(): String {
            if (files == 0) return "没有可清理的下载文件"
            return "${files} 个文件，共 ${bytes / (1024L * 1024L)} MB"
        }
    }

    fun scan(dir: File = AppPaths.cloudSongsDir): Summary {
        if (!dir.isDirectory) return Summary(0, 0L)
        var files = 0
        var bytes = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && OWNED_NAME.matches(file.name)) {
                files++
                bytes += file.length()
            }
        }
        return Summary(files, bytes)
    }

    /**
     * @param keep 需要保留的曲库编号（正在下载或刚点过的歌），空则清空全部自有文件。
     */
    fun clean(dir: File = AppPaths.cloudSongsDir, keep: Set<String> = emptySet()): Summary {
        if (!dir.isDirectory) return Summary(0, 0L)
        var files = 0
        var bytes = 0L
        dir.listFiles()?.forEach { file ->
            val match = OWNED_NAME.matchEntire(file.name) ?: return@forEach
            val number = match.groupValues[1]
            if (keep.contains(number)) return@forEach
            val length = file.length()
            if (file.delete()) {
                files++
                bytes += length
            }
        }
        AppLog.i("SongCache", "清理自有缓存：${files} 个文件，${bytes / (1024L * 1024L)} MB")
        return Summary(files, bytes)
    }
}
