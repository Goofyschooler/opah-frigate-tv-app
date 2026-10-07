package app.opah.tv.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal fun httpLiveClient(authenticatedClient: OkHttpClient): OkHttpClient =
    authenticatedClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

internal class AuthenticatedHttpLive(
    val uri: String,
    val isAuthorized: () -> Boolean,
)

/** Only named streams from discovery, on the configured HTTPS origin. Never accepts source URLs. */
internal fun httpLiveUri(baseUrl: String, streamName: String): String? {
    if (!Regex("[A-Za-z0-9_.-]{1,160}").matches(streamName)) return null
    val base = baseUrl.toHttpUrlOrNull() ?: return null
    if (base.scheme != "https" || base.username.isNotEmpty() || base.password.isNotEmpty() ||
        base.query != null || base.fragment != null
    ) return null
    return base.newBuilder()
        .addPathSegments("live/mse/api/ws")
        .addQueryParameter("src", streamName)
        .build().toString()
}
