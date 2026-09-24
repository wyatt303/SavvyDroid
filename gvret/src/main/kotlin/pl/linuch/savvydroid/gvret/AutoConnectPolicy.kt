package pl.linuch.savvydroid.gvret

/**
 * Decides whether a freshly discovered device should be connected to
 * automatically. Kept as plain logic (no Android, no coroutines) so it is
 * unit-tested on the JVM.
 *
 * Auto-connect is on by default. A user pressing Disconnect turns it off, so
 * the app does not immediately reconnect against their wishes; pressing
 * Connect turns it back on. Only an idle client is ever connected -- a
 * connection that is already up, in progress or backing off is left alone.
 */
class AutoConnectPolicy(initiallyEnabled: Boolean = true) {

    @Volatile
    var enabled: Boolean = initiallyEnabled
        private set

    fun onUserConnect() {
        enabled = true
    }

    fun onUserDisconnect() {
        enabled = false
    }

    fun shouldConnect(state: ConnectionState): Boolean =
        enabled && state == ConnectionState.Disconnected
}
