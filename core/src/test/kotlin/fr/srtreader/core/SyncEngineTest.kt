package fr.srtreader.core

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {
    private var clock = 0L
    private val engine = SyncEngine { clock }

    private fun cue(i: Int, startS: Long, text: String = "c$i") = Cue(i, startS * 1000, startS * 1000 + 2000, text)

    @Test
    fun clockAdvancesOnlyWhilePlaying() {
        engine.play()
        clock += 1000
        assertEquals(1000, engine.position())
        engine.pause()
        clock += 5000
        assertEquals(1000, engine.position())
        engine.seekBy(-10_000)
        assertEquals(0, engine.position())
    }

    @Test
    fun findsActiveAndNextCues() {
        engine.load(listOf(cue(0, 10), cue(1, 20)))
        assertTrue(engine.activeAt(5_000).isEmpty())
        assertEquals(0, engine.nextAfter(5_000)?.index)
        assertEquals(listOf(0), engine.activeAt(11_000).map { it.index })
        assertEquals(1, engine.nextAfter(11_000)?.index)
        assertNull(engine.nextAfter(25_000))
    }

    @Test
    fun syncMakesCueStartNow() {
        engine.load(listOf(cue(0, 10), cue(1, 20)))
        engine.seekTo(12_500)
        val outcome = engine.syncTo(engine.cues[0])
        assertNull(outcome.measured)
        assertEquals(2_500, engine.offsetMs)
        assertEquals(listOf(0), engine.activeAt(12_600).map { it.index })
    }

    @Test
    fun twoDistantSyncsDetectPalSpeedup() {
        // SRT fait pour une version 25 i/s, film à 23,976 i/s : tout arrive 4,27 % plus tard
        val pal = 25.0 / (24000.0 / 1001.0)
        engine.load(listOf(cue(0, 60), cue(1, 3000)))
        engine.play()
        clock = (60_000 * pal).roundToLong() + 3_000 // 3 s de pub/logo avant le film
        engine.syncTo(engine.cues[0])
        clock = (3_000_000 * pal).roundToLong() + 3_000 + 400 // appui 0,4 s trop tard
        val outcome = engine.syncTo(engine.cues[1])
        assertEquals(pal, outcome.applied)
        assertEquals(pal, engine.scale)
    }

    @Test
    fun incoherentSpeedIsIgnored() {
        // l'utilisateur a reculé de 30 s dans le film sans le dire : la mesure est fausse
        engine.load(listOf(cue(0, 60), cue(1, 3000)))
        engine.play()
        clock = 60_000
        engine.syncTo(engine.cues[0])
        clock = 3_030_000
        val outcome = engine.syncTo(engine.cues[1])
        assertNull(outcome.applied)
        assertEquals(1.0, engine.scale)
        assertEquals(30_000, engine.offsetMs)
    }

    @Test
    fun followsVideoAppThenFallsBackToManualClock() {
        engine.load(listOf(cue(0, 100)))
        clock = 10_000
        engine.follow(ExternalPlayback("Netflix", playing = true, positionMs = 100_500, updatedAt = 9_000))
        assertEquals(101_500, engine.position()) // extrapolé depuis la dernière mise à jour
        assertTrue(engine.playing)
        assertEquals(listOf(0), engine.activeAt(engine.position()).map { it.index })

        clock = 12_000
        engine.follow(ExternalPlayback("Netflix", playing = false, positionMs = 103_000, updatedAt = 12_000))
        clock = 20_000
        assertEquals(103_000, engine.position())

        engine.follow(null)
        assertEquals(103_000, engine.position())
        assertFalse(engine.playing)
        engine.play()
        clock += 1_000
        assertEquals(104_000, engine.position())
    }

    @Test
    fun syncUsesVideoAppPosition() {
        engine.load(listOf(cue(0, 10)))
        clock = 5_000
        engine.follow(ExternalPlayback("Netflix", playing = true, positionMs = 12_000, updatedAt = 5_000))
        engine.syncTo(engine.cues[0])
        assertEquals(2_000, engine.offsetMs)
    }

    @Test
    fun changingScaleKeepsCurrentCueOnScreen() {
        engine.load(listOf(cue(0, 600)))
        engine.seekTo(601_000)
        assertEquals(listOf(0), engine.activeAt(engine.position()).map { it.index })
        engine.setScale(25.0 / 24.0)
        assertEquals(listOf(0), engine.activeAt(engine.position()).map { it.index })
    }
}
