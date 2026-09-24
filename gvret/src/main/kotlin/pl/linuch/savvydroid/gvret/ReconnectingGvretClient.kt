package pl.linuch.savvydroid.gvret

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Wraps [GvretClient] with reconnect-with-backoff: keeps (re)connecting
 * forever until the collecting coroutine is cancelled. [state] tracks
 * what's currently happening for the UI; [events] is the actual decoded
 * frame/keepalive stream, transparently reconnecting underneath.
 */
class ReconnectingGvretClient(
    private val clientFactory: () -> GvretClient,
    private val initialBackoffMs: Long = 1000,
    private val maxBackoffMs: Long = 30_000,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun events(): Flow<GvretEvent> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            if (attempt == 0) {
                _state.value = ConnectionState.Connecting
            } else {
                val backoff = backoffFor(attempt)
                _state.value = ConnectionState.Reconnecting(attempt, backoff)
                delay(backoff)
            }

            var connectedOk = false
            try {
                clientFactory().run().collect { event ->
                    if (!connectedOk) {
                        connectedOk = true
                        attempt = 0
                        _state.value = ConnectionState.Connected
                    }
                    emit(event)
                }
            } catch (e: CancellationException) {
                _state.value = ConnectionState.Disconnected
                throw e
            } catch (e: Exception) {
                // Connect failure, read error, or keepalive timeout -- fall
                // through and retry with backoff below. Deliberately broad:
                // any exception from the underlying client is a reconnect
                // trigger, never a crash.
            }

            attempt++
            _state.value = ConnectionState.Disconnected
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 10)
        val exp = initialBackoffMs * (1L shl shift)
        return exp.coerceAtMost(maxBackoffMs)
    }
}
