package fr.srtreader.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ControllerTest {
    private val keys = mutableListOf<Boolean>()
    private val controller = Controller(null, object : Host {
        override fun sendMediaKey(play: Boolean) {
            keys += play
        }
    }) { 0L }

    @Test
    fun followsVideoAppUnlessDisabled() {
        controller.load("a.srt", "1\n00:01:00,000 --> 00:01:02,000\nHi\n")
        controller.updateExternal(ExternalPlayback("Netflix", playing = true, positionMs = 60_500, updatedAt = 0))
        assertEquals("Hi", controller.overlayText())
        assertContains(controller.stateJson(), "\"following\":\"Netflix\"")

        controller.handle("set", mapOf("k" to "follow", "v" to "0"))
        val manual = controller.stateJson()
        assertContains(manual, "\"following\":null")
        assertContains(manual, "\"position\":60500")
        assertContains(manual, "\"playing\":false")

        // Une nouvelle position publiée est ignorée tant que le suivi est coupé
        controller.updateExternal(ExternalPlayback("Netflix", playing = true, positionMs = 90_000, updatedAt = 0))
        assertContains(controller.stateJson(), "\"position\":60500")

        controller.handle("set", mapOf("k" to "follow", "v" to "1"))
        assertContains(controller.stateJson(), "\"position\":90000")
    }

    @Test
    fun playPausePilotsVideoAppWhenFollowingEvenWithoutMediaKeys() {
        controller.handle("set", mapOf("k" to "mediaKeys", "v" to "0"))
        controller.handle("pause", emptyMap())
        assertEquals(emptyList(), keys)

        controller.updateExternal(ExternalPlayback("Netflix", playing = true, positionMs = 0, updatedAt = 0))
        controller.handle("toggle", emptyMap())
        assertEquals(listOf(false), keys)
    }
}
