package app.opah.tv.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Continuous MP4 transport, retaining the existing authenticated client's TLS and cookie policy. */
@UnstableApi
internal class HttpsLivePlayer(context: Context, authenticatedClient: OkHttpClient) : LivePlayer {
    override val player = RecordedPlayerFactory.create(
        context,
        httpLiveClient(authenticatedClient),
        preferSoftwareVideoDecoder = false,
    )

    override fun prepare(uri: String, options: LivePlaybackOptions) {
        require(uri.toHttpUrl().scheme == "https")
        player.volume = if (options.videoOnly) 0f else 1f
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType("video/mp4").build())
        player.playWhenReady = true
        player.prepare()
    }

    override fun release() = player.release()
}
