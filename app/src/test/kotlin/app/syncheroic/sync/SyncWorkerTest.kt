package app.syncheroic.sync

import app.syncheroic.data.FrequentSyncSettings
import java.time.LocalTime
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncWorkerTest {
    private val settings = FrequentSyncSettings(
        enabled = true,
        start = LocalTime.of(12, 0),
        end = LocalTime.of(13, 30),
    )

    @Test
    fun `frequent sync window includes both boundaries`() {
        assertTrue(SyncWorker.isWithinFrequentWindow(LocalTime.of(12, 0), settings))
        assertTrue(SyncWorker.isWithinFrequentWindow(LocalTime.of(13, 30), settings))
    }

    @Test
    fun `frequent sync window excludes times outside boundaries`() {
        assertFalse(SyncWorker.isWithinFrequentWindow(LocalTime.of(11, 59, 59), settings))
        assertFalse(SyncWorker.isWithinFrequentWindow(LocalTime.of(13, 30, 1), settings))
    }
}
