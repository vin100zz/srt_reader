package fr.srtreader.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Mini serveur HTTP : sert la page de contrôle et son API JSON.
 *
 *   GET  /              page de contrôle
 *   GET  /api/state     état courant
 *   GET  /api/search?q= recherche de répliques
 *   POST /api/load      corps = contenu du .srt (UTF-8), en-tête X-File-Name
 *   POST /api/cmd?a=... commande (play, pause, seek, nudge, sync...)
 *
 *   GET  /api/subs/status            clé OpenSubtitles enregistrée ?
 *   POST /api/subs/config            corps (formulaire) = key, user, password
 *   GET  /api/subs/search?q=&season=&episode=
 *   POST /api/subs/download?file=    télécharge et charge le sous-titre
 *
 * [webRoot] permet, en développement, de relire la page depuis les sources à chaque requête.
 */
class ControlServer(
    private val controller: Controller,
    private val webRoot: File? = null,
    private val subtitles: SubtitleSearch? = null,
) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "srt-http").apply { isDaemon = true } }

    @Volatile
    private var socket: ServerSocket? = null

    /** Démarre sur le premier port libre à partir de [preferredPort] (0 = port au hasard). */
    fun start(preferredPort: Int): Int {
        val candidates = if (preferredPort == 0) listOf(0) else (preferredPort until preferredPort + 10).toList()
        var error: IOException? = null
        for (p in candidates) {
            try {
                val s = ServerSocket(p)
                socket = s
                pool.execute { acceptLoop(s) }
                return s.localPort
            } catch (e: IOException) {
                error = e
            }
        }
        throw error ?: IOException("Aucun port libre")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            pool.execute { client.use { serve(it) } }
        }
    }

    private class Response(val status: Int, val type: String, val body: ByteArray)

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val response = if (length > MAX_BODY) {
                error(413, "Fichier trop gros")
            } else {
                val body = readBody(input, length)
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                try {
                    route(method, path, query, headers, body)
                } catch (e: IllegalArgumentException) {
                    error(400, e.message ?: "Requête invalide")
                } catch (e: OpenSubtitlesException) {
                    error(502, e.message ?: "Erreur OpenSubtitles")
                } catch (e: Exception) {
                    error(500, e.toString())
                }
            }
            write(client.getOutputStream(), response)
        } catch (_: IOException) {
            // client parti en cours de route : rien à faire
        }
    }

    private fun route(
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray,
    ): Response = when {
        method == "GET" && (path == "/" || path == "/index.html") -> Response(200, "text/html; charset=utf-8", page())
        method == "GET" && path == "/api/state" -> json(controller.stateJson())
        method == "GET" && path == "/api/search" -> json(controller.searchJson(query["q"].orEmpty()))
        method == "POST" && path == "/api/load" -> {
            val name = headers["x-file-name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "sous-titres.srt"
            controller.load(name, String(body, Charsets.UTF_8))
            json(controller.stateJson())
        }
        method == "POST" && path == "/api/cmd" -> {
            controller.handle(query["a"].orEmpty(), query)
            json(controller.stateJson())
        }
        path.startsWith("/api/subs/") -> subtitlesRoute(method, path, query, body)
        else -> error(404, "Introuvable")
    }

    private fun subtitlesRoute(method: String, path: String, query: Map<String, String>, body: ByteArray): Response {
        val subs = subtitles ?: return error(404, "Recherche de sous-titres indisponible")
        return when {
            method == "GET" && path == "/api/subs/status" -> json(subs.statusJson())
            method == "POST" && path == "/api/subs/config" -> {
                val form = parseQuery(String(body, Charsets.UTF_8))
                subs.configure(form["key"].orEmpty(), form["user"].orEmpty(), form["password"].orEmpty())
                json(subs.statusJson())
            }
            method == "GET" && path == "/api/subs/search" -> {
                val netflix = controller.followingSource()?.contains("netflix", ignoreCase = true) == true
                json(subs.searchJson(query["q"].orEmpty(), query["season"]?.toIntOrNull(), query["episode"]?.toIntOrNull(), netflix))
            }
            method == "POST" && path == "/api/subs/download" -> {
                val fileId = query["file"]?.toLongOrNull() ?: throw IllegalArgumentException("Sous-titre inconnu")
                val download = subs.download(fileId)
                val note = download.remaining?.let { " · encore $it téléchargements aujourd'hui" }.orEmpty()
                controller.load(download.fileName, download.content, note)
                json(controller.stateJson())
            }
            else -> error(404, "Introuvable")
        }
    }

    private fun page(): ByteArray {
        webRoot?.let { root -> File(root, "index.html").takeIf { it.isFile }?.let { return it.readBytes() } }
        return cachedPage
    }

    private val cachedPage: ByteArray by lazy {
        ControlServer::class.java.getResourceAsStream("/web/index.html")?.use { it.readBytes() }
            ?: "Page de contrôle introuvable".toByteArray()
    }

    private fun json(text: String) = Response(200, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))

    private fun error(status: Int, text: String) = Response(
        status, "application/json; charset=utf-8", JsonObj().put("error", text).toString().toByteArray(Charsets.UTF_8)
    )

    private fun write(out: OutputStream, r: Response) {
        val head = "HTTP/1.1 ${r.status} ${reason(r.status)}\r\n" +
            "Content-Type: ${r.type}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        502 -> "Bad Gateway"
        else -> "Internal Server Error"
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > MAX_LINE) throw IOException("Ligne trop longue")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readBody(input: InputStream, length: Int): ByteArray {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n == -1) throw IOException("Corps incomplet")
            read += n
        }
        return body
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    private companion object {
        const val MAX_BODY = 5 * 1024 * 1024
        const val MAX_LINE = 16 * 1024
    }
}
