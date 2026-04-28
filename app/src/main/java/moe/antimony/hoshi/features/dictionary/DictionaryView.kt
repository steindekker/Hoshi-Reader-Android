package moe.antimony.hoshi.features.dictionary

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    val settingsStore = remember { DictionarySettingsStore(context) }
    var selectedType by remember { mutableStateOf(DictionaryType.Term) }
    var importType by remember { mutableStateOf(DictionaryType.Term) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<DictionaryDestination?>(null) }
    var dictionaries by remember { mutableStateOf<Map<DictionaryType, List<DictionaryInfo>>>(emptyMap()) }
    var dictionarySettings by remember { mutableStateOf(settingsStore.load()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        dictionaries = DictionaryType.entries.associateWith { repository.loadDictionaries(it) }
        runCatching { repository.rebuildLookupQuery() }
    }

    fun importDictionaries(uris: List<Uri>, type: DictionaryType) {
        scope.launch {
            isImporting = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        repository.importDictionary(context.contentResolver, uri, type)
                    }
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
                repository.setDictionaryEnabled(selectedType, dictionary.path.name, enabled)
            }
            reload()
        }
    }

    fun deleteDictionary(dictionary: DictionaryInfo) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteDictionary(selectedType, dictionary.path.name)
            }
            reload()
        }
    }

    fun moveDictionary(fromIndex: Int, toIndex: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.moveDictionary(selectedType, fromIndex, toIndex)
            }
            reload()
        }
    }

    fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        dictionarySettings = transform(dictionarySettings).normalized()
        settingsStore.save(dictionarySettings)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        importDictionaries(uris, importType)
    }

    val currentDictionaries = dictionaries[selectedType].orEmpty()
    val listState = rememberLazyListState()
    var draggedFileName by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragStartTop by remember { mutableFloatStateOf(0f) }
    var dragStartHeight by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dictionaryStartGlobalIndex = 1 + if (errorMessage != null) 1 else 0
    val dragTargetIndex by remember(
        listState,
        currentDictionaries,
        dictionaryStartGlobalIndex,
        dragStartIndex,
        dragStartTop,
        dragStartHeight,
        dragOffsetY,
    ) {
        derivedStateOf {
            if (dragStartIndex !in currentDictionaries.indices) {
                dragStartIndex
            } else {
                val visibleRows = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    val dictionaryIndex = item.index - dictionaryStartGlobalIndex
                    if (dictionaryIndex in currentDictionaries.indices) {
                        DictionaryDragReorder.RowBounds(
                            index = dictionaryIndex,
                            top = item.offset.toFloat(),
                            bottom = (item.offset + item.size).toFloat(),
                        )
                    } else {
                        null
                    }
                }
                DictionaryDragReorder.targetIndex(
                    startIndex = dragStartIndex,
                    draggedCenterY = dragStartTop + dragStartHeight / 2f + dragOffsetY,
                    visibleRows = visibleRows,
                )
            }
        }
    }

    fun resetDrag() {
        draggedFileName = null
        dragStartIndex = -1
        dragStartTop = 0f
        dragStartHeight = 0f
        dragOffsetY = 0f
    }

    fun startDrag(index: Int, fileName: String) {
        val globalIndex = dictionaryStartGlobalIndex + index
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == globalIndex } ?: return
        draggedFileName = fileName
        dragStartIndex = index
        dragStartTop = item.offset.toFloat()
        dragStartHeight = item.size.toFloat()
        dragOffsetY = 0f
    }

    LaunchedEffect(Unit) {
        reload()
    }

    when (destination) {
        DictionaryDestination.Settings -> {
            DictionarySettingsView(
                settings = dictionarySettings,
                onSettingsChange = ::updateSettings,
                onClose = { destination = null },
                modifier = modifier,
            )
            return
        }
        DictionaryDestination.CustomCss -> {
            DictionaryCustomCssView(
                settings = dictionarySettings,
                onSettingsChange = ::updateSettings,
                onClose = { destination = null },
                modifier = modifier,
            )
            return
        }
        null -> Unit
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
                    TextButton(onClick = { destination = DictionaryDestination.CustomCss }) {
                        Text("{}")
                    }
                    Box {
                        TextButton(
                            onClick = { importMenuExpanded = true },
                            enabled = !isImporting,
                        ) {
                            Text("+")
                        }
                        DropdownMenu(
                            expanded = importMenuExpanded,
                            onDismissRequest = { importMenuExpanded = false },
                        ) {
                            DictionaryType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName) },
                                    onClick = {
                                        importMenuExpanded = false
                                        importType = type
                                        importer.launch(zipMimeTypes)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            color = Color.White,
                        ) {
                            Column {
                                ListItem(
                                    headlineContent = { Text("Default to Dictionary Tab") },
                                    trailingContent = {
                                        Switch(
                                            checked = dictionarySettings.dictionaryTabDefault,
                                            onCheckedChange = { checked ->
                                                updateSettings { it.copy(dictionaryTabDefault = checked) }
                                            },
                                        )
                                    },
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                ListItem(
                                    headlineContent = { Text("Settings") },
                                    trailingContent = { Text("›", color = Color(0xFF8E8E93)) },
                                    modifier = Modifier.clickable { destination = DictionaryDestination.Settings },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            DictionaryType.entries.forEachIndexed { index, type ->
                                SegmentedButton(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = DictionaryType.entries.size,
                                    ),
                                ) {
                                    Text(type.displayName)
                                }
                            }
                        }
                        Text(
                            text = "Yomitan term, frequency and pitch dictionaries (.zip) are supported",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                errorMessage?.let { item { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                if (currentDictionaries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No ${selectedType.displayName} Dictionaries")
                        }
                    }
                } else {
                    itemsIndexed(
                        items = currentDictionaries,
                        key = { _, dictionary -> dictionary.path.name },
                    ) { index, dictionary ->
                        val isDragging = draggedFileName == dictionary.path.name
                        DictionaryRow(
                            dictionary = dictionary,
                            onEnabledChange = { setDictionaryEnabled(dictionary, it) },
                            onDelete = { deleteDictionary(dictionary) },
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                    shadowElevation = if (isDragging) 16f else 0f
                                },
                            onDragStart = { startDrag(index, dictionary.path.name) },
                            onDrag = { delta ->
                                dragOffsetY += delta
                            },
                            onDragEnd = {
                                if (dragStartIndex in currentDictionaries.indices &&
                                    dragTargetIndex in currentDictionaries.indices &&
                                    dragTargetIndex != dragStartIndex
                                ) {
                                    moveDictionary(dragStartIndex, dragTargetIndex)
                                }
                                resetDrag()
                            },
                        )
                    }
                }
            }
            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

private enum class DictionaryDestination {
    Settings,
    CustomCss,
}

private val zipMimeTypes = arrayOf(
    "application/zip",
    "application/octet-stream",
    "application/x-zip-compressed",
)

private val DictionaryType.displayName: String
    get() = when (this) {
        DictionaryType.Term -> "Term"
        DictionaryType.Frequency -> "Frequency"
        DictionaryType.Pitch -> "Pitch"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryRow(
    dictionary: DictionaryInfo,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
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
        modifier = modifier,
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
            modifier = Modifier.pointerInput(dictionary.path.name) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { _: Offset -> onDragStart() },
                    onDragCancel = onDragEnd,
                    onDragEnd = onDragEnd,
                    onDrag = { _, dragAmount ->
                        onDrag(dragAmount.y)
                    },
                )
            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionarySettingsView(
    settings: DictionarySettings,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Text("‹")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F5F8))
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SectionLabel("Lookup")
                SettingsGroup {
                    StepperRow(
                        title = "Max Results",
                        value = settings.maxResults,
                        onDecrease = {
                            onSettingsChange { it.copy(maxResults = it.maxResults - 1) }
                        },
                        onIncrease = {
                            onSettingsChange { it.copy(maxResults = it.maxResults + 1) }
                        },
                        canDecrease = settings.maxResults > DictionarySettings.MIN_MAX_RESULTS,
                        canIncrease = settings.maxResults < DictionarySettings.MAX_MAX_RESULTS,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    StepperRow(
                        title = "Scan Length",
                        value = settings.scanLength,
                        onDecrease = {
                            onSettingsChange { it.copy(scanLength = it.scanLength - 1) }
                        },
                        onIncrease = {
                            onSettingsChange { it.copy(scanLength = it.scanLength + 1) }
                        },
                        canDecrease = settings.scanLength > DictionarySettings.MIN_SCAN_LENGTH,
                        canIncrease = settings.scanLength < DictionarySettings.MAX_SCAN_LENGTH,
                    )
                }
                SectionLabel("Behaviour")
                SettingsGroup {
                    ToggleRow("Auto-collapse Dictionaries", settings.collapseDictionaries) {
                        onSettingsChange { current -> current.copy(collapseDictionaries = it) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow("Compact Glossaries", settings.compactGlossaries) {
                        onSettingsChange { current -> current.copy(compactGlossaries = it) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow("Show Expression Tags", settings.showExpressionTags) {
                        onSettingsChange { current -> current.copy(showExpressionTags = it) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow("Harmonic Frequency", settings.harmonicFrequency) {
                        onSettingsChange { current -> current.copy(harmonicFrequency = it) }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleRow("Deduplicate Pitch Accents", settings.deduplicatePitchAccents) {
                        onSettingsChange { current -> current.copy(deduplicatePitchAccents = it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryCustomCssView(
    settings: DictionarySettings,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Custom CSS") },
                navigationIcon = {
                    TextButton(onClick = onClose) {
                        Text("‹")
                    }
                },
                actions = {
                    TextButton(onClick = { onSettingsChange { it.copy(customCSS = "") } }) {
                        Text("Reset")
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
        ) {
            BasicTextField(
                value = settings.customCSS,
                onValueChange = { value ->
                    onSettingsChange { it.copy(customCSS = value) }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF8E8E93),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
    ) {
        Column(content = content)
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value.toString())
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFFD1D1D6),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDecrease, enabled = canDecrease) {
                            Text("−")
                        }
                        TextButton(onClick = onIncrease, enabled = canIncrease) {
                            Text("+")
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}
