package moe.antimony.hoshi.features.texthooker

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal enum class TextHookerStatusTone { Connected, Pending, Problem, Inactive }

internal data class TextHookerStatusPresentation(
    val title: UiText,
    val details: List<UiText>,
    val tone: TextHookerStatusTone,
    val canRetry: Boolean,
)

internal fun textHookerStatusPresentation(state: TextHookerUiState): TextHookerStatusPresentation {
    val address = state.settings.displayAddress
    return when (val connection = state.connection) {
        TextHookerConnectionState.NotConfigured -> TextHookerStatusPresentation(
            title = UiText.Resource(R.string.texthooker_status_not_configured),
            details = emptyList(),
            tone = TextHookerStatusTone.Inactive,
            canRetry = false,
        )
        TextHookerConnectionState.Idle,
        is TextHookerConnectionState.Connecting,
        -> TextHookerStatusPresentation(
            title = UiText.Resource(R.string.texthooker_status_connecting, address),
            details = emptyList(),
            tone = TextHookerStatusTone.Pending,
            canRetry = false,
        )
        TextHookerConnectionState.Connected -> TextHookerStatusPresentation(
            title = UiText.Resource(R.string.texthooker_status_connected, address),
            details = listOfNotNull(
                UiText.Resource(R.string.texthooker_no_text_source)
                    .takeIf { state.serverStatus?.hasNoConnectedSource == true },
            ),
            tone = TextHookerStatusTone.Connected,
            canRetry = false,
        )
        is TextHookerConnectionState.Disconnected -> TextHookerStatusPresentation(
            title = UiText.Resource(R.string.texthooker_status_disconnected, address),
            details = listOfNotNull(
                textHookerReasonText(connection.reason),
                connection.retryInMillis?.let {
                    UiText.Resource(R.string.texthooker_retry_in, ((it + 999) / 1000).toInt())
                },
            ),
            tone = TextHookerStatusTone.Problem,
            canRetry = true,
        )
    }
}

internal fun textHookerReasonText(reason: TextHookerDisconnectReason): UiText = when (reason) {
    TextHookerDisconnectReason.Unreachable -> UiText.Resource(R.string.texthooker_reason_unreachable)
    TextHookerDisconnectReason.Unauthorized -> UiText.Resource(R.string.texthooker_reason_unauthorized)
    is TextHookerDisconnectReason.HttpError -> UiText.Resource(R.string.texthooker_reason_http, reason.code)
    TextHookerDisconnectReason.ServerClosed -> UiText.Resource(R.string.texthooker_reason_closed)
    TextHookerDisconnectReason.InvalidResponse -> UiText.Resource(R.string.texthooker_reason_invalid_response)
    is TextHookerDisconnectReason.IncompatibleProtocol ->
        UiText.Resource(R.string.texthooker_reason_incompatible, reason.serverProtocol)
    TextHookerDisconnectReason.InvalidSettings -> UiText.Resource(R.string.texthooker_reason_invalid_settings)
}
