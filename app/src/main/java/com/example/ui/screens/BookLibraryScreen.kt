package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BookCollection
import com.example.data.LibraryBook
import com.example.data.LibraryManager
import com.example.ui.LibraryImportFlowState
import com.example.ui.components.IosFloatingDialog
import com.example.ui.components.IosPrimaryButton
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosDarkBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosHairline
import com.example.ui.theme.IosRed
import com.example.ui.theme.IosSeparator
import com.example.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class LibraryTab(val label: String) {
    ALL("Tất cả sách"),
    RECENT("Đọc gần đây"),
    COLLECTIONS("Bộ sưu tập")
}

@Composable
fun BookLibraryScreen(
    books: List<LibraryBook>,
    collections: List<BookCollection>,
    isImporting: Boolean,
    importFlowState: LibraryImportFlowState = LibraryImportFlowState.Idle,
    cleaningProgressPercent: Int = 0,
    cleaningStatusMessage: String = "",
    onBackClick: () -> Unit,
    onImportBookClick: () -> Unit,
    onOpenReaderGeneralSettingsClick: () -> Unit = {},
    onConfirmCleanBeforeImport: () -> Unit = {},
    onConfirmDirectImportNoClean: () -> Unit = {},
    onCancelAutoClean: () -> Unit = {},
    onContinueEditCleanedBook: () -> Unit = {},
    onAcceptCleanedBookOk: () -> Unit = {},
    onDismissImportFlow: () -> Unit = {},
    onOpenBook: (LibraryBook) -> Unit,
    onDeleteBook: (String) -> Unit,
    onCreateCollection: (String) -> Unit,
    onSetCollectionBooks: (collectionId: String, selectedBookIds: Set<String>) -> Unit,
    onAddBookToCollection: (collectionId: String, bookId: String) -> Unit,
    onDeleteCollection: (String) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val accentBlue = if (isDark) IosDarkBlue else IosBlue
    val cardBorderColor = if (isDark) Color(0xFF2C2C2E) else IosSeparator
    val dividerColor = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E5EA)

    var selectedTab by remember { mutableStateOf(LibraryTab.ALL) }
    var openedCollectionId by remember { mutableStateOf<String?>(null) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    var pickerTargetCollection by remember { mutableStateOf<BookCollection?>(null) }
    var assignCollectionBook by remember { mutableStateOf<LibraryBook?>(null) }

    val activeCollection = remember(collections, openedCollectionId) {
        collections.firstOrNull { it.id == openedCollectionId }
    }

    // Hardware/gesture back pops collection detail first, otherwise exits library
    BackHandler {
        if (openedCollectionId != null) {
            openedCollectionId = null
        } else {
            onBackClick()
        }
    }

    val allSortedBooks = remember(books) { LibraryManager.sortAllBooks(books) }
    val recentSortedBooks = remember(books) { LibraryManager.sortRecentlyRead(books) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 1. Minimalist iOS Navigation Bar (Back | Title | + Import)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 6.dp)
            ) {
                IconButton(
                    onClick = {
                        if (openedCollectionId != null) {
                            openedCollectionId = null
                        } else {
                            onBackClick()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(48.dp)
                        .testTag("library_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBackIosNew,
                        contentDescription = "Quay lại",
                        tint = accentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = activeCollection?.name ?: "Thư viện sách",
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp)
                )

                if (activeCollection != null) {
                    // Inside a collection: top-right "+" quickly adds books to this collection
                    IconButton(
                        onClick = { pickerTargetCollection = activeCollection },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(48.dp)
                            .testTag("collection_detail_add_books_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Thêm sách vào bộ sưu tập",
                            tint = accentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Bánh răng cài đặt chung cho chỗ đọc sách (ngay cạnh dấu +)
                        IconButton(
                            onClick = onOpenReaderGeneralSettingsClick,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("library_reader_general_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Cài đặt chung cho trình đọc sách",
                                tint = accentBlue,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        IconButton(
                            onClick = { if (!isImporting) onImportBookClick() },
                            enabled = !isImporting,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("library_import_book_button")
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = accentBlue
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Nhập sách EPUB / PDF",
                                    tint = accentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. iOS Segmented Control (hidden when drilled into a specific Collection)
            if (activeCollection == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    IosLibrarySegmentedControl(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it }
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 3. Main Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                when {
                    activeCollection != null -> {
                        val booksInCollection = remember(books, activeCollection.bookIds) {
                            val map = books.associateBy { it.id }
                            activeCollection.bookIds.mapNotNull { map[it] }
                        }
                        if (booksInCollection.isEmpty()) {
                            LibraryEmptyStateCard(
                                icon = Icons.Rounded.CollectionsBookmark,
                                title = "Bộ sưu tập chưa có sách",
                                subtitle = "Bấm dấu + ở góc trên phải để chọn sách thêm vào bộ sưu tập này.",
                                actionLabel = "+ Thêm sách",
                                onActionClick = { pickerTargetCollection = activeCollection }
                            )
                        } else {
                            CompactInsetBookList(
                                books = booksInCollection,
                                collections = collections,
                                cardBorderColor = cardBorderColor,
                                dividerColor = dividerColor,
                                onOpenBook = onOpenBook,
                                onAssignToCollection = { assignCollectionBook = it },
                                onDeleteBook = { bookId ->
                                    val remaining = activeCollection.bookIds.filterNot { it == bookId }.toSet()
                                    onSetCollectionBooks(activeCollection.id, remaining)
                                },
                                deleteLabel = "Gỡ khỏi bộ sưu tập"
                            )
                        }
                    }

                    selectedTab == LibraryTab.ALL -> {
                        if (allSortedBooks.isEmpty()) {
                            LibraryEmptyStateCard(
                                icon = Icons.Rounded.AutoStories,
                                title = "Thư viện sách trống",
                                subtitle = "Bấm dấu + ở góc phải phía trên để thêm sách EPUB hoặc PDF đọc hoàn toàn ngoại tuyến.",
                                actionLabel = "+ Nhập sách",
                                onActionClick = onImportBookClick
                            )
                        } else {
                            CompactInsetBookList(
                                books = allSortedBooks,
                                collections = collections,
                                cardBorderColor = cardBorderColor,
                                dividerColor = dividerColor,
                                onOpenBook = onOpenBook,
                                onAssignToCollection = { assignCollectionBook = it },
                                onDeleteBook = onDeleteBook,
                                deleteLabel = "Xóa khỏi thư viện"
                            )
                        }
                    }

                    selectedTab == LibraryTab.RECENT -> {
                        if (recentSortedBooks.isEmpty()) {
                            LibraryEmptyStateCard(
                                icon = Icons.Rounded.AutoStories,
                                title = "Chưa có sách đọc gần đây",
                                subtitle = "Những cuốn sách bạn mở đọc sẽ tự động xuất hiện tại đây theo thời gian đọc gần nhất."
                            )
                        } else {
                            CompactInsetBookList(
                                books = recentSortedBooks,
                                collections = collections,
                                cardBorderColor = cardBorderColor,
                                dividerColor = dividerColor,
                                onOpenBook = onOpenBook,
                                onAssignToCollection = { assignCollectionBook = it },
                                onDeleteBook = onDeleteBook,
                                deleteLabel = "Xóa khỏi thư viện"
                            )
                        }
                    }

                    selectedTab == LibraryTab.COLLECTIONS -> {
                        CollectionsTabContent(
                            collections = collections,
                            cardBorderColor = cardBorderColor,
                            dividerColor = dividerColor,
                            accentBlue = accentBlue,
                            onCreateCollectionClick = { showCreateCollectionDialog = true },
                            onOpenCollection = { openedCollectionId = it.id },
                            onAddBooksToCollectionClick = { pickerTargetCollection = it },
                            onDeleteCollection = onDeleteCollection
                        )
                    }
                }
            }
        }
    }

    // Dialog 1: Create new Collection
    if (showCreateCollectionDialog) {
        CreateCollectionDialog(
            onConfirm = { name ->
                onCreateCollection(name)
                showCreateCollectionDialog = false
            },
            onDismiss = { showCreateCollectionDialog = false }
        )
    }

    // Dialog 2: Multi-select books to add to a Collection
    val targetCol = pickerTargetCollection
    if (targetCol != null) {
        AddBooksToCollectionDialog(
            collection = targetCol,
            allBooks = allSortedBooks,
            onSaveSelection = { selectedIds ->
                onSetCollectionBooks(targetCol.id, selectedIds)
                pickerTargetCollection = null
            },
            onDismiss = { pickerTargetCollection = null }
        )
    }

    // Dialog 3: Quick add a single book to an existing Collection
    val bookToAssign = assignCollectionBook
    if (bookToAssign != null) {
        PickCollectionForBookDialog(
            book = bookToAssign,
            collections = collections,
            onSelectCollection = { col ->
                onAddBookToCollection(col.id, bookToAssign.id)
                assignCollectionBook = null
            },
            onCreateNewClick = {
                assignCollectionBook = null
                showCreateCollectionDialog = true
            },
            onDismiss = { assignCollectionBook = null }
        )
    }

    // Dialog 4: Clean-on-Import Workflow (Ask Có/Không -> Spinning Auto-Clean -> Sửa tiếp / OK)
    LibraryImportFlowDialogs(
        flowState = importFlowState,
        cleaningProgressPercent = cleaningProgressPercent,
        cleaningStatusMessage = cleaningStatusMessage,
        onConfirmClean = onConfirmCleanBeforeImport,
        onConfirmNoClean = onConfirmDirectImportNoClean,
        onCancelClean = onCancelAutoClean,
        onContinueEdit = onContinueEditCleanedBook,
        onFinishOk = onAcceptCleanedBookOk,
        onDismiss = onDismissImportFlow
    )
}

@Composable
private fun IosLibrarySegmentedControl(
    selectedTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val trackColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFE5E5EA)
    val thumbColor = if (isDark) Color(0xFF3A3A3C) else Color(0xFFFFFFFF)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibraryTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val bg by animateColorAsState(
                targetValue = if (selected) thumbColor else Color.Transparent,
                animationSpec = tween(160),
                label = "seg_bg_${tab.name}"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (selected) {
                            Modifier.shadow(
                                elevation = 2.dp,
                                shape = RoundedCornerShape(8.dp),
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                spotColor = Color.Black.copy(alpha = 0.12f)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onSelectTab(tab) }
                    .testTag("library_tab_${tab.name.lowercase()}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Ultra-compact iOS Inset Grouped Book List:
 * - Rounded `14.dp` surface container (`#FFFFFF` / `#1C1C1E`, `0.5.dp` hairline border)
 * - Row height strictly `40.dp` (`padding(horizontal = 10.dp, vertical = 4.dp)`)
 * - Left mini cover `20.dp x 30.dp` (`RoundedCornerShape(4.dp)`, subtle shadow)
 * - Single-line book title `12.sp` `FontWeight.Medium` (`maxLines = 1`, `Ellipsis`)
 * - `0.5.dp` divider indented to align precisely with the book title (`start = 40.dp`)
 */
@Composable
private fun CompactInsetBookList(
    books: List<LibraryBook>,
    collections: List<BookCollection>,
    cardBorderColor: Color,
    dividerColor: Color,
    onOpenBook: (LibraryBook) -> Unit,
    onAssignToCollection: (LibraryBook) -> Unit,
    onDeleteBook: (String) -> Unit,
    deleteLabel: String
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(14.dp)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDark) 2.dp else 3.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.25f else 0.05f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.35f else 0.08f)
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, cardBorderColor)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_books_list"),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            itemsIndexed(
                items = books,
                key = { _, item -> item.id }
            ) { index, book ->
                CompactBookRow(
                    book = book,
                    showCollectionAction = collections.isNotEmpty() || deleteLabel == "Xóa khỏi thư viện",
                    deleteLabel = deleteLabel,
                    onClick = { onOpenBook(book) },
                    onAddToCollectionClick = { onAssignToCollection(book) },
                    onDeleteClick = { onDeleteBook(book.id) }
                )
                if (index < books.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 40.dp, end = 10.dp),
                        thickness = 0.5.dp,
                        color = dividerColor
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactBookRow(
    book: LibraryBook,
    showCollectionAction: Boolean,
    deleteLabel: String,
    onClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("library_book_row_${book.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppleBooksMiniCover(
            title = book.title,
            coverPath = book.coverPath
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = book.title,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Box {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { menuExpanded = true }
                    .testTag("library_book_menu_${book.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "Tùy chọn sách",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            ) {
                if (showCollectionAction) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Thêm vào bộ sưu tập",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.CollectionsBookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onAddToCollectionClick()
                        }
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = deleteLabel,
                            fontSize = 12.sp,
                            color = IosRed
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = IosRed,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDeleteClick()
                    }
                )
            }
        }
    }
}

/**
 * Apple Books Mini Cover Thumbnail (`20.dp x 30.dp`, `RoundedCornerShape(4.dp)`, subtle shadow).
 * Decodes compressed cover thumbnail asynchronously off the main thread; falls back to an
 * understated minimalist initial-letter cover when the book has no cover artwork.
 */
@Composable
private fun AppleBooksMiniCover(
    title: String,
    coverPath: String?
) {
    var bitmap by remember(coverPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(coverPath) {
        bitmap = if (!coverPath.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(coverPath)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else {
                        null
                    }
                }.getOrNull()
            }
        } else {
            null
        }
    }

    val coverShape = RoundedCornerShape(4.dp)
    val initial = remember(title) {
        title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"
    }
    val gradientColors = remember(title) {
        val palettes = listOf(
            listOf(Color(0xFF5B8DEF), Color(0xFF3A66C9)),
            listOf(Color(0xFFE08B55), Color(0xFFB96432)),
            listOf(Color(0xFF58A883), Color(0xFF397C5E)),
            listOf(Color(0xFF9378D4), Color(0xFF6B52AD)),
            listOf(Color(0xFF6B7B8C), Color(0xFF475563))
        )
        palettes[(title.hashCode().and(0x7fffffff)) % palettes.size]
    }

    Box(
        modifier = Modifier
            .size(width = 20.dp, height = 30.dp)
            .shadow(
                elevation = 2.dp,
                shape = coverShape,
                ambientColor = Color.Black.copy(alpha = 0.14f),
                spotColor = Color.Black.copy(alpha = 0.22f)
            )
            .clip(coverShape)
            .background(Brush.verticalGradient(gradientColors)),
        contentAlignment = Alignment.Center
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = "Bìa sách $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CollectionsTabContent(
    collections: List<BookCollection>,
    cardBorderColor: Color,
    dividerColor: Color,
    accentBlue: Color,
    onCreateCollectionClick: () -> Unit,
    onOpenCollection: (BookCollection) -> Unit,
    onAddBooksToCollectionClick: (BookCollection) -> Unit,
    onDeleteCollection: (String) -> Unit
) {
    val isDark = LocalIsDarkTheme.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact "+ Tạo bộ sưu tập" iOS Pill button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isDark) Color(0xFF1C1C1E) else Color.White,
                border = BorderStroke(0.5.dp, cardBorderColor),
                modifier = Modifier
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(50),
                        ambientColor = Color.Black.copy(alpha = 0.06f),
                        spotColor = Color.Black.copy(alpha = 0.10f)
                    )
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onCreateCollectionClick)
                    .testTag("create_collection_pill_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = accentBlue,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Tạo bộ sưu tập",
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        maxLines = 1
                    )
                }
            }
        }

        if (collections.isEmpty()) {
            LibraryEmptyStateCard(
                icon = Icons.Rounded.CollectionsBookmark,
                title = "Chưa có bộ sưu tập nào",
                subtitle = "Tạo bộ sưu tập để nhóm các cuốn sách yêu thích theo thể loại hoặc chủ đề."
            )
        } else {
            val shape = RoundedCornerShape(14.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (isDark) 2.dp else 3.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.05f),
                        spotColor = Color.Black.copy(alpha = 0.08f)
                    ),
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(0.5.dp, cardBorderColor)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collections_list"),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    itemsIndexed(
                        items = collections,
                        key = { _, item -> item.id }
                    ) { index, col ->
                        var colMenuOpen by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .clickable { onOpenCollection(col) }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag("collection_row_${col.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = accentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = col.name,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${col.bookIds.size} sách",
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }

                            // Compact "+ Thêm sách" pill
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = accentBlue.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onAddBooksToCollectionClick(col) }
                                    .testTag("collection_add_books_${col.id}")
                            ) {
                                Text(
                                    text = "+ Thêm sách",
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accentBlue,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable { colMenuOpen = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = "Tùy chọn bộ sưu tập",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = colMenuOpen,
                                    onDismissRequest = { colMenuOpen = false },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text("Xóa bộ sưu tập", fontSize = 12.sp, color = IosRed)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = null,
                                                tint = IosRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = {
                                            colMenuOpen = false
                                            onDeleteCollection(col.id)
                                        }
                                    )
                                }
                            }

                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (index < collections.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 36.dp, end = 10.dp),
                                thickness = 0.5.dp,
                                color = dividerColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    val accentBlue = if (isDark) IosDarkBlue else IosBlue
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, if (isDark) Color(0xFF2C2C2E) else IosSeparator),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (actionLabel != null && onActionClick != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accentBlue.copy(alpha = 0.12f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onActionClick)
                    ) {
                        Text(
                            text = actionLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accentBlue,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val isDark = LocalIsDarkTheme.current

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tạo bộ sưu tập mới",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7))
                    .border(
                        width = 0.5.dp,
                        color = if (isDark) Color(0xFF3A3A3C) else IosHairline,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = "Ví dụ: Tiểu thuyết yêu thích, Kinh tế…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collection_name_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Hủy", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.width(104.dp)) {
                    IosPrimaryButton(
                        text = "Tạo",
                        height = 36.dp,
                        enabled = name.trim().isNotEmpty(),
                        onClick = { onConfirm(name.trim()) },
                        modifier = Modifier.testTag("confirm_create_collection_button")
                    )
                }
            }
        }
    }
}

