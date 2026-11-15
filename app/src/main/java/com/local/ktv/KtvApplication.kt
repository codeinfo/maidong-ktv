package com.local.ktv

import android.app.Application
import com.liulishuo.okdownload.OkDownload
import java.util.concurrent.Executors

class KtvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        OkDownload.setSingletonInstance(OkDownload.Builder(this).build())
        IgebaAccount.initialize(this)
        AppLog.i(
            TAG,
            "启动：i歌霸会话=" + (IgebaAccount.session?.let { "${it.nickname}(${it.userId})" } ?: "游客")
                + " 自动登录=" + if (IgebaAccount.canAutoLogin()) "可用" else "无保存账号",
        )
        // 存过账号但会话已失效时，开机后台补一次登录，避免首次点歌才去登录、白等一轮请求
        if (IgebaAccount.session == null && IgebaAccount.canAutoLogin()) {
            startup.execute {
                val restored = IgebaAccount.autoLogin()
                AppLog.i(
                    TAG,
                    "启动自动登录" + if (restored != null) "成功：${restored.nickname}(${restored.userId})" else "失败，按游客继续",
                )
            }
        }
        AppLog.i(TAG, "KTV application initialized without native services")
    }

    companion object {
        private const val TAG = "KtvApplication"
        private val startup = Executors.newSingleThreadExecutor()

        lateinit var instanceRef: KtvApplication
            private set

        @JvmStatic
        fun getInstance(): KtvApplication = instanceRef
    }
}
