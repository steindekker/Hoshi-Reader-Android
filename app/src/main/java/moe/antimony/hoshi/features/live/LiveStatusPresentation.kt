package moe.antimony.hoshi.features.live

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal enum class LiveStatusTone { Connected, Pending, Problem, Inactive }

internal data class LiveStatusPresentation(
    val title: UiText,
    val details: List<UiText>,
    val tone: LiveStatusTone,
    val canRetry: Boolean,
)

/** The header status: the active session's host, or a summary of all hosts while none streams. */
internal fun liveStatusPresentation(state: LiveUiState): LiveStatusPresentation {
    if (!state.settings.isConfigured) {
        return LiveStatusPresentation(UiText.Resource(R.string.live_status_not_configured), emptyList(), LiveStatusTone.Inactive, false)
    }
    val activeHost = state.activeSession?.let { session -> state.hosts.firstOrNull { it.host.id == session.hostId } }
    val hosts = activeHost?.let(::listOf) ?: state.hosts
    val connected = hosts.filter { it.connection == LiveConnectionState.Connected }
    val connecting = hosts.filter { it.connection is LiveConnectionState.Connecting }
    val disconnected = hosts.filter { it.connection is LiveConnectionState.Disconnected }
    fun names(list: List<LiveHostState>) = list.joinToString(", ") { it.displayName }
    return when {
        connected.isNotEmpty() -> LiveStatusPresentation(
            title = UiText.Resource(R.string.live_status_connected, names(connected)),
            details = emptyList(),
            tone = LiveStatusTone.Connected,
            canRetry = disconnected.isNotEmpty(),
        )
        connecting.isNotEmpty() || hosts.isEmpty() -> LiveStatusPresentation(
            title = UiText.Resource(R.string.live_status_connecting, names(connecting.ifEmpty { state.hosts })),
            details = emptyList(),
            tone = LiveStatusTone.Pending,
            canRetry = false,
        )
        else -> {
            val first = disconnected.first().connection as LiveConnectionState.Disconnected
            LiveStatusPresentation(
                title = UiText.Resource(R.string.live_status_disconnected, names(disconnected)),
                details = listOfNotNull(
                    liveReasonText(first.reason),
                    first.retryInMillis?.let { UiText.Resource(R.string.live_retry_in, ((it + 999) / 1000).toInt()) },
                ),
                tone = LiveStatusTone.Problem,
                canRetry = true,
            )
        }
    }
}

internal fun liveReasonText(reason: LiveDisconnectReason): UiText = when (reason) {
    LiveDisconnectReason.Unreachable -> UiText.Resource(R.string.live_reason_unreachable)
    LiveDisconnectReason.Unauthorized -> UiText.Resource(R.string.live_reason_unauthorized)
    is LiveDisconnectReason.HttpError -> UiText.Resource(R.string.live_reason_http, reason.code)
    LiveDisconnectReason.ServerClosed -> UiText.Resource(R.string.live_reason_closed)
    LiveDisconnectReason.InvalidResponse -> UiText.Resource(R.string.live_reason_invalid_response)
    is LiveDisconnectReason.IncompatibleProtocol -> UiText.Resource(R.string.live_reason_incompatible, reason.serverProtocol)
    LiveDisconnectReason.InvalidSettings -> UiText.Resource(R.string.live_reason_invalid_settings)
}
