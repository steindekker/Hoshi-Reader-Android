package moe.antimony.hoshi.features.reader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Appearance", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        containerColor = appearanceBackground(settings),
    ) { padding ->
        ReaderAppearanceContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            fontManager = fontManager,
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 14.dp,
                bottom = 128.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = appearanceBackground(settings),
    ) {
        ReaderAppearanceContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            fontManager = fontManager,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            showDone = true,
            onDone = onDismiss,
        )
    }
}

@Composable
private fun ReaderAppearanceContent(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    showDone: Boolean = false,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importedFonts by remember { mutableStateOf(fontManager.storedFonts()) }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var fontToDelete by remember { mutableStateOf<String?>(null) }
    var isImportingFont by remember { mutableStateOf(false) }
    val fontImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            isImportingFont = true
            runCatching {
                withContext(Dispatchers.IO) {
                    fontManager.importFont(context.contentResolver, uri)
                }
            }
            importedFonts = fontManager.storedFonts()
            isImportingFont = false
        }
    }
    val fontOptions = remember(importedFonts, settings.selectedFont) {
        (ReaderFontManager.defaultFonts + importedFonts.map { it.name } + settings.selectedFont)
            .filter { it.isNotBlank() }
            .distinct()
    }
    val groupColor = appearanceGroupColor(settings)

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(appearanceBackground(settings)),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }
        item {
            AppearanceSection(title = "Theme", color = groupColor) {
                SegmentedRow(
                    label = "Appearance",
                    options = ReaderTheme.entries.map { it.label },
                    selected = settings.theme.label,
                    onSelected = { label ->
                        ReaderTheme.entries.firstOrNull { it.label == label }?.let {
                            onSettingsChange(settings.copy(theme = it))
                        }
                    },
                )
            }
        }
        item {
            AppearanceSection(title = "Text", color = groupColor) {
                SegmentedRow(
                    label = "Text Orientation",
                    options = listOf("縦", "横"),
                    selected = if (settings.verticalWriting) "縦" else "横",
                    onSelected = { label ->
                        onSettingsChange(settings.copy(verticalWriting = label == "縦"))
                    },
                )
                AppearanceDivider()
                ReaderFontRow(
                    settings = settings,
                    fontOptions = fontOptions,
                    fontMenuExpanded = fontMenuExpanded,
                    onFontMenuExpandedChange = { fontMenuExpanded = it },
                    onFontSelected = { fontName ->
                        fontMenuExpanded = false
                        onSettingsChange(settings.copy(selectedFont = fontName))
                    },
                    canDeleteFont = !fontManager.isDefaultFont(settings.selectedFont),
                    onDeleteFont = { fontToDelete = settings.selectedFont },
                )
                AppearanceDivider()
                ActionRow(
                    label = "Import Font",
                    button = if (isImportingFont) "Importing..." else "Import",
                    enabled = !isImportingFont,
                    onClick = { fontImporter.launch(fontMimeTypes) },
                )
                AppearanceDivider()
                StepperRow(
                    label = "Font Size",
                    value = settings.fontSize.toString(),
                    onDecrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(16))) },
                    onIncrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(40))) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Hide Furigana",
                    checked = settings.hideFurigana,
                    onCheckedChange = { onSettingsChange(settings.copy(hideFurigana = it)) },
                )
            }
        }
        item {
            AppearanceSection(title = "Layout", color = groupColor) {
                StepperRow(
                    label = "Horizontal Padding",
                    value = "${settings.horizontalPadding}%",
                    onDecrease = { onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding - 1).coerceAtLeast(0))) },
                    onIncrease = { onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding + 1).coerceAtMost(50))) },
                )
                AppearanceDivider()
                StepperRow(
                    label = "Vertical Padding",
                    value = "${settings.verticalPadding}%",
                    onDecrease = { onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding - 1).coerceAtLeast(0))) },
                    onIncrease = { onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding + 1).coerceAtMost(50))) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Avoid Page Break",
                    checked = settings.avoidPageBreak,
                    onCheckedChange = { onSettingsChange(settings.copy(avoidPageBreak = it)) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Justify Text",
                    checked = settings.justifyText,
                    onCheckedChange = { onSettingsChange(settings.copy(justifyText = it)) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Advanced",
                    checked = settings.layoutAdvanced,
                    onCheckedChange = { onSettingsChange(settings.copy(layoutAdvanced = it)) },
                )
                if (settings.layoutAdvanced) {
                    AppearanceDivider()
                    SliderRow(
                        label = "Line Height",
                        value = String.format(Locale.US, "%.2f", settings.lineHeight),
                        sliderValue = settings.lineHeight.toFloat(),
                        valueRange = 1.0f..2.5f,
                        steps = 29,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(lineHeight = round(value * 20) / 20.0))
                        },
                    )
                    AppearanceDivider()
                    SliderRow(
                        label = "Character Spacing",
                        value = "${settings.characterSpacing.toInt()}%",
                        sliderValue = settings.characterSpacing.toFloat(),
                        valueRange = -10f..10f,
                        steps = 19,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(characterSpacing = round(value).toDouble()))
                        },
                    )
                }
            }
        }
        item {
            AppearanceSection(title = "Display", color = groupColor) {
                SwitchRow(
                    label = "Show Title",
                    checked = settings.showTitle,
                    onCheckedChange = { onSettingsChange(settings.copy(showTitle = it)) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Show Character Count",
                    checked = settings.showCharacters,
                    onCheckedChange = { onSettingsChange(settings.copy(showCharacters = it)) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Show Percentage",
                    checked = settings.showPercentage,
                    onCheckedChange = { onSettingsChange(settings.copy(showPercentage = it)) },
                )
                if (settings.showCharacters || settings.showPercentage) {
                    AppearanceDivider()
                    SegmentedRow(
                        label = "Progress Position",
                        options = listOf("Top", "Bottom"),
                        selected = if (settings.showProgressTop) "Top" else "Bottom",
                        onSelected = { label -> onSettingsChange(settings.copy(showProgressTop = label == "Top")) },
                    )
                }
            }
        }
        item {
            AppearanceSection(title = "Popup", color = groupColor) {
                SliderRow(
                    label = "Width",
                    value = settings.popupWidth.toString(),
                    sliderValue = settings.popupWidth.toFloat(),
                    valueRange = 100f..700f,
                    steps = 59,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(popupWidth = (round(value / 10) * 10).toInt()))
                    },
                )
                AppearanceDivider()
                SliderRow(
                    label = "Height",
                    value = settings.popupHeight.toString(),
                    sliderValue = settings.popupHeight.toFloat(),
                    valueRange = 100f..500f,
                    steps = 39,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(popupHeight = (round(value / 10) * 10).toInt()))
                    },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Full-width",
                    checked = settings.popupFullWidth,
                    onCheckedChange = { onSettingsChange(settings.copy(popupFullWidth = it)) },
                )
                AppearanceDivider()
                SwitchRow(
                    label = "Swipe to Dismiss",
                    checked = settings.popupSwipeToDismiss,
                    onCheckedChange = { onSettingsChange(settings.copy(popupSwipeToDismiss = it)) },
                )
                if (settings.popupSwipeToDismiss) {
                    AppearanceDivider()
                    SliderRow(
                        label = "Swipe Threshold",
                        value = settings.popupSwipeThreshold.toString(),
                        sliderValue = settings.popupSwipeThreshold.toFloat(),
                        valueRange = 20f..80f,
                        steps = 11,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(popupSwipeThreshold = (round(value / 5) * 5).toInt()))
                        },
                    )
                }
            }
        }
        if (showDone) {
            item {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done")
                }
            }
        }
    }

    fontToDelete?.let { fontName ->
        AlertDialog(
            onDismissRequest = { fontToDelete = null },
            title = { Text("Delete \"$fontName\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        fontManager.deleteFont(fontName)
                        importedFonts = fontManager.storedFonts()
                        onSettingsChange(settings.copy(selectedFont = ReaderFontManager.defaultFonts.first()))
                        fontToDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fontToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private val fontMimeTypes = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

@Composable
private fun AppearanceSection(
    title: String,
    color: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = color,
            tonalElevation = 1.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SegmentedRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(option)
                }
            }
        }
    }
}

