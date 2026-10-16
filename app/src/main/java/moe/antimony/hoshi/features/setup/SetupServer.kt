package moe.antimony.hoshi.features.setup

import hoshi.card.setup.SetupServerConnections
import moe.antimony.hoshi.BuildConfig

/**
 * Connections for this build's setup server: requests to the catalog's host carry the build's
 * `X-Hoshi-Token` (from `HOSHI_SETUP_TOKEN`); other hosts never see it.
 */
internal fun hoshiSetupConnections(): SetupServerConnections =
    SetupServerConnections(catalogUrl = SetupCatalogConfig.DefaultUrl, token = BuildConfig.HOSHI_SETUP_TOKEN)
