package com.local.ktv

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音源路由：决定一首歌「从哪里取下载/播放地址」。
 *
 * 优先级：
 * 1. i歌霸歌源——移植自 2.1.12，需登录账号；地址为纯 HTTP，4.4 无 TLS 障碍，且能覆盖全库；
 * 2. 本地镜像 [BuildConfig.MEDIA_BASE_URL]——内网/HTTP 自建源，i歌霸未登录或该曲取不到地址时的兜底；
 * 3. 内置签名接口（旧链路）——保留作为最后兜底，其返回的 https CDN 在 4.4 上可能握手失败。
 *
 * 若现场希望「本地镜像永远优先」，把 [IGEBA_FIRST] 改为 false 即可。
 *
 * 所有方法都会阻塞网络，必须在后台线程调用。
 */
object SongSourceRouter {
    private const val TAG = "SongSource"

    /** true：已登录时 i歌霸优先；false：本地镜像优先。 */
    private const val IGEBA_FIRST = true

    enum class Source(val label: String) {
        MIRROR("本地镜像"),
        IGEBA("i歌霸"),
        LEGACY("内置接口"),
    }

    class Resolution(val url: String, val source: Source, val songNumber: String?)

    @JvmStatic
    fun resolve(song: Song, forceRefresh: Boolean = false): Resolution? {
        AppLog.i(
            TAG,
            "取地址开始 歌曲=${song.title} 编号=${song.sourceSongNumber ?: "-"} " +
                "filename=${song.filename ?: "-"} 缓存地址=${if (song.downloadUrl.isNullOrBlank()) "无" else "有"} " +
                "强制刷新=$forceRefresh 登录=${IgebaAccount.session?.userId ?: 0}",
        )
        if (!forceRefresh) {
            val cached = song.downloadUrl?.takeIf { it.isNotBlank() }
            val cachedSource = Source.values().firstOrNull { it.label == song.sourceName }
            // 只信任 i歌霸 的缓存地址。镜像地址随时可能下线（PC 没开机就会挂），
            // 内置签名地址带时效，两者命中缓存反而会把下载钉死在失效地址上。
            if (cached != null && cachedSource == Source.IGEBA) {
                return Resolution(cached, Source.IGEBA, song.sourceSongNumber)
            }
            if (cached != null) {
                AppLog.w(
                    TAG,
                    "丢弃不可信缓存地址 歌曲=${song.title} 来源=${song.sourceName ?: "-"} " +
                        "地址=${AppLog.maskUrl(cached)}",
                )
                song.downloadUrl = null
            }
        }
        if (IGEBA_FIRST) {
            val igeabaFirst = igeabaUrl(song)
            if (igeabaFirst != null) return apply(song, igeabaFirst.first, Source.IGEBA, igeabaFirst.second)
        }
        val mirror = mirrorUrl(song)
        if (mirror != null) return apply(song, mirror, Source.MIRROR)
        if (!IGEBA_FIRST) {
            val igeabaLater = igeabaUrl(song)
            if (igeabaLater != null) {
                return apply(song, igeabaLater.first, Source.IGEBA, igeabaLater.second)
            }
        }
        val legacy = legacyUrl(song)
        if (legacy != null) return apply(song, legacy, Source.LEGACY)
        AppLog.w(TAG, "所有音源均未取到地址：${song.displayTitle()} 编号=${song.sourceSongNumber ?: "-"}")
        return null
    }

    /** 地址失效或下载失败时调用：清缓存，并在来源是 i歌霸且开启自动登录时尝试恢复会话。 */
    @JvmStatic
    fun invalidate(song: Song) {
        song.downloadUrl = null
        SongApiClient.clearTokenCache()
        if (song.sourceName == Source.IGEBA.label && IgebaAccount.automaticLoginEnabled) {
            runCatching { IgebaAccount.session?.let { IgebaAccount.relogin(it) } }
                .onFailure { Log.w(TAG, "i歌霸会话恢复失败：${it.message}") }
        }
    }

