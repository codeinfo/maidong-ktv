package com.local.ktv

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * i歌霸（iGeba）曲库访问层，替换原有的 Muse 曲库（[MuseDatabase]）作为点歌目录。
 *
 * 数据来源：assets/igeba_catalog.db（由 2.1.12 内置目录抽出，81801 首、4807 位歌手、
 * 63 个歌单分类、7 个榜单），首次使用时解压到 [AppPaths.igebaCatalogFile]，之后只读查询。
 *
 * 表结构与字段映射（与 2.1.12 的 IgebaCatalog 保持一致）：
 * - source_songs(number,name,singer,language,filename,version,duration,voice_channel,pinyin,pinyin_full,name_len,sort_no,album)
 * - source_singers(number,name,region,type,pinyin,pinyin_full,image_url,sort_no)
 * - source_playlists(number,package_type,name,picture,description,sort_no,song_count)，package_type 3=歌单分类 4=榜单
 * - source_playlist_songs / source_singer_songs
 *
 * 对上层保留与 [MuseDatabase] 相同的方法签名，页面查询逻辑无需改SQL；
 * 差异点：没有 rec/hot 权重与 deleted_at，因此统一按 sort_no（服务端推荐序）排序；
 * voice_channel 直接映射到 Song.accomp（1=左伴右原，2=左原右伴），与原伴唱声道切换一致。
 */
class IgebaCatalog(private val sourceFile: File = AppPaths.igebaCatalogFile) {
    private var database: SQLiteDatabase? = null

