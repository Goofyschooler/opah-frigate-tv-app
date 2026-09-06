package app.opah.tv.playback.media3

import app.opah.tv.playback.compatibility.DecoderMode

/** Applies the attempt's decoder preference without exceeding its resource policy. */
internal fun <T> applyMedia3VideoDecoderPolicy(
    decoders: List<T>,
    decoderMode: DecoderMode,
    softwareDecoderPermitted: Boolean,
    isHardware: (T) -> Boolean,
    isSoftware: (T) -> Boolean,
): List<T> = when (decoderMode) {
    DecoderMode.PLATFORM_DEFAULT -> if (softwareDecoderPermitted) {
        decoders
    } else {
        decoders.filter(isHardware)
    }

    DecoderMode.PREFER_HARDWARE -> if (softwareDecoderPermitted) {
        decoders.sortedByDescending(isHardware)
    } else {
        decoders.filter(isHardware)
    }

    DecoderMode.ALLOW_SOFTWARE -> if (softwareDecoderPermitted) {
        decoders.sortedByDescending(isSoftware)
    } else {
        emptyList()
    }
}
