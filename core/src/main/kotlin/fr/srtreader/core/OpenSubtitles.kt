package fr.srtreader.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Un sous-titre trouvé sur OpenSubtitles. */
data class SubtitleResult(
    val fileId: Long,
    val title: String,
    val release: String,
    val downloads: Long,
    val fps: Double?,
    val hearingImpaired: Boolean,
    val machineTranslated: Boolean,
) {
    /** Fait à partir d'une copie Netflix (« NF » dans le nom de version) : timing calé sur Netflix. */
    val netflix: Boolean
        get() = NETFLIX_TAG.containsMatchIn(release)
}

private val NETFLIX_TAG = Regex("""(?i)(^|[^a-z])(nf|netflix)([^a-z]|$)""")

class OpenSubtitlesException(message: String, val status: Int = 0) : Exception(message)

/**
 * Client minimal de l'API REST d'OpenSubtitles.com : recherche, puis téléchargement d'un fichier.
 * La clé d'API est obligatoire ; le compte est facultatif et augmente le quota de téléchargements.
 */
class OpenSubtitles(private val baseUrl: String = DEFAULT_URL) {
    data class Credentials(val apiKey: String, val username: String = "", val password: String = "")

    class Download(val fileName: String, val content: String, val remaining: Int?)

    private var token: String? = null
    private var tokenFor: Credentials? = null
    private var downloadUrl = baseUrl

    @Synchronized
    fun search(
        credentials: Credentials,
        query: String,
        season: Int? = null,
        episode: Int? = null,
        language: String = "en",
    ): List<SubtitleResult> {
        // Paramètres triés, en minuscules, espaces en « + » : sinon l'API redirige
        val params = sortedMapOf(
            "languages" to language,
            "order_by" to "download_count",
            "query" to query.trim().lowercase(),
        )
        season?.let { params["season_number"] = it.toString() }
        episode?.let { params["episode_number"] = it.toString() }
        val queryString = params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return parseResults(call("GET", "$baseUrl/subtitles?$queryString", credentials))
    }

    @Synchronized
    fun download(credentials: Credentials, fileId: Long): Download {
        val body = JsonObj().put("file_id", fileId).toString()
        login(credentials)
        val json = try {
            call("POST", "$downloadUrl/download", credentials, body)
        } catch (e: OpenSubtitlesException) {
            if (token == null || e.status != 401) throw e
            // Jeton expiré : on se reconnecte une fois
            token = null
            login(credentials)
            call("POST", "$downloadUrl/download", credentials, body)
        }
        val link = json.text("link")
            ?: throw OpenSubtitlesException(json.text("message") ?: "OpenSubtitles n'a pas donné de lien de téléchargement")
        return Download(
            fileName = json.text("file_name") ?: "$fileId.srt",
            content = decodeText(fetch(link)),
            remaining = json.number("remaining")?.toInt(),
        )
    }

    private fun login(credentials: Credentials) {
        if (credentials.username.isBlank()) {
            token = null
            tokenFor = null
            downloadUrl = baseUrl
            return
        }
        if (token != null && tokenFor == credentials) return
        token = null
        val body = JsonObj().put("username", credentials.username).put("password", credentials.password).toString()
        val json = call("POST", "$baseUrl/login", credentials, body)
        token = json.text("token") ?: throw OpenSubtitlesException("Connexion au compte OpenSubtitles refusée")
        tokenFor = credentials
        // Les comptes VIP ont un serveur dédié ; le jeton ne part que vers opensubtitles.com
        downloadUrl = json.text("base_url")
            ?.takeIf { it.endsWith("opensubtitles.com") }
            ?.let { "https://$it/api/v1" }
            ?: baseUrl
    }

    private fun call(method: String, url: String, credentials: Credentials, body: String? = null): Any? = guarded {
        val c = open(url)
        c.requestMethod = method
        c.setRequestProperty("Api-Key", credentials.apiKey)
        c.setRequestProperty("Accept", "application/json")
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = c.responseCode
        val text = (if (status < 400) c.inputStream else c.errorStream)
            ?.use { String(it.readBytes(), Charsets.UTF_8) }
            .orEmpty()
        val json = runCatching { JsonReader.parse(text) }.getOrNull()
        if (status >= 400) throw OpenSubtitlesException(errorText(status, json), status)
        json
    }

    private fun fetch(link: String): ByteArray = guarded {
        val c = open(link)
        if (c.responseCode >= 400) throw OpenSubtitlesException("Fichier de sous-titres introuvable (${c.responseCode})")
        c.inputStream.use { it.readBytes() }
    }

    private fun open(url: String) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", USER_AGENT)
    }

    private fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw OpenSubtitlesException("OpenSubtitles injoignable, vérifie la connexion internet de la TV (${e.message})")
    }

    private fun errorText(status: Int, json: Any?): String {
        val detail = json.text("message")
            ?: json.items("errors").filterIsInstance<String>().joinToString(" ").ifBlank { null }
        val summary = when (status) {
            401 -> "Identifiant ou mot de passe OpenSubtitles refusé"
            403 -> "Accès refusé par OpenSubtitles (clé d'API ?)"
            429 -> "Trop de requêtes, réessaie dans quelques secondes"
            else -> "Erreur OpenSubtitles ($status)"
        }
        return if (detail == null) summary else "$summary : $detail"
    }

    companion object {
        const val DEFAULT_URL = "https://api.opensubtitles.com/api/v1"
        const val USER_AGENT = "SRTReader v0.2"

        internal fun parseResults(json: Any?): List<SubtitleResult> = json.items("data").mapNotNull { item ->
            val a = item.field("attributes")
            // Les sous-titres découpés en plusieurs fichiers (un par CD) ne servent pas ici
            val file = a.items("files").singleOrNull() ?: return@mapNotNull null
            SubtitleResult(
                fileId = file.number("file_id")?.toLong() ?: return@mapNotNull null,
                title = featureTitle(a.field("feature_details")),
                release = a.text("release")?.takeIf { it.isNotBlank() } ?: file.text("file_name").orEmpty(),
                downloads = a.number("download_count")?.toLong() ?: 0,
                fps = a.number("fps")?.toDouble()?.takeIf { it > 0 },
                hearingImpaired = a.flag("hearing_impaired"),
                machineTranslated = a.flag("machine_translated") || a.flag("ai_translated"),
            )
        }

        private fun featureTitle(f: Any?): String {
            val title = f.text("title") ?: f.text("movie_name") ?: "?"
            val parent = f.text("parent_title")
            val season = f.number("season_number")?.toInt()
            val episode = f.number("episode_number")?.toInt()
            if (parent != null && season != null && episode != null) {
                return "%s S%02dE%02d · %s".format(parent, season, episode, title)
            }
            val year = f.number("year")?.toInt()?.takeIf { it > 0 }
            return if (year == null) title else "$title ($year)"
        }

        /** Copies Netflix d'abord si on regarde Netflix, puis les plus téléchargés ; traductions automatiques à la fin. */
        fun rank(results: List<SubtitleResult>, preferNetflix: Boolean): List<SubtitleResult> =
            results.sortedWith(
                compareBy<SubtitleResult> { it.machineTranslated }
                    .thenByDescending { preferNetflix && it.netflix }
                    .thenByDescending { it.downloads }
            )
    }
}
