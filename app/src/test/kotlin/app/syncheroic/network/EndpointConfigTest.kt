package app.syncheroic.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EndpointConfigTest {
    @Test
    fun `bundled config is restricted to expected hosts`() {
        val config = EndpointConfigLoader.loadBundled()
        assertEquals("apis.trainheroic.com", config.auth.host)
        assertEquals("/3.0/athlete/programworkout/range", config.workouts.path)
    }

    @Test
    fun `remote config rejects redirected hosts and traversal`() {
        val badHost = """{"schemaVersion":1,"auth":{"host":"example.com","method":"POST","path":"/auth"},"profile":{"host":"api.trainheroic.com","method":"GET","path":"/user/simple"},"workouts":{"host":"api.trainheroic.com","method":"GET","path":"/workouts"}}"""
        assertThrows(IllegalArgumentException::class.java) { EndpointConfigLoader.parseRemote(badHost) }
        val traversal = badHost.replace("example.com", "apis.trainheroic.com").replace("/auth", "/../auth")
        assertThrows(IllegalArgumentException::class.java) { EndpointConfigLoader.parseRemote(traversal) }
    }
}

