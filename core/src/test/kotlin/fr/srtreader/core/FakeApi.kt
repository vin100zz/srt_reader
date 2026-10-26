package fr.srtreader.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Faux serveur OpenSubtitles pour les tests : réponses préparées, requêtes enregistrées. */
internal class FakeApi : AutoCloseable {
    data class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: String)

    val requests = CopyOnWriteArrayList<Request>()
    private val routes = HashMap<String, Pair<Int, ByteArray>>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += Request(
                exchange.requestMethod,
                exchange.requestURI.toString(),
                exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            val (status, bytes) = routes[exchange.requestURI.path] ?: (404 to "{}".toByteArray())
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val url: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun on(path: String, body: String, status: Int = 200) = onBytes(path, body.toByteArray(), status)

    fun onBytes(path: String, body: ByteArray, status: Int = 200) {
        routes[path] = status to body
    }

    override fun close() = server.stop(0)
}
