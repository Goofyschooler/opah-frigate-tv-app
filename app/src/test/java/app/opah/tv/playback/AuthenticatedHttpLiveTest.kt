package app.opah.tv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AuthenticatedHttpLiveTest {
    @Test
    fun streamingClientRetainsAuthenticationAndTlsWithoutRedirectsOrWholeCallDeadline() {
        val original = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val streaming = httpLiveClient(original)
        assertSame(original.cookieJar, streaming.cookieJar)
        assertSame(original.sslSocketFactory, streaming.sslSocketFactory)
        assertSame(original.hostnameVerifier, streaming.hostnameVerifier)
        assertEquals(0, streaming.callTimeoutMillis)
        assertEquals(20_000, streaming.readTimeoutMillis)
        assertEquals(30_000, original.callTimeoutMillis)
        assertFalse(streaming.followRedirects)
        assertFalse(streaming.followSslRedirects)
    }

    @Test
    fun usesConfiguredHttpsOriginAndBasePath() {
        assertEquals("https://example.test/frigate/api/go2rtc/api/stream.mp4?src=cam1&mp4=",
            httpLiveUri("https://example.test/frigate/", "cam1"))
        assertEquals("https://example.test/api/go2rtc/api/stream.mp4?src=cam1&mp4=",
            httpLiveUri("https://example.test", "cam1"))
    }

    @Test
    fun rejectsInsecureOriginsCredentialsAndSourceInjection() {
        for (base in listOf("http://example.test", "https://user:secret@example.test", "https://example.test/?x=1", "https://example.test/#fragment")) {
            assertNull(httpLiveUri(base, "cam1"))
        }
        for (stream in listOf("rtsp://example.test/cam", "ffmpeg:cam1", "cam1&src=other", "", "../other")) {
            assertNull(httpLiveUri("https://example.test", stream))
        }
    }
}
