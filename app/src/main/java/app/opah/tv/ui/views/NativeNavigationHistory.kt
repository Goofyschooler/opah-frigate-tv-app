package app.opah.tv.ui.views

/** Only locations live here; authenticated data and player objects stay with their owners. */
internal data class NativeLocation(
    val route: NativeRoute = NativeRoute(),
    val activityPage: NativeActivityPage = NativeActivityPage.NEW,
    val clipsPage: NativeClipsPage = NativeClipsPage.RECORDINGS,
    val serverPage: NativeServerPage = NativeServerPage.PERFORMANCE,
) {
    fun canonical(): NativeLocation = copy(
        activityPage = if (route.destination == NativeDestination.ACTIVITY) activityPage else NativeActivityPage.NEW,
        clipsPage = if (route.destination == NativeDestination.CLIPS) clipsPage else NativeClipsPage.RECORDINGS,
        serverPage = if (route.destination == NativeDestination.SETTINGS) serverPage else NativeServerPage.PERFORMANCE,
    )
}

internal class NativeNavigationHistory(initial: NativeLocation) {
    private val locations = ArrayDeque<NativeLocation>()
    var current: NativeLocation = initial.canonical()
        private set

    fun visit(location: NativeLocation) {
        val next = location.canonical()
        if (next == current) return
        // Revisiting a location unwinds to it instead of building cycles between root tabs.
        if (next in locations) {
            while (locations.isNotEmpty() && locations.last() != next) locations.removeLast()
            if (locations.isNotEmpty()) locations.removeLast()
        } else {
            locations.addLast(current)
            while (locations.size > 24) locations.removeFirst()
        }
        current = next
    }

    fun back(): NativeLocation? = locations.removeLastOrNull()?.also { current = it }

    /** Oldest origin first; contains no content, filters, modal state, or player objects. */
    fun snapshot(): List<NativeLocation> = locations.toList()

    fun restore(current: NativeLocation, past: List<NativeLocation>) {
        val bounded = past.takeLast(24).map(NativeLocation::canonical)
        reset(bounded.firstOrNull() ?: current)
        bounded.drop(1).forEach(::visit)
        visit(current)
    }

    /** A capability fallback replaces an unavailable location rather than adding a Back loop. */
    fun replaceCurrent(location: NativeLocation) {
        val next = location.canonical()
        if (next in locations) {
            while (locations.isNotEmpty() && locations.last() != next) locations.removeLast()
            if (locations.isNotEmpty()) locations.removeLast()
        }
        current = next
    }

    fun reset(location: NativeLocation = NativeLocation()) {
        locations.clear()
        current = location.canonical()
    }
}
