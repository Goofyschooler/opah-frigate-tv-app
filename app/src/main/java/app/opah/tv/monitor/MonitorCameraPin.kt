package app.opah.tv.monitor

/** Session-local selection; never outlives the current view's allowed camera list. */
internal class MonitorCameraPin {
    var cameraId: String? = null
        private set

    fun select(id: String, visibleIds: Collection<String>) {
        cameraId = id.takeIf { it in visibleIds }
    }

    fun reconcile(preset: MonitorPreset, visibleIds: Collection<String>): String? {
        if (preset != MonitorPreset.FIXED || cameraId !in visibleIds) clear()
        return cameraId
    }

    fun clear() {
        cameraId = null
    }
}
