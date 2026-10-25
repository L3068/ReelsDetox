package dev.reelsguard.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServiceStatus(
    val connected: Boolean = false,
    val storageError: Boolean = false,
    val detection: String = "idle",
    val overlayVisible: Boolean = false,
    val navigationFailed: Boolean = false,
)

/** In-process liveness; never persisted, so a dead service cannot leave a green status. */
object RuntimeState {
    private val mutable = MutableStateFlow(ServiceStatus())
    val status = mutable.asStateFlow()
    internal fun publish(value: ServiceStatus) { mutable.value = value }
}
