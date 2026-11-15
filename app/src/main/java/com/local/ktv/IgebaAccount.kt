package com.local.ktv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * i歌霸账号（移植自 2.1.12）：登录、会话保持与自动恢复。
 *
 * 音源取地址必须带登录后的 UserId，所以这是「音源」链路的前置条件。
 * Cookie 与密码都通过 IgebaSessionVault 加密落盘，且密码只在用户勾选后才保存。
 */
internal object IgebaAccount {
    private const val TAG = "IgebaAccount"
    private const val REGISTER_URL = "http://gc.amanktv.cn/usr.php"

    class Session(val userId: Long, val nickname: String)

    @Volatile
    var session: Session? = null
        private set

    private var cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private val registrationCookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    private var generation = 0
    private class Credentials(val account: String, val password: String)
    private var credentials: Credentials? = null
    private val reloginLock = Any()
    private var lastReloginAttempt = 0L
    val automaticLoginEnabled: Boolean get() = synchronized(this) { credentials != null }
    private var codeSentAt = 0L
    private var codePhone = ""
    private var vault: IgebaSessionVault? = null

    @Synchronized
    fun initialize(context: Context) {
        if (vault != null) return
        val storage = IgebaSessionVault(context)
        vault = storage
        runCatching {
            val saved = storage.load() ?: return@runCatching
            val restored = Session(saved.getLong("userId"), saved.getString("nickname"))
            require(restored.userId > 0)
            val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
            val entries = saved.getJSONArray("cookies")
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                val expires = item.optLong("expires", -1)
                if (expires >= 0 && expires <= System.currentTimeMillis()) continue
                val uri = URI(item.getString("uri"))
                if (uri.host != "app.ige8.net") continue
                val cookie = HttpCookie(item.getString("name"), item.getString("value")).apply {
                    domain = item.optString("domain").takeIf { it.isNotEmpty() }
                    path = item.optString("path", "/")
                    secure = item.optBoolean("secure")
                    version = item.optInt("version")
                    if (expires >= 0) {
                        maxAge = ((expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    }
                }
                jar.cookieStore.add(uri, cookie)
            }
            credentials = saved.optJSONObject("credentials")?.let {
                Credentials(it.getString("account"), it.getString("password"))
            }
            cookies = jar
            session = restored
        }.onFailure {
            storage.clear()
            Log.w(TAG, "保存的账号会话无法恢复，请重新登录")
        }
    }

    private fun persist(
        value: Session,
        jar: CookieManager,
        savedCredentials: Credentials? = credentials,
    ) {
        val entries = JSONArray()
        for (uri in jar.cookieStore.urIs) {
            if (uri.host != "app.ige8.net") continue
            for (cookie in jar.cookieStore.get(uri)) {
                if (cookie.hasExpired()) continue
                entries.put(
                    JSONObject().put("uri", uri.toString()).put("name", cookie.name)
                        .put("value", cookie.value).put("domain", cookie.domain.orEmpty())
                        .put("path", cookie.path ?: "/").put("secure", cookie.secure)
                        .put("version", cookie.version)
                        .put(
                            "expires",
                            if (cookie.maxAge < 0) -1L
                            else System.currentTimeMillis() + cookie.maxAge.coerceAtMost(315_360_000L) * 1000
                        )
                )
            }
        }
        val saved = JSONObject().put("userId", value.userId).put("nickname", value.nickname)
            .put("cookies", entries)
        savedCredentials?.let {
            saved.put("credentials", JSONObject().put("account", it.account).put("password", it.password))
        }
        vault?.save(saved)
    }

    @Synchronized
    fun logout() {
        generation++
        session = null
        credentials = null
        cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        vault?.clear()
    }

    @Synchronized
    fun attach(connection: HttpURLConnection) {
        cookies.get(connection.url.toURI(), emptyMap<String, List<String>>()).forEach { (key, values) ->
            if (key.equals("Cookie", true)) connection.setRequestProperty(key, values.joinToString("; "))
        }
    }

    @Synchronized
    fun receive(connection: HttpURLConnection) {
        cookies.put(connection.url.toURI(), connection.headerFields.filterKeys { it != null })
        if (connection.headerFields.keys.any { it.equals("Set-Cookie", true) }) {
            session?.let { value ->
                runCatching { persist(value, cookies) }
                    .onFailure { Log.w(TAG, "账号会话更新未能保存") }
            }
        }
    }

    private fun request(
        url: String,
        version: String,
        command: String,
        params: JSONObject,
        jar: CookieManager = registrationCookies,
    ): JSONObject {
        val target = URL(url)
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("User-Agent", IgebaApiClient.USER_AGENT)
            jar.get(target.toURI(), emptyMap<String, List<String>>()).forEach { (key, values) ->
                if (key.equals("Cookie", true)) {
                    connection.setRequestProperty(key, values.joinToString("; "))
                }
            }
            val form = linkedMapOf("VER" to version, "CMD" to command, "PARAMS" to params.toString())
            val body = form.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("账号服务 HTTP $status")
            jar.put(target.toURI(), connection.headerFields.filterKeys { it != null })
            return JSONObject(
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
        } finally {
            connection.disconnect()
        }
    }

    fun login(account: String, password: String, remember: Boolean = false): Session {
        require(account.isNotBlank() && password.isNotEmpty()) { "请输入账号和密码" }
        val epoch = synchronized(this) { ++generation }
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val value = authenticate(account, password, jar)
        val savedCredentials = if (remember) Credentials(account.trim(), password) else null
        synchronized(this) {
            check(epoch == generation) { "本次登录已取消" }
            persist(value, jar, savedCredentials)
            session = value
            cookies = jar
            credentials = savedCredentials
        }
        return value
    }

    private fun authenticate(account: String, password: String, jar: CookieManager): Session {
        val params = JSONObject().put("UserName", account.trim()).put("Password", password)
            .put("ExeInfos", JSONObject().put("MaidongKTV", BuildConfig.VERSION_NAME).toString())
            .put("EnvInfos", JSONObject().put("Os", "Android ${Build.VERSION.RELEASE}").toString())
            .put("PlayerInfos", JSONObject().toString()).put("ReferId", 0)
        val result = request(IgebaApiClient.ADDRESS_URL, "10.0", "DO_LOGIN", params, jar)
        val user = result.optJSONObject("UserInfo")
        AppLog.i(
            TAG,
            "DO_LOGIN 账号=${account.trim()} Code=${result.optInt("Code", -1)} " +
                "Reason=${result.optString("Reason")} UserId=${user?.optLong("UserId", 0) ?: 0}",
        )
        return parseSession(result)
    }

    /** 同一时刻只允许一次恢复；失败时保留原会话。 */
    fun relogin(expected: Session): Session = synchronized(reloginLock) recovery@ {
        val saved: Credentials
        val epoch: Int
        synchronized(this) {
            val current = session ?: throw IOException("账号已退出，请重新登录")
            check(current.userId == expected.userId) { "账号已切换，请重试" }
            if (current !== expected) return@recovery current
            saved = credentials ?: throw IOException("会话已失效，请重新登录并勾选自动恢复登录")
            val now = System.nanoTime()
            check(lastReloginAttempt == 0L || now - lastReloginAttempt >= 60_000_000_000L) {
                "账号恢复请求过于频繁，请稍后重试"
            }
            lastReloginAttempt = now
            epoch = generation
        }
        val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val value = authenticate(saved.account, saved.password, jar)
        check(value.userId == expected.userId) { "恢复登录返回的账号不一致" }
        synchronized(this) {
            check(epoch == generation && session === expected) { "账号已变更，本次恢复已取消" }
            persist(value, jar)
            cookies = jar
            session = value
            generation++
        }
        value
    }

    private fun parseSession(result: JSONObject): Session {
        if (result.optInt("Code", -1) <= 0) {
            throw IOException(friendlyLoginError(result.optString("Reason", "登录失败")))
        }
        val user = result.optJSONObject("UserInfo") ?: throw IOException("登录响应缺少用户信息")
        val id = user.optLong("UserId", 0)
        if (id <= 0) throw IOException("登录响应缺少有效账号编号")
        return Session(id, user.optString("NickName", "i歌霸用户"))
    }

    /**
     * 服务端只回「用户不存在 / 密码错误」这类短语，直接甩给用户不好判断下一步。
     * 实测（2026-11-15）：错误账号返回 `{"Code":-1,"Reason":"用户不存在"}`。
     */
    private fun friendlyLoginError(reason: String): String = when {
        reason.contains("不存在") ->
            "账号不存在（服务端原文：$reason）。可点「注册账号」用手机号注册，或先用 i歌霸官方客户端注册后再登录"
        reason.contains("密码") || reason.contains("错误") ->
            "账号或密码不对（服务端原文：$reason），请核对后重试"
        reason.contains("禁用") || reason.contains("封") ->
            "该账号已被限制（服务端原文：$reason）"
        reason.isBlank() -> "登录失败，请稍后重试"
        else -> "登录失败（服务端原文：${reason.take(120)}）"
    }

    /** 设置页/登录框展示用：当前账号状态。 */
    @Synchronized
    fun statusText(): String {
        val current = session
        return if (current != null) {
            "已登录：${current.nickname}（编号 ${current.userId}）"
        } else {
            "游客模式：未登录也能点歌下载，服务端会限速"
        }
    }

    fun canAutoLogin(): Boolean = synchronized(this) { credentials != null }

    /**
     * 用已保存的账号密码自动登录（必须在后台线程调用）。
     * 失败返回 null，不抛出，避免影响取地址主流程。
     */
    fun autoLogin(): Session? = synchronized(reloginLock) {
        val saved = synchronized(this) { credentials } ?: return null
        val now = System.nanoTime()
        if (lastReloginAttempt != 0L && now - lastReloginAttempt < 60_000_000_000L) return null
        lastReloginAttempt = now
        runCatching {
            val jar = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
            val value = authenticate(saved.account, saved.password, jar)
            synchronized(this) {
                persist(value, jar)
                cookies = jar
                session = value
                generation++
            }
            value
        }.getOrElse {
            Log.w(TAG, "自动登录失败：${it.message}")
            null
        }
    }

    /**
     * 服务端判定「未登录」时作废本地会话：清掉会话与 Cookie，但保留已保存的账号密码，
     * 下次取地址会先尝试自动登录，失败则按游客继续可用。
     */
    @Synchronized
    fun invalidateSession(reason: String) {
        if (session == null) return
        generation++
        session = null
        cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        Log.w(TAG, "账号会话失效：$reason")
    }

    @Synchronized
    fun remainingSeconds(phone: String): Long =
        if (codePhone == phone) {
            ((codeSentAt + 120_000L - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0)
        } else {
            0
        }

    fun sendCode(phone: String) {
        require(phone.matches(Regex("1[3-9]\\d{9}"))) { "请输入有效的中国大陆手机号" }
        synchronized(this) {
            val left = remainingSeconds(phone)
            check(left == 0L) { "请在 ${left} 秒后重新获取验证码" }
            codePhone = phone
            codeSentAt = SystemClock.elapsedRealtime()
        }
        val result = request(
            REGISTER_URL, "1", "SND_VERIFY",
            JSONObject().put("Addr", phone).put("VerifyType", 1).put("SignName", "i歌霸")
        )
        if (result.optInt("Result", -1) < 0) {
            throw IOException(result.optString("Description", "验证码发送失败").take(160))
        }
    }

    fun register(phone: String, password: String, code: String) {
        require(phone.matches(Regex("1[3-9]\\d{9}"))) { "请输入有效的中国大陆手机号" }
        require(password.isNotEmpty() && code.isNotBlank()) { "请输入密码和验证码" }
        val result = request(
            REGISTER_URL, "1", "DO_REG", JSONObject()
                .put("AccountName", phone).put("Email", "").put("Mobile", phone)
                .put("Password", password).put("VerifyBy", 4).put("VerifyCode", code.trim())
        )
        if (result.optInt("Result", -1) < 0) {
            throw IOException(result.optString("Description", "注册失败").take(160))
        }
    }
}
