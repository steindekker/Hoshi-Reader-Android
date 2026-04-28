package moe.antimony.hoshi.features.bookshelf

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.dictionary.DictionaryView
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderSettingsStore
import moe.antimony.hoshi.features.reader.ReaderWebView
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfView(
    pendingImportUri: Uri? = null,
    onPendingImportConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookStorage = remember { BookStorage(context.filesDir) }
    val dictionaryRepository = remember { DictionaryRepository(context.filesDir, context.cacheDir) }
    val readerSettingsStore = remember { ReaderSettingsStore(context) }
    var readerSettings by remember { mutableStateOf(readerSettingsStore.load()) }
    var selectedTab by remember { mutableStateOf(MainTab.Books) }
    var settingsDestination by remember { mutableStateOf<SettingsDestination?>(null) }
    var bookEntries by remember { mutableStateOf<List<BookEntry>>(emptyList()) }
    var sortOption by remember { mutableStateOf(BookSortOption.Recent) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuEntry by remember { mutableStateOf<BookEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var selectedBookRoot by remember { mutableStateOf<File?>(null) }
    var book by remember { mutableStateOf<EpubBook?>(null) }
    var bookmark by remember { mutableStateOf<Bookmark?>(null) }
    var isReading by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reloadBookEntries() {
        bookEntries = bookStorage.loadBookEntries(sortOption)
    }

    fun saveMetadata(root: File, parsedBook: EpubBook, previous: BookMetadata? = null) {
        val metadata = BookMetadata(
            id = previous?.id ?: root.name,
            title = parsedBook.title,
            cover = parsedBook.coverHref,
            folder = root.name,
            lastAccess = bookStorage.currentAppleReferenceDateSeconds(),
        )
        bookStorage.saveMetadata(root, metadata)
    }

    fun saveBookInfo(root: File, parsedBook: EpubBook) {
        bookStorage.saveBookInfo(root, parsedBook.bookInfo)
    }

    fun parseBook(file: File, openReader: Boolean, refreshAccess: Boolean) {
        scope.launch {
            isLoading = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val parsedBook = EpubBookParser().parse(file)
                    if (refreshAccess) {
                        saveMetadata(file, parsedBook, bookStorage.loadMetadata(file))
                    }
                    saveBookInfo(file, parsedBook)
                    parsedBook
                }
            }.onSuccess { parsedBook ->
                selectedBookRoot = file
                book = parsedBook
                bookmark = bookStorage.loadBookmark(file)
                reloadBookEntries()
                isReading = openReader
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to open EPUB."
            }
            isLoading = false
        }
    }

    fun importBook(uri: Uri) {
        scope.launch {
            isLoading = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = bookStorage.importBook(context.contentResolver, uri)
                    val parsedBook = EpubBookParser().parse(root)
                    saveMetadata(root, parsedBook, bookStorage.loadMetadata(root))
                    saveBookInfo(root, parsedBook)
                    root to parsedBook
                }
            }.onSuccess { (root, parsedBook) ->
                selectedBookRoot = root
                book = parsedBook
                bookmark = bookStorage.loadBookmark(root)
                reloadBookEntries()
                selectedTab = MainTab.Books
                isReading = true
                isLoading = false
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to import EPUB."
                isLoading = false
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            importBook(uri)
        }
    }

    fun launchBookImporter() {
        importer.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }

    LaunchedEffect(Unit) {
        reloadBookEntries()
        withContext(Dispatchers.IO) {
            runCatching { dictionaryRepository.rebuildLookupQuery() }
        }
    }

    LaunchedEffect(pendingImportUri) {
        val uri = pendingImportUri ?: return@LaunchedEffect
        onPendingImportConsumed()
        importBook(uri)
    }

    if (isReading && book != null) {
        ReaderWebView(
            book = requireNotNull(book),
            initialChapterIndex = bookmark?.chapterIndex ?: 0,
            initialProgress = bookmark?.progress ?: 0.0,
            readerSettings = readerSettings,
            onReaderSettingsChange = { settings: ReaderSettings ->
                readerSettings = settings
                readerSettingsStore.save(settings)
            },
            onSaveBookmark = { chapterIndex, progress ->
                val file = selectedBookRoot ?: return@ReaderWebView
                val parsedBook = book ?: return@ReaderWebView
                val savedBookmark = Bookmark(
                    chapterIndex = chapterIndex,
                    progress = progress,
                    characterCount = parsedBook.characterCountAt(chapterIndex, progress),
                    lastModified = bookStorage.currentAppleReferenceDateSeconds(),
                )
                bookmark = savedBookmark
                scope.launch(Dispatchers.IO) {
                    bookStorage.saveBookmark(file, savedBookmark)
                }
            },
            onClose = { isReading = false },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (settingsDestination == SettingsDestination.Dictionaries) {
        DictionaryView(
            onClose = { settingsDestination = null },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    HoshiMainShell(
        selectedTab = selectedTab,
        onSelectedTabChange = {
            selectedTab = it
            settingsDestination = null
        },
        modifier = modifier,
    ) { contentModifier ->
        when (selectedTab) {
            MainTab.Books -> BooksTab(
                modifier = contentModifier,
                bookEntries = bookEntries,
                bookStorage = bookStorage,
                isLoading = isLoading,
                errorMessage = errorMessage,
                sortOption = sortOption,
                sortMenuExpanded = sortMenuExpanded,
                onSortMenuExpandedChange = { sortMenuExpanded = it },
                onSortChange = {
                    sortOption = it
                    sortMenuExpanded = false
                    reloadBookEntries()
                },
                onImport = ::launchBookImporter,
                onOpenBook = { parseBook(it.root, openReader = true, refreshAccess = true) },
                contextMenuEntry = contextMenuEntry,
                onContextMenuEntryChange = { contextMenuEntry = it },
                onDeleteCandidate = { deleteCandidate = it },
            )
            MainTab.Dictionary -> DictionaryView(
                onClose = { selectedTab = MainTab.Books },
                modifier = contentModifier.fillMaxSize(),
            )
            MainTab.Settings -> SettingsTab(
                modifier = contentModifier,
                onDestination = { destination ->
                    when (destination) {
                        SettingsDestination.Dictionaries -> settingsDestination = destination
                        SettingsDestination.ReportIssue -> context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Manhhao/Hoshi-Reader/issues"),
                            ),
                        )
                        else -> settingsDestination = destination
                    }
                },
            )
        }
    }

    settingsDestination?.takeIf { it != SettingsDestination.Dictionaries }?.let { destination ->
        AlertDialog(
            onDismissRequest = { settingsDestination = null },
            title = { Text(destination.placeholderTitle()) },
            text = { Text("This settings page is not implemented yet.") },
            confirmButton = {
                TextButton(onClick = { settingsDestination = null }) {
                    Text("OK")
                }
            },
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete \"${candidate.metadata.title ?: ""}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            bookStorage.deleteBook(candidate.root)
                            withContext(Dispatchers.Main) {
                                if (selectedBookRoot == candidate.root) {
                                    selectedBookRoot = null
                                    book = null
                                    bookmark = null
                                    isReading = false
                                }
                                reloadBookEntries()
                                deleteCandidate = null
                            }
                        }
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun HoshiMainShell(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F8)),
    ) {
        content(Modifier.fillMaxSize())
        HoshiBottomTabs(
            selectedTab = selectedTab,
            onSelectedTabChange = onSelectedTabChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun BooksTab(
    bookEntries: List<BookEntry>,
    bookStorage: BookStorage,
    isLoading: Boolean,
    errorMessage: String?,
    sortOption: BookSortOption,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    onImport: () -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    contextMenuEntry: BookEntry?,
    onContextMenuEntryChange: (BookEntry?) -> Unit,
    onDeleteCandidate: (BookEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(bookEntries) { bookshelfSections(bookEntries) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            bookEntries.isEmpty() -> EmptyBooksView(
                errorMessage = errorMessage,
                onImport = onImport,
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 158.dp, bottom = 164.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                sections.forEach { section ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        BookshelfSectionHeader(
                            title = section.title,
                            count = section.books.size,
                        )
                    }
                    items(
                        items = section.books,
                        key = { it.metadata.id },
                    ) { entry ->
                        Box {
                            BookGridCell(
                                entry = entry,
                                bookStorage = bookStorage,
                                onOpen = { onOpenBook(entry) },
                                onLongPress = { onContextMenuEntryChange(entry) },
                            )
                            DropdownMenu(
                                expanded = contextMenuEntry?.metadata?.id == entry.metadata.id,
                                onDismissRequest = { onContextMenuEntryChange(null) },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        contextMenuEntry?.let(onDeleteCandidate)
                                        onContextMenuEntryChange(null)
                                    },
                                )
                            }
                        }
                    }
                }
                errorMessage?.let { message ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        BooksTopChrome(
            sortOption = sortOption,
            sortMenuExpanded = sortMenuExpanded,
            onSortMenuExpandedChange = onSortMenuExpandedChange,
            onSortChange = onSortChange,
            onImport = onImport,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun BooksTopChrome(
    sortOption: BookSortOption,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 18.dp, start = 28.dp, end = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrostedCapsule {
            Box {
                ChromeIconButton(onClick = { onSortMenuExpandedChange(true) }) {
                    SortGlyph()
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { onSortMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("Recent") },
                        onClick = { onSortChange(BookSortOption.Recent) },
                    )
                    DropdownMenuItem(
                        text = { Text("Title") },
                        onClick = { onSortChange(BookSortOption.Title) },
                    )
                }
            }
            ChromeIconButton(onClick = { onSortChange(if (sortOption == BookSortOption.Recent) BookSortOption.Title else BookSortOption.Recent) }) {
                ListCheckGlyph()
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Books",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        FrostedCircle(onClick = {}) {
            FolderGearGlyph()
        }
        Spacer(Modifier.width(14.dp))
        FrostedCircle(onClick = onImport) {
            PlusGlyph()
        }
    }
}

@Composable
private fun BookshelfSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color.Black,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF8C8C92),
        )
        Spacer(Modifier.width(8.dp))
        ChevronRightGlyph(Color.Black, Modifier.size(24.dp))
    }
}

@Composable
private fun BookGridCell(
    entry: BookEntry,
    bookStorage: BookStorage,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = onLongPress,
        ),
    ) {
        BookCoverCard(entry = entry, bookStorage = bookStorage)
        Spacer(Modifier.height(10.dp))
        Text(
            text = entry.metadata.title ?: entry.root.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookCoverCard(entry: BookEntry, bookStorage: BookStorage) {
    val coverFile = remember(entry) { bookStorage.coverFile(entry) }
    val bitmap = remember(coverFile) {
        coverFile?.absolutePath?.let(BitmapFactory::decodeFile)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ReadingProgressPill(
            progress = remember(entry) { bookStorage.loadReadingProgress(entry.root) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ReadingProgressPill(progress: Double, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0.0, 1.0).toFloat()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(5.dp)
                .weight(1f)
                .clip(RoundedCornerShape(100))
                .background(Color.White.copy(alpha = 0.75f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.04f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(100))
                    .background(Color(0xFFB7B7B7)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(clamped * 100).coerceAtMost(99.9f).formatOneDecimal()}%",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF76767C),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SettingsTab(
    onDestination: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F8)),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 112.dp, bottom = 230.dp),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = Color.Black,
            )
        }
        settingsGroups().forEach { group ->
            item {
                SettingsGroupCard(rows = group, onDestination = onDestination)
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    rows: List<SettingsRowModel>,
    onDestination: (SettingsDestination) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            rows.forEachIndexed { index, row ->
                SettingsRow(row = row, onClick = { onDestination(row.destination) })
                if (index != rows.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(start = 94.dp, end = 34.dp)
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(Color(0xFFE3E3E6)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(row: SettingsRowModel, onClick: () -> Unit) {
    val tint = if (row.destination == SettingsDestination.ReportIssue) Color(0xFF007AFF) else Color.Black
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(row.destination, tint, Modifier.size(32.dp))
        Spacer(Modifier.width(26.dp))
        Text(
            text = row.label,
            style = MaterialTheme.typography.headlineSmall,
            color = tint,
        )
    }
}

@Composable
private fun HoshiBottomTabs(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 20.dp)
            .shadow(22.dp, RoundedCornerShape(38.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(38.dp),
        color = Color.White.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)),
    ) {
        Row(
            modifier = Modifier.padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MainTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val interactionSource = remember(tab) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(34.dp))
                        .background(if (selected) Color(0xFFDADDE3).copy(alpha = 0.75f) else Color.Transparent)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelectedTabChange(tab) },
                        )
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BottomTabGlyph(tab, selected, Modifier.size(34.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = Color.Black,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrostedCapsule(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(34.dp),
        color = Color.White.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun FrostedCircle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .size(66.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)),
        shadowElevation = 12.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun ChromeIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun EmptyBooksView(
    errorMessage: String?,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No Books",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Import an EPUB using the + button to start reading.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = onImport) {
                Text("Import EPUB")
            }
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(18.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BottomTabGlyph(tab: MainTab, selected: Boolean, modifier: Modifier = Modifier) {
    val color = if (selected) Color.Black else Color(0xFF111111)
    when (tab) {
        MainTab.Books -> BooksGlyph(color, modifier)
        MainTab.Dictionary -> DictionaryGlyph(color, modifier)
        MainTab.Settings -> GearGlyph(color, modifier)
    }
}

@Composable
private fun SettingsGlyph(destination: SettingsDestination, color: Color, modifier: Modifier = Modifier) {
    when (destination) {
        SettingsDestination.Dictionaries -> DictionaryBookGlyph(color, modifier)
        SettingsDestination.Anki -> TrayGlyph(color, modifier)
        SettingsDestination.Appearance -> PaletteGlyph(color, modifier)
        SettingsDestination.Advanced -> DoubleGearGlyph(color, modifier)
        SettingsDestination.ReportIssue -> BubbleGlyph(color, modifier)
        SettingsDestination.About -> InfoGlyph(color, modifier)
    }
}

@Composable
private fun BooksGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        listOf(0.22f, 0.45f, 0.68f).forEach { x ->
            drawLine(color, Offset(w * x, h * 0.18f), Offset(w * x, h * 0.82f), stroke.width, StrokeCap.Round)
            drawLine(color, Offset(w * (x + 0.12f), h * 0.82f), Offset(w * (x + 0.12f), h * 0.32f), stroke.width, StrokeCap.Round)
        }
    }
}

@Composable
private fun DictionaryGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = size.minDimension * 0.28f, center = Offset(size.width * 0.38f, size.height * 0.42f), style = stroke)
        drawLine(color, Offset(size.width * 0.55f, size.height * 0.58f), Offset(size.width * 0.78f, size.height * 0.78f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.25f, size.height * 0.45f), Offset(size.width * 0.60f, size.height * 0.30f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.30f, size.height * 0.65f), Offset(size.width * 0.67f, size.height * 0.18f), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun GearGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = size.minDimension * 0.20f, center = c, style = stroke)
        for (i in 0 until 8) {
            val angle = Math.PI * 2 * i / 8
            val start = Offset(
                x = c.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.32f,
                y = c.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.32f,
            )
            val end = Offset(
                x = c.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.42f,
                y = c.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.42f,
            )
            drawLine(color, start, end, stroke.width, StrokeCap.Round)
        }
    }
}

@Composable
private fun SortGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.30f, size.height * 0.18f), Offset(size.width * 0.30f, size.height * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.16f, size.height * 0.32f), Offset(size.width * 0.30f, size.height * 0.18f), stroke.width, StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.44f, size.height * 0.32f), Offset(size.width * 0.30f, size.height * 0.18f), stroke.width, StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.70f, size.height * 0.18f), Offset(size.width * 0.70f, size.height * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.56f, size.height * 0.68f), Offset(size.width * 0.70f, size.height * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.84f, size.height * 0.68f), Offset(size.width * 0.70f, size.height * 0.82f), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun ListCheckGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        listOf(0.30f, 0.62f).forEach { y ->
            drawCircle(Color.Black, radius = 4.dp.toPx(), center = Offset(size.width * 0.24f, size.height * y), style = stroke)
            drawLine(Color.Black, Offset(size.width * 0.42f, size.height * y), Offset(size.width * 0.82f, size.height * y), stroke.width, StrokeCap.Round)
        }
        drawLine(Color.Black, Offset(size.width * 0.19f, size.height * 0.30f), Offset(size.width * 0.23f, size.height * 0.35f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.23f, size.height * 0.35f), Offset(size.width * 0.31f, size.height * 0.23f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun FolderGearGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.34f)
            lineTo(size.width * 0.38f, size.height * 0.34f)
            lineTo(size.width * 0.46f, size.height * 0.44f)
            lineTo(size.width * 0.86f, size.height * 0.44f)
            lineTo(size.width * 0.86f, size.height * 0.80f)
            lineTo(size.width * 0.12f, size.height * 0.80f)
            close()
        }
        drawPath(path, Color.Black, style = stroke)
        drawCircle(Color.Black, size.minDimension * 0.13f, Offset(size.width * 0.78f, size.height * 0.28f), style = stroke)
    }
}

