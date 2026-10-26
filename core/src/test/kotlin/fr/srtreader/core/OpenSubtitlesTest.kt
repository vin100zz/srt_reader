package fr.srtreader.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenSubtitlesTest {
    private val fake = FakeApi()
    private val api = OpenSubtitles(fake.url + "/api/v1")
    private val key = OpenSubtitles.Credentials("ma-cle")
    private val sample = javaClass.getResource("/opensubtitles-search.json")!!.readText()

    @AfterTest
    fun tearDown() = fake.close()

    @Test
    fun parsesSearchResponse() {
        val results = OpenSubtitles.parseResults(JsonReader.parse(sample))
        assertEquals(listOf(4982777L, 102201L, 555L, 777L, 42L), results.map { it.fileId }) // sous-titre en 2 CD écarté

        val first = results[0]
        assertEquals("Inception (2010)", first.title)
        assertEquals(23.976, first.fps)
        assertFalse(first.netflix)

        assertTrue(results[2].netflix)
        assertTrue(results[2].hearingImpaired)

        val auto = results[3]
        assertEquals("auto.srt", auto.release)
        assertNull(auto.fps)
        assertTrue(auto.machineTranslated)

        assertEquals("Breaking Bad S01E02 · Cat's in the Bag...", results[4].title)
    }

    @Test
    fun ranksNetflixCopiesFirstWhenWatchingNetflix() {
        val results = OpenSubtitles.parseResults(JsonReader.parse(sample))
        assertEquals(listOf(555L, 42L, 102201L, 4982777L, 777L), OpenSubtitles.rank(results, preferNetflix = true).map { it.fileId })
        assertEquals(listOf(102201L, 4982777L, 555L, 42L, 777L), OpenSubtitles.rank(results, preferNetflix = false).map { it.fileId })
    }

    @Test
    fun searchSendsKeyAndNormalizedQuery() {
        fake.on("/api/v1/subtitles", sample)
        val results = api.search(key, "  Breaking Bad ", season = 1, episode = 2)
        assertEquals(5, results.size)

        val request = fake.requests.single()
        assertEquals("/api/v1/subtitles?episode_number=2&languages=en&order_by=download_count&query=breaking+bad&season_number=1", request.uri)
        assertEquals("ma-cle", request.headers["api-key"])
        assertEquals(OpenSubtitles.USER_AGENT, request.headers["user-agent"])
    }

    @Test
    fun downloadLogsInThenFetchesFile() {
        fake.on("/api/v1/login", """{"token":"jeton","base_url":"ailleurs.example.com","status":200}""")
        fake.on("/api/v1/download", """{"link":"${fake.url}/files/x.srt","file_name":"Inception.srt","remaining":4}""")
        fake.onBytes("/files/x.srt", "1\n00:00:01,000 --> 00:00:02,000\nCafé\n".toByteArray(Charsets.ISO_8859_1))

        val download = api.download(key.copy(username = "moi", password = "secret"), 555)
        assertEquals("Inception.srt", download.fileName)
        assertEquals(4, download.remaining)
        assertContains(download.content, "Café") // fichier « ANSI » décodé

        val (login, request) = fake.requests.take(2)
        assertEquals("""{"username":"moi","password":"secret"}""", login.body)
        assertEquals("/api/v1/download", request.uri) // base_url hors opensubtitles.com ignorée
        assertEquals("Bearer jeton", request.headers["authorization"])
        assertEquals("""{"file_id":555}""", request.body)
    }

    @Test
    fun reportsApiErrorsInFrench() {
        fake.on("/api/v1/subtitles", """{"message":"You cannot consume this service"}""", status = 403)
        val error = assertFailsWith<OpenSubtitlesException> { api.search(key, "inception") }
        assertEquals("Accès refusé par OpenSubtitles (clé d'API ?) : You cannot consume this service", error.message)
    }

    @Test
    fun reportsUnreachableServer() {
        val offline = OpenSubtitles("http://127.0.0.1:1/api/v1")
        val error = assertFailsWith<OpenSubtitlesException> { offline.search(key, "inception") }
        assertContains(error.message.orEmpty(), "injoignable")
    }
}
