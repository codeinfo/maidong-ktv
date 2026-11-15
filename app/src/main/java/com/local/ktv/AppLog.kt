package com.local.ktv

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 现场诊断日志。
 *
 * 这台 4.4 机型上 `adb logcat` 经常读不到应用日志（缓冲被系统刷掉/权限不足），
 * 现场排「取地址失败 / 下载失败 / 登录失败」基本无从下手，所以关键链路同步落盘到
 * `/sdcard/MaidongKTV/logs/app.log`（超过 2 MB 滚动为 app.log.1）。
 *
 * 只记参数摘要与结果，不落密码、Cookie、完整地址签名。
 */
internal object AppLog {
    const val DIR = "/sdcard/MaidongKTV/logs"
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @JvmStatic
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
    }

    @JvmStatic
    fun w(tag: String, message: String) {
        Log.w(tag, message)
        write("W", tag, message)
    }

    @JvmStatic
    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        write("E", tag, message + (error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    /** 地址只留协议+主机+路径前缀，签名参数（rid/uid）不入日志。 */
    @JvmStatic
    fun maskUrl(url: String): String {
        if (url.isEmpty()) return "<empty>"
        val cut = url.indexOf('?')
        val head = if (cut >= 0) url.substring(0, cut) else url
        return if (cut >= 0) "$head?…" else head
    }

    private fun write(level: String, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val dir = File(DIR)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "app.log")
                if (file.length() > 2_000_000L) {
                    val rolled = File(dir, "app.log.1")
                    rolled.delete()
                    file.renameTo(rolled)
                }
                FileWriter(file, true).use {
                    it.append(stamp.format(Date())).append(' ').append(level).append('/')
                        .append(tag).append(": ").append(message).append('\n')
                }
            }
        }
    }
}