    /**
     * 打开曲库；若本地还没有（首次安装/重置后），先从 assets 解压。
     * 会阻塞线程，必须在后台线程调用。
     */
    fun open(): Boolean {
        if (isAvailable()) return true
        if (!sourceFile.isFile) {
            if (!runCatching { prepareAssets(null) }.getOrDefault(false)) return false
        }
        val opened = runCatching {
            database = SQLiteDatabase.openDatabase(
                sourceFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            // 防止拿到旧版 muse.db 或半截文件
            val provider = database!!.rawQuery("SELECT value FROM meta WHERE key='provider'", null).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            check(provider == "igeba") { "曲库来源不匹配：$provider" }
            true
        }.onFailure {
            Log.e(TAG, "打开 i歌霸曲库失败: ${sourceFile.absolutePath}", it)
            close()
        }.getOrDefault(false)
        if (opened) {
            Log.i(
                TAG,
                "曲库已打开：${songCount()} 首 / ${singerCount()} 位歌手，示例检索[稀客]=${searchSongCount("稀客")} 首"
            )
        }
        return opened
    }

    /** 从 assets 解压曲库到应用私有目录；onProgress 回调 0..100。 */
    fun prepareAssets(onProgress: ((Int) -> Unit)?): Boolean {
        val context = KtvApplication.getInstance()
        // asset 在 APK 里是压缩存储，AssetInputStream.available() 拿不到真实大小，改读 zip 条目
        val total = runCatching {
            java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                zip.getEntry("assets/$ASSET_NAME")?.size ?: -1L
            }
        }.getOrDefault(-1L)
        AppPaths.ensureDirectories()
        val temp = File(sourceFile.parentFile ?: AppPaths.databaseDir, "${sourceFile.name}.part")
        temp.delete()
        var written = 0L
        var lastPercent = -1
        context.assets.open(ASSET_NAME, android.content.res.AssetManager.ACCESS_STREAMING).use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    val percent = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else -1
                    if (percent >= 0 && percent != lastPercent) {
                        lastPercent = percent
                        onProgress?.invoke(percent)
                    }
                }
            }
        }
        if (written <= 0) {
            temp.delete()
            return false
        }
        sourceFile.delete()
        if (!temp.renameTo(sourceFile)) {
            temp.delete()
            return false
        }
        onProgress?.invoke(100)
        Log.i(TAG, "i歌霸曲库解压完成：${sourceFile.absolutePath} (${written / 1024} KB)")
        return true
    }

    fun close() {
        runCatching { database?.close() }
        database = null
    }

    fun isAvailable(): Boolean = database?.isOpen == true

    fun dbFile(): File = sourceFile

    fun songCount(): Int = count("SELECT COUNT(*) FROM source_songs")

    fun singerCount(): Int = count("SELECT COUNT(*) FROM source_singers")

    /** 设置页展示用：曲库来源与规模。 */
    fun catalogInfo(): String {
        if (!isAvailable()) return "未加载"
        val snapshot = countText("SELECT value FROM meta WHERE key='snapshot_utc'")
        val remote = countText("SELECT value FROM meta WHERE key='remote_version'")
        val builder = StringBuilder("i歌霸 ")
        builder.append(songCount()).append(" 首 / ").append(singerCount()).append(" 位歌手")
        if (snapshot.isNotEmpty()) builder.append("，快照 ").append(snapshot.take(10))
        if (remote.isNotEmpty()) builder.append("，服务端版本 ").append(remote)
        return builder.toString()
    }

    // ---------------------------------------------------------------- 歌曲检索

    fun searchSongs(
        keyword: String?,
        offset: Int,
        limit: Int,
        language: String? = null,
    ): MutableList<Song> {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return mutableListOf()
        val args = mutableListOf<String>()
        val condition = songKeywordCondition(query, args)
        val languageCondition = if (!language.isNullOrEmpty() && language != "全部") {
            args += language
            " AND s.language=?"
        } else {
            ""
        }
        return songQuery("$condition$languageCondition", args, offset, limit)
    }

    fun searchSongCount(keyword: String?, language: String? = null): Int {
        val query = keyword?.trim().orEmpty()
        if (query.isEmpty()) return 0
        val args = mutableListOf<String>()
        val condition = songKeywordCondition(query, args)
        val languageCondition = if (!language.isNullOrEmpty() && language != "全部") {
            args += language
            " AND s.language=?"
        } else {
            ""
        }
        return count("SELECT COUNT(*) FROM source_songs s WHERE $condition$languageCondition", args.toTypedArray())
    }

    fun hotSongs(offset: Int, limit: Int): MutableList<Song> = songQuery("1=1", mutableListOf(), offset, limit, HOT_ORDER)

    fun quickSongs(limit: Int): MutableList<Song> = hotSongs(0, limit)

    fun songsByLanguage(language: String?, offset: Int, limit: Int): MutableList<Song> =
        if (language.isNullOrEmpty() || language == "全部") {
            hotSongs(offset, limit)
        } else {
            songQuery("s.language=?", mutableListOf(language), offset, limit)
        }

    fun countByLanguage(language: String?): Int =
        if (language.isNullOrEmpty() || language == "全部") {
            songCount()
        } else {
            count("SELECT COUNT(*) FROM source_songs WHERE language=?", arrayOf(language))
        }

    fun songsByWordCount(wordCount: Int, offset: Int, limit: Int): MutableList<Song> {
        val condition = when {
            wordCount <= 0 -> "1=1"
            wordCount >= 6 -> "s.name_len>=6"
            else -> "s.name_len=$wordCount"
        }
        return songQuery(condition, mutableListOf(), offset, limit)
    }

    fun countByWordCount(wordCount: Int): Int {
        val condition = when {
            wordCount <= 0 -> "1=1"
            wordCount >= 6 -> "name_len>=6"
            else -> "name_len=$wordCount"
        }
        return count("SELECT COUNT(*) FROM source_songs WHERE $condition")
    }

    fun languages(): MutableList<String> = rows(
        "SELECT language, COUNT(*) FROM source_songs WHERE language!='' GROUP BY language ORDER BY 2 DESC",
    ) { it.getString(0) }.filterNotNull().filter(String::isNotEmpty).toMutableList()

    // ---------------------------------------------------------------- 歌手

    fun singers(area: String?, type: String?, offset: Int, limit: Int): MutableList<Array<String?>> =
        searchSingers(null, area, type, offset, limit)

    fun singerCount(area: String?, type: String?): Int = searchSingerCount(null, area, type)

    fun searchSingers(
        keyword: String?,
        area: String?,
        type: String?,
        offset: Int,
        limit: Int,
    ): MutableList<Array<String?>> {
        val args = mutableListOf<String>()
        val where = singerWhere(keyword, area, type, args)
        return rows(
            "SELECT sg.number, sg.name, sg.type, sg.region, sg.sort_no, sg.image_url " +
                "FROM source_singers sg WHERE $where ORDER BY sg.sort_no ASC LIMIT ? OFFSET ?",
            (args + listOf(limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString())).toTypedArray(),
        ) { cursor ->
            arrayOf(
                cursor.getString(0),
                cursor.getString(1),
                cursor.getString(2),
                cursor.getString(3),
                // Muse 用 hot_score 展示热度：i歌霸按 sort_no 升序推荐，这里换算成递减的热度值。
                (SINGER_TOTAL - cursor.getInt(4)).coerceAtLeast(0).toString(),
                cursor.getString(5),
            )
        }
    }

    fun searchSingerCount(keyword: String?, area: String?, type: String?): Int {
        val args = mutableListOf<String>()
        return count(
            "SELECT COUNT(*) FROM source_singers sg WHERE " + singerWhere(keyword, area, type, args),
            args.toTypedArray(),
        )
    }

    private fun singerWhere(keyword: String?, area: String?, type: String?, args: MutableList<String>): String {
        val value = keyword?.trim().orEmpty()
        val condition = if (value.isEmpty()) {
            "1=1"
        } else {
            val pattern = "%${value.uppercase(Locale.ROOT)}%"
            repeat(3) { args += pattern }
            "(sg.name LIKE ? OR sg.pinyin LIKE ? OR sg.pinyin_full LIKE ?)"
        }
        var where = condition
        if (!area.isNullOrEmpty() && area != "全部") {
            // 页面沿用的是 Muse 的叫法「内地」，i歌霸库里是「大陆」
            args += if (area == "内地") "大陆" else area
            where += " AND sg.region=?"
        }
        if (!type.isNullOrEmpty() && type != "全部") {
            args += when {
                "男" in type -> "男"
                "女" in type -> "女"
                else -> "组合"
            }
            where += " AND sg.type=?"
        }
        return where
    }

    fun songsBySinger(singerId: String, offset: Int, limit: Int): MutableList<Song> =
        searchSongsBySinger(singerId, null, offset, limit)

    fun countSongsBySinger(singerId: String): Int = countSearchSongsBySinger(singerId, null)

    fun searchSongsBySinger(
        singerId: String,
        keyword: String?,
        offset: Int,
        limit: Int,
    ): MutableList<Song> {
        val args = mutableListOf(singerId)
        val condition = "s.number IN (SELECT song_number FROM source_singer_songs WHERE singer_number=?) " +
            "AND " + songKeywordCondition(keyword?.trim().orEmpty(), args)
        return songQuery(condition, args, offset, limit)
    }

    fun countSearchSongsBySinger(singerId: String, keyword: String?): Int {
        val args = mutableListOf(singerId)
        val condition = "s.number IN (SELECT song_number FROM source_singer_songs WHERE singer_number=?) " +
            "AND " + songKeywordCondition(keyword?.trim().orEmpty(), args)
        return count("SELECT COUNT(*) FROM source_songs s WHERE $condition", args.toTypedArray())
    }

    fun songsBySingerName(singerName: String, offset: Int, limit: Int): MutableList<Song> {
        val (where, args) = singerNameCondition(singerName)
        return songQuery(where, args, offset, limit)
    }

    fun countSongsBySingerName(singerName: String): Int {
        val (where, args) = singerNameCondition(singerName)
        return count("SELECT COUNT(*) FROM source_songs s WHERE $where", args.toTypedArray())
    }

    private fun singerNameCondition(singerName: String): Pair<String, MutableList<String>> {
        val value = singerName.uppercase(Locale.ROOT)
        val pattern = "%$value%"
        val args = mutableListOf(pattern, pattern, pattern, pattern)
        val where = "(s.singer LIKE ? OR s.number IN (SELECT r.song_number FROM source_singer_songs r " +
            "JOIN source_singers sg ON sg.number=r.singer_number " +
            "WHERE sg.name LIKE ? OR sg.pinyin LIKE ? OR sg.pinyin_full LIKE ?))"
        return where to args
    }

    // ---------------------------------------------------------------- 歌单 / 榜单

    fun playlists(offset: Int, limit: Int): MutableList<Array<String?>> = rows(
        "SELECT number, name, song_count, sort_no FROM source_playlists WHERE package_type=3 " +
            "ORDER BY sort_no ASC LIMIT ? OFFSET ?",
        arrayOf(limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString()),
    ) { cursor ->
        arrayOf(
            cursor.getString(0),
            cursor.getString(1),
            cursor.getInt(2).toString(),
            // Muse 第 4 列是 hot_score，页面用它做展示，这里换算成递减值保持单调。
            (PLAYLIST_TOTAL - cursor.getInt(3)).coerceAtLeast(0).toString(),
        )
    }

    fun rankPlaylists(): MutableList<Array<String?>> = rows(
        "SELECT number, name, sort_no, song_count FROM source_playlists WHERE package_type=4 " +
            "ORDER BY sort_no ASC",
    ) { cursor ->
        arrayOf(
            cursor.getString(0),
            cursor.getString(1),
            cursor.getInt(2).toString(),
            cursor.getInt(3).toString(),
        )
    }

    fun categoryPlaylists(keyword: String?, offset: Int, limit: Int): MutableList<Array<String?>> = rows(
        "SELECT number, name, song_count, picture FROM source_playlists " +
            "WHERE package_type=3 AND name LIKE ? ORDER BY sort_no ASC LIMIT ? OFFSET ?",
        arrayOf(
            "%${keyword?.trim().orEmpty()}%",
            limit.coerceAtLeast(0).toString(),
            offset.coerceAtLeast(0).toString(),
        ),
    ) { cursor ->
        arrayOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2).toString(), cursor.getString(3))
    }

    fun categoryPlaylistCount(keyword: String?): Int = count(
        "SELECT COUNT(*) FROM source_playlists WHERE package_type=3 AND name LIKE ?",
        arrayOf("%${keyword?.trim().orEmpty()}%"),
    )

    fun songsInPlaylist(playlistId: String, offset: Int, limit: Int): MutableList<Song> =
        searchSongsInPlaylist(playlistId, null, offset, limit)

    fun searchSongsInPlaylist(
        playlistId: String,
        keyword: String?,
        offset: Int,
        limit: Int,
    ): MutableList<Song> {
        val args = mutableListOf(playlistId)
        val condition = "ps.playlist_number=? AND " + songKeywordCondition(keyword?.trim().orEmpty(), args)
        return rows(
            "$SELECT JOIN source_playlist_songs ps ON ps.song_number=s.number " +
                "WHERE $condition ORDER BY ps.sort_no ASC LIMIT ? OFFSET ?",
            (args + listOf(limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString())).toTypedArray(),
            ::songFromCursor,
        )
    }

    fun countSearchSongsInPlaylist(playlistId: String, keyword: String?): Int {
        val args = mutableListOf(playlistId)
        val condition = "ps.playlist_number=? AND " + songKeywordCondition(keyword?.trim().orEmpty(), args)
        return count(
            "SELECT COUNT(*) FROM source_playlist_songs ps JOIN source_songs s ON s.number=ps.song_number " +
                "WHERE $condition",
            args.toTypedArray(),
        )
    }

    fun rankSongs(playlistId: String, offset: Int, limit: Int): MutableList<Song> =
        songsInPlaylist(playlistId, offset, limit)

    fun rankSongCount(playlistId: String): Int = playlistSongCount(playlistId)

    fun playlistSongCount(playlistId: String): Int = countSearchSongsInPlaylist(playlistId, null)

    // ---------------------------------------------------------------- 单曲

    fun songById(songId: String?): Song? {
        val number = songId?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        return rows("$SELECT WHERE s.number=? LIMIT 1", arrayOf(number), ::songFromCursor).firstOrNull()
    }

    fun songsByFilenames(filenames: Collection<String>): MutableList<Song> {
        if (filenames.isEmpty()) return mutableListOf()
        val result = mutableListOf<Song>()
        filenames.distinct().chunked(300).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            result += rows(
                "$SELECT WHERE s.filename COLLATE NOCASE IN ($placeholders)",
                chunk.toTypedArray(),
                ::songFromCursor,
            )
        }
        return result
    }

    /** i歌霸曲库没有 songs.path 概念（下载目录统一由 filename 推导），历史导入记录不再从曲库列出。 */
    fun localPathSongs(): MutableList<Song> = mutableListOf()

    // ---------------------------------------------------------------- 内部实现

    private fun songKeywordCondition(query: String, args: MutableList<String>): String {
        val tokens = SongSearchTokens.of(query)
        if (tokens.isEmpty()) return "1=1"
        // 多个词取 AND：「刘德华 忘情水」「周杰伦 稻香」都能命中；
        // 每个词内部按 歌名 / 歌手 / 拼音首字母 / 全拼 / 编号 任一匹配
        val perToken = "(s.name LIKE ? OR s.singer LIKE ? OR s.pinyin LIKE ? OR s.pinyin_full LIKE ? OR s.number LIKE ?)"
        return tokens.joinToString(" AND ") {
            val pattern = "%${it.uppercase(Locale.ROOT)}%"
            repeat(5) { args += pattern }
            perToken
        }
    }

    private fun songQuery(
        where: String,
        args: List<String>,
        offset: Int,
        limit: Int,
        order: String = DEFAULT_ORDER,
    ): MutableList<Song> = rows(
        "$SELECT WHERE $where ORDER BY $order LIMIT ? OFFSET ?",
        (args + listOf(limit.coerceAtLeast(0).toString(), offset.coerceAtLeast(0).toString())).toTypedArray(),
        ::songFromCursor,
    )

    private fun songFromCursor(cursor: Cursor): Song = Song().apply {
        val number = cursor.getString(0)?.trim().orEmpty()
        id = number
        dbId = number
        sourceSongNumber = number
        title = cursor.getString(1)?.trim()
        singer = cursor.getString(2)?.trim()
        singerNames = singer
        language = cursor.getString(3)
        category = language
        filename = cursor.getString(4)
        pinyin = cursor.getString(8)
        pinyinFull = cursor.getString(9)
        album = cursor.getString(10)
        // voice_channel 即原伴唱声道模式：1=左伴右原，2=左原右伴，0=无切换
        accomp = cursor.getInt(7)
        remote = true
        path = resolveSongFilePath(this)
    }

    private fun count(sql: String, args: Array<String> = emptyArray()): Int =
        rows(sql, args) { it.getInt(0) }.firstOrNull() ?: 0

    private fun countText(sql: String): String =
        rows(sql) { it.getString(0) }.firstOrNull().orEmpty()

    private fun <T> rows(sql: String, args: Array<String> = emptyArray(), mapper: (Cursor) -> T): MutableList<T> {
        val db = database?.takeIf { it.isOpen } ?: return mutableListOf()
        return runCatching {
            db.rawQuery(sql, args).use { cursor ->
                val list = ArrayList<T>()
                while (cursor.moveToNext()) list += mapper(cursor)
                list
            }
        }.onFailure { Log.e(TAG, "曲库查询失败：$sql", it) }.getOrDefault(mutableListOf())
    }

    companion object {
        private const val TAG = "IgebaCatalog"
        const val ASSET_NAME = "igeba_catalog.db"
        const val PAGE_SIZE = 10
        val VIDEO_ROOT = AppPaths.VIDEO_PATH
        const val CLOUD_SONG_DIR = "cloud-song"
        const val LOCAL_SONG_DIR = "local"

        /** 歌手/歌单的 sort_no 是 0 起递增，用总数减一下得到递减的「热度」展示值。 */
        private const val SINGER_TOTAL = 4807
        private const val PLAYLIST_TOTAL = 63

        private const val SELECT =
            "SELECT s.number, s.name, s.singer, s.language, s.filename, s.version, s.duration, " +
                "s.voice_channel, s.pinyin, s.pinyin_full, s.album, s.name_len, s.sort_no FROM source_songs s"

        private const val DEFAULT_ORDER = "s.sort_no ASC, s.number ASC"

        /** 首页热歌：优先取「热歌榜」里的排位，其余按服务端推荐序。 */
        private const val HOT_ORDER =
            "COALESCE((SELECT ps.sort_no FROM source_playlist_songs ps " +
                "JOIN source_playlists p ON p.number=ps.playlist_number AND p.package_type=ps.package_type " +
                "WHERE ps.song_number=s.number AND p.package_type=4 AND p.name LIKE '热歌榜%' LIMIT 1), 1000000), " +
                "s.sort_no ASC, s.number ASC"

        @JvmStatic
        fun resolveSongFilePath(song: Song?): String =
            song?.filename?.takeIf(String::isNotEmpty)
                ?.let { File(AppPaths.cloudSongsDir, it).absolutePath }.orEmpty()

        @JvmStatic
        fun getStandardLocalPath(filename: String?): String =
            filename?.takeIf(String::isNotEmpty)?.let { "$VIDEO_ROOT/$CLOUD_SONG_DIR/$it" }.orEmpty()

        @JvmStatic
        fun songDirectories(): List<File> = listOf(AppPaths.cloudSongsDir)

        @JvmStatic
        fun defaultDbFile(): File = AppPaths.igebaCatalogFile
    }
}
