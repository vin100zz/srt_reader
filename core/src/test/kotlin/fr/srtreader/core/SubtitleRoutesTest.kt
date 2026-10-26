package fr.srtreader.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SubtitleRoutesTest {
    private val fake = FakeApi()
    private val dir: File = Files.createTempDirectory("srt-subs").toFile()
    private val controller = Controller(null, object : Host {
        override fun sendMediaKey(play: Boolean) {}
    }) { 0L }
    private val server = ControlServer(controller, subtitles = SubtitleSearch(dir, OpenSubtitles(fake.url + "/api/v1")))
    private val port = server.start(0)

    @AfterTest
    fun tearDown() {
        server.stop()
        fake.close()
        dir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        if (method == "POST") {
            c.doOutput = true
            c.outputStream.use { it.write((body ?: "").toByteArray()) }
        }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        return c.responseCode to stream.bufferedReader().readText()
    }

    @Test
    fun asksForKeyBeforeSearching() {
        assertContains(call("GET", "/api/subs/status").second, "\"configured\":false")
        val (status, body) = call("GET", "/api/subs/search?q=inception")
        assertEquals(400, status)
        assertContains(body, "clé d'API")
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun configuresSearchesAndLoadsSubtitle() {
        val (_, status) = call("POST", "/api/subs/config", "key=ma-cle&user=&password=")
        assertContains(status, "\"configured\":true")

        fake.on("/api/v1/subtitles", javaClass.getResource("/opensubtitles-search.json")!!.readText())
        val (code, results) = call("GET", "/api/subs/search?q=inception")
        assertEquals(200, code)
        assertContains(results, "\"title\":\"Inception (2010)\"")

        fake.on("/api/v1/download", """{"link":"${fake.url}/files/x.srt","file_name":"Inception.srt","remaining":19}""")
        fake.on("/files/x.srt", "1\n00:00:01,000 --> 00:00:02,000\nDream\n")
        val (loadCode, state) = call("POST", "/api/subs/download?file=102201")
        assertEquals(200, loadCode)
        assertContains(state, "\"name\":\"Inception.srt\"")
        assertContains(state, "encore 19 téléchargements")
    }

    @Test
    fun keepsSavedKeyAndPasswordWhenLeftBlank() {
        call("POST", "/api/subs/config", "key=cle-1&user=moi&password=secret")
        call("POST", "/api/subs/config", "key=&user=moi&password=")
        val saved = File(dir, "opensubtitles.properties").readText()
        assertContains(saved, "apiKey=cle-1")
        assertContains(saved, "password=secret")

        call("POST", "/api/subs/config", "key=&user=&password=")
        assertFalse(File(dir, "opensubtitles.properties").readText().contains("secret"))
    }

    @Test
    fun reportsOpenSubtitlesErrors() {
        call("POST", "/api/subs/config", "key=mauvaise")
        fake.on("/api/v1/subtitles", """{"message":"You cannot consume this service"}""", status = 403)
        val (status, body) = call("GET", "/api/subs/search?q=inception")
        assertEquals(502, status)
        assertContains(body, "You cannot consume this service")
    }
}
