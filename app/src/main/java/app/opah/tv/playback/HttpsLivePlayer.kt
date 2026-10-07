package app.opah.tv.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Continuous fMP4 over Frigate's MSE route; retains authenticated TLS and cookie policy. */
@UnstableApi
internal class HttpsLivePlayer(context: Context, authenticatedClient: OkHttpClient) : LivePlayer {
    private val sources = CopyOnWriteArrayList<MseLiveDataSource>()
    private var released = false
    private val client = httpLiveClient(authenticatedClient)
    override val player = ExoPlayer.Builder(context.applicationContext,
        DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .forceDisableMediaCodecAsynchronousQueueing(),
    ).setMediaSourceFactory(DefaultMediaSourceFactory(context.applicationContext)
        .setDataSourceFactory(DataSource.Factory {
            synchronized(sources) {
                if (released) throw IOException("Live player released")
                MseLiveDataSource(client).also { sources.add(it) }
            }
        })
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0)),
    ).build()

    override fun prepare(uri: String, options: LivePlaybackOptions) {
        require(uri.toHttpUrl().scheme == "https")
        player.volume = if (options.videoOnly) 0f else 1f
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMimeType("video/mp4").build())
        player.playWhenReady = true
        player.prepare()
    }

    override fun release() {
        synchronized(sources) {
            released = true
            sources.forEach { it.cancel() }
        }
        player.release()
        sources.clear()
    }
}