@Composable
private fun AddBooksToCollectionDialog(
    collection: BookCollection,
    allBooks: List<LibraryBook>,
    onSaveSelection: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember(collection.id) { mutableStateOf(collection.bookIds.toSet()) }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Thêm sách vào \"${collection.name}\"",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (allBooks.isEmpty()) {
                Text(
                    text = "Chưa có sách nào trong thư viện để thêm.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    itemsIndexed(allBooks, key = { _, b -> b.id }) { idx, book ->
                        val checked = book.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clickable {
                                    selectedIds = if (checked) {
                                        selectedIds - book.id
                                    } else {
                                        selectedIds + book.id
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedIds = if (isChecked) {
                                        selectedIds + book.id
                                    } else {
                                        selectedIds - book.id
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            AppleBooksMiniCover(title = book.title, coverPath = book.coverPath)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = book.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (idx < allBooks.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Đóng", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.width(104.dp)) {
                    IosPrimaryButton(
                        text = "Xong",
                        height = 36.dp,
                        onClick = { onSaveSelection(selectedIds) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PickCollectionForBookDialog(
    book: LibraryBook,
    collections: List<BookCollection>,
    onSelectCollection: (BookCollection) -> Unit,
    onCreateNewClick: () -> Unit,
    onDismiss: () -> Unit
) {
    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Thêm vào bộ sưu tập",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = book.title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (collections.isEmpty()) {
                Text(
                    text = "Bạn chưa có bộ sưu tập nào.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                ) {
                    itemsIndexed(collections, key = { _, c -> c.id }) { idx, col ->
                        val alreadyIn = book.id in col.bookIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clickable { onSelectCollection(col) }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = col.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (alreadyIn) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Đã có",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        if (idx < collections.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCreateNewClick) {
                    Text("+ Bộ sưu tập mới", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Đóng", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * iOS Floating Dialog sequence for Book Library Import:
 * 1. AskCleanConfirmation: "Bạn có muốn dọn sách không?" (Không | Có)
 * 2. CleaningInProgress / ImportingDirect / FinalizingCleanImport: Spinning loader + live progress
 * 3. CleanCompleted: Green checkmark + "Sửa tiếp" (opens Cleaning Review/Editor) | "OK" (stays in Library)
 * 4. Error: Friendly error message
 */
@Composable
private fun LibraryImportFlowDialogs(
    flowState: LibraryImportFlowState,
    cleaningProgressPercent: Int,
    cleaningStatusMessage: String,
    onConfirmClean: () -> Unit,
    onConfirmNoClean: () -> Unit,
    onCancelClean: () -> Unit,
    onContinueEdit: () -> Unit,
    onFinishOk: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val accentBlue = if (isDark) IosDarkBlue else IosBlue

    when (flowState) {
        is LibraryImportFlowState.Idle -> Unit

        is LibraryImportFlowState.AskCleanConfirmation -> {
            IosFloatingDialog(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(accentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = accentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = "Bạn có muốn dọn sách không?",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = flowState.source.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Chọn \"Có\" để tự động dọn link rác, chuẩn hóa phông, khoảng trắng và chính tả trước khi lưu vào Thư viện sách.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onConfirmNoClean,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("library_import_no_clean_button"),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = "Không",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            IosPrimaryButton(
                                text = "Có",
                                height = 42.dp,
                                onClick = onConfirmClean,
                                modifier = Modifier.testTag("library_import_yes_clean_button")
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.ImportingDirect -> {
            IosFloatingDialog(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp,
                        color = accentBlue
                    )
                    Text(
                        text = "Đang thêm sách vào thư viện…",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = flowState.fileName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        is LibraryImportFlowState.CleaningInProgress,
        is LibraryImportFlowState.FinalizingCleanImport -> {
            val isFinalizing = flowState is LibraryImportFlowState.FinalizingCleanImport
            val displayPercent = if (isFinalizing) 100 else cleaningProgressPercent
            val displayTitle = if (isFinalizing) {
                "Đang lưu vào Thư viện sách…"
            } else {
                "Đang tự động dọn sách…"
            }
            val displaySubtitle = if (isFinalizing) {
                (flowState as LibraryImportFlowState.FinalizingCleanImport).bookTitle
            } else {
                cleaningStatusMessage.ifBlank { "Đang phân tích và làm sạch nội dung…" }
            }

            IosFloatingDialog(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("library_auto_clean_spinner"),
                        strokeWidth = 3.2.dp,
                        color = accentBlue
                    )

                    Text(
                        text = displayTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { (displayPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = accentBlue,
                        trackColor = accentBlue.copy(alpha = 0.16f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displaySubtitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$displayPercent%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentBlue
                        )
                    }

                    if (!isFinalizing) {
                        TextButton(
                            onClick = onCancelClean,
                            modifier = Modifier.testTag("library_cancel_auto_clean_button")
                        ) {
                            Text(
                                text = "Hủy quét",
                                fontSize = 12.sp,
                                color = IosRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.CleanCompleted -> {
            IosFloatingDialog(onDismissRequest = onFinishOk) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (isDark) IosGreen.copy(alpha = 0.18f) else IosGreenSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = IosGreen,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Text(
                        text = "Đã dọn sách xong!",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = flowState.book.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = if (flowState.totalFixes > 0) {
                            "Đã tự động sửa ${flowState.totalFixes} lỗi và thêm sẵn vào Thư viện sách."
                        } else {
                            "Nội dung đã chuẩn sạch và được thêm sẵn vào Thư viện sách."
                        },
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onContinueEdit,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("library_clean_continue_edit_button"),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, accentBlue.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = "Sửa tiếp",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentBlue,
                                maxLines = 1
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            IosPrimaryButton(
                                text = "OK",
                                height = 42.dp,
                                onClick = onFinishOk,
                                modifier = Modifier.testTag("library_clean_ok_button")
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.Error -> {
            IosFloatingDialog(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Không thể xử lý tệp",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosRed
                    )
                    Text(
                        text = flowState.message,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    IosPrimaryButton(
                        text = "Đóng",
                        height = 40.dp,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}

