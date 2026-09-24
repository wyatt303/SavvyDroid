package pl.linuch.savvydroid.gvret

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val attempt: Int, val delayMs: Long) : ConnectionState
}
