package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.ui.components.AddBooksToCollectionDialog
import com.example.ui.components.CreateCollectionDialog
import com.example.ui.components.Ios3DCircleIconButton
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.LibraryBookCoverThumbnail
import com.example.ui.components.LibraryImportFlowDialogs
import com.example.ui.components.LibraryInfoStatsDialog
import com.example.ui.components.Neo3DDropdownMenu
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.NeoPaperStackContainer
import com.example.ui.components.PickCollectionForBookDialog
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoHardShadowColor
import com.example.ui.components.neoInkBorderColor
import com.example.ui.components.neoPaperSurfaceColor
import com.example.ui.components.neoSecondaryPaperColor
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosDarkBlue
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosRed
import com.example.ui.theme.IosSeparator
import com.example.ui.theme.LibraryCloudAccent
import com.example.ui.theme.LibraryCoralRed
import com.example.ui.theme.LibraryCoralSoft
import com.example.ui.theme.LibraryCoralText
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.NeoInkLight
import kotlin.math.roundToInt

private enum class LibraryFilterMode(val label: String) {
    BOOK_VAULT("Kho sách"),
    BOOKS("Sách"),
    AUTHORS("Tác giả"),
    SERIES("Bộ sách"),
    GENRES("Thể loại"),
    FORMATS("Định dạng"),
    COLLECTIONS("Sưu tập")
}

private enum class LibrarySortOption(val label: String) {
    RECENT("Đọc gần đây"),
    NEWEST("Mới thêm gần nhất"),
    TITLE_AZ("Tên sách A–Z"),
    AUTHOR_AZ("Tác giả A–Z"),
    PROGRESS_DESC("Tiến độ đọc cao nhất")
}

private enum class LibraryReadStatusFilter(val label: String) {
    ALL("Tất cả"),
    READING("Đang đọc"),
    UNREAD("Chưa đọc"),
    COMPLETED("Đã đọc xong")
}

