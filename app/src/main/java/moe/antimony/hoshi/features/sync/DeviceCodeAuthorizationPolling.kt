package moe.antimony.hoshi.features.sync

import java.io.IOException

internal fun Throwable.isTransientDeviceCodePollingFailure(): Boolean = this is IOException

internal fun Throwable.toDeviceCodePollingFailureResult(): DriveAuthorizationResult =
    if (isTransientDeviceCodePollingFailure()) {
        DriveAuthorizationResult.TransientNetworkFailure
    } else {
        DriveAuthorizationResult.Failed(message ?: "Google Drive authorization failed.")
    }

internal fun nextDeviceCodePollIntervalSeconds(
    currentIntervalSeconds: Long,
    result: DriveAuthorizationResult,
): Long =
    when (result) {
        DriveAuthorizationResult.SlowDown -> currentIntervalSeconds + DeviceCodeDriveAuthorizer.SlowDownIncrementSeconds
        DriveAuthorizationResult.TransientNetworkFailure ->
            (currentIntervalSeconds * DeviceCodeDriveAuthorizer.TransientNetworkBackoffMultiplier)
                .coerceAtMost(DeviceCodeDriveAuthorizer.MaxTransientNetworkBackoffSeconds)
        else -> currentIntervalSeconds
    }
