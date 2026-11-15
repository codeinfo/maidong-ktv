package com.local.ktv

import java.io.File

/**
 * 旧下载目录迁移（P0）。
 *
 * 1.0/1.1 早期版本把歌曲存在 `video/cloud-songNNNN/`（按分片编号分目录），
 * 后来统一到 `video/cloud-song/`。切到 i歌霸曲库后，文件名也变成曲库自带的
 * `nnnnnnnnn.mkv`，于是历史文件既不在新目录、名字也不匹配，表现就是「明明下过还要重下」。
 *
 * 这里做一次性迁移：把旧目录里符合命名规则的文件搬到 `cloud-song/`，目标已存在则跳过（不动新文件）。
 */
internal object LegacyStorageUpgrade {
    private val SONG_FILE = Regex("^(\\d{7,10})\\.(mkv|mp4|ts)$", RegexOption.IGNORE_CASE)

    class Report(val moved: Int, val skipped: Int) {
        val changed: Boolean get() = moved > 0
    }

    fun run(target: File = AppPaths.cloudSongsDir): Report {
        if (!target.exists()) target.mkdirs()
        val legacyDirs = findLegacyDirs()
        if (legacyDirs.isEmpty()) return Report(0, 0)
        var moved = 0
        var skipped = 0
        legacyDirs.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !SONG_FILE.matches(file.name)) return@forEach
                val destination = File(target, file.name)
                if (destination.exists() && destination.length() >= file.length()) {
                    skipped++
                    return@forEach
                }
                if (destination.exists()) destination.delete()
                if (file.renameTo(destination)) {
                    moved++
                } else {
                    skipped++
                }
            }
            // 目录空了就顺手删掉，免得下次还要扫
            if (dir.listFiles()?.isEmpty() != false) dir.delete()
        }
        if (moved > 0 || skipped > 0) {
            AppLog.i("StorageUpgrade", "旧目录迁移：移动 $moved 个，跳过 $skipped 个，来源 ${legacyDirs.size} 个目录")
        }
        return Report(moved, skipped)
    }

    private fun findLegacyDirs(): List<File> {
        val video = AppPaths.videoDir
        val candidates = ArrayList<File>()
        video.listFiles()?.forEach { file ->
            if (file.isDirectory && (file.name == "cloud-song" || file.name.startsWith("cloud-song"))) {
                if (file.absolutePath != AppPaths.cloudSongsDir.absolutePath) candidates.add(file)
            }
        }
        // 早期版本也有直接放在 root/songs 的导入曲目
        AppPaths.songsDir.takeIf { it.isDirectory }?.let { candidates.add(it) }
        return candidates
    }
}
