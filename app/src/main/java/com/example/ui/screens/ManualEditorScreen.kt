package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pipeline.ManualChapterContent
import com.example.pipeline.ManualSearchMatch
import com.example.pipeline.ManualTocEntry
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SaveMode
import com.example.ui.ManualEditorUiState
import com.example.ui.SaveUiState
import com.example.ui.components.AddManualTocDialog
import com.example.ui.components.IosSaveSuccessDialog
import com.example.ui.components.ManualTocOverlayDialog
import com.example.ui.components.SaveDialog
import com.example.ui.components.SepiaAccent
import com.example.ui.components.SepiaAccentSoft
import com.example.ui.components.SepiaBg
import com.example.ui.components.SepiaBorder
import com.example.ui.components.SepiaCard
import com.example.ui.components.SepiaChrome
import com.example.ui.components.SepiaDiffCurrBg
import com.example.ui.components.SepiaDiffOrigBg
import com.example.ui.components.SepiaFindReplaceBar
import com.example.ui.components.SepiaInk
import com.example.ui.components.SepiaInkMuted
import com.example.ui.components.SepiaSearchActive
import com.example.ui.components.SepiaSearchHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Upgrade 1.1 Minimalist Vintage-Paper Sepia Manual Reader & Inline Editor (Pure Jetpack Compose).
 * - Background `#F4ECD8`, primary ink `#3B2F2F`, same-tone `#EFE5CE` compact top bar
 * - Top-Left: `☰` (TOC drawer) + compact pill `Lưu`
 * - Top-Right: compact pill `Sửa` + Search (`Find & Replace`) + `BookmarkAdd` (Manual TOC) + `ErrorOutline` menu (`a`/`A` + `Splitscreen` Compare)
 * - Supports Read-only mode, Inline Edit mode with Undo, and 2-Half Synchronized Split-Screen Comparison
 */
