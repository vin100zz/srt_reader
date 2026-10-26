package fr.srtreader.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ControlServerTest {
    private val keys = mutableListOf<Boolean>()
    private val dir: File = Files.createTempDirectory("srt-test").toFile()
    private val controller = Controller(dir, object : Host {
        override fun sendMediaKey(play: Boolean) {
            keys += play
        }
    }) { 0L }
    private val server = ControlServer(controller)
    private val port = server.start(0)

    @AfterTest
    fun tearDown() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun call(method: String, path: String, body: String? = null, fileName: String? = null): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        fileName?.let { c.setRequestProperty("X-File-Name", it) }
        if (body != null || method == "POST") {
            c.doOutput = true
            c.outputStream.use { it.write((body ?: "").toByteArray()) }
        }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        return c.responseCode to stream.bufferedReader().readText()
    }

    @Test
    fun servesPageAndState() {
        val (status, page) = call("GET", "/")
        assertEquals(200, status)
        assertContains(page, "<html")
        assertContains(call("GET", "/api/state").second, "\"loaded\":false")
    }

    @Test
    fun loadsSrtThenSyncsAndSendsMediaKeys() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nÇa va ?\n\n2\n00:00:05,000 --> 00:00:06,000\nOui\n"
        val (status, state) = call("POST", "/api/load", srt, "Film%20%C3%A9t%C3%A9.srt")
        assertEquals(200, status)
        assertContains(state, "\"name\":\"Film été.srt\"")
        assertContains(state, "\"count\":2")

        call("POST", "/api/cmd?a=play")
        call("POST", "/api/cmd?a=pause")
        assertEquals(listOf(true, false), keys)

        call("POST", "/api/cmd?a=syncnext")
        assertEquals("Ça va ?", controller.overlayText())
        assertContains(call("GET", "/api/search?q=ca%20va").second, "\"i\":0")
    }

    @Test
    fun rejectsBadCommands() {
        val (status, body) = call("POST", "/api/cmd?a=nope")
        assertEquals(400, status)
        assertContains(body, "Action inconnue")
    }

    @Test
    fun restoresStateAfterRestart() {
        call("POST", "/api/load", "1\n00:00:01,000 --> 00:00:02,000\nHi\n", "a.srt")
        call("POST", "/api/cmd?a=nudge&ms=1500")
        call("POST", "/api/cmd?a=set&k=fontSize&v=40")
        val restarted = Controller(dir, object : Host {
            override fun sendMediaKey(play: Boolean) {}
        }) { 0L }
        val state = restarted.stateJson()
        assertContains(state, "\"name\":\"a.srt\"")
        assertContains(state, "\"offset\":1500")
        assertContains(state, "\"fontSize\":40")
    }
}
