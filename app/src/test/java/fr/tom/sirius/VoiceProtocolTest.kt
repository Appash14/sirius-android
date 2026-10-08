package fr.tom.sirius

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceProtocolTest {
    @Test fun phraseMatchesServerMultipartAndSignalsLockWithoutUnknownField() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":true,\"id\":\"voix-1\"}"))
            server.start()
            val settings = ServerSettings(server.url("/voix/").toString(), "test-user", "test-password")
            val protocol = VoiceProtocol(server.url("/voix/"), settings, "android-test")
            val pcm = pcmBytes(ShortArray(320), 320)
            OkHttpClient().newCall(protocol.phraseRequest(wavBytes(pcm), 1000.0, 1001.0, true)).execute().use { assertEquals(202, it.code) }
            val received = server.takeRequest()
            assertEquals("/voix/api/phrase?verrouille=true", received.path)
            assertEquals("true", received.getHeader("X-Sirius-Verrouille"))
            assertTrue(received.getHeader("Authorization")!!.startsWith("Basic "))
            val body = received.body.readUtf8()
            assertTrue(body.contains("name=\"audio\"; filename=\"phrase.wav\""))
            assertTrue(body.contains("Content-Type: audio/wav"))
            assertTrue(body.contains("name=\"client\""))
            assertTrue(body.contains("android-test"))
            assertFalse(body.contains("name=\"verrouille\""))
            assertTrue(body.contains("RIFF"))
        }
    }
    @Test fun livePieceCarriesUtteranceFieldsAndTheSilentLastPieceNoAudio() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(202).setBody("{\"accepted\":true}")) }
            server.start()
            val protocol = VoiceProtocol(server.url("/voix/"), ServerSettings("", "user", "password"), "android-test")
            val id = VoiceProtocol.newUtteranceId()
            assertTrue(Regex("[A-Za-z0-9_-]{4,40}").matches(id))
            assertNotEquals(id, VoiceProtocol.newUtteranceId())
            val wav = wavBytes(pcmBytes(ShortArray(320), 320))
            OkHttpClient().newCall(protocol.chunkRequest(wav, id, 3, false, 640, 1000.0, 1001.2, false)).execute().close()
            OkHttpClient().newCall(protocol.chunkRequest(null, id, 4, true, 0, 1001.2, 1002.0, true)).execute().close()
            val piece = server.takeRequest()
            assertEquals("/voix/api/phrase?verrouille=false", piece.path)
            assertEquals("false", piece.getHeader("X-Sirius-Verrouille"))
            val body = piece.body.readUtf8()
            for ((name, value) in listOf("client" to "android-test", "enonce" to id, "seq" to "3", "fin" to "0", "parole" to "640")) {
                assertTrue("$name absent", body.contains("name=\"$name\"\r\nContent-Length: ${value.length}\r\n\r\n$value\r\n"))
            }
            assertTrue(body.contains("name=\"audio\"; filename=\"bout.wav\""))
            val last = server.takeRequest().body.readUtf8()
            assertFalse(last.contains("name=\"audio\""))
            assertTrue(last.contains("name=\"fin\"\r\nContent-Length: 1\r\n\r\n1\r\n"))
        }
    }
    @Test fun socketUsesSameClientAndPageControlMessages() {
        MockWebServer().use { server ->
            val protocol = VoiceProtocol(server.url("/voix/"), ServerSettings("", "user", "password"), "android-test")
            assertEquals("/voix/ws?client=android-test", protocol.socketRequest().url.encodedPath + "?" + protocol.socketRequest().url.encodedQuery)
            assertTrue(JSONObject(VoiceProtocol.ready(true)).getBoolean("ready"))
            assertTrue(JSONObject(VoiceProtocol.speech(true)).getBoolean("active"))
            assertEquals("interrupt", JSONObject(VoiceProtocol.interrupt()).getString("type"))
        }
    }
    @Test fun pilotageIsAnnouncedAgainOnlyWhenSwitchOrKeyguardChanges() {
        val announcer = PilotAnnouncer()
        val first = JSONObject(announcer.frame(true, true)!!)
        assertEquals("pilotage", first.getString("type"))
        assertTrue(first.getBoolean("enabled")); assertTrue(first.getBoolean("verrouille"))
        assertNull(announcer.frame(true, true))
        assertFalse(JSONObject(announcer.frame(true, false)!!).getBoolean("verrouille"))
        assertNull(announcer.frame(true, false))
        assertNotNull(announcer.frame(true, false, force = true))
        assertFalse(JSONObject(announcer.frame(false, false)!!).getBoolean("enabled"))
        announcer.reset()
        assertNotNull(announcer.frame(false, false))
    }
    @Test fun serverAddressRejectsCleartextAndEmbeddedSecrets() {
        assertEquals("https://example.org/voix/", normalizeAddress("https://example.org").toString())
        assertEquals("https://example.org/voix/", normalizeAddress("https://example.org/voix").toString())
        for (bad in listOf("http://example.org", "https://user:password@example.org", "https://example.org/?token=x")) {
            try { normalizeAddress(bad); fail("Adresse acceptée : $bad") } catch (_: IllegalArgumentException) {}
        }
    }
}
