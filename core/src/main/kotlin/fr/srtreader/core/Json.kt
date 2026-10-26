package fr.srtreader.core

/** Petit générateur JSON, pour ne dépendre d'aucune bibliothèque. */
internal class JsonObj {
    private val fields = ArrayList<String>()

    fun put(key: String, value: Any?): JsonObj {
        fields += jsonString(key) + ":" + toJson(value)
        return this
    }

    override fun toString() = fields.joinToString(",", "{", "}")
}

internal fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> jsonString(value)
    is Boolean, is Int, is Long -> value.toString()
    is Double -> if (value.isFinite()) value.toString() else "null"
    is JsonObj -> value.toString()
    is Iterable<*> -> value.joinToString(",", "[", "]") { toJson(it) }
    else -> jsonString(value.toString())
}

/** Petit lecteur JSON : objet → Map, tableau → List, nombre → Long ou Double. */
internal object JsonReader {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipSpaces()
        if (!parser.atEnd()) parser.fail("contenu en trop")
        return value
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(what: String): Nothing = throw IllegalArgumentException("JSON invalide : $what (position $i)")

        fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipSpaces()
            if (atEnd()) fail("fin inattendue")
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> array()
                c == '"' -> string()
                c == 't' -> literal("true", true)
                c == 'f' -> literal("false", false)
                c == 'n' -> literal("null", null)
                c == '-' || c.isDigit() -> number()
                else -> fail("caractère « $c »")
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipSpaces()
            if (consume('}')) return map
            while (true) {
                skipSpaces()
                if (atEnd() || s[i] != '"') fail("clé attendue")
                val key = string()
                skipSpaces()
                expect(':')
                map[key] = value()
                skipSpaces()
                if (consume('}')) return map
                expect(',')
            }
        }

        private fun array(): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipSpaces()
            if (consume(']')) return list
            while (true) {
                list += value()
                skipSpaces()
                if (consume(']')) return list
                expect(',')
            }
        }

        private fun consume(c: Char): Boolean {
            if (i < s.length && s[i] == c) {
                i++
                return true
            }
            return false
        }

        private fun expect(c: Char) {
            if (!consume(c)) fail("« $c » attendu")
        }

        private fun string(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (atEnd()) fail("chaîne non terminée")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("échappement incomplet")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u incomplet")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val text = s.substring(start, i)
            return text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("nombre « $text »")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("« $word » attendu")
            i += word.length
            return value
        }
    }
}

// Accès tolérants dans un JSON lu : null si le champ manque ou n'a pas le bon type
internal fun Any?.field(key: String): Any? = (this as? Map<*, *>)?.get(key)
internal fun Any?.text(key: String): String? = field(key) as? String
internal fun Any?.number(key: String): Number? = field(key) as? Number
internal fun Any?.flag(key: String): Boolean = field(key) == true
internal fun Any?.items(key: String): List<*> = field(key) as? List<*> ?: emptyList<Any?>()

internal fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.append('"').toString()
}
