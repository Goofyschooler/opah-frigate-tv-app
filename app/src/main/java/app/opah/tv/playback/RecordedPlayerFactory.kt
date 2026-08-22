package app.opah.tv.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.OkHttpClient

@UnstableApi
object RecordedPlayerFactory {
    fun create(
        context: Context,
        authenticatedClient: OkHttpClient,
        preferSoftwareVideoDecoder: Boolean,
    ): ExoPlayer {
        val applicationContext = context.applicationContext
        val dataSourceFactory = OkHttpDataSource.Factory(authenticatedClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(applicationContext)
            .setDataSourceFactory(dataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(applicationContext)
            .setEnableDecoderFallback(true)
            .forceDisableMediaCodecAsynchronousQueueing()
            .apply {
                if (preferSoftwareVideoDecoder) {
                    setMediaCodecSelector(softwareFirstMediaCodecSelector)
                }
            }
        return ExoPlayer.Builder(applicationContext, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
