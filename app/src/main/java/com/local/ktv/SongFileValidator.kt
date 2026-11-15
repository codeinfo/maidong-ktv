package com.local.ktv

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载文件完整性校验（移植自 2.1.12 的 SongFileValidator）。
 *
 * 原来只按「文件大小 ≥ 6MB」判断，会把 CDN 预览片、被截断的下载当成正常歌曲文件，
 * 现场表现为「点歌能播几秒就黑屏/卡住」。这里改成按容器特性判断：
 * - MKV/MP4/FLV/音频：看文件头魔数（EBML 1A45DFA3 / ftyp / ID3 / fLaC / RIFF / OggS / 同步字）
 * - MPEG-TS：找 188 字节包同步头，采样首尾 PTS 估算时长，时长过短判定为预览片
 *
 * 校验结果按「路径 + 大小 + 修改时间」缓存，重复调用不重复读盘。
 */
object SongFileValidator {
    const val MIN_VALID_FILE_SIZE = 64L
    const val MIN_SONG_DURATION_MS = 60_000L
    private const val TS_PACKET_SIZE = 188
    private const val PROBE_PACKET_COUNT = 24_000 // 首尾各约 4.3 MiB
    private const val PTS_WRAP = 1L shl 33

    data class Result(
        val valid: Boolean,
        val durationMs: Long? = null,
        val reason: String = "",
    )

    private data class Cached(val length: Long, val modified: Long, val result: Result)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun inspect(file: File, requireTransportStream: Boolean = true): Result {
        if (!file.isFile) return Result(false, reason = "文件不存在")
        val key = file.absolutePath
        cache[key]?.takeIf { it.length == file.length() && it.modified == file.lastModified() }?.let {
            return it.result
        }
        val result = inspectUncached(file, requireTransportStream)
        cache[key] = Cached(file.length(), file.lastModified(), result)
        return result
    }

    fun forget(file: File) {
        cache.remove(file.absolutePath)
    }

    fun requiresTransportStream(file: File): Boolean =
        file.extension.equals("ts", true) || file.extension.equals("ls", true)

    private fun inspectUncached(file: File, requireTransportStream: Boolean): Result {
        if (file.length() < MIN_VALID_FILE_SIZE) {
            return Result(false, reason = "文件过小：${file.length()} 字节")
        }
        RandomAccessFile(file, "r").use { input ->
            if (!requireTransportStream) {
                val header = ByteArray(12)
                input.readFully(header)
                input.seek(0)
                val signature = header.take(4).toByteArray()
                val ebml = signature.contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
                val ftyp = String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                val audio = String(header, 0, 3, Charsets.US_ASCII) == "ID3" ||
                    String(header, 0, 4, Charsets.US_ASCII) in listOf("fLaC", "RIFF", "OggS") ||
                    (header[0].toInt() and 0xff == 0xff && header[1].toInt() and 0xe0 == 0xe0)
                if (ebml || ftyp || audio) {
                    val marker = File(file.parentFile, file.name + ".complete.json")
                    if (marker.exists()) {
                        val total = runCatching {
                            org.json.JSONObject(marker.readText()).getLong("bytes")
                        }.getOrDefault(-1)
                        if (total != file.length()) return Result(false, reason = "下载文件长度与清单不一致")
                    }
                    return Result(true)
                }
            }
            val syncOffset = findSyncOffset(input)
            if (syncOffset < 0) return Result(false, reason = "无法识别媒体容器")
            val packetCount = ((input.length() - syncOffset) / TS_PACKET_SIZE).coerceAtLeast(0)
            if (packetCount < 10) return Result(false, reason = "MPEG-TS 文件不完整")
            val sampleCount = minOf(packetCount, PROBE_PACKET_COUNT.toLong()).toInt()
            val first = readPts(input, syncOffset, sampleCount)
            val tailOffset = syncOffset + (packetCount - sampleCount) * TS_PACKET_SIZE
            val last = readPts(input, tailOffset, sampleCount)
            if (first.isEmpty() || last.isEmpty()) {
                // 部分合法 TS 的采样区间没有 PTS，此时退回大小判定。
                return Result(true, reason = "时长不可用")
            }
            val startPts = first.minOrNull() ?: return Result(true, reason = "时长不可用")
            val endPts = last.maxOrNull() ?: return Result(true, reason = "时长不可用")
            val ticks = if (endPts >= startPts) endPts - startPts else endPts + PTS_WRAP - startPts
            val durationMs = ticks * 1_000L / 90_000L
            return if (durationMs < MIN_SONG_DURATION_MS) {
                Result(false, durationMs, "预览片过短：${durationMs}ms")
            } else {
                Result(true, durationMs)
            }
        }
    }

    private fun findSyncOffset(input: RandomAccessFile): Long {
        val probeSize = minOf(input.length(), TS_PACKET_SIZE.toLong() * 8).toInt()
        if (probeSize < TS_PACKET_SIZE * 3) return -1
        val probe = ByteArray(probeSize)
        input.seek(0)
        input.readFully(probe)
        for (offset in 0 until minOf(TS_PACKET_SIZE, probe.size)) {
            var matches = 0
            var index = offset
            while (index < probe.size && matches < 5) {
                if (probe[index].toInt() and 0xff != 0x47) break
                matches++
                index += TS_PACKET_SIZE
            }
            if (matches >= 3) return offset.toLong()
        }
        return -1
    }

    private fun readPts(input: RandomAccessFile, offset: Long, count: Int): List<Long> {
        val values = ArrayList<Long>()
        val packet = ByteArray(TS_PACKET_SIZE)
        input.seek(offset)
        repeat(count) {
            if (input.read(packet) != TS_PACKET_SIZE) return@repeat
            if ((packet[0].toInt() and 0xff) != 0x47 || (packet[1].toInt() and 0x40) == 0) return@repeat
            val adaptationControl = packet[3].toInt().ushr(4) and 0x03
            if (adaptationControl != 1 && adaptationControl != 3) return@repeat
            var payload = 4
            if (adaptationControl == 3) payload += 1 + (packet[4].toInt() and 0xff)
            if (payload + 14 > TS_PACKET_SIZE) return@repeat
            if (packet[payload].toInt() != 0 || packet[payload + 1].toInt() != 0 ||
                packet[payload + 2].toInt() and 0xff != 1
            ) {
                return@repeat
            }
            if ((packet[payload + 7].toInt() and 0x80) == 0) return@repeat
            val p = payload + 9
            val pts = (((packet[p].toLong() ushr 1) and 0x07) shl 30) or
                ((packet[p + 1].toLong() and 0xff) shl 22) or
                (((packet[p + 2].toLong() ushr 1) and 0x7f) shl 15) or
                ((packet[p + 3].toLong() and 0xff) shl 7) or
                ((packet[p + 4].toLong() ushr 1) and 0x7f)
            values += pts
        }
        return values
    }
}
