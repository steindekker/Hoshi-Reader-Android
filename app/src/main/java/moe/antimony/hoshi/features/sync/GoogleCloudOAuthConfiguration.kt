package moe.antimony.hoshi.features.sync

internal object GoogleCloudOAuthConfiguration {
    const val ttuSetupUrl: String = "https://github.com/Manhhao/Hoshi-Reader/blob/main/TTUSYNC.md"
    const val ttuSetupLinkLabel: String = "ッツ Sync guide"
    const val googleCloudConsoleUrl: String = "https://console.cloud.google.com/auth/clients"
    const val googleCloudConsoleLinkLabel: String = "Google Cloud Console"
    const val googleDeviceUrl: String = "https://www.google.com/device"
    const val googleDeviceLinkLabel: String = "Google device page"

    const val introduction: String =
        "Google Drive sync uses Device Code flow so this Android app can use the same user-owned Google Cloud project described in the ッツ Sync guide."

    val instructions: List<String> = listOf(
        "Follow the ッツ Sync guide to create or open the shared Google Cloud project, publish the OAuth consent screen, and enable the Google Drive API.",
        "In that same project, open Google Auth Platform -> Clients in the $googleCloudConsoleLinkLabel, click CREATE CLIENT, and select application type TVs and Limited Input devices.",
        "Copy the client ID and client secret from that TVs and Limited Input devices client and paste them here. Do not use the iOS bundle ID or a standard Android OAuth client for this flow.",
        "Press Connect Google Drive, copy the code shown by Hoshi, open the verification URL, and enter the code.",
        "If the verification URL is hard to use on this device, open the $googleDeviceLinkLabel on another phone or computer and enter the same code.",
        "Authorize the Google Account whose Drive contains the ッツ or Hoshi sync folder.",
    )

    val instructionLinks: Map<String, String> = mapOf(
        googleCloudConsoleLinkLabel to googleCloudConsoleUrl,
        googleDeviceLinkLabel to googleDeviceUrl,
    )
}
