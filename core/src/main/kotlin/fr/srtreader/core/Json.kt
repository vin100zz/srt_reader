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