@Composable
private fun PlusGlyph() {
    Canvas(Modifier.size(38.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), stroke.width, StrokeCap.Round)
        drawLine(Color.Black, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun ChevronRightGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * 0.35f, size.height * 0.18f)
            lineTo(size.width * 0.66f, size.height * 0.50f)
            lineTo(size.width * 0.35f, size.height * 0.82f)
        }
        drawPath(path, color, style = stroke)
    }
}

@Composable
private fun DictionaryBookGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(size.width * 0.18f, size.height * 0.12f), size = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.76f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = stroke)
        drawLine(color, Offset(size.width * 0.32f, size.height * 0.24f), Offset(size.width * 0.66f, size.height * 0.24f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.48f, size.height * 0.18f), Offset(size.width * 0.48f, size.height * 0.70f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.34f, size.height * 0.44f), Offset(size.width * 0.62f, size.height * 0.44f), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun TrayGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.46f)
            lineTo(size.width * 0.30f, size.height * 0.24f)
            lineTo(size.width * 0.70f, size.height * 0.24f)
            lineTo(size.width * 0.82f, size.height * 0.46f)
            lineTo(size.width * 0.82f, size.height * 0.78f)
            lineTo(size.width * 0.18f, size.height * 0.78f)
            close()
        }
        drawPath(path, color, style = stroke)
        drawLine(color, Offset(size.width * 0.32f, size.height * 0.50f), Offset(size.width * 0.68f, size.height * 0.50f), stroke.width, StrokeCap.Round)
    }
}

