package fr.srtreader.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SrtParserTest {
    @Test
    fun parsesStandardFile() {
        val cues = SrtParser.parse(
            """
            1
            00:00:01,000 --> 00:00:02,500
            Hello
            world

            2
            00:01:03,250 --> 00:01:04,000
            <i>Bye</i>
            """.trimIndent()
        )
        assertEquals(2, cues.size)
        assertEquals(1000, cues[0].startMs)
        assertEquals(2500, cues[0].endMs)
        assertEquals("Hello\nworld", cues[0].text)
        assertEquals(63_250, cues[1].startMs)
        assertEquals("<i>Bye</i>", cues[1].text)
    }

    @Test
    fun toleratesBomCrlfDotsAndMissingIndex() {
        val text = "﻿1\r\n00:00:01.5 --> 00:00:02.000\r\nFirst\r\n\r\n00:00:03,000 --> 00:00:04,000 X1:10 X2:20\r\nSecond\r\n"
        val cues = SrtParser.parse(text)
        assertEquals(listOf("First", "Second"), cues.map { it.text })
        assertEquals(1500, cues[0].startMs)
    }

    @Test
    fun dropsBlankLinesInsideTextAndNextIndex() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\nA\n\nB\n\n2\n00:00:03,000 --> 00:00:04,000\nC\n")
        assertEquals(listOf("A\nB", "C"), cues.map { it.text })
    }

    @Test
    fun sortsCuesAndStripsAssTags() {
        val cues = SrtParser.parse(
            "2\n00:00:05,000 --> 00:00:06,000\n{\\an8}Later\n\n1\n00:00:01,000 --> 00:00:02,000\nEarlier\n"
        )
        assertEquals(listOf("Earlier", "Later"), cues.map { it.text })
        assertEquals(listOf(0, 1), cues.map { it.index })
    }

    @Test
    fun ignoresEmptyCuesAndGarbage() {
        val cues = SrtParser.parse("garbage\n\n1\n00:00:01,000 --> 00:00:02,000\n\n2\n00:00:03,000 --> 00:00:04,000\nOk\n")
        assertEquals(listOf("Ok"), cues.map { it.text })
    }

    @Test
    fun searchTextIgnoresCaseAccentsAndTags() {
        val cue = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n<i>Où est   Éric ?</i>\n").single()
        assertEquals("ou est eric ?", cue.searchText)
    }
}
