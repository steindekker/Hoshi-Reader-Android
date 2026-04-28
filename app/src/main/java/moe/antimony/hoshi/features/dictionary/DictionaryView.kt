package moe.antimony.hoshi.features.dictionary

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DictionaryRepository(context.filesDir, context.cacheDir) }
    var dictionaries by remember { mutableStateOf<List<DictionaryInfo>>(emptyList()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        dictionaries = repository.loadDictionaries(DictionaryType.Term)
        runCatching { repository.rebuildLookupQuery() }
    }

    fun importDictionary(uri: Uri) {
        scope.launch {
            isImporting = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.importDictionary(context.contentResolver, uri, DictionaryType.Term)
                }
            }.onSuccess {
                reload()
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to import dictionary."
            }
            isImporting = false
        }
    }

    fun setDictionaryEnabled(dictionary: DictionaryInfo, enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.setDictionaryEnabled(DictionaryType.Term, dictionary.path.name, enabled)
            }
            reload()
        }
    }

    fun deleteDictionary(dictionary: DictionaryInfo) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteDictionary(DictionaryType.Term, dictionary.path.name)
            }
            reload()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        importDictionary(uri)
    }

    LaunchedEffect(Unit) {
        reload()
    }
    BackHandler(onBack = onClose)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Dictionaries") },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Text("‹")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !isImporting,
                    ) {
                        Text("+")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            isImporting -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            dictionaries.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(errorMessage ?: "No Dictionaries")
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                errorMessage?.let { item { Text(it) } }
                items(
                    items = dictionaries,
                    key = { it.path.name },
                ) { dictionary ->
                    DictionaryRow(
                        dictionary = dictionary,
                        onEnabledChange = { setDictionaryEnabled(dictionary, it) },
                        onDelete = { deleteDictionary(dictionary) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryRow(
    dictionary: DictionaryInfo,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(Color(0xFFB3261E))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("Delete", color = Color.White)
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(dictionary.index.title) },
            supportingContent = { Text(dictionary.index.revision) },
            trailingContent = {
                Switch(
                    checked = dictionary.isEnabled,
                    onCheckedChange = onEnabledChange,
                )
            },
        )
    }
}
