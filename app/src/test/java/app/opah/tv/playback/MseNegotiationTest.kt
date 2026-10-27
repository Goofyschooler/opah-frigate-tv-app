package app.opah.tv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MseNegotiationTest {
    @Test fun acceptsVideoAndOptionalSupportedAudio() {
        assertTrue(acceptsMseNegotiation("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029,mp4a.40.2\""}"""))
        assertTrue(acceptsMseNegotiation("""{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}"""))
    }

    @Test fun rejectsMalformedErrorsAndUnsupportedMedia() {
        for (message in listOf(
            "not json", "[]", "{}", "x".repeat(16_385),
            """{"type":"error","value":"private source URL"}""",
            """{"type":"mse","value":"video/mp4; codecs=\"hvc1.1.6.L153.B0\""}""",
            """{"type":"mse","value":"video/mp4; codecs=\"mp4a.40.2\""}""",
            """{"type":"mse","value":"text/html"}""",
        )) assertFalse(acceptsMseNegotiation(message))
    }
}