private data class LibraryGroupDrillDown(
    val title: String,
    val books: List<LibraryBook>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookLibraryScreen(
    books: List<LibraryBook>,
    collections: List<BookCollection>,
    isImporting: Boolean,
    importFlowState: LibraryImportFlowState = LibraryImportFlowState.Idle,
    cleaningProgressPercent: Int = 0,
    cleaningStatusMessage: String = "",
    onImportBookClick: () -> Unit,
    onOpenReaderGeneralSettingsClick: () -> Unit = {},
    onConfirmCleanBeforeImport: () -> Unit = {},
    onConfirmDirectImportNoClean: () -> Unit = {},
    onConfirmBatchClean: () -> Unit = {},
    onConfirmBatchNoClean: () -> Unit = {},
    onCancelBatchImport: () -> Unit = {},
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
    val surfaceColor = MaterialTheme.colorScheme.surface
    val subtleBorder = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB)
    val dividerColor = if (isDark) Color(0xFF262629) else Color(0xFFF3F4F6)
    val pillTrackColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF3F4F6)

    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(LibraryFilterMode.BOOKS) }
    var sortOption by remember { mutableStateOf(LibrarySortOption.RECENT) }
    var statusFilter by remember { mutableStateOf(LibraryReadStatusFilter.ALL) }

    var openedCollectionId by remember { mutableStateOf<String?>(null) }
    var drillDownGroup by remember { mutableStateOf<LibraryGroupDrillDown?>(null) }

    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedBookIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showQuickLinkMenu by remember { mutableStateOf(false) }
    var showCreateCollectionDialog by remember { mutableStateOf(false) }
    var pickerTargetCollection by remember { mutableStateOf<BookCollection?>(null) }
    var assignCollectionBooks by remember { mutableStateOf<List<LibraryBook>?>(null) }

    val activeCollection = remember(collections, openedCollectionId) {
        collections.firstOrNull { it.id == openedCollectionId }
    }

    // Back gesture exits multi-select or sub-views cleanly
    BackHandler(enabled = isMultiSelectMode || openedCollectionId != null || drillDownGroup != null) {
        when {
            isMultiSelectMode -> {
                isMultiSelectMode = false
                selectedBookIds = emptySet()
            }
            openedCollectionId != null -> openedCollectionId = null
            drillDownGroup != null -> drillDownGroup = null
        }
    }

    // Filtered + Sorted list of books
    val processedBooks = remember(books, searchQuery, sortOption, statusFilter) {
        val query = searchQuery.trim().lowercase()
        val filtered = books.filter { book ->
            val matchesQuery = query.isEmpty() ||
                book.title.lowercase().contains(query) ||
                book.author.lowercase().contains(query) ||
                book.sourceType.lowercase().contains(query)
            val pct = computeBookProgressPercent(book)
            val matchesStatus = when (statusFilter) {
                LibraryReadStatusFilter.ALL -> true
                LibraryReadStatusFilter.READING -> pct in 1..99
                LibraryReadStatusFilter.UNREAD -> pct == 0
                LibraryReadStatusFilter.COMPLETED -> pct >= 100
            }
            matchesQuery && matchesStatus
        }

        when (sortOption) {
            LibrarySortOption.RECENT -> filtered.sortedWith(
                compareByDescending<LibraryBook> { it.lastOpenedAtMillis }
                    .thenByDescending { it.addedAtMillis }
            )
            LibrarySortOption.NEWEST -> LibraryManager.sortAllBooks(filtered)
            LibrarySortOption.TITLE_AZ -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.AUTHOR_AZ -> filtered.sortedBy {
                it.author.ifBlank { "zzz" }.lowercase()
            }
            LibrarySortOption.PROGRESS_DESC -> filtered.sortedByDescending { computeBookProgressPercent(it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("book_library_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
        ) {
            // 1. Swiss Neo-Brutalist Header: Crisp 21.sp Bold "Thư viện sách" + (i) | 3D Inked 4-icon Action Pill
            if (activeCollection != null || drillDownGroup != null) {
                val subTitle = activeCollection?.name ?: drillDownGroup?.title.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                openedCollectionId = null
                                drillDownGroup = null
                            }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBackIosNew,
                            contentDescription = "Quay lại",
                            tint = accentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = subTitle,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (activeCollection != null) {
                        NeoBrutalistPressBox(
                            onClick = { pickerTargetCollection = activeCollection },
                            isPill = true,
                            shadowOffset = 2.5.dp,
                            pressDepth = 1.8.dp,
                            containerColor = IosOrange,
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("collection_detail_add_books_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Thêm sách",
                                    tint = NeoInkLight,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Thêm sách",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeoInkLight,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "Thư viện sách",
                            fontSize = 21.sp,
                            lineHeight = 25.sp,
                            letterSpacing = (-0.4).sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("library_header_title")
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { showInfoDialog = true }
                                .testTag("library_info_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = "Thông tin thư viện",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Top-right Swiss Neo-Brutalist 3D Inked Pill with 4 quick action icons
                    Box {
                        Box(
                            modifier = Modifier
                                .padding(end = 3.dp, bottom = 3.dp)
                                .neoHardOffsetShadow(
                                    cornerRadius = 999.dp,
                                    offsetX = 3.dp,
                                    offsetY = 3.dp
                                )
                                .clip(RoundedCornerShape(50))
                                .background(neoPaperSurfaceColor())
                                .border(1.5.dp, neoInkBorderColor(), RoundedCornerShape(50))
                                .testTag("library_quick_action_pill")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                LibraryHeaderPillActionIcon(
                                    icon = Icons.Rounded.Checklist,
                                    contentDescription = "Chọn nhiều sách",
                                    tint = if (isMultiSelectMode) LibraryCoralRed else MaterialTheme.colorScheme.onSurface,
                                    highlighted = isMultiSelectMode,
                                    testTagName = "library_select_mode_button",
                                    onClick = {
                                        isMultiSelectMode = !isMultiSelectMode
                                        if (!isMultiSelectMode) selectedBookIds = emptySet()
                                    }
                                )
                                LibraryHeaderPillActionIcon(
                                    icon = Icons.Rounded.CloudDownload,
                                    contentDescription = "Cài đặt trình đọc & tải sách",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    highlighted = false,
                                    testTagName = "library_reader_general_settings_button",
                                    onClick = onOpenReaderGeneralSettingsClick
                                )
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = !isImporting, onClick = onImportBookClick)
                                        .testTag("library_import_book_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isImporting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(15.dp),
                                            strokeWidth = 2.dp,
                                            color = accentBlue
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = "Quét 1 hoặc nhiều sách EPUB / PDF",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                LibraryHeaderPillActionIcon(
                                    icon = Icons.Rounded.Link,
                                    contentDescription = "Gắn liên kết & quét nhiều sách",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    highlighted = false,
                                    testTagName = "library_link_menu_button",
                                    onClick = { showQuickLinkMenu = true }
                                )
                            }
                        }

                        Neo3DDropdownMenu(
                            expanded = showQuickLinkMenu,
                            onDismissRequest = { showQuickLinkMenu = false },
                            containerColor = neoPaperSurfaceColor()
                        ) {
                            val itemDividerColor = neoInkBorderColor().copy(alpha = 0.16f)
                            val badgeBg = neoSecondaryPaperColor()
                            val badgeBorder = neoInkBorderColor()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Cài đặt chung đọc & âm thanh",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(badgeBg)
                                            .border(1.2.dp, badgeBorder, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = null,
                                            tint = accentBlue,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    showQuickLinkMenu = false
                                    onOpenReaderGeneralSettingsClick()
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                thickness = 1.dp,
                                color = itemDividerColor
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Tạo bộ sưu tập mới",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(badgeBg)
                                            .border(1.2.dp, badgeBorder, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CollectionsBookmark,
                                            contentDescription = null,
                                            tint = LibraryCoralRed,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    showQuickLinkMenu = false
                                    showCreateCollectionDialog = true
                                }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                thickness = 1.dp,
                                color = itemDividerColor
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Quét & nhập nhiều sách",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = accentBlue
                                    )
                                },
                                leadingIcon = {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(accentBlue.copy(alpha = 0.14f))
                                            .border(1.2.dp, badgeBorder, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = null,
                                            tint = accentBlue,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    showQuickLinkMenu = false
                                    onImportBookClick()
                                }
                            )
                        }
                    }
                }

                // 2. Small Stats Subtitle right beneath Header Title
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "${books.size} cuốn sách · EPUB, PDF & MOBI",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("library_stats_subtitle")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // 3. Neo-Brutalist 3D Inked Search Pill + Circular Filter Stamp Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 3.dp, bottom = 3.dp)
                            .height(38.dp)
                            .neoHardOffsetShadow(cornerRadius = 999.dp, offsetX = 3.dp, offsetY = 3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(neoPaperSurfaceColor())
                            .border(1.5.dp, neoInkBorderColor(), RoundedCornerShape(50))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Tên sách, tác giả, bộ sách...",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(IosBlue),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("library_search_input")
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { searchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Xóa tìm kiếm",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    val hasActiveSortOrStatus = sortOption != LibrarySortOption.RECENT ||
                        statusFilter != LibraryReadStatusFilter.ALL
                    Ios3DCircleIconButton(
                        onClick = { showFilterSheet = true },
                        size = 38.dp,
                        highlighted = hasActiveSortOrStatus,
                        highlightBg = IosOrange,
                        modifier = Modifier.testTag("library_filter_sliders_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Bộ lọc & sắp xếp",
                            tint = if (hasActiveSortOrStatus) NeoInkLight else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(9.dp))

                // 4. Two Rows of Swiss Neo-Brutalist Inked Pill Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isVaultSelected = activeFilter == LibraryFilterMode.BOOK_VAULT
                    NeoBrutalistPressBox(
                        onClick = { activeFilter = LibraryFilterMode.BOOK_VAULT },
                        isPill = true,
                        shadowOffset = 2.5.dp,
                        pressDepth = 1.8.dp,
                        containerColor = if (isVaultSelected) IosOrange else neoPaperSurfaceColor(),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("library_chip_book_vault")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Cloud,
                                contentDescription = null,
                                tint = if (isVaultSelected) NeoInkLight else LibraryCloudAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Kho sách",
                                fontSize = 11.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isVaultSelected) NeoInkLight else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    ConnectedFilterPillGroup(
                        items = listOf(
                            LibraryFilterMode.BOOKS,
                            LibraryFilterMode.AUTHORS,
                            LibraryFilterMode.SERIES
                        ),
                        selected = activeFilter,
                        trackColor = pillTrackColor,
                        onSelect = { activeFilter = it },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(7.dp))

                // Row 2: Connected group ["Thể loại", "Định dạng", "Sưu tập"]
                ConnectedFilterPillGroup(
                    items = listOf(
                        LibraryFilterMode.GENRES,
                        LibraryFilterMode.FORMATS,
                        LibraryFilterMode.COLLECTIONS
                    ),
                    selected = activeFilter,
                    trackColor = pillTrackColor,
                    onSelect = { activeFilter = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Multi-select batch toolbar when active
            AnimatedVisibility(visible = isMultiSelectMode) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isDark) Color(0xFF1C1C1E) else Color(0xFFF3F4F6),
                    border = BorderStroke(0.8.dp, subtleBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Đã chọn ${selectedBookIds.size} sách",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (selectedBookIds.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        val selectedBooks = books.filter { it.id in selectedBookIds }
                                        assignCollectionBooks = selectedBooks
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Sưu tập", fontSize = 12.sp, color = accentBlue, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(
                                    onClick = {
                                        selectedBookIds.forEach { onDeleteBook(it) }
                                        selectedBookIds = emptySet()
                                        isMultiSelectMode = false
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Xóa", fontSize = 12.sp, color = IosRed, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            TextButton(
                                onClick = {
                                    isMultiSelectMode = false
                                    selectedBookIds = emptySet()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Xong", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 5. Main Scrollable Content Area (with bottom padding 96.dp for Floating Bottom Nav Bar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                                subtitle = "Bấm \"+ Thêm sách\" ở góc trên để thêm sách vào bộ sưu tập này.",
                                actionLabel = "+ Thêm sách",
                                onActionClick = { pickerTargetCollection = activeCollection }
                            )
                        } else {
                            ModernBookVerticalList(
                                books = booksInCollection,
                                isMultiSelectMode = isMultiSelectMode,
                                selectedBookIds = selectedBookIds,
                                dividerColor = dividerColor,
                                deleteLabel = "Gỡ khỏi bộ sưu tập",
                                onToggleSelect = { id ->
                                    selectedBookIds = if (id in selectedBookIds) selectedBookIds - id else selectedBookIds + id
                                },
                                onOpenBook = onOpenBook,
                                onAddToCollection = { assignCollectionBooks = listOf(it) },
                                onDeleteBook = { bookId ->
                                    val remaining = activeCollection.bookIds.filterNot { it == bookId }.toSet()
                                    onSetCollectionBooks(activeCollection.id, remaining)
                                }
                            )
                        }
                    }

                    drillDownGroup != null -> {
                        val groupBooks = drillDownGroup?.books.orEmpty()
                        ModernBookVerticalList(
                            books = groupBooks,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedBookIds = selectedBookIds,
                            dividerColor = dividerColor,
                            deleteLabel = "Xóa khỏi thư viện",
                            onToggleSelect = { id ->
                                selectedBookIds = if (id in selectedBookIds) selectedBookIds - id else selectedBookIds + id
                            },
                            onOpenBook = onOpenBook,
                            onAddToCollection = { assignCollectionBooks = listOf(it) },
                            onDeleteBook = onDeleteBook
                        )
                    }

                    activeFilter == LibraryFilterMode.BOOKS || activeFilter == LibraryFilterMode.BOOK_VAULT -> {
                        if (processedBooks.isEmpty()) {
                            LibraryEmptyStateCard(
                                icon = Icons.Rounded.AutoStories,
                                title = if (searchQuery.isNotBlank()) "Không tìm thấy sách phù hợp" else "Thư viện sách trống",
                                subtitle = if (searchQuery.isNotBlank()) {
                                    "Thử nhập từ khóa khác hoặc xóa bộ lọc tìm kiếm."
                                } else {
                                    "Bấm dấu + ở góc trên bên phải để thêm sách EPUB hoặc PDF đọc hoàn toàn ngoại tuyến."
                                },
                                actionLabel = if (searchQuery.isNotBlank()) "Xóa tìm kiếm" else "+ Nhập sách",
                                onActionClick = {
                                    if (searchQuery.isNotBlank()) {
                                        searchQuery = ""
                                    } else {
                                        onImportBookClick()
                                    }
                                }
                            )
                        } else {
                            ModernBookVerticalList(
                                books = processedBooks,
                                isMultiSelectMode = isMultiSelectMode,
                                selectedBookIds = selectedBookIds,
                                dividerColor = dividerColor,
                                deleteLabel = "Xóa khỏi thư viện",
                                onToggleSelect = { id ->
                                    selectedBookIds = if (id in selectedBookIds) selectedBookIds - id else selectedBookIds + id
                                },
                                onOpenBook = onOpenBook,
                                onAddToCollection = { assignCollectionBooks = listOf(it) },
                                onDeleteBook = onDeleteBook
                            )
                        }
                    }

                    activeFilter == LibraryFilterMode.AUTHORS -> {
                        val authorGroups = remember(processedBooks) {
                            processedBooks
                                .groupBy { it.author.ifBlank { "Chưa rõ tác giả" } }
                                .entries
                                .sortedByDescending { it.value.size }
                        }
                        LibraryCategoryGroupList(
                            icon = Icons.Rounded.Person,
                            emptyTitle = "Chưa có dữ liệu tác giả",
                            emptySubtitle = "Thêm sách EPUB hoặc PDF vào thư viện để tự động phân nhóm theo tác giả.",
                            groups = authorGroups.map { it.key to it.value },
                            subtleBorder = subtleBorder,
                            dividerColor = dividerColor,
                            onOpenGroup = { title, items ->
                                drillDownGroup = LibraryGroupDrillDown(title, items)
                            }
                        )
                    }

                    activeFilter == LibraryFilterMode.FORMATS -> {
                        val formatGroups = remember(processedBooks) {
                            processedBooks
                                .groupBy { it.sourceType.uppercase().ifBlank { "EPUB" } }
                                .entries
                                .sortedByDescending { it.value.size }
                        }
                        LibraryCategoryGroupList(
                            icon = Icons.Rounded.Description,
                            emptyTitle = "Chưa có định dạng sách nào",
                            emptySubtitle = "Thư viện hỗ trợ đọc ngoại tuyến sách định dạng EPUB và PDF.",
                            groups = formatGroups.map { "Định dạng ${it.key}" to it.value },
                            subtleBorder = subtleBorder,
                            dividerColor = dividerColor,
                            onOpenGroup = { title, items ->
                                drillDownGroup = LibraryGroupDrillDown(title, items)
                            }
                        )
                    }

                    activeFilter == LibraryFilterMode.GENRES -> {
                        val genreGroups = remember(processedBooks) {
                            buildList {
                                val reading = processedBooks.filter { computeBookProgressPercent(it) in 1..99 }
                                val unread = processedBooks.filter { computeBookProgressPercent(it) == 0 }
                                val done = processedBooks.filter { computeBookProgressPercent(it) >= 100 }
                                if (reading.isNotEmpty()) add("Đang đọc dở" to reading)
                                if (unread.isNotEmpty()) add("Sách mới chưa đọc" to unread)
                                if (done.isNotEmpty()) add("Đã đọc hoàn tất" to done)
                            }
                        }
                        LibraryCategoryGroupList(
                            icon = Icons.Rounded.Category,
                            emptyTitle = "Chưa có phân loại sách",
                            emptySubtitle = "Sách của bạn sẽ tự động được phân nhóm theo trạng thái đọc tại đây.",
                            groups = genreGroups,
                            subtleBorder = subtleBorder,
                            dividerColor = dividerColor,
                            onOpenGroup = { title, items ->
                                drillDownGroup = LibraryGroupDrillDown(title, items)
                            }
                        )
                    }

                    activeFilter == LibraryFilterMode.SERIES || activeFilter == LibraryFilterMode.COLLECTIONS -> {
                        CollectionsTabContent(
                            collections = collections,
                            subtleBorder = subtleBorder,
                            dividerColor = dividerColor,
                            accentBlue = accentBlue,
                            isSeriesMode = activeFilter == LibraryFilterMode.SERIES,
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

    // Bottom Sheet: Sort, Read Status Filter & Direct jump to Reader General Settings
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = surfaceColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Sắp xếp & Bộ lọc",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = {
                            sortOption = LibrarySortOption.RECENT
                            statusFilter = LibraryReadStatusFilter.ALL
                        }
                    ) {
                        Text("Đặt lại", fontSize = 13.sp, color = LibraryCoralRed, fontWeight = FontWeight.SemiBold)
                    }
                }

                Text(
                    text = "Trạng thái đọc",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LibraryReadStatusFilter.entries.forEach { st ->
                        val active = st == statusFilter
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (active) LibraryCoralSoft else pillTrackColor,
                            border = BorderStroke(
                                0.8.dp,
                                if (active) LibraryCoralRed.copy(alpha = 0.45f) else Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { statusFilter = st }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = st.label,
                                    fontSize = 11.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) LibraryCoralText else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Sắp xếp danh sách",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = pillTrackColor,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        LibrarySortOption.entries.forEachIndexed { idx, opt ->
                            val selected = opt == sortOption
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clickable { sortOption = opt }
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = opt.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Đang chọn",
                                        tint = LibraryCoralRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            if (idx < LibrarySortOption.entries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    thickness = 0.6.dp,
                                    color = subtleBorder
                                )
                            }
                        }
                    }
                }

                IosPrimaryButton(
                    text = "Cài đặt chung trình đọc & âm thanh",
                    height = 46.dp,
                    onClick = {
                        showFilterSheet = false
                        onOpenReaderGeneralSettingsClick()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }

    // Dialog: Library Info Stats
    if (showInfoDialog) {
        LibraryInfoStatsDialog(
            books = books,
            collectionsCount = collections.size,
            onOpenReaderSettings = onOpenReaderGeneralSettingsClick,
            onDismiss = { showInfoDialog = false }
        )
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
            allBooks = LibraryManager.sortAllBooks(books),
            onSaveSelection = { selectedIds ->
                onSetCollectionBooks(targetCol.id, selectedIds)
                pickerTargetCollection = null
            },
            onDismiss = { pickerTargetCollection = null }
        )
    }

    // Dialog 3: Quick add 1 or multiple books to an existing Collection
    val booksToAssign = assignCollectionBooks
    if (!booksToAssign.isNullOrEmpty()) {
        val label = if (booksToAssign.size == 1) {
            booksToAssign.first().title
        } else {
            "${booksToAssign.size} cuốn sách đã chọn"
        }
        PickCollectionForBookDialog(
            titleLabel = label,
            existingBookIds = booksToAssign.map { it.id }.toSet(),
            collections = collections,
            onSelectCollection = { col ->
                booksToAssign.forEach { b -> onAddBookToCollection(col.id, b.id) }
                assignCollectionBooks = null
                if (isMultiSelectMode) {
                    isMultiSelectMode = false
                    selectedBookIds = emptySet()
                }
            },
            onCreateNewClick = {
                assignCollectionBooks = null
                showCreateCollectionDialog = true
            },
            onDismiss = { assignCollectionBooks = null }
        )
    }

    // Dialog 4: Clean-on-Import Workflow (Single Book + Multi-Book Batch)
    LibraryImportFlowDialogs(
        flowState = importFlowState,
        cleaningProgressPercent = cleaningProgressPercent,
        cleaningStatusMessage = cleaningStatusMessage,
        onConfirmClean = onConfirmCleanBeforeImport,
        onConfirmNoClean = onConfirmDirectImportNoClean,
        onConfirmBatchClean = onConfirmBatchClean,
        onConfirmBatchNoClean = onConfirmBatchNoClean,
        onCancelBatch = onCancelBatchImport,
        onCancelClean = onCancelAutoClean,
        onContinueEdit = onContinueEditCleanedBook,
        onFinishOk = onAcceptCleanedBookOk,
        onDismiss = onDismissImportFlow
    )
}

@Composable
private fun LibraryHeaderPillActionIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    highlighted: Boolean,
    testTagName: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (highlighted) LibraryCoralSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .testTag(testTagName),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun ConnectedFilterPillGroup(
    items: List<LibraryFilterMode>,
    selected: LibraryFilterMode,
    trackColor: Color,
    onSelect: (LibraryFilterMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val selectedIdx = items.indexOf(selected)
    val ink = neoInkBorderColor()

    Box(
        modifier = modifier
            .padding(end = 2.5.dp, bottom = 2.5.dp)
            .height(34.dp)
            .neoHardOffsetShadow(cornerRadius = 999.dp, offsetX = 2.5.dp, offsetY = 2.5.dp)
            .clip(RoundedCornerShape(50))
            .background(neoSecondaryPaperColor())
            .border(width = 1.5.dp, color = ink, shape = RoundedCornerShape(50))
            .padding(2.5.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val segWidth = maxWidth / items.size.coerceAtLeast(1)
            if (selectedIdx >= 0) {
                val animatedOffset by animateDpAsState(
                    targetValue = segWidth * selectedIdx,
                    animationSpec = spring(
                        dampingRatio = 0.80f,
                        stiffness = 500f
                    ),
                    label = "filter_thumb_slide"
                )
                Box(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .width(segWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(IosOrange)
                        .border(1.2.dp, if (isDark) NeoInkLight else ink, RoundedCornerShape(50))
                )
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { mode ->
                    val isSelected = mode == selected
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            NeoInkLight
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(150),
                        label = "filter_text_${mode.name}"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(50))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(mode) }
                            )
                            .testTag("library_chip_${mode.name.lowercase()}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.label,
                            fontSize = 11.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = textColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modern iOS vertical book list matching reference design:
 * - Rounded book cover thumbnail on left (`50.dp x 72.dp`)
 * - Bold black title (`15.sp`), gray author subtitle (`13.sp`)
 * - Coral-red linear reading progress bar + percentage + soft pink pill tag with red text
 * - Three-dot overflow menu (`•••`) on far right
 * - Generous bottom content padding (`96.dp`) to clear the Floating Bottom Navigation Pill
 */
@Composable
private fun ModernBookVerticalList(
    books: List<LibraryBook>,
    isMultiSelectMode: Boolean,
    selectedBookIds: Set<String>,
    dividerColor: Color,
    deleteLabel: String,
    onToggleSelect: (String) -> Unit,
    onOpenBook: (LibraryBook) -> Unit,
    onAddToCollection: (LibraryBook) -> Unit,
    onDeleteBook: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_books_list"),
        contentPadding = PaddingValues(top = 4.dp, bottom = 98.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = books,
            key = { _, item -> item.id }
        ) { _, book ->
            ModernBookRowItem(
                book = book,
                isMultiSelectMode = isMultiSelectMode,
                isSelected = book.id in selectedBookIds,
                deleteLabel = deleteLabel,
                modifier = Modifier.animateItem(),
                onClick = {
                    if (isMultiSelectMode) {
                        onToggleSelect(book.id)
                    } else {
                        onOpenBook(book)
                    }
                },
                onAddToCollectionClick = { onAddToCollection(book) },
                onDeleteClick = { onDeleteBook(book.id) }
            )
        }
    }
}

@Composable
private fun ModernBookRowItem(
    book: LibraryBook,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    deleteLabel: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    var menuExpanded by remember { mutableStateOf(false) }

    val progressPercent = remember(book.lastChapterIndex, book.chapterCount, book.lastOpenedAtMillis) {
        computeBookProgressPercent(book)
    }
    val progressFraction = (progressPercent / 100f).coerceIn(0f, 1f)
    val chapterBadgeText = remember(book.lastChapterIndex, book.chapterCount, book.sourceType) {
        if (book.chapterCount > 0) {
            "Ch. ${(book.lastChapterIndex + 1).coerceAtMost(book.chapterCount)}/${book.chapterCount}"
        } else {
            book.sourceType.uppercase().ifBlank { "EPUB" }
        }
    }

    val ink = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()

    NeoBrutalistPressBox(
        onClick = onClick,
        isPill = false,
        cornerRadius = 14.dp,
        shadowOffset = 3.dp,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = neoPaperSurfaceColor(),
        borderColor = ink,
        shadowColor = hardShadow,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(94.dp)
            .testTag("library_book_row_${book.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 9.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = LibraryCoralRed),
                modifier = Modifier.padding(end = 4.dp)
            )
        }

        // Left: Rounded Book Cover Thumbnail
        LibraryBookCoverThumbnail(
            title = book.title,
            coverPath = book.coverPath,
            width = 50.dp,
            height = 72.dp,
            cornerRadius = 8.dp
        )

        Spacer(modifier = Modifier.width(13.dp))

        // Center: Bold Title, Gray Author, Coral-Red Progress Bar + % + Soft Pink Pill Tag
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = book.title,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = book.author.ifBlank { "Chưa rõ tác giả" },
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Coral-red progress bar
                Box(
                    modifier = Modifier
                        .width(86.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB))
                ) {
                    if (progressFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction.coerceAtLeast(0.06f))
                                .clip(RoundedCornerShape(50))
                                .background(LibraryCoralRed)
                        )
                    }
                }

                // Percentage label
                Text(
                    text = "$progressPercent%",
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )

                // Soft pink pill badge with red text (e.g., Ch. 3/20)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isDark) Color(0xFF3E1820) else LibraryCoralSoft
                ) {
                    Text(
                        text = chapterBadgeText,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFFFF8096) else LibraryCoralText,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Far right: Three-dot button (•••)
        Box {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { menuExpanded = true }
                    .testTag("library_book_menu_${book.id}"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "Tùy chọn sách",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            Neo3DDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = neoPaperSurfaceColor()
            ) {
                val itemDividerColor = neoInkBorderColor().copy(alpha = 0.16f)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Đọc sách",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 1.dp,
                    color = itemDividerColor
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Thêm vào bộ sưu tập",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.CollectionsBookmark,
                            contentDescription = null,
                            tint = LibraryCoralRed,
                            modifier = Modifier.size(17.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onAddToCollectionClick()
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = 1.dp,
                    color = itemDividerColor
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = deleteLabel,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = IosRed
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = IosRed,
                            modifier = Modifier.size(17.dp)
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
}

@Composable
private fun LibraryCategoryGroupList(
    icon: ImageVector,
    emptyTitle: String,
    emptySubtitle: String,
    groups: List<Pair<String, List<LibraryBook>>>,
    subtleBorder: Color,
    dividerColor: Color,
    onOpenGroup: (String, List<LibraryBook>) -> Unit
) {
    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()
    if (groups.isEmpty()) {
        LibraryEmptyStateCard(
            icon = icon,
            title = emptyTitle,
            subtitle = emptySubtitle
        )
    } else {
        val cardShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 3.dp, bottom = 3.dp)
                .neoHardOffsetShadow(
                    shadowColor = hardShadow,
                    offsetX = 3.dp,
                    offsetY = 3.dp,
                    cornerRadius = 14.dp
                )
                .clip(cardShape)
                .background(neoPaperSurfaceColor())
                .border(1.5.dp, inkBorder, cardShape)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 98.dp)
            ) {
                itemsIndexed(groups) { idx, (label, groupBooks) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clickable { onOpenGroup(label, groupBooks) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(neoSecondaryPaperColor())
                                .border(1.2.dp, inkBorder, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = LibraryCoralRed,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${groupBooks.size} cuốn sách",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (idx < groups.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp, end = 14.dp),
                            thickness = 0.7.dp,
                            color = dividerColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionsTabContent(
    collections: List<BookCollection>,
    subtleBorder: Color,
    dividerColor: Color,
    accentBlue: Color,
    isSeriesMode: Boolean,
    onCreateCollectionClick: () -> Unit,
    onOpenCollection: (BookCollection) -> Unit,
    onAddBooksToCollectionClick: (BookCollection) -> Unit,
    onDeleteCollection: (String) -> Unit
) {
    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()
    val createLabel = if (isSeriesMode) "Tạo bộ sách mới" else "Tạo bộ sưu tập"

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            NeoBrutalistPressBox(
                onClick = onCreateCollectionClick,
                isPill = true,
                shadowOffset = 2.5.dp,
                pressDepth = 1.8.dp,
                containerColor = neoPaperSurfaceColor(),
                borderColor = inkBorder,
                shadowColor = hardShadow,
                modifier = Modifier.testTag("create_collection_pill_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = LibraryCoralRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = createLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        if (collections.isEmpty()) {
            LibraryEmptyStateCard(
                icon = Icons.Rounded.CollectionsBookmark,
                title = if (isSeriesMode) "Chưa có bộ sách nào" else "Chưa có bộ sưu tập nào",
                subtitle = "Tạo bộ sách hoặc bộ sưu tập để nhóm các tác phẩm yêu thích theo chủ đề."
            )
        } else {
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 3.dp, bottom = 3.dp)
                    .neoHardOffsetShadow(
                        shadowColor = hardShadow,
                        offsetX = 3.dp,
                        offsetY = 3.dp,
                        cornerRadius = 14.dp
                    )
                    .clip(shape)
                    .background(neoPaperSurfaceColor())
                    .border(1.5.dp, inkBorder, shape)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collections_list"),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 98.dp)
                ) {
                    itemsIndexed(
                        items = collections,
                        key = { _, item -> item.id }
                    ) { index, col ->
                        var colMenuOpen by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clickable { onOpenCollection(col) }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .testTag("collection_row_${col.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = LibraryCoralRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = col.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${col.bookIds.size} cuốn sách",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }

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
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accentBlue,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { colMenuOpen = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = "Tùy chọn bộ sưu tập",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Neo3DDropdownMenu(
                                    expanded = colMenuOpen,
                                    onDismissRequest = { colMenuOpen = false },
                                    containerColor = neoPaperSurfaceColor()
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "Xóa bộ sưu tập",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
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
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        if (index < collections.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 42.dp, end = 12.dp),
                                thickness = 0.7.dp,
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
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 94.dp),
        contentAlignment = Alignment.Center
    ) {
        NeoPaperStackContainer(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            cornerRadius = 14.dp,
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (actionLabel != null && onActionClick != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    NeoBrutalistPressBox(
                        onClick = onActionClick,
                        isPill = true,
                        shadowOffset = 2.5.dp,
                        pressDepth = 1.8.dp,
                        containerColor = IosBlue,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            text = actionLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun computeBookProgressPercent(book: LibraryBook): Int {
    if (book.chapterCount <= 0) return 0
    if (book.lastOpenedAtMillis <= 0L && book.lastChapterIndex == 0 && book.lastParagraphIndex == 0) {
        return 0
    }
    val raw = if (book.chapterCount == 1) {
        if (book.lastParagraphIndex > 0) 25 else 5
    } else {
        (((book.lastChapterIndex + 1).toFloat() / book.chapterCount.toFloat()) * 100f).roundToInt()
    }
    return raw.coerceIn(1, 100)
}
