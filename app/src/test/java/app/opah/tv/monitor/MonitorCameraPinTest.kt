package app.opah.tv.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorCameraPinTest {
    @Test
    fun fixedSelectionSurvivesRepeatedUpdatesWithoutAHoldDeadline() {
        val pin = MonitorCameraPin()
        pin.select("cam1", listOf("cam1", "cam2"))
        repeat(100) {
            assertEquals("cam1", pin.reconcile(MonitorPreset.FIXED, listOf("cam1", "cam2")))
        }
    }

    @Test
    fun removedCameraCannotRemainPinned() {
        val pin = MonitorCameraPin()
        pin.select("cam1", listOf("cam1"))
        assertNull(pin.reconcile(MonitorPreset.FIXED, listOf("cam2")))
        assertNull(pin.reconcile(MonitorPreset.FIXED, listOf("cam1")))
    }

    @Test
    fun unavailableSelectionIsRejectedAndPresetChangeClearsPin() {
        val pin = MonitorCameraPin()
        pin.select("cam1", emptyList())
        assertNull(pin.cameraId)
        pin.select("cam1", listOf("cam1"))
        assertNull(pin.reconcile(MonitorPreset.PATROL, listOf("cam1")))
        pin.select("cam1", listOf("cam1"))
        pin.clear()
        assertNull(pin.cameraId)
    }
}
