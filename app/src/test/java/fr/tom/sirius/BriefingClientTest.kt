package fr.tom.sirius

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BriefingClientTest {
    private val settings = ServerSettings("https://example.test/voix/", "tom", "secret-test")
    private val client = BriefingClient(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build())

    @Test fun requestUsesTheConfiguredHttpsPathAndAuthentication() {
        val request = client.request(settings)
        assertEquals("GET", request.method)
        assertEquals("https://example.test/voix/api/briefing", request.url.toString())
        assertEquals(Credentials.basic("tom", "secret-test", Charsets.UTF_8), request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertNull(request.body)
    }

    private fun fetch(response: MockResponse): String = MockWebServer().use { server ->
        server.enqueue(response)
        server.start()
        runBlocking { client.fetch(client.request(settings).newBuilder().url(server.url("/voix/api/briefing")).build()) }
    }

    @Test fun missingEndpointProducesExactlyTheSpokenFallback() {
        assertEquals("Le briefing n'est pas encore disponible", fetch(MockResponse().setResponseCode(404).setBody("Not Found")))
    }
    @Test fun dailyTextIsDecodedAndTrimmed() {
        assertEquals("Bonjour Tom. Voici ta journée.", fetch(MockResponse().setBody("""{"texte":"  Bonjour Tom. Voici ta journée.  "}""")))
    }
    @Test fun redirectsAuthenticationErrorsAndInvalidPayloadsNeverBecomeSpokenServerContent() {
        for (response in listOf(MockResponse().setResponseCode(302).setHeader("Location", "http://other.test/"),
            MockResponse().setResponseCode(401), MockResponse().setBody("""{"texte":" "}"""),
            MockResponse().setBody("""{"unexpected":"secret"}"""), MockResponse().setBody("x".repeat(70_000)))) {
            assertTrue(runCatching { fetch(response) }.isFailure)
        }
    }
    @Test fun speechSplittingPreservesWordsAndBoundsEachInput() {
        val text = (1..2000).joinToString(" ") { "bonjour" }
        val pieces = briefingPieces(text)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= 3000 })
        assertEquals(text, pieces.joinToString(" "))
    }
}
