package pl.linuch.savvydroid.gvret

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoConnectPolicyTest {

    @Test
    fun `connects to a discovered device when idle and enabled`() {
        assertTrue(AutoConnectPolicy().shouldConnect(ConnectionState.Disconnected))
    }

    @Test
    fun `never interrupts a connection that is up, starting or backing off`() {
        val policy = AutoConnectPolicy()
        assertFalse(policy.shouldConnect(ConnectionState.Connected))
        assertFalse(policy.shouldConnect(ConnectionState.Connecting))
        assertFalse(policy.shouldConnect(ConnectionState.Reconnecting(attempt = 3, delayMs = 4000)))
    }

    @Test
    fun `user disconnect switches auto-connect off until the user connects again`() {
        val policy = AutoConnectPolicy()
        policy.onUserDisconnect()
        assertFalse(policy.shouldConnect(ConnectionState.Disconnected))
        policy.onUserConnect()
        assertTrue(policy.shouldConnect(ConnectionState.Disconnected))
    }

    @Test
    fun `can start disabled`() {
        assertFalse(AutoConnectPolicy(initiallyEnabled = false).shouldConnect(ConnectionState.Disconnected))
    }
}
