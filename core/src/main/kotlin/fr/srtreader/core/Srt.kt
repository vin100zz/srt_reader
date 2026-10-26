package fr.srtreader.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/** Une réplique du fichier SRT. [index] est sa position dans la liste triée. */
data class Cue(val index: Int, val startMs: Long, val endMs: Long, val text: String) {
    /** Texte sans balises ni accents, en minuscules : sert à la recherche. */
    val searchText: String by lazy { normalizeForSearch(stripTags(text)) }
}

object SrtParser {
    private val timing = Regex(
        """^\s*(\d+):(\d{1,2}):(\d{1,2})(?:[,.](\d{1,3}))?\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})(?:[,.](\d{1,3}))?"""
    )
    private val assTags = Regex("""\{\\[^}]*\}""")

    /**
     * Lit un fichier SRT en tolérant les écarts courants : BOM, fins de ligne Windows,
     * numéros manquants, millisecondes avec un point, lignes vides dans le texte.
     */
    fun parse(content: String): List<Cue> {
        val lines = content.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val timings = lines.indices.mapNotNull { i -> timing.find(lines[i])?.let { i to it } }
        val cues = ArrayList<Cue>(timings.size)

        for ((k, entry) in timings.withIndex()) {
            val (lineIndex, match) = entry
            val blockEnd = if (k + 1 < timings.size) timings[k + 1].first else lines.size
            var block = lines.subList(lineIndex + 1, blockEnd).map { it.trim() }.dropLastWhile { it.isBlank() }
            // La dernière ligne du bloc est le numéro de la réplique suivante
            if (k + 1 < timings.size && block.isNotEmpty() && block.last().all { it.isDigit() } &&
                (block.size == 1 || block[block.size - 2].isBlank())
            ) {
                block = block.dropLast(1)
            }
            val text = block.filter { it.isNotBlank() }.joinToString("\n").let { assTags.replace(it, "") }.trim()
            if (text.isEmpty()) continue

            val start = toMs(match.groupValues, 1)
            var end = toMs(match.groupValues, 5)
            if (end <= start) end = start + 2000
            cues += Cue(0, start, end, text)
        }
        return cues.sortedBy { it.startMs }.mapIndexed { i, cue -> cue.copy(index = i) }
    }

    private fun toMs(groups: List<String>, from: Int): Long {
        val h = groups[from].toLong()
        val m = groups[from + 1].toLong()
        val s = groups[from + 2].toLong()
        val fraction = groups[from + 3]
        val ms = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').toLong()
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    }
}

private val tags = Regex("<[^>]*>")
private val marks = Regex("\\p{M}+")
private val spaces = Regex("\\s+")

fun stripTags(text: String): String = tags.replace(text, "")

/** Décode un fichier texte : UTF-16 (avec BOM), UTF-8, sinon Windows-1252 (vieux fichiers « ANSI »). */
fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
    }
}

fun normalizeForSearch(text: String): String =
    spaces.replace(marks.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), ""), " ").trim()
