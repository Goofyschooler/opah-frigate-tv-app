package app.opah.tv.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@UnstableApi
internal val softwareFirstMediaCodecSelector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
    val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
    if (mimeType.startsWith("video/")) {
        decoders.sortedByDescending { decoder -> decoder.softwareOnly }
    } else {
        decoders
    }
}
