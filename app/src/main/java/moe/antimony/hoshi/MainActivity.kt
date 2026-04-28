package moe.antimony.hoshi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.antimony.hoshi.features.bookshelf.BookshelfView
import moe.antimony.hoshi.features.reader.ReaderSettingsStore
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme

class MainActivity : ComponentActivity() {
    private var pendingImportUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri = intent.importUri()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val readerSettingsStore = remember { ReaderSettingsStore(this) }
            var readerSettings by remember { mutableStateOf(readerSettingsStore.load()) }
            val systemDark = isSystemInDarkTheme()
            HoshiReaderTheme(darkTheme = readerSettings.usesDarkInterface(systemDark)) {
                BookshelfView(
                    pendingImportUri = pendingImportUri,
                    onPendingImportConsumed = { pendingImportUri = null },
                    readerSettings = readerSettings,
                    onReaderSettingsChange = { settings ->
                        readerSettings = settings
                        readerSettingsStore.save(settings)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingImportUri = intent.importUri()
    }

    private fun Intent?.importUri(): Uri? =
        this?.data?.takeIf { action == Intent.ACTION_VIEW }
}
