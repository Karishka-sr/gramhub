package dev.gramhub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The percentage shown next to a running download. */
class InstallStateTest {

    @Test
    fun `fraction is the share of the total that has arrived`() {
        val state = InstallState(
            clientId = "nekogram",
            phase = InstallState.Phase.DOWNLOADING,
            downloadedBytes = 25L * 1024 * 1024,
            totalBytes = 50L * 1024 * 1024,
        )
        assertEquals(0.5f, state.fraction!!, 0.001f)
    }

    @Test
    fun `fraction is null when the total size is unknown`() {
        val state = InstallState(
            clientId = "nekogram",
            phase = InstallState.Phase.DOWNLOADING,
            downloadedBytes = 1024,
            totalBytes = 0,
        )
        assertNull(state.fraction)
    }

    @Test
    fun `fraction never exceeds one even if the server over-reports the size`() {
        val state = InstallState(
            clientId = "nekogram",
            phase = InstallState.Phase.DOWNLOADING,
            downloadedBytes = 200,
            totalBytes = 100,
        )
        assertEquals(1f, state.fraction!!, 0.001f)
    }
}
