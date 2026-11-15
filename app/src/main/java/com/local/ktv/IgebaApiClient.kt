package com.local.ktv

import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * i歌霸（iGeba）音源接口。
 *
 * 移植自 2.1.12 的 IgebaApiClient，只保留「取播放地址」这一条链路：
 * - 目录接口（app.ige8.net/cloud.php，GET_SONGS / GET_PACGS）当前被服务端限制，
 *   只应答鸿蒙客户端（返回「请使用鸿蒙客户端」），因此这里不调用，也不依赖它。
 * - 地址接口（app.ige8.net/telnet.php，GET_SONGADDR）实测可用，需要登录后的 UserId。
 * 协议与 2.1.12 保持一致：表单 POST，字段 VER / CMD / PARAMS。
 */
object IgebaApiClient {
    private const val TAG = "IgebaApi"
    const val ADDRESS_URL = "http://app.ige8.net/telnet.php"
    const val USER_AGENT = "Mozilla/3.0 (compatible; Indy Library)"

    internal class HttpStatusException(val statusCode: Int) : IOException("歌源接口 HTTP $statusCode")

    fun post(command: String, params: JSONObject, address: Boolean = true): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "歌源请求必须在后台线程执行" }
        val form = linkedMapOf(
            "VER" to if (address) "10.0" else "6",
            "CMD" to command,
            "PARAMS" to params.toString(),
        )
        val body = form.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)
        // 地址接口当前是 http；若日后切到 https，API 19 需要 TlsCompat 兜底。
        TlsCompat.install()
        val connection = URL(ADDRESS_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 40_000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("User-Agent", USER_AGENT)
            IgebaAccount.attach(connection)
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw HttpStatusException(statusCode)
            IgebaAccount.receive(connection)
            val result = JSONObject(
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            if (result.has("Result") && result.optInt("Result") < 0) {
                throw IOException(result.optString("Description", "歌源拒绝请求"))
            }
            // telnet.php 的错误用 code/msg 字段，与目录接口不同。
            if (result.has("code") && result.optInt("code") < 0) {
                throw IOException(result.optString("msg", "歌源拒绝请求"))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    class MediaAddress(
        val songNumber: String,
        val url: String,
        val voiceChannel: Int,
        val rightsCode: Int,
        val reason: String,
        val userId: Long,
    )

    /**
     * 取歌源播放地址。
     *
     * 实测结论（2026-11-15）：服务端**允许游客取地址**——未登录时
     * `Rights.Code=0 / Reason=未登录`，但 `FileUrl` 仍然返回，且该地址可直接下到完整文件
     * （首个 1KB 的前 4 字节为 `1A 45 DF A3`，即 MKV，Content-Length 49090742）。
     * 因此这里不再强制登录：有账号就带 UserId，没有就按游客（UserId=0）请求，
     * 只在「是否真的拿到地址」上做判定。未登录会被服务端限速（Rights.RatedSpeed）。
     */
    fun address(songNumber: String): MediaAddress {
        require(songNumber.matches(Regex("\\d{1,16}"))) { "缺少有效的 i歌霸歌曲编号" }
        val userId = IgebaAccount.session?.userId?.takeIf { it > 0 } ?: 0L
        AppLog.i(TAG, "GET_SONGADDR 编号=$songNumber userId=$userId" + if (userId == 0L) "（游客）" else "")
        val result = try {
            post(
                "GET_SONGADDR",
                JSONObject().put("UserId", userId).put("SongNumber", songNumber),
                true,
            )
        } catch (error: Throwable) {
            AppLog.e(TAG, "取地址异常 编号=$songNumber userId=$userId", error)
            throw error
        }
        val rights = result.optJSONObject("Rights")
        val code = rights?.optInt("Code", 0) ?: 0
        val reason = rights?.optString("Reason").orEmpty()
        val url = result.optString("FileUrl")
        require(url.startsWith("http://") || url.startsWith("https://")) {
            AppLog.w(TAG, "未返回地址 编号=$songNumber rights=$code/$reason")
            if (reason.isNotEmpty()) "歌源未返回播放地址（$reason）" else "歌源未返回播放地址"
        }
        AppLog.i(
            TAG,
            "取地址成功 编号=$songNumber userId=$userId rights=$code/$reason " +
                "voice=${result.optInt("VoiceChannel", 0)} url=${AppLog.maskUrl(url)}",
        )
        return MediaAddress(songNumber, url, result.optInt("VoiceChannel", 0), code, reason, userId)
    }
}
