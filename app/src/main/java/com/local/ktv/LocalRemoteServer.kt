package com.local.ktv

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalRemoteServer {
    interface Callback {
        fun statusJson(): String
        fun catalogJson(): String
        fun queueJson(): String
        fun rankJson(): String
        fun filterJson(type: String?, value: String?): String
        fun favoritesJson(): String
        fun search(query: String?): String
        fun order(query: String?): String
        fun command(action: String?, params: Map<String, String>): String
    }

    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var callback: Callback? = null
    private val serverPort = 8765

    fun start(callback: Callback) {
        this.callback = callback
        if (running) return
        running = true
        pool.execute {
            runCatching {
                server = ServerSocket(serverPort)
                while (running) {
                    val socket = server?.accept() ?: break
                    pool.execute { handle(socket) }
                }
            }
            running = false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    fun port(): Int = serverPort

    private fun handle(socket: Socket) {
        runCatching {
            socket.use { client ->
                val reader = client.getInputStream().bufferedReader()
                val output = client.getOutputStream()
                val request = reader.readLine()
                if (request == null || !request.startsWith("GET ")) {
                    write(output, 400, "{\"ok\":false,\"error\":\"bad request\"}")
                    return@use
                }
                val target = request.split(' ')[1]
                val path = target.substringBefore('?')
                val params = parseQuery(target.substringAfter('?', ""))
                if (path == "/logs" || path == "/logs.zip" || path.startsWith("/logs/")) {
                    // 现场机器不一定有 adb：同一局域网内用手机浏览器即可把日志取走
                    serveLogs(output, path)
                    return@use
                }
                val api = callback
                val body = when (path) {
                    "/", "/mobile" -> mobilePage()
                    "/status" -> api?.statusJson()
                    "/catalog" -> api?.catalogJson()
                    "/queue" -> api?.queueJson()
                    "/rank" -> api?.rankJson()
                    "/filter" -> api?.filterJson(params["type"], params["value"])
                    "/favorites" -> api?.favoritesJson()
                    "/search" -> api?.search(params["q"])
                    "/order" -> api?.order(params["q"])
                    "/cmd" -> api?.command(params["a"], params)
                    else -> null
                } ?: "{\"ok\":false,\"error\":\"not found\"}"
                write(output, 200, body)
            }
        }
    }

    // ---------- 日志导出 ----------

    private fun logFiles(): List<File> {
        val dir = File(AppLog.DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    private fun serveLogs(output: OutputStream, path: String) {
        val files = logFiles()
        when {
            path == "/logs" -> write(output, 200, logsPage(files))
            path == "/logs.zip" -> writeBinary(output, 200, "application/zip", "maidong-logs.zip", zipLogs(files))
            else -> {
                val name = path.removePrefix("/logs/")
                val file = files.firstOrNull { it.name == name }
                if (file == null) {
                    write(output, 404, "{\"ok\":false,\"error\":\"no such log\"}")
                } else {
                    writeBinary(output, 200, "text/plain; charset=utf-8", file.name, file.readBytes())
                }
            }
        }
    }

    private fun zipLogs(files: List<File>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            var total = 0L
            for (file in files) {
                if (file.length() <= 0L || total > 30L * 1024 * 1024) continue
                zip.putNextEntry(ZipEntry(file.name))
                zip.write(file.readBytes())
                zip.closeEntry()
                total += file.length()
            }
        }
        return out.toByteArray()
    }

    private fun logsPage(files: List<File>): String {
        val rows = if (files.isEmpty()) {
            "<li>（日志目录还没有文件，先让盒子运行一次再刷新）</li>"
        } else {
            files.joinToString("") { file ->
                "<li><a href=\"/logs/" + file.name + "\">" + file.name + "</a><span>" + (file.length() / 1024) + " KB</span></li>"
            }
        }
        return """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>麦动日志导出</title><style>body{background:#17083d;color:#fff;font-family:sans-serif;padding:16px}a{color:#9ad}li{padding:10px 0;border-bottom:1px solid #ffffff22;display:flex;justify-content:space-between}span{color:#bbb}.tip{color:#bbb;font-size:13px;line-height:1.6}</style></head>
            <body><h3>麦动 KTV 日志导出</h3>
            <p><a href="/logs.zip"><b>打包下载全部日志（zip）</b></a></p>
            <ul>$rows</ul>
            <p class="tip">目录：${AppLog.DIR}<br>单个文件可直接点开查看；打包下载适合一次性发回排查。<br>日志只记录参数摘要与结果，不含密码与完整下载地址签名。</p>
            </body></html>
        """.trimIndent()
    }

    private fun writeBinary(output: OutputStream, status: Int, type: String, filename: String?, bytes: ByteArray) {
        val header = StringBuilder()
        header.append("HTTP/1.1 $status OK\r\n")
        header.append("Content-Type: $type\r\n")
        if (filename != null) header.append("Content-Disposition: attachment; filename=\"$filename\"\r\n")
        header.append("Access-Control-Allow-Origin: *\r\n")
        header.append("Content-Length: ${bytes.size}\r\n\r\n")
        output.write(header.toString().toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun parseQuery(query: String): Map<String, String> = buildMap {
        if (query.isBlank()) return@buildMap
        query.split('&').forEach { part ->
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            put(key, value)
        }
    }

    private fun mobilePage(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>本地KTV点歌</title><style>body{background:#17083d;color:white;font-family:sans-serif;margin:0}header,main{padding:14px}input,button{font-size:16px;padding:10px;margin:4px;border:0;border-radius:7px}button{background:#7952c7;color:white}.song{padding:12px;border-bottom:1px solid #ffffff22}</style></head>
        <body><header><b>麦动点歌台</b><br><input id="q" placeholder="歌名 / 歌手 / 拼音"><button onclick="search()">搜索</button><button onclick="queue()">已点</button><button onclick="cmd('next')">切歌</button><button onclick="cmd('pause')">暂停/继续</button><button onclick="cmd('replay')">重唱</button></header><main id="list"></main>
        <script>async function j(u){return await(await fetch(u)).json()}function esc(s){return String(s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]))}function render(a){list.innerHTML=(a||[]).map(x=>'<div class="song"><b>'+esc(x.title)+'</b><br>'+esc(x.singer)+' <button onclick="order(\''+encodeURIComponent(x.title)+'\')">点歌</button></div>').join('')}async function search(){let r=await j('/search?q='+encodeURIComponent(q.value));render(r.songs)}async function order(v){await j('/order?q='+v)}async function queue(){let r=await j('/queue');render(r.queue)}async function cmd(a){await j('/cmd?a='+a)}search()</script></body></html>
    """.trimIndent()

    private fun write(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val type = if (body.startsWith("<!doctype")) "text/html" else "application/json"
        output.write("HTTP/1.1 $status OK\r\nContent-Type: $type; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }
}