@Composable
fun ManualEditorScreen(
    editorState: ManualEditorUiState,
    snapshot: ProcessingSnapshot,
    saveUiState: SaveUiState,
    customSaveFolderLabel: String?,
    onSelectChapter: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleCompareMode: () -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onUpdateParagraph: (chapterIndex: Int, paragraphIndex: Int, newText: String) -> Unit,
    onUndoEdit: () -> Unit,
    onAddManualToc: (chapterIndex: Int, paragraphIndex: Int, title: String) -> Unit,
    onRemoveManualToc: (String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onNavigateSearchMatch: (Int) -> Unit,
    onReplaceCurrentMatch: () -> Unit,
    onReplaceAllMatches: () -> Unit,
    onClearFeedback: () -> Unit,
    onPickSaveFolderClick: () -> Unit,
    onSaveWithMode: (SaveMode) -> Unit,
    onOpenSavedFile: (absolutePath: String?, uriString: String?) -> Unit,
    onBackToReview: () -> Unit,
    onFinishToHome: () -> Unit,
    onDismissSaveError: () -> Unit
) {
    var showTocDialog by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    var showAddTocDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

    val mainListState = rememberLazyListState()
    val origListState = rememberLazyListState()
    val currListState = rememberLazyListState()

    BackHandler {
        when {
            showSearchBar -> {
                showSearchBar = false
                onSearchQueryChange("")
            }
            else -> onBackToReview()
        }
    }

    // Auto-dismiss transient feedback toast after 2.4s
    LaunchedEffect(editorState.feedbackMessage) {
        if (editorState.feedbackMessage != null) {
            delay(2400L)
            onClearFeedback()
        }
    }

    // Jump smoothly to target paragraph when switching chapters, clicking TOC bookmark, or stepping through Search matches
    LaunchedEffect(editorState.scrollRequestId, editorState.isCompareMode) {
        val target = editorState.scrollTargetParagraphIndex.coerceAtLeast(0)
        if (editorState.isCompareMode) {
            runCatching { origListState.scrollToItem(target) }
            runCatching { currListState.scrollToItem(target) }
        } else {
            runCatching { mainListState.scrollToItem(target) }
        }
    }

    // Bidirectional synchronized scroll between Original (top) and Current (bottom) in Compare Mode
    if (editorState.isCompareMode) {
        LaunchedEffect(origListState) {
            snapshotFlow { origListState.firstVisibleItemIndex to origListState.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (idx, offset) ->
                    if (origListState.isScrollInProgress && !currListState.isScrollInProgress) {
                        val maxCurr = (editorState.activeChapter?.currentParagraphs?.lastIndex ?: 0).coerceAtLeast(0)
                        currListState.scrollToItem(idx.coerceAtMost(maxCurr), offset)
                    }
                }
        }
        LaunchedEffect(currListState) {
            snapshotFlow { currListState.firstVisibleItemIndex to currListState.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (idx, offset) ->
                    if (currListState.isScrollInProgress && !origListState.isScrollInProgress) {
                        val maxOrig = (editorState.activeChapter?.originalParagraphs?.lastIndex ?: 0).coerceAtLeast(0)
                        origListState.scrollToItem(idx.coerceAtMost(maxOrig), offset)
                    }
                }
        }
    }

    val activeChapter = editorState.activeChapter
    val currentVisibleParagraphIdx = remember(
        editorState.isCompareMode,
        mainListState.firstVisibleItemIndex,
        currListState.firstVisibleItemIndex
    ) {
        if (editorState.isCompareMode) currListState.firstVisibleItemIndex
        else mainListState.firstVisibleItemIndex
    }

    val activeChapterBookmarks = remember(editorState.manualTocEntries, editorState.currentChapterIndex) {
        editorState.manualTocEntries
            .filter { it.chapterIndex == editorState.currentChapterIndex }
            .associateBy { it.paragraphIndex }
    }

    val activeChapterMatches = remember(editorState.searchMatches, editorState.currentChapterIndex) {
        editorState.searchMatches
            .mapIndexedNotNull { globalIdx, m ->
                if (m.chapterIndex == editorState.currentChapterIndex) globalIdx to m else null
            }
            .groupBy { it.second.paragraphIndex }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SepiaBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // 1. Compact Same-Tone Sepia Top Bar
            SepiaTopBar(
                isEditMode = editorState.isEditMode,
                isCompareMode = editorState.isCompareMode,
                isSearchOpen = showSearchBar,
                isSaving = saveUiState is SaveUiState.Saving,
                fontSizeSp = editorState.fontSizeSp,
                showMoreMenu = showMoreMenu,
                onOpenToc = { showTocDialog = true },
                onSaveClick = { showSaveDialog = true },
                onToggleEdit = onToggleEditMode,
                onToggleSearch = {
                    showSearchBar = !showSearchBar
                    if (!showSearchBar) onSearchQueryChange("")
                },
                onAddTocClick = { showAddTocDialog = true },
                onMoreMenuChange = { showMoreMenu = it },
                onDecreaseFont = { onAdjustFontSize(-1f) },
                onIncreaseFont = { onAdjustFontSize(+1f) },
                onToggleCompare = {
                    showMoreMenu = false
                    onToggleCompareMode()
                }
            )

            // 2. Optional Find & Replace Bar
            if (showSearchBar) {
                SepiaFindReplaceBar(
                    searchQuery = editorState.searchQuery,
                    replaceQuery = editorState.replaceQuery,
                    matchCount = editorState.searchMatches.size,
                    activeMatchIndex = editorState.activeMatchIndex,
                    onSearchQueryChange = onSearchQueryChange,
                    onReplaceQueryChange = onReplaceQueryChange,
                    onPrevMatch = { onNavigateSearchMatch(-1) },
                    onNextMatch = { onNavigateSearchMatch(+1) },
                    onReplaceOne = onReplaceCurrentMatch,
                    onReplaceAll = onReplaceAllMatches,
                    onClose = {
                        showSearchBar = false
                        onSearchQueryChange("")
                    }
                )
            }

            // 3. Subtle Chapter Strip (Chapter name + Undo button when editing + Save error if any)
            if (activeChapter != null) {
                SepiaChapterStrip(
                    chapterIndex = editorState.currentChapterIndex,
                    totalChapters = editorState.chapters.size,
                    chapterTitle = activeChapter.title,
                    isEditMode = editorState.isEditMode,
                    canUndo = editorState.canUndo,
                    onUndo = onUndoEdit
                )
            }

            if (saveUiState is SaveUiState.Error) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x28C0392B))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = saveUiState.message,
                        fontSize = 11.sp,
                        color = Color(0xFF8B1E1E),
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Đóng",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8B1E1E),
                        modifier = Modifier
                            .clickable(onClick = onDismissSaveError)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 4. Main Content Area (Loading / Single Sepia Reader-Editor / 2-Half Split-Screen Compare)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                when {
                    editorState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = SepiaAccent,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = "Đang mở trang sách…",
                                    fontSize = 13.sp,
                                    color = SepiaInkMuted
                                )
                            }
                        }
                    }

                    activeChapter == null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Không có nội dung để hiển thị.",
                                fontSize = 13.sp,
                                color = SepiaInkMuted
                            )
                        }
                    }

                    editorState.isCompareMode -> {
                        SepiaSplitCompareView(
                            chapter = activeChapter,
                            fontSizeSp = editorState.fontSizeSp,
                            isEditMode = editorState.isEditMode,
                            origListState = origListState,
                            currListState = currListState,
                            activeChapterBookmarks = activeChapterBookmarks,
                            activeChapterMatches = activeChapterMatches,
                            activeGlobalMatchIndex = editorState.activeMatchIndex,
                            onUpdateParagraph = { pIdx, text ->
                                onUpdateParagraph(editorState.currentChapterIndex, pIdx, text)
                            }
                        )
                    }

                    else -> {
                        SepiaSingleBookView(
                            chapter = activeChapter,
                            chapterIndex = editorState.currentChapterIndex,
                            totalChapters = editorState.chapters.size,
                            fontSizeSp = editorState.fontSizeSp,
                            isEditMode = editorState.isEditMode,
                            listState = mainListState,
                            activeChapterBookmarks = activeChapterBookmarks,
                            activeChapterMatches = activeChapterMatches,
                            activeGlobalMatchIndex = editorState.activeMatchIndex,
                            onUpdateParagraph = { pIdx, text ->
                                onUpdateParagraph(editorState.currentChapterIndex, pIdx, text)
                            },
                            onSelectChapter = { nextChap -> onSelectChapter(nextChap, 0) }
                        )
                    }
                }

                // Floating compact Sepia feedback pill (for bookmark added, undo, replace count)
                val feedback = editorState.feedbackMessage
                if (feedback != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SepiaInk.copy(alpha = 0.90f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Text(
                            text = feedback,
                            fontSize = 12.sp,
                            color = SepiaBg,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }

    // TOC Overlay Dialog (Chapters + Manual Bookmarks)
    ManualTocOverlayDialog(
        visible = showTocDialog,
        chapters = editorState.chapters,
        currentChapterIndex = editorState.currentChapterIndex,
        manualBookmarks = editorState.manualTocEntries,
        onSelectChapter = onSelectChapter,
        onDeleteBookmark = onRemoveManualToc,
        onDismiss = { showTocDialog = false }
    )

    // Add Manual TOC Bookmark Dialog (pre-filled from currently visible paragraph)
    if (activeChapter != null) {
        val visibleText = activeChapter.currentParagraphs
            .getOrNull(currentVisibleParagraphIdx)
            .orEmpty()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(64)
            .ifBlank { activeChapter.title }

        AddManualTocDialog(
            visible = showAddTocDialog,
            chapterTitle = activeChapter.title,
            paragraphIndex = currentVisibleParagraphIdx,
            suggestedTitle = visibleText,
            onConfirm = { title ->
                showAddTocDialog = false
                onAddManualToc(editorState.currentChapterIndex, currentVisibleParagraphIdx, title)
            },
            onDismiss = { showAddTocDialog = false }
        )
    }

    // Reused Compact Save Dialog (tappable folder picker + Lưu mới / Lưu thay thế)
    val sourceFile = snapshot.sourceFile
    if (sourceFile != null) {
        val displayFolder = customSaveFolderLabel ?: sourceFile.displayParentFolder
        SaveDialog(
            visible = showSaveDialog,
            displayFolderPath = displayFolder,
            onPickFolderClick = onPickSaveFolderClick,
            onSelectMode = { mode ->
                showSaveDialog = false
                onSaveWithMode(mode)
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    // Reused Save Success Dialog
    if (saveUiState is SaveUiState.Completed) {
        IosSaveSuccessDialog(
            result = saveUiState.result,
            onOpenEpub = {
                onOpenSavedFile(
                    saveUiState.result.savedAbsolutePath,
                    saveUiState.result.savedUriString
                )
            },
            onDone = onFinishToHome
        )
    }
}

@Composable
private fun SepiaTopBar(
    isEditMode: Boolean,
    isCompareMode: Boolean,
    isSearchOpen: Boolean,
    isSaving: Boolean,
    fontSizeSp: Float,
    showMoreMenu: Boolean,
    onOpenToc: () -> Unit,
    onSaveClick: () -> Unit,
    onToggleEdit: () -> Unit,
    onToggleSearch: () -> Unit,
    onAddTocClick: () -> Unit,
    onMoreMenuChange: (Boolean) -> Unit,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit,
    onToggleCompare: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SepiaChrome)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Top-Left 1: ☰ TOC Menu
            IconButton(
                onClick = onOpenToc,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("manual_toc_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Mở Mục lục",
                    tint = SepiaInk,
                    modifier = Modifier.size(21.dp)
                )
            }

            // Top-Left 2: Compact Pill "Lưu" right next to ☰
            Surface(
                shape = RoundedCornerShape(50),
                color = SepiaAccent,
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = !isSaving, onClick = onSaveClick)
                    .testTag("manual_save_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.6.dp,
                            color = Color.White
                        )
                    }
                    Text(
                        text = if (isSaving) "Đang lưu" else "Lưu",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Top-Right 1: Compact Pill "Sửa" (12.sp SemiBold) right next to Search
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isEditMode) SepiaAccent else SepiaCard,
                border = if (isEditMode) null else BorderStroke(0.9.dp, SepiaBorder),
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleEdit)
                    .testTag("manual_edit_toggle_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sửa",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEditMode) Color.White else SepiaInk,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.width(2.dp))

            // Top-Right 2: Search (Find & Replace)
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("manual_search_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Tìm kiếm và thay thế",
                    tint = if (isSearchOpen) SepiaAccent else SepiaInk,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Top-Right 3: Add Manual TOC Bookmark
            IconButton(
                onClick = onAddTocClick,
                modifier = Modifier
                    .size(38.dp)
                    .testTag("manual_add_toc_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdd,
                    contentDescription = "Thêm mục lục thủ công",
                    tint = SepiaInk,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Top-Right 4: Exclamation Menu (ONLY Font a/A + Split-Screen Compare)
            Box {
                IconButton(
                    onClick = { onMoreMenuChange(true) },
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("manual_more_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Tùy chọn cỡ chữ và so sánh",
                        tint = if (isCompareMode) SepiaAccent else SepiaInk,
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { onMoreMenuChange(false) },
                    modifier = Modifier
                        .background(SepiaCard)
                        .width(204.dp)
                ) {
                    // Option Group 1: Font size — ONLY 2 buttons small 'a' (-1.sp) and large 'A' (+1.sp), NO slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Cỡ chữ (${fontSizeSp.toInt()}sp)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SepiaInkMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SepiaChrome,
                                border = BorderStroke(0.8.dp, SepiaBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClick = onDecreaseFont)
                                    .testTag("manual_font_decrease")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "a",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SepiaInk
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SepiaChrome,
                                border = BorderStroke(0.8.dp, SepiaBorder),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClick = onIncreaseFont)
                                    .testTag("manual_font_increase")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "A",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SepiaInk
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.8.dp,
                        color = SepiaBorder,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    // Option Group 2: Split-Screen Compare toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleCompare)
                            .testTag("manual_compare_toggle")
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Splitscreen,
                            contentDescription = null,
                            tint = if (isCompareMode) SepiaAccent else SepiaInk,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "So sánh",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCompareMode) SepiaAccent else SepiaInk,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCompareMode) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Đang bật so sánh",
                                tint = SepiaAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.8.dp, color = SepiaBorder)
    }
}

@Composable
private fun SepiaChapterStrip(
    chapterIndex: Int,
    totalChapters: Int,
    chapterTitle: String,
    isEditMode: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SepiaBg)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "${chapterIndex + 1}/$totalChapters · $chapterTitle",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = SepiaInkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (isEditMode) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (canUndo) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SepiaChrome,
                        border = BorderStroke(0.7.dp, SepiaBorder),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onUndo)
                            .testTag("manual_undo_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = "Hoàn tác",
                                tint = SepiaInk,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Hoàn tác",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SepiaInk
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SepiaAccentSoft
                ) {
                    Text(
                        text = "Đang sửa",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SepiaAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SepiaSingleBookView(
    chapter: ManualChapterContent,
    chapterIndex: Int,
    totalChapters: Int,
    fontSizeSp: Float,
    isEditMode: Boolean,
    listState: LazyListState,
    activeChapterBookmarks: Map<Int, ManualTocEntry>,
    activeChapterMatches: Map<Int, List<Pair<Int, ManualSearchMatch>>>,
    activeGlobalMatchIndex: Int,
    onUpdateParagraph: (Int, String) -> Unit,
    onSelectChapter: (Int) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = chapter.currentParagraphs,
            key = { pIdx, _ -> "${chapter.fileName}_p_$pIdx" }
        ) { pIdx, paragraph ->
            SepiaParagraphItem(
                text = paragraph,
                isHeading = pIdx == 0 && paragraph.length <= 120,
                fontSizeSp = fontSizeSp,
                isEditMode = isEditMode,
                tintBg = Color.Transparent,
                bookmark = activeChapterBookmarks[pIdx],
                paragraphMatches = activeChapterMatches[pIdx].orEmpty(),
                activeGlobalMatchIndex = activeGlobalMatchIndex,
                onTextChange = { updated -> onUpdateParagraph(pIdx, updated) }
            )
        }

        // Compact chapter navigation footer
        item(key = "chapter_nav_footer") {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.7.dp, color = SepiaBorder)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (chapterIndex > 0) SepiaChrome else Color.Transparent,
                    border = if (chapterIndex > 0) BorderStroke(0.8.dp, SepiaBorder) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = chapterIndex > 0) {
                            onSelectChapter(chapterIndex - 1)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.NavigateBefore,
                            contentDescription = null,
                            tint = if (chapterIndex > 0) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Chương trước",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (chapterIndex > 0) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (chapterIndex < totalChapters - 1) SepiaChrome else Color.Transparent,
                    border = if (chapterIndex < totalChapters - 1) BorderStroke(0.8.dp, SepiaBorder) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = chapterIndex < totalChapters - 1) {
                            onSelectChapter(chapterIndex + 1)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chương sau",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (chapterIndex < totalChapters - 1) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                            contentDescription = null,
                            tint = if (chapterIndex < totalChapters - 1) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SepiaSplitCompareView(
    chapter: ManualChapterContent,
    fontSizeSp: Float,
    isEditMode: Boolean,
    origListState: LazyListState,
    currListState: LazyListState,
    activeChapterBookmarks: Map<Int, ManualTocEntry>,
    activeChapterMatches: Map<Int, List<Pair<Int, ManualSearchMatch>>>,
    activeGlobalMatchIndex: Int,
    onUpdateParagraph: (Int, String) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxSize()
    ) {
        // TOP HALF: Bản gốc (Chỉ đọc)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaChrome)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BẢN GỐC (CHỈ ĐỌC)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SepiaInkMuted,
                letterSpacing = 0.6.sp
            )
            Text(
                text = "${chapter.originalParagraphs.size} đoạn",
                fontSize = 10.sp,
                color = SepiaInkMuted
            )
        }

        LazyColumn(
            state = origListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = chapter.originalParagraphs,
                key = { pIdx, _ -> "${chapter.fileName}_orig_$pIdx" }
            ) { pIdx, origPara ->
                val counterpart = chapter.currentParagraphs.getOrNull(pIdx)
                val differs = counterpart != null && counterpart != origPara
                SepiaParagraphItem(
                    text = origPara,
                    isHeading = pIdx == 0 && origPara.length <= 120,
                    fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(12f),
                    isEditMode = false,
                    tintBg = if (differs) SepiaDiffOrigBg else Color.Transparent,
                    bookmark = null,
                    paragraphMatches = emptyList(),
                    activeGlobalMatchIndex = -1,
                    onTextChange = {}
                )
            }
        }

        // Center Divider between halves
        HorizontalDivider(thickness = 2.dp, color = SepiaAccent.copy(alpha = 0.45f))

        // BOTTOM HALF: Bản hiện tại / Đang sửa
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaChrome)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEditMode) "BẢN HIỆN TẠI · ĐANG SỬA" else "BẢN HIỆN TẠI",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEditMode) SepiaAccent else SepiaInkMuted,
                letterSpacing = 0.6.sp
            )
            Text(
                text = "${chapter.currentParagraphs.size} đoạn",
                fontSize = 10.sp,
                color = SepiaInkMuted
            )
        }

        LazyColumn(
            state = currListState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = chapter.currentParagraphs,
                key = { pIdx, _ -> "${chapter.fileName}_curr_$pIdx" }
            ) { pIdx, currPara ->
                val counterpart = chapter.originalParagraphs.getOrNull(pIdx)
                val differs = counterpart != null && counterpart != currPara
                SepiaParagraphItem(
                    text = currPara,
                    isHeading = pIdx == 0 && currPara.length <= 120,
                    fontSizeSp = (fontSizeSp - 1f).coerceAtLeast(12f),
                    isEditMode = isEditMode,
                    tintBg = if (differs) SepiaDiffCurrBg else Color.Transparent,
                    bookmark = activeChapterBookmarks[pIdx],
                    paragraphMatches = activeChapterMatches[pIdx].orEmpty(),
                    activeGlobalMatchIndex = activeGlobalMatchIndex,
                    onTextChange = { updated -> onUpdateParagraph(pIdx, updated) }
                )
            }
        }
    }
}