@Composable
private fun PaletteGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawOval(color, topLeft = Offset(size.width * 0.12f, size.height * 0.20f), size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.58f), style = stroke)
        listOf(0.30f to 0.40f, 0.46f to 0.30f, 0.62f to 0.38f).forEach { (x, y) ->
            drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(size.width * x, size.height * y))
        }
        drawCircle(Color(0xFFF5F5F8), radius = 5.dp.toPx(), center = Offset(size.width * 0.66f, size.height * 0.62f))
    }
}

@Composable
private fun DoubleGearGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = size.minDimension * 0.18f, center = Offset(size.width * 0.38f, size.height * 0.40f), style = stroke)
        drawCircle(color, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.64f, size.height * 0.66f), style = stroke)
    }
}

@Composable
private fun BubbleGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(color, topLeft = Offset(size.width * 0.14f, size.height * 0.14f), size = androidx.compose.ui.geometry.Size(size.width * 0.72f, size.height * 0.58f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = stroke)
        drawLine(color, Offset(size.width * 0.38f, size.height * 0.72f), Offset(size.width * 0.32f, size.height * 0.88f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.50f, size.height * 0.30f), Offset(size.width * 0.50f, size.height * 0.50f), stroke.width, StrokeCap.Round)
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width * 0.50f, size.height * 0.60f))
    }
}

@Composable
private fun InfoGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = size.minDimension * 0.37f, center = Offset(size.width * 0.5f, size.height * 0.5f), style = stroke)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.45f), Offset(size.width * 0.5f, size.height * 0.67f), stroke.width, StrokeCap.Round)
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width * 0.5f, size.height * 0.32f))
    }
}

private fun SettingsDestination.placeholderTitle(): String = when (this) {
    SettingsDestination.Anki -> "Anki"
    SettingsDestination.Appearance -> "Appearance"
    SettingsDestination.Advanced -> "Advanced"
    SettingsDestination.About -> "About"
    SettingsDestination.Dictionaries -> "Dictionaries"
    SettingsDestination.ReportIssue -> "Report an Issue"
}

private fun Float.formatOneDecimal(): String =
    String.format(java.util.Locale.US, "%.1f", this)