    /** 设置页展示用：当前生效的音源顺序。 */
    @JvmStatic
    fun describe(): String {
        val igeaba = when {
            IgebaAccount.session != null -> "i歌霸（已登录）"
            IgebaAccount.canAutoLogin() -> "i歌霸（游客，可自动登录）"
            else -> "i歌霸（游客，未登录限速）"
        }
        val mirror = BuildConfig.MEDIA_BASE_URL.trim()
            .ifEmpty { "本地镜像（未配置）" }
        val parts = ArrayList<String>(4)
        if (IGEBA_FIRST) parts.add(igeaba)
        parts.add(mirror)
        if (!IGEBA_FIRST) parts.add(igeaba)
        parts.add("内置接口")
        return parts.joinToString(" → ")
    }

    private fun mirrorUrl(song: Song): String? {
        val base = BuildConfig.MEDIA_BASE_URL.trim().removeSuffix("/")
        if (base.isEmpty()) return null
        // 内网镜像经常是「配了但没开」，先花 3 秒探活，探不通就别让下载白等
        if (!probe(base)) {
            AppLog.w(TAG, "本地镜像不可达，跳过：$base")
            return null
        }
        val fileName = song.filename?.takeIf { it.isNotEmpty() } ?: "${song.id}.ts"
        return "$base/$fileName"
    }

    private fun probe(base: String): Boolean = runCatching {
        val connection = URL("$base/").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            // 只要服务器应答（含 404/403）就算活着；连不上会抛异常
            connection.responseCode in 200..499
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun igeabaUrl(song: Song): Pair<String, String>? {
        val number = song.sourceSongNumber?.takeIf { it.isNotBlank() } ?: return null
        // 未登录也能取地址（服务端按游客限速）；存过账号就先尝试自动登录，失败不影响取地址
        if (IgebaAccount.session == null && IgebaAccount.canAutoLogin()) {
            IgebaAccount.autoLogin()
        }
        var address = runCatching { IgebaApiClient.address(number) }.getOrElse {
            Log.w(TAG, "i歌霸取地址失败 歌曲=${song.title} 编号=$number：${it.message}")
            return null
        }
        if (address.rightsCode <= 0 && address.userId > 0) {
            // 本地还留着会话、服务端却回「未登录」：会话已过期。作废后按游客重取一次，保证还能下载
            IgebaAccount.invalidateSession(address.reason.ifEmpty { "服务端判定未登录" })
            address = runCatching { IgebaApiClient.address(number) }.getOrElse {
                Log.w(TAG, "i歌霸游客重取地址失败 歌曲=${song.title} 编号=$number：${it.message}")
                return null
            }
        }
        if (address.rightsCode <= 0) {
            Log.i(TAG, "i歌霸游客取地址成功 歌曲=${song.title} 编号=$number（未登录，服务端限速）")
        }
        // 服务端在取地址时返回 VoiceChannel（原伴唱声道模式），比曲库快照里的值更准，回写到歌曲上
        if (address.voiceChannel in 1..2) song.accomp = address.voiceChannel
        return address.url to number
    }

    private fun legacyUrl(song: Song): String? {
        val musicNo = song.filename?.takeIf { it.isNotEmpty() }?.substringBeforeLast('.')
            ?: song.id
        return SongApiClient.getSongDownloadUrl(musicNo)?.takeIf { it.isNotBlank() }
    }

    private fun apply(
        song: Song,
        url: String,
        source: Source,
        number: String? = null,
    ): Resolution {
        song.downloadUrl = url
        song.sourceName = source.label
        if (number != null) song.sourceSongNumber = number
        AppLog.i(
            TAG,
            "音源=${source.label} 歌曲=${song.title} 编号=${song.sourceSongNumber ?: "-"} " +
                "地址=${AppLog.maskUrl(url)}",
        )
        return Resolution(url, source, song.sourceSongNumber)
    }
}
