package fr.srtreader.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonReaderTest {
    @Test
    fun readsNestedValues() {
        val json = JsonReader.parse("""{"a":[1,-2.5,true,false,null],"b":{"c":"x"},"e":1e3}""")
        assertEquals(listOf(1L, -2.5, true, false, null), json.items("a"))
        assertEquals("x", json.field("b").text("c"))
        assertEquals(1000.0, json.number("e"))
    }

    @Test
    fun decodesEscapes() {
        assertEquals("é \"q\" \\ /\n", JsonReader.parse(""""é \"q\" \\ \/\n""""))
    }

    @Test
    fun roundTripsWhatWeWrite() {
        val text = JsonObj().put("s", "ligne 1\nligne \"2\"").put("n", 3L).put("l", listOf(1, null)).toString()
        val json = JsonReader.parse(text)
        assertEquals("ligne 1\nligne \"2\"", json.text("s"))
        assertEquals(listOf(1L, null), json.items("l"))
    }

    @Test
    fun rejectsInvalidJson() {
        assertFailsWith<IllegalArgumentException> { JsonReader.parse("""{"a":1""") }
        assertFailsWith<IllegalArgumentException> { JsonReader.parse("""{"a":1} x""") }
        assertFailsWith<IllegalArgumentException> { JsonReader.parse("<html>") }
    }
}
