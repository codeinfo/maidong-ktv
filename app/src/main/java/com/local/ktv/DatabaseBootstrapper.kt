package com.local.ktv

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * Downloads, verifies, decompresses and installs the catalog database atomically.
 *
 * Two manifest layouts are supported:
 * - flat: size / sha256 / chunks[].url (plain SQLite parts)
 * - gzip: original_size / compressed_size / sha256 / files[].file (one gzip stream split into parts)
 *
 * Downloaded parts survive failed attempts so an interrupted large download resumes where it stopped,
 * and every part is retried against the other manifest mirrors.
 */
object DatabaseBootstrapper {
    private const val DOWNLOAD_ATTEMPTS = 2
    private const val CHUNK_ATTEMPTS = 2
    private const val DOWNLOAD_THREADS = 4
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MIN_FREE_MARGIN = 64L * 1024L * 1024L

    val manifestUrl: String get() = BuildConfig.DATABASE_MANIFEST_URL
    private val token: String get() = BuildConfig.DATABASE_TOKEN

    private val manifestUrls: List<String>
        get() = (listOf(manifestUrl) + BuildConfig.DATABASE_MANIFEST_MIRRORS.split(','))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Retries the complete operation; downloaded parts are kept between attempts. */
    fun download(onProgress: (Int) -> Unit): Result<File> {
        var lastError: Throwable? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            val result = runCatching { downloadOnce(onProgress) }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            if (isNoSpaceError(lastError)) return Result.failure(readableError(lastError))
            if (attempt + 1 < DOWNLOAD_ATTEMPTS) {
                onProgress(0)
                Thread.sleep(1000L)
            }
        }
        return Result.failure(readableError(lastError))
    }

    private fun downloadOnce(onProgress: (Int) -> Unit): File {
        val source = fetchManifest()
        val record = parseManifest(source)
        val target = MuseDatabase.defaultDbFile()
        val databaseDir = target.parentFile ?: error("无法创建曲库目录")
        check(databaseDir.exists() || databaseDir.mkdirs()) { "无法创建曲库目录：$databaseDir" }
        val temporaryDb = File(databaseDir, "${target.name}.download")
        val partsDir = File(databaseDir, "${target.name}.parts")
        temporaryDb.delete()
        check(partsDir.exists() || partsDir.mkdirs()) { "无法创建曲库临时目录" }
        partsDir.listFiles()?.forEach { if (it.name.endsWith(".tmp")) it.delete() }
        checkSpace(databaseDir, record.requiredSpace)

        try {
            downloadChunks(source, record, partsDir, onProgress)
            onProgress(80)
            if (record.gzip) {
                inflateParts(record.chunks.size, partsDir, temporaryDb) { bytes ->
                    onProgress((80 + bytes * 15L / record.originalSize.coerceAtLeast(1L)).toInt().coerceIn(80, 95))
                }
            } else {
                assembleParts(record.chunks.size, partsDir, temporaryDb) { bytes ->
                    onProgress((80 + bytes * 15L / record.originalSize.coerceAtLeast(1L)).toInt().coerceIn(80, 95))
                }
            }
            onProgress(96)

            if (temporaryDb.length() != record.originalSize) {
                partsDir.deleteRecursively()
                error("数据库大小错误：${temporaryDb.length()} != ${record.originalSize}")
            }
            if (record.sha256.isNotEmpty() && !sha256(temporaryDb).equals(record.sha256, ignoreCase = true)) {
                partsDir.deleteRecursively()
                error("数据库校验失败")
            }
            install(temporaryDb, target)
            partsDir.deleteRecursively()
            onProgress(100)
            return target
        } finally {
            temporaryDb.delete()
        }
    }

    private fun install(downloaded: File, target: File) {
        val backup = File(target.parentFile, "${target.name}.backup")
        backup.delete()
        if (target.exists()) check(target.renameTo(backup)) { "无法备份旧曲库" }
        if (!downloaded.renameTo(target)) {
            backup.renameTo(target)
            error("无法安装下载的曲库")
        }
        backup.delete()
    }

    private data class Source(val manifestUrl: String, val json: JSONObject)

    private data class Chunk(val path: String, val size: Long, val md5: String)

    private data class Catalog(
        val gzip: Boolean,
        val chunks: List<Chunk>,
        val originalSize: Long,
        val downloadSize: Long,
        val sha256: String,
        val requiredSpace: Long,
    )

    private fun fetchManifest(): Source {
        val urls = manifestUrls
        require(urls.isNotEmpty()) { "未配置曲库下载地址" }
        var lastError: Throwable? = null
        urls.forEach { url ->
            val result = runCatching { Source(url, JSONObject(readText(url))) }
            result.getOrNull()?.let { return it }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("曲库清单下载失败")
    }

    private fun parseManifest(source: Source): Catalog {
        val manifest = source.json
        if (manifest.has("chunks")) {
            val size = manifest.getLong("size")
            return Catalog(
                gzip = false,
                chunks = readChunks(manifest, "chunks", "url"),
                originalSize = size,
                downloadSize = size,
                sha256 = manifest.optString("sha256", ""),
                requiredSpace = size + MIN_FREE_MARGIN,
            )
        }
        if (manifest.has("files") && manifest.has("original_size")) {
            val original = manifest.getLong("original_size")
            val compressed = manifest.getLong("compressed_size")
            return Catalog(
                gzip = true,
                chunks = readChunks(manifest, "files", "file"),
                originalSize = original,
                downloadSize = compressed,
                sha256 = manifest.optString("sha256", ""),
                requiredSpace = original + compressed + MIN_FREE_MARGIN,
            )
        }
        error("曲库清单格式不支持：${manifest.keys().asSequence().joinToString()}")
    }

    private fun readChunks(manifest: JSONObject, array: String, pathKey: String): List<Chunk> {
        val items = manifest.getJSONArray(array)
        require(items.length() > 0) { "曲库清单没有分片" }
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            Chunk(
                path = item.getString(pathKey),
                size = item.optLong("size", -1L),
                md5 = item.optString("md5", ""),
            )
        }
    }

    private fun downloadChunks(
        source: Source,
        record: Catalog,
        partsDir: File,
        onProgress: (Int) -> Unit,
    ) {
        val chunks = record.chunks
        val mirrors = listOf(source.manifestUrl) + manifestUrls.filter { it != source.manifestUrl }
        val total = record.downloadSize.coerceAtLeast(1L)
        val done = AtomicLong(0L)
        val lastReported = AtomicInteger(-1)
        val next = AtomicInteger(0)
        val preferred = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val report = { bytes: Long ->
            val percent = (bytes * 78L / total).toInt().coerceIn(0, 78)
            while (true) {
                val previous = lastReported.get()
                if (percent <= previous || lastReported.compareAndSet(previous, percent)) break
            }
            if (lastReported.get() == percent) onProgress(percent)
        }
        val worker = Runnable {
            while (failure.get() == null) {
                val index = next.getAndIncrement()
                if (index >= chunks.size) return@Runnable
                val part = partFile(partsDir, index)
                try {
                    if (!isPartValid(part, chunks[index])) {
                        part.delete()
                        fetchChunk(chunks[index], mirrors, preferred, part)
                    }
                    report(done.addAndGet(part.length()))
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                    return@Runnable
                }
            }
        }
        val threads = List(minOf(DOWNLOAD_THREADS, chunks.size)) { Thread(worker, "catalog-part-$it") }
        threads.forEach { it.isDaemon = true }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        failure.get()?.let { throw it }
    }

    private fun fetchChunk(chunk: Chunk, mirrors: List<String>, preferred: AtomicInteger, part: File) {
        val urls = mirrors.map { resolveUrl(it, chunk.path) }.distinct()
        if (urls.isEmpty()) error("曲库分片没有可用的下载地址：${chunk.path}")
        val start = preferred.get().coerceIn(0, urls.lastIndex)
        var lastError: Throwable? = null
        for (offset in urls.indices) {
            val mirror = (start + offset) % urls.size
            repeat(CHUNK_ATTEMPTS) { attempt ->
                val staging = File(part.parentFile, "${part.name}.${Thread.currentThread().id}.tmp")
                staging.delete()
                val result = runCatching {
                    downloadTo(urls[mirror], staging)
                    verifyPart(staging, chunk)
                }
                if (result.isSuccess) {
                    check(staging.renameTo(part)) { "无法保存曲库分片：${chunk.path}" }
                    preferred.set(mirror)
                    return
                }
                lastError = result.exceptionOrNull()
                staging.delete()
                if (attempt + 1 < CHUNK_ATTEMPTS) Thread.sleep(500L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("曲库分片下载失败：${chunk.path}")
    }

    private fun isPartValid(part: File, chunk: Chunk): Boolean {
        if (!part.isFile) return false
        if (chunk.size < 0L && chunk.md5.isEmpty()) return false
        return runCatching { verifyPart(part, chunk) }.isSuccess
    }

    private fun verifyPart(file: File, chunk: Chunk) {
        if (chunk.size >= 0L) check(file.length() == chunk.size) {
            "分片大小错误：${chunk.path} ${file.length()} != ${chunk.size}"
        }
        if (chunk.md5.isNotEmpty()) {
            check(md5(file).equals(chunk.md5, ignoreCase = true)) { "分片校验失败：${chunk.path}" }
        }
    }

    private fun assembleParts(count: Int, partsDir: File, target: File, onBytes: (Long) -> Unit) {
        var bytes = 0L
        BufferedOutputStream(FileOutputStream(target), 256 * 1024).use { output ->
            for (index in 0 until count) {
                val part = partFile(partsDir, index)
                part.inputStream().buffered(256 * 1024).use { it.copyTo(output, 256 * 1024) }
                bytes += part.length()
                onBytes(bytes)
                part.delete()
            }
        }
    }

    private fun inflateParts(count: Int, partsDir: File, target: File, onBytes: (Long) -> Unit) {
        val streams = object : Enumeration<InputStream> {
            private var index = 0
            override fun hasMoreElements(): Boolean = index < count
            override fun nextElement(): InputStream {
                val part = partFile(partsDir, index++)
                return object : FilterInputStream(part.inputStream().buffered(256 * 1024)) {
                    override fun close() {
                        super.close()
                        part.delete()
                    }
                }
            }
        }
        var bytes = 0L
        SequenceInputStream(streams).use { sequence ->
            GZIPInputStream(sequence, 256 * 1024).use { gzip ->
                BufferedOutputStream(FileOutputStream(target), 256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = gzip.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytes += read
                        onBytes(bytes)
                    }
                }
            }
        }
    }

    private fun partFile(partsDir: File, index: Int): File =
        File(partsDir, "part-${index.toString().padStart(5, '0')}")

    private fun downloadTo(url: String, target: File) {
        val connection = openConnection(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            connection.inputStream.buffered(256 * 1024).use { input ->
                target.outputStream().buffered(256 * 1024).use { output ->
                    input.copyTo(output, 256 * 1024)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(url: String): String {
        val connection = openConnection(url)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MaidongKTV/1.1")
            if (token.isNotBlank() && url.contains("gitee.com")) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

    private fun resolveUrl(manifestUrl: String, value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) value
        else URL(URL(manifestUrl), value).toString()

    private fun checkSpace(directory: File, required: Long) {
        val usable = directory.usableSpace
        check(usable >= required) {
            "曲库需要 ${formatSize(required)} 临时空间，当前曲库分区可用 ${formatSize(usable)}"
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(file: File): String = digest(file, "SHA-256")
    private fun md5(file: File): String = digest(file, "MD5")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun formatSize(bytes: Long): String =
        String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)

    private fun isAccessDenied(error: Throwable?): Boolean =
        error is HttpStatusException && error.code in listOf(401, 403)

    private fun isNoSpaceError(error: Throwable?): Boolean = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.lowercase(Locale.ROOT) }
        .any { "enospc" in it || "no space left" in it }

    private fun readableError(error: Throwable?): Throwable {
        if (isNoSpaceError(error)) {
            val target = MuseDatabase.defaultDbFile()
            return IllegalStateException(
                "曲库分区空间不足（当前可用 ${formatSize(target.parentFile?.usableSpace ?: 0L)}），" +
                    "已保留已下载分片，请释放空间后重试",
                error,
            )
        }
        if (isAccessDenied(error)) {
            return IllegalStateException(
                "曲库源拒绝匿名下载（${error?.message}），请更换下载地址或配置 DATABASE_TOKEN",
                error,
            )
        }
        return error ?: IllegalStateException("曲库下载失败")
    }

    private class HttpStatusException(val code: Int, url: String) :
        RuntimeException("HTTP $code: $url")
}
