package com.local.ktv

import android.os.Environment
import java.io.File

/** The only external-storage namespace used by the fresh Maidong KTV app. */
object AppPaths {
    private val externalStorage = Environment.getExternalStorageDirectory()

    val root = File(externalStorage, "MaidongKTV")
    val ROOT_PATH = root.absolutePath
    val VIDEO_PATH = File(root, "video").absolutePath

    val stateFile = File(root, "state.json")
    val songsDir = File(root, "songs")
    val importDir = File(root, "import")
    val catalogFile = File(root, "catalog.json")
    val recordsDir = File(root, "records")
    val moviesDir = File(root, "movies")
    val appsDir = File(root, "apps")
    val backupDir = File(root, "backup")
    val databaseDir = File(root, "database")
    val databaseFile = File(databaseDir, "muse.db")
    val videoDir = File(VIDEO_PATH)
    val cloudSongsDir = File(videoDir, "cloud-song")

    fun ensureDirectories() {
        listOf(root, songsDir, importDir, recordsDir, moviesDir, appsDir, backupDir,
            databaseDir, videoDir, cloudSongsDir).forEach {
            check(it.exists() || it.mkdirs()) { "Cannot create ${it.absolutePath}" }
        }
    }

    /** The original IJK binary creates this log folder internally; remove it after native startup. */
    fun removeNativeLegacyLogDirectory() {
        File(externalStorage, "muse").deleteRecursively()
    }
}