@Composable
private fun ReaderFontRow(
    settings: ReaderSettings,
    fontOptions: List<String>,
    fontMenuExpanded: Boolean,
    onFontMenuExpandedChange: (Boolean) -> Unit,
    onFontSelected: (String) -> Unit,
    canDeleteFont: Boolean,
    onDeleteFont: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Font", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { onFontMenuExpandedChange(true) }) {
                    Text(settings.selectedFont)
                }
                DropdownMenu(
                    expanded = fontMenuExpanded,
                    onDismissRequest = { onFontMenuExpandedChange(false) },
                ) {
                    fontOptions.forEach { fontName ->
                        DropdownMenuItem(
                            text = { Text(fontName) },
                            onClick = { onFontSelected(fontName) },
                        )
                    }
                }
            }
            if (canDeleteFont) {
                TextButton(onClick = onDeleteFont) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    button: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClick, enabled = enabled) {
            Text(button)
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = sliderValue.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFE2E1E7),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDecrease) {
                        Icon(
                            imageVector = Icons.Rounded.Remove,
                            contentDescription = "Decrease",
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 28.dp)
                            .background(Color(0xFF7D7A85).copy(alpha = 0.35f)),
                    )
                    IconButton(onClick = onIncrease) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Increase",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = Color(0xFFE4E2E8),
    )
}

@Composable
private fun appearanceBackground(settings: ReaderSettings): Color = when (settings.theme) {
    ReaderTheme.Dark -> Color(0xFF1E1E1E)
    ReaderTheme.Sepia -> Color(0xFFF6EBD8)
    ReaderTheme.Light, ReaderTheme.System -> Color(0xFFF7F6FA)
}

@Composable
private fun appearanceGroupColor(settings: ReaderSettings): Color = when (settings.theme) {
    ReaderTheme.Dark -> Color(0xFF2A2A2A)
    ReaderTheme.Sepia -> Color(0xFFFFF8EC)
    ReaderTheme.Light, ReaderTheme.System -> Color.White
}
