package com.local.ktv

import android.os.Build
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Android 4.4（API 19）的 SSLSocket 默认不启用 TLS1.2，只支持 TLS1.2+ 的 CDN 会握手失败
 * （表现为 SSL23_GET_SERVER_HELLO: unsupported protocol）。这里给 HttpsURLConnection 换一个
 * 显式开启 TLS1.2/1.1/1.0 的 socket factory，仅在 API<21 生效，其余平台不受影响。
 */
object TlsCompat {
    @Volatile
    private var installed = false

    @JvmStatic
    fun install() {
        if (installed || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return
        installed = true
        runCatching {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            val delegate = context.socketFactory
            HttpsURLConnection.setDefaultSSLSocketFactory(object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

                override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

                override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                    enableModernTls(delegate.createSocket(s, host, port, autoClose))

                override fun createSocket(host: String, port: Int): Socket =
                    enableModernTls(delegate.createSocket(host, port))

                override fun createSocket(
                    host: String,
                    port: Int,
                    localHost: InetAddress,
                    localPort: Int,
                ): Socket = enableModernTls(delegate.createSocket(host, port, localHost, localPort))

                override fun createSocket(host: InetAddress, port: Int): Socket =
                    enableModernTls(delegate.createSocket(host, port))

                override fun createSocket(
                    address: InetAddress,
                    port: Int,
                    localAddress: InetAddress,
                    localPort: Int,
                ): Socket = enableModernTls(delegate.createSocket(address, port, localAddress, localPort))
            })
        }
    }

    private fun enableModernTls(socket: Socket): Socket {
        val sslSocket = socket as? SSLSocket ?: return socket
        val supported = sslSocket.supportedProtocols?.toHashSet().orEmpty()
        val wanted = listOf("TLSv1.2", "TLSv1.1", "TLSv1").filter { it in supported }
        if (wanted.isNotEmpty()) sslSocket.enabledProtocols = wanted.toTypedArray()
        return sslSocket
    }
}