@Composable
private fun SepiaParagraphItem(
    text: String,
    isHeading: Boolean,
    fontSizeSp: Float,
    isEditMode: Boolean,
    tintBg: Color,
    bookmark: ManualTocEntry?,
    paragraphMatches: List<Pair<Int, ManualSearchMatch>>,
    activeGlobalMatchIndex: Int,
    onTextChange: (String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val effectiveFontSize = if (isHeading) (fontSizeSp + 2.5f).sp else fontSizeSp.sp
    val effectiveLineHeight = if (isHeading) ((fontSizeSp + 2.5f) * 1.38f).sp else (fontSizeSp * 1.62f).sp
    val effectiveWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal

    val containerBg = when {
        isEditMode && isFocused -> SepiaCard
        tintBg != Color.Transparent -> tintBg
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerBg)
            .then(
                if (isEditMode) {
                    Modifier.border(
                        width = if (isFocused) 1.2.dp else 0.6.dp,
                        color = if (isFocused) SepiaAccent else SepiaBorder.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = if (isEditMode || tintBg != Color.Transparent) 10.dp else 2.dp,
                vertical = if (isEditMode || tintBg != Color.Transparent) 7.dp else 2.dp
            )
    ) {
        if (bookmark != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bookmark,
                    contentDescription = null,
                    tint = SepiaAccent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = bookmark.title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SepiaAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isEditMode) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    fontSize = effectiveFontSize,
                    lineHeight = effectiveLineHeight,
                    fontWeight = effectiveWeight,
                    color = SepiaInk
                ),
                cursorBrush = SolidColor(SepiaAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isFocused = it.isFocused }
            )
        } else {
            val annotated = remember(text, paragraphMatches, activeGlobalMatchIndex) {
                if (paragraphMatches.isEmpty()) {
                    null
                } else {
                    buildAnnotatedString {
                        append(text)
                        for ((globalIdx, m) in paragraphMatches) {
                            val start = m.startOffset.coerceIn(0, text.length)
                            val end = m.endOffset.coerceIn(start, text.length)
                            if (start < end) {
                                addStyle(
                                    style = SpanStyle(
                                        background = if (globalIdx == activeGlobalMatchIndex) {
                                            SepiaSearchActive
                                        } else {
                                            SepiaSearchHighlight
                                        },
                                        color = SepiaInk,
                                        fontWeight = if (globalIdx == activeGlobalMatchIndex) {
                                            FontWeight.Bold
                                        } else {
                                            null
                                        }
                                    ),
                                    start = start,
                                    end = end
                                )
                            }
                        }
                    }
                }
            }

            if (annotated != null) {
                Text(
                    text = annotated,
                    fontSize = effectiveFontSize,
                    lineHeight = effectiveLineHeight,
                    fontWeight = effectiveWeight,
                    color = SepiaInk
                )
            } else {
                Text(
                    text = text,
                    fontSize = effectiveFontSize,
                    lineHeight = effectiveLineHeight,
                    fontWeight = effectiveWeight,
                    color = SepiaInk
                )
            }
        }
    }
}
