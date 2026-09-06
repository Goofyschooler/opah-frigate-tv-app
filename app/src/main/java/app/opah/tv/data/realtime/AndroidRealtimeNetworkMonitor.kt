package app.opah.tv.data.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Read-only default-network observation. This adapter never changes device network state. */
class AndroidRealtimeNetworkMonitor(
    context: Context,
    private val events: RealtimeEventSink,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            events.dispatch(RealtimeTransportEvent.NetworkAvailabilityChanged(true))
        }

        override fun onLost(network: Network) {
            events.dispatch(RealtimeTransportEvent.NetworkAvailabilityChanged(false))
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        events.dispatch(
            RealtimeTransportEvent.NetworkAvailabilityChanged(connectivity?.activeNetwork != null),
        )
        if (connectivity == null) return
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }
}
