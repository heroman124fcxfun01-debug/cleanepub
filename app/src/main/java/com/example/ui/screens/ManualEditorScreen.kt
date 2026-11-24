package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.CustomReplaceRule
import com.example.pipeline.ManualChapterContent
import com.example.pipeline.ManualReadingMode
import com.example.pipeline.ManualSearchMatch
import com.example.pipeline.ManualTocEntry
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SaveMode
import com.example.pipeline.TextDiffHelper
import com.example.ui.ManualEditorUiState
import com.example.ui.SaveUiState
import com.example.ui.components.AddManualTocDialog
import com.example.ui.components.CustomReplaceDialog
import com.example.ui.components.IosSaveSuccessDialog
import com.example.ui.components.ManualTocOverlayDialog
import com.example.ui.components.SaveDialog
import com.example.ui.components.SepiaAccent
import com.example.ui.components.SepiaAccentSoft
import com.example.ui.components.SepiaActiveParagraphBg
import com.example.ui.components.SepiaBg
import com.example.ui.components.SepiaBorder
import com.example.ui.components.SepiaCard
import com.example.ui.components.SepiaChrome
import com.example.ui.components.SepiaFindReplaceBar
import com.example.ui.components.SepiaInk
import com.example.ui.components.SepiaInkMuted
import com.example.ui.components.SepiaSearchActive
import com.example.ui.components.SepiaSearchHighlight
import com.example.ui.components.UndoConfirmDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Upgrade 1.1 Sepia Manual Reader & Inline Editor (Pure Jetpack Compose, 100% Offline):
 * - Top-Left: `☰` (Editable TOC with "Sửa" rename/delete) | `Lưu` pill
 * - Top-Right: `Sửa` pill | Standalone `So sánh` icon | `🔍` Find & Replace | `BookmarkAdd` | `!` menu (`a`/`A` + `Cuộn dọc`/`Lướt ngang`)
 * - Compare Mode: Top half = Current book text (active paragraph highlighted in `#E8D8B5`, editable inline when `Sửa` is ON with live word-diff highlight), Bottom half = Original text with word-level diff highlights & auto-sync.
 * - Undo: Compact Undo trigger with iOS confirmation dialog + persisted "Không hỏi lại" Checkbox.
 */
@Composable
fun ManualEditorScreen(
    editorState: ManualEditorUiState,
    snapshot: ProcessingSnapshot,
    saveUiState: SaveUiState,
    customSaveFolderLabel: String?,
    autoSaveDraft: Boolean,
    customReplaceRules: List<CustomReplaceRule>,
    onSelectChapter: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onActiveParagraphChanged: (Int) -> Unit,
    onToggleEditMode: () -> Unit,
    onToggleCompareMode: () -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onSelectReadingMode: (ManualReadingMode) -> Unit,
    onUpdateParagraph: (chapterIndex: Int, paragraphIndex: Int, newText: String) -> Unit,
    onUndoEdit: () -> Unit,
    onSetSkipUndoConfirm: (Boolean) -> Unit,
    onAddManualToc: (chapterIndex: Int, paragraphIndex: Int, title: String) -> Unit,
    onRenameTocItem: (entryId: String, newTitle: String) -> Unit,
    onDeleteTocItem: (entryId: String) -> Unit,
    onSplitChapterAtActiveParagraph: () -> Unit,
    onMergeChapterWithNext: (chapterIndex: Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onNavigateSearchMatch: (Int) -> Unit,
    onReplaceCurrentMatch: () -> Unit,
    onReplaceAllMatches: () -> Unit,
    onSaveDraftClick: () -> Unit,
    onAutoSaveDraft: () -> Unit,
    onAddCustomReplaceRule: (findText: String, replaceText: String) -> Unit,
    onDeleteCustomReplaceRule: (id: String) -> Unit,
    onApplyAllCustomReplaceRules: () -> Unit,
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
    var showUndoDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showCustomReplaceDialog by remember { mutableStateOf(false) }

    val currListState = rememberLazyListState()
    val origListState = rememberLazyListState()

    BackHandler {
        when {
            showSearchBar -> {
                showSearchBar = false
                onSearchQueryChange("")
            }
            else -> onBackToReview()
        }
    }

    LaunchedEffect(editorState.feedbackMessage) {
        if (editorState.feedbackMessage != null) {
            delay(2400L)
            onClearFeedback()
        }
    }

    // Upgrade 1.2: Silent Auto-Save Draft every 2 minutes when enabled in Settings
    LaunchedEffect(autoSaveDraft, editorState.chapters.isNotEmpty()) {
        if (autoSaveDraft && editorState.chapters.isNotEmpty()) {
            while (true) {
                delay(120_000L)
                onAutoSaveDraft()
            }
        }
    }

    // Jump to target paragraph on chapter switch, TOC selection, or search step
    LaunchedEffect(editorState.scrollRequestId, editorState.readingMode) {
        val target = editorState.scrollTargetParagraphIndex.coerceAtLeast(0)
        if (editorState.readingMode == ManualReadingMode.VERTICAL_SCROLL) {
            runCatching { currListState.scrollToItem(target) }
        }
        if (editorState.isCompareMode) {
            val maxOrig = (editorState.activeChapter?.originalParagraphs?.lastIndex ?: 0).coerceAtLeast(0)
            runCatching { origListState.scrollToItem(target.coerceAtMost(maxOrig)) }
        }
    }

    // Track first visible paragraph as activeParagraphIndex while scrolling vertically
    if (editorState.readingMode == ManualReadingMode.VERTICAL_SCROLL) {
        LaunchedEffect(currListState) {
            snapshotFlow { currListState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { firstVisible ->
                    if (currListState.isScrollInProgress) {
                        onActiveParagraphChanged(firstVisible)
                    }
                }
        }
    }

    // Smoothly align bottom Original compare window whenever activeParagraphIndex changes or Compare turns ON
    LaunchedEffect(editorState.isCompareMode, editorState.activeParagraphIndex, editorState.currentChapterIndex) {
        if (editorState.isCompareMode) {
            val maxOrig = (editorState.activeChapter?.originalParagraphs?.lastIndex ?: 0).coerceAtLeast(0)
            val targetOrig = editorState.activeParagraphIndex.coerceIn(0, maxOrig)
            runCatching { origListState.animateScrollToItem(targetOrig) }
        }
    }

    val activeChapter = editorState.activeChapter

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

    fun triggerUndoClick() {
        if (!editorState.canUndo) return
        if (editorState.skipUndoConfirm) {
            onUndoEdit()
        } else {
            showUndoDialog = true
        }
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
            // 1. Compact Same-Tone Sepia Top Bar with Save Draft & standalone Compare icon
            SepiaTopBar(
                isEditMode = editorState.isEditMode,
                isCompareMode = editorState.isCompareMode,
                isSearchOpen = showSearchBar,
                isSaving = saveUiState is SaveUiState.Saving,
                fontSizeSp = editorState.fontSizeSp,
                readingMode = editorState.readingMode,
                showMoreMenu = showMoreMenu,
                onOpenToc = { showTocDialog = true },
                onSaveClick = { showSaveDialog = true },
                onSaveDraftClick = onSaveDraftClick,
                onHomeClick = { showExitDialog = true },
                onToggleEdit = onToggleEditMode,
                onToggleCompare = onToggleCompareMode,
                onToggleSearch = {
                    showSearchBar = !showSearchBar
                    if (!showSearchBar) onSearchQueryChange("")
                },
                onAddTocClick = { showAddTocDialog = true },
                onMoreMenuChange = { showMoreMenu = it },
                onDecreaseFont = { onAdjustFontSize(-1f) },
                onIncreaseFont = { onAdjustFontSize(+1f) },
                onSelectReadingMode = { mode ->
                    showMoreMenu = false
                    onSelectReadingMode(mode)
                }
            )

            // 2. Optional Find & Replace Bar (+ Quick Replace Dictionary button)
            if (showSearchBar) {
                SepiaFindReplaceBar(
                    searchQuery = editorState.searchQuery,
                    replaceQuery = editorState.replaceQuery,
                    matchCount = editorState.searchMatches.size,
                    activeMatchIndex = editorState.activeMatchIndex,
                    customReplaceCount = customReplaceRules.size,
                    onSearchQueryChange = onSearchQueryChange,
                    onReplaceQueryChange = onReplaceQueryChange,
                    onPrevMatch = { onNavigateSearchMatch(-1) },
                    onNextMatch = { onNavigateSearchMatch(+1) },
                    onReplaceOne = onReplaceCurrentMatch,
                    onReplaceAll = onReplaceAllMatches,
                    onOpenCustomReplace = { showCustomReplaceDialog = true },
                    onClose = {
                        showSearchBar = false
                        onSearchQueryChange("")
                    }
                )
            }

            // 3. Chapter Strip + Compact Undo Button + Edit Mode "Tách chương từ đây" Action Bar
            if (activeChapter != null) {
                SepiaChapterStrip(
                    chapterIndex = editorState.currentChapterIndex,
                    totalChapters = editorState.chapters.size,
                    chapterTitle = activeChapter.title,
                    activeParagraphIndex = editorState.activeParagraphIndex,
                    totalParagraphsInChapter = activeChapter.currentParagraphs.size,
                    isEditMode = editorState.isEditMode,
                    isCompareMode = editorState.isCompareMode,
                    canUndo = editorState.canUndo,
                    onUndoClick = ::triggerUndoClick,
                    onSplitChapterClick = onSplitChapterAtActiveParagraph
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

            // 4. Main Reading / Editing / Split-Screen Compare Content
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

                    else -> {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 680.dp)
                                .fillMaxSize()
                        ) {
                            // TOP HALF (or Full Screen when Compare OFF): Current Book Text
                            if (editorState.isCompareMode) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(SepiaChrome)
                                        .padding(horizontal = 14.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (editorState.isEditMode) {
                                            "BẢN HIỆN TẠI · CHẠM ĐỂ SỬA"
                                        } else {
                                            "BẢN HIỆN TẠI · ĐOẠN #${editorState.activeParagraphIndex + 1}"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SepiaAccent,
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = "${activeChapter.currentParagraphs.size} đoạn",
                                        fontSize = 10.sp,
                                        color = SepiaInkMuted
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                if (editorState.readingMode == ManualReadingMode.VERTICAL_SCROLL) {
                                    SepiaVerticalBookPane(
                                        chapter = activeChapter,
                                        chapterIndex = editorState.currentChapterIndex,
                                        totalChapters = editorState.chapters.size,
                                        activeParagraphIndex = editorState.activeParagraphIndex,
                                        fontSizeSp = editorState.fontSizeSp,
                                        isEditMode = editorState.isEditMode,
                                        isCompareMode = editorState.isCompareMode,
                                        listState = currListState,
                                        activeChapterBookmarks = activeChapterBookmarks,
                                        activeChapterMatches = activeChapterMatches,
                                        activeGlobalMatchIndex = editorState.activeMatchIndex,
                                        onSelectParagraph = onActiveParagraphChanged,
                                        onUpdateParagraph = { pIdx, text ->
                                            onUpdateParagraph(editorState.currentChapterIndex, pIdx, text)
                                        },
                                        onSelectChapter = { nextChap -> onSelectChapter(nextChap, 0) }
                                    )
                                } else {
                                    SepiaHorizontalPagerPane(
                                        chapter = activeChapter,
                                        chapterIndex = editorState.currentChapterIndex,
                                        totalChapters = editorState.chapters.size,
                                        activeParagraphIndex = editorState.activeParagraphIndex,
                                        scrollRequestId = editorState.scrollRequestId,
                                        scrollTargetParagraphIndex = editorState.scrollTargetParagraphIndex,
                                        fontSizeSp = editorState.fontSizeSp,
                                        isEditMode = editorState.isEditMode,
                                        isCompareMode = editorState.isCompareMode,
                                        activeChapterBookmarks = activeChapterBookmarks,
                                        activeChapterMatches = activeChapterMatches,
                                        activeGlobalMatchIndex = editorState.activeMatchIndex,
                                        onSelectParagraph = onActiveParagraphChanged,
                                        onUpdateParagraph = { pIdx, text ->
                                            onUpdateParagraph(editorState.currentChapterIndex, pIdx, text)
                                        },
                                        onSelectChapter = { nextChap -> onSelectChapter(nextChap, 0) }
                                    )
                                }
                            }

                            // BOTTOM HALF (Only when Compare Mode is ON): Original Reference Pane with Diff Highlights
                            if (editorState.isCompareMode) {
                                HorizontalDivider(
                                    thickness = 2.dp,
                                    color = SepiaAccent.copy(alpha = 0.55f)
                                )
                                SepiaOriginalCompareBottomPane(
                                    chapter = activeChapter,
                                    activeParagraphIndex = editorState.activeParagraphIndex,
                                    fontSizeSp = (editorState.fontSizeSp - 0.5f).coerceAtLeast(12f),
                                    origListState = origListState,
                                    onTapOriginalParagraph = onActiveParagraphChanged,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // Floating transient feedback pill
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

    // Editable Table of Contents Overlay (with dedicated "Sửa" toggle for Merge, Rename & Delete)
    ManualTocOverlayDialog(
        visible = showTocDialog,
        tocItems = editorState.editableTocItems,
        currentChapterIndex = editorState.currentChapterIndex,
        totalChapters = editorState.chapters.size,
        onSelectTarget = onSelectChapter,
        onRenameItem = onRenameTocItem,
        onDeleteItem = onDeleteTocItem,
        onMergeChapterWithNext = onMergeChapterWithNext,
        onDismiss = { showTocDialog = false }
    )

    // Undo Confirmation Dialog with "Không hỏi lại" Checkbox
    UndoConfirmDialog(
        visible = showUndoDialog,
        onConfirmUndo = { dontAskAgain ->
            showUndoDialog = false
            if (dontAskAgain) {
                onSetSkipUndoConfirm(true)
            }
            onUndoEdit()
        },
        onDismiss = { showUndoDialog = false }
    )

    // Add Manual TOC Bookmark Dialog at activeParagraphIndex
    if (activeChapter != null) {
        val targetParaIdx = editorState.activeParagraphIndex
            .coerceIn(0, (activeChapter.currentParagraphs.lastIndex).coerceAtLeast(0))
        val visibleText = activeChapter.currentParagraphs
            .getOrNull(targetParaIdx)
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
            paragraphIndex = targetParaIdx,
            suggestedTitle = visibleText,
            onConfirm = { title ->
                showAddTocDialog = false
                onAddManualToc(editorState.currentChapterIndex, targetParaIdx, title)
            },
            onDismiss = { showAddTocDialog = false }
        )
    }

    // Compact Save Dialog
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

    // 2-Tap Exit Confirmation Dialog when pressing Home on Top Bar
    ExitConfirmDialog(
        visible = showExitDialog,
        onConfirmExit = {
            showExitDialog = false
            onFinishToHome()
        },
        onDismiss = { showExitDialog = false }
    )

    // Personal Quick-Replace Dictionary Dialog with 1-tap "Áp dụng toàn bộ sách"
    CustomReplaceDialog(
        visible = showCustomReplaceDialog,
        rules = customReplaceRules,
        canApplyToBook = editorState.chapters.isNotEmpty(),
        onAddRule = onAddCustomReplaceRule,
        onDeleteRule = onDeleteCustomReplaceRule,
        onApplyAllToBook = onApplyAllCustomReplaceRules,
        onDismiss = { showCustomReplaceDialog = false }
    )

    // Save Success Dialog
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

/**
 * Floating iOS Rounded-Square Exit Confirmation Dialog with 2-Tap Guard:
 * - Tap 1 on "Hủy sửa chữa" -> switches to warning red (#FF3B30) "Bấm lần nữa để xác nhận"
 * - Tap 2 -> cancels editing session & navigates back to MainScreen
 * - Tap outside dialog -> dismisses cleanly & resets tap counter to 0
 */
@Composable
fun ExitConfirmDialog(
    visible: Boolean,
    onConfirmExit: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var confirmStep by remember { mutableIntStateOf(0) }
    val isArmed = confirmStep >= 1

    val buttonBg by animateColorAsState(
        targetValue = if (isArmed) Color(0xFFFF3B30) else Color(0xFFFFE5DC),
        label = "exit_confirm_btn_bg"
    )
    val buttonTextColor by animateColorAsState(
        targetValue = if (isArmed) Color.White else Color(0xFFD84315),
        label = "exit_confirm_btn_text"
    )
    val dialogShape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = {
            confirmStep = 0
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = dialogShape,
            color = SepiaBg,
            border = BorderStroke(1.dp, SepiaBorder),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = dialogShape,
                    ambientColor = Color.Black.copy(alpha = 0.22f),
                    spotColor = Color.Black.copy(alpha = 0.34f)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isArmed) Color(0x26FF3B30) else Color(0x24E67E22)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (isArmed) Color(0xFFFF3B30) else Color(0xFFD84315),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Thoát về màn hình chính?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SepiaInk,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Bạn có chấp nhận hủy việc sửa chữa và về màn hình chính?",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = SepiaInkMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // 2-Step Confirmation Pill Button
                Surface(
                    shape = RoundedCornerShape(50),
                    color = buttonBg,
                    border = if (isArmed) null else BorderStroke(1.dp, Color(0xFFFFA58A)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            if (confirmStep == 0) {
                                confirmStep = 1
                            } else {
                                confirmStep = 0
                                onConfirmExit()
                            }
                        }
                        .testTag("exit_confirm_two_step_button")
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isArmed) "Bấm lần nữa để xác nhận" else "Hủy sửa chữa",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = buttonTextColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Chạm ra ngoài để tiếp tục sửa",
                    fontSize = 11.sp,
                    color = SepiaInkMuted.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SepiaTopBar(
    isEditMode: Boolean,
    isCompareMode: Boolean,
    isSearchOpen: Boolean,
    isSaving: Boolean,
    fontSizeSp: Float,
    readingMode: ManualReadingMode,
    showMoreMenu: Boolean,
    onOpenToc: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveDraftClick: () -> Unit,
    onHomeClick: () -> Unit,
    onToggleEdit: () -> Unit,
    onToggleCompare: () -> Unit,
    onToggleSearch: () -> Unit,
    onAddTocClick: () -> Unit,
    onMoreMenuChange: (Boolean) -> Unit,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit,
    onSelectReadingMode: (ManualReadingMode) -> Unit
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
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Top-Left 1: ☰ TOC Menu
            IconButton(
                onClick = onOpenToc,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("manual_toc_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Mở Mục lục",
                    tint = SepiaInk,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Top-Left 2: Compact Pill "Lưu"
            Surface(
                shape = RoundedCornerShape(50),
                color = SepiaAccent,
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = !isSaving, onClick = onSaveClick)
                    .testTag("manual_save_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.5.dp,
                            color = Color.White
                        )
                    }
                    Text(
                        text = if (isSaving) "Lưu…" else "Lưu",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Top-Left 3 (Upgrade 1.2): Compact "Lưu nháp" Icon right next to "Lưu"
            IconButton(
                onClick = onSaveDraftClick,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("manual_save_draft_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SaveAs,
                    contentDescription = "Lưu nháp",
                    tint = SepiaAccent,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Top-Left 4: Compact Home Icon (Sepia tone)
            IconButton(
                onClick = onHomeClick,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("manual_home_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Home,
                    contentDescription = "Về màn hình chính",
                    tint = SepiaInk,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Top-Right 1: Compact Pill "Sửa"
            Surface(
                shape = RoundedCornerShape(50),
                color = if (isEditMode) SepiaAccent else SepiaCard,
                border = if (isEditMode) null else BorderStroke(0.9.dp, SepiaBorder),
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onToggleEdit)
                    .testTag("manual_edit_toggle_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 9.dp),
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

            // Top-Right 2: Standalone "So sánh" Icon right next to "Sửa"
            IconButton(
                onClick = onToggleCompare,
                modifier = Modifier
                    .size(33.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (isCompareMode) SepiaAccentSoft else Color.Transparent)
                    .testTag("manual_compare_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Splitscreen,
                    contentDescription = "Bật/tắt So sánh với bản gốc",
                    tint = if (isCompareMode) SepiaAccent else SepiaInk,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Top-Right 3: Search (Find & Replace)
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier
                    .size(33.dp)
                    .testTag("manual_search_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Tìm kiếm và thay thế",
                    tint = if (isSearchOpen) SepiaAccent else SepiaInk,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Top-Right 4: Add Manual TOC Bookmark
            IconButton(
                onClick = onAddTocClick,
                modifier = Modifier
                    .size(33.dp)
                    .testTag("manual_add_toc_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdd,
                    contentDescription = "Thêm mục lục thủ công",
                    tint = SepiaInk,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Top-Right 5: Exclamation Menu (!) — ONLY Font a/A + Vertical/Horizontal Reading Mode
            Box {
                IconButton(
                    onClick = { onMoreMenuChange(true) },
                    modifier = Modifier
                        .size(33.dp)
                        .testTag("manual_more_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Cỡ chữ và chế độ lật trang",
                        tint = SepiaInk,
                        modifier = Modifier.size(18.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { onMoreMenuChange(false) },
                    modifier = Modifier
                        .background(SepiaCard)
                        .width(214.dp)
                ) {
                    // Section 1: Font size (ONLY 2 buttons 'a' and 'A')
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

                    // Section 2: Page-turn mode (Cuộn dọc / Lướt ngang)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Chế độ lật trang",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SepiaInkMuted
                        )

                        ReadingModeOptionRow(
                            label = "Cuộn dọc",
                            icon = Icons.Rounded.SwapVert,
                            selected = readingMode == ManualReadingMode.VERTICAL_SCROLL,
                            onClick = { onSelectReadingMode(ManualReadingMode.VERTICAL_SCROLL) },
                            modifier = Modifier.testTag("manual_mode_vertical")
                        )

                        ReadingModeOptionRow(
                            label = "Lướt ngang",
                            icon = Icons.Rounded.AutoStories,
                            selected = readingMode == ManualReadingMode.HORIZONTAL_PAGER,
                            onClick = { onSelectReadingMode(ManualReadingMode.HORIZONTAL_PAGER) },
                            modifier = Modifier.testTag("manual_mode_horizontal")
                        )
                    }
                }
            }
        }
        HorizontalDivider(thickness = 0.8.dp, color = SepiaBorder)
    }
}

@Composable
private fun ReadingModeOptionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SepiaAccentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) SepiaAccent else SepiaInk,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) SepiaAccent else SepiaInk,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Đang chọn",
                tint = SepiaAccent,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun SepiaChapterStrip(
    chapterIndex: Int,
    totalChapters: Int,
    chapterTitle: String,
    activeParagraphIndex: Int,
    totalParagraphsInChapter: Int,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    canUndo: Boolean,
    onUndoClick: () -> Unit,
    onSplitChapterClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SepiaBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 5.dp),
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
                            .clickable(onClick = onUndoClick)
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

                if (isEditMode) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SepiaAccentSoft
                    ) {
                        Text(
                            text = if (isCompareMode) "Đang sửa · So sánh" else "Đang sửa",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SepiaAccent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Upgrade 1.3: Compact Split Chapter action bar shown when Edit Mode is active
        if (isEditMode && totalParagraphsInChapter > 1) {
            val canSplitHere = activeParagraphIndex in 1 until totalParagraphsInChapter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SepiaChrome.copy(alpha = 0.72f))
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Đang chọn đoạn #${activeParagraphIndex + 1}/$totalParagraphsInChapter",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = SepiaInkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (canSplitHere) SepiaAccent else SepiaCard,
                    border = if (canSplitHere) null else BorderStroke(0.8.dp, SepiaBorder),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onSplitChapterClick)
                        .testTag("manual_split_chapter_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallSplit,
                            contentDescription = "Tách chương từ đây",
                            tint = if (canSplitHere) Color.White else SepiaInkMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Tách chương từ đây",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (canSplitHere) Color.White else SepiaInkMuted,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.6.dp, color = SepiaBorder)
        }
    }
}

@Composable
private fun SepiaVerticalBookPane(
    chapter: ManualChapterContent,
    chapterIndex: Int,
    totalChapters: Int,
    activeParagraphIndex: Int,
    fontSizeSp: Float,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    listState: LazyListState,
    activeChapterBookmarks: Map<Int, ManualTocEntry>,
    activeChapterMatches: Map<Int, List<Pair<Int, ManualSearchMatch>>>,
    activeGlobalMatchIndex: Int,
    onSelectParagraph: (Int) -> Unit,
    onUpdateParagraph: (Int, String) -> Unit,
    onSelectChapter: (Int) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = chapter.currentParagraphs,
            key = { pIdx, _ -> "${chapter.fileName}_p_$pIdx" }
        ) { pIdx, paragraph ->
            val origPara = chapter.originalParagraphs.getOrNull(pIdx).orEmpty()
            val isActiveHighlightPara = (isCompareMode || isEditMode) && pIdx == activeParagraphIndex

            SepiaCurrentParagraphItem(
                text = paragraph,
                originalText = origPara,
                isHeading = pIdx == 0 && paragraph.length <= 120,
                fontSizeSp = fontSizeSp,
                isEditMode = isEditMode,
                isCompareMode = isCompareMode,
                isActiveHighlight = isActiveHighlightPara,
                bookmark = activeChapterBookmarks[pIdx],
                paragraphMatches = activeChapterMatches[pIdx].orEmpty(),
                activeGlobalMatchIndex = activeGlobalMatchIndex,
                onActivate = { onSelectParagraph(pIdx) },
                onTextChange = { updated -> onUpdateParagraph(pIdx, updated) }
            )
        }

        // Compact chapter navigation footer
        item(key = "chapter_nav_footer") {
            ChapterNavFooter(
                chapterIndex = chapterIndex,
                totalChapters = totalChapters,
                onSelectChapter = onSelectChapter
            )
        }
    }
}

/**
 * Horizontal Pager ("Lướt ngang") view that groups paragraphs into screen-fitted pages.
 * Automatically locks horizontal swipe when `isEditMode == true` to prevent gesture conflicts while typing.
 */
@Composable
private fun SepiaHorizontalPagerPane(
    chapter: ManualChapterContent,
    chapterIndex: Int,
    totalChapters: Int,
    activeParagraphIndex: Int,
    scrollRequestId: Long,
    scrollTargetParagraphIndex: Int,
    fontSizeSp: Float,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    activeChapterBookmarks: Map<Int, ManualTocEntry>,
    activeChapterMatches: Map<Int, List<Pair<Int, ManualSearchMatch>>>,
    activeGlobalMatchIndex: Int,
    onSelectParagraph: (Int) -> Unit,
    onUpdateParagraph: (Int, String) -> Unit,
    onSelectChapter: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableHeightDp: Dp = (maxHeight - 44.dp).coerceAtLeast(180.dp)
        val availableWidthDp: Dp = (maxWidth - 36.dp).coerceAtLeast(220.dp)

        // Paginate paragraph indices dynamically to fit available screen height cleanly
        val pages: List<List<Int>> = remember(
            chapter.fileName,
            chapter.currentParagraphs.size,
            fontSizeSp,
            availableHeightDp,
            availableWidthDp
        ) {
            paginateParagraphIndices(
                paragraphs = chapter.currentParagraphs,
                fontSizeSp = fontSizeSp,
                availableHeightDp = availableHeightDp.value,
                availableWidthDp = availableWidthDp.value
            )
        }

        val pageCount = pages.size.coerceAtLeast(1)
        val initialPage = remember(chapter.fileName, pageCount) {
            pages.indexOfFirst { activeParagraphIndex in it }.coerceAtLeast(0)
        }
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })

        // Sync page when TOC / Search jumps to a paragraph
        LaunchedEffect(scrollRequestId, pages) {
            val targetPage = pages.indexOfFirst { scrollTargetParagraphIndex in it }
            if (targetPage >= 0 && targetPage != pagerState.currentPage) {
                runCatching { pagerState.scrollToPage(targetPage) }
            }
        }

        // Keep activeParagraphIndex synced to the first paragraph of the currently visible horizontal page
        LaunchedEffect(pagerState.currentPage, pages) {
            val firstParaOnPage = pages.getOrNull(pagerState.currentPage)?.firstOrNull()
            if (firstParaOnPage != null && activeParagraphIndex !in pages[pagerState.currentPage]) {
                onSelectParagraph(firstParaOnPage)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isEditMode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("manual_horizontal_pager")
            ) { pageIndex ->
                val paraIndices = pages.getOrNull(pageIndex).orEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (pIdx in paraIndices) {
                        val paragraph = chapter.currentParagraphs.getOrNull(pIdx) ?: continue
                        val origPara = chapter.originalParagraphs.getOrNull(pIdx).orEmpty()
                        val isActiveHighlightPara = (isCompareMode || isEditMode) && pIdx == activeParagraphIndex

                        SepiaCurrentParagraphItem(
                            text = paragraph,
                            originalText = origPara,
                            isHeading = pIdx == 0 && paragraph.length <= 120,
                            fontSizeSp = fontSizeSp,
                            isEditMode = isEditMode,
                            isCompareMode = isCompareMode,
                            isActiveHighlight = isActiveHighlightPara,
                            bookmark = activeChapterBookmarks[pIdx],
                            paragraphMatches = activeChapterMatches[pIdx].orEmpty(),
                            activeGlobalMatchIndex = activeGlobalMatchIndex,
                            onActivate = { onSelectParagraph(pIdx) },
                            onTextChange = { updated -> onUpdateParagraph(pIdx, updated) }
                        )
                    }
                }
            }

            // Compact Horizontal Page Indicator + Prev/Next Page (works even when swipe locked in Edit Mode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SepiaChrome)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val canPrevPage = pagerState.currentPage > 0 || chapterIndex > 0
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (canPrevPage) SepiaCard else Color.Transparent,
                    border = if (canPrevPage) BorderStroke(0.7.dp, SepiaBorder) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = canPrevPage) {
                            if (pagerState.currentPage > 0) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                            } else if (chapterIndex > 0) {
                                onSelectChapter(chapterIndex - 1)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.NavigateBefore,
                            contentDescription = "Trang trước",
                            tint = if (canPrevPage) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (pagerState.currentPage == 0 && chapterIndex > 0) "Chương trước" else "Trước",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (canPrevPage) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f)
                        )
                    }
                }

                Text(
                    text = buildString {
                        append("Trang ${pagerState.currentPage + 1}/$pageCount")
                        if (isEditMode) append(" · Khóa vuốt")
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isEditMode) SepiaAccent else SepiaInkMuted,
                    maxLines = 1
                )

                val canNextPage = pagerState.currentPage < pageCount - 1 || chapterIndex < totalChapters - 1
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (canNextPage) SepiaCard else Color.Transparent,
                    border = if (canNextPage) BorderStroke(0.7.dp, SepiaBorder) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = canNextPage) {
                            if (pagerState.currentPage < pageCount - 1) {
                                scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                            } else if (chapterIndex < totalChapters - 1) {
                                onSelectChapter(chapterIndex + 1)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (pagerState.currentPage == pageCount - 1 && chapterIndex < totalChapters - 1) "Chương sau" else "Sau",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (canNextPage) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                            contentDescription = "Trang sau",
                            tint = if (canNextPage) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bottom half of Split-Screen Compare Mode: Read-only Original text with word-level Diff Highlights
 * and matching warm highlight on `activeParagraphIndex`.
 */
@Composable
private fun SepiaOriginalCompareBottomPane(
    chapter: ManualChapterContent,
    activeParagraphIndex: Int,
    fontSizeSp: Float,
    origListState: LazyListState,
    onTapOriginalParagraph: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(Color(0xFFEEE4CC))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaChrome)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "BẢN GỐC ĐỐI CHIẾU (CHỈ ĐỌC)",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SepiaInkMuted,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Đoạn #${(activeParagraphIndex + 1).coerceAtMost(chapter.originalParagraphs.size.coerceAtLeast(1))}/${chapter.originalParagraphs.size}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = SepiaInkMuted
            )
        }

        LazyColumn(
            state = origListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = chapter.originalParagraphs,
                key = { pIdx, _ -> "${chapter.fileName}_orig_$pIdx" }
            ) { pIdx, origPara ->
                val currPara = chapter.currentParagraphs.getOrNull(pIdx).orEmpty()
                val diffPair = remember(origPara, currPara) {
                    TextDiffHelper.buildDiffAnnotatedPair(origPara, currPara)
                }
                val isSelected = pIdx == activeParagraphIndex
                val isHeading = pIdx == 0 && origPara.length <= 120

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) SepiaActiveParagraphBg else Color.Transparent)
                        .then(
                            if (isSelected) {
                                Modifier.border(1.dp, SepiaAccent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            } else {
                                Modifier
                            }
                        )
                        .clickable { onTapOriginalParagraph(pIdx) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = diffPair.originalAnnotated,
                        fontSize = if (isHeading) (fontSizeSp + 2f).sp else fontSizeSp.sp,
                        lineHeight = if (isHeading) ((fontSizeSp + 2f) * 1.38f).sp else (fontSizeSp * 1.56f).sp,
                        fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                        color = SepiaInk
                    )
                }
            }
        }
    }
}

@Composable
private fun SepiaCurrentParagraphItem(
    text: String,
    originalText: String,
    isHeading: Boolean,
    fontSizeSp: Float,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    isActiveHighlight: Boolean,
    bookmark: ManualTocEntry?,
    paragraphMatches: List<Pair<Int, ManualSearchMatch>>,
    activeGlobalMatchIndex: Int,
    onActivate: () -> Unit,
    onTextChange: (String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val effectiveFontSize = if (isHeading) (fontSizeSp + 2.5f).sp else fontSizeSp.sp
    val effectiveLineHeight = if (isHeading) ((fontSizeSp + 2.5f) * 1.38f).sp else (fontSizeSp * 1.62f).sp
    val effectiveWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal

    val containerBg = when {
        isActiveHighlight -> SepiaActiveParagraphBg
        isEditMode && isFocused -> SepiaCard
        else -> Color.Transparent
    }

    val containerBorder = when {
        isEditMode && isFocused -> BorderStroke(1.3.dp, SepiaAccent)
        isActiveHighlight -> BorderStroke(1.1.dp, SepiaAccent.copy(alpha = 0.65f))
        isEditMode -> BorderStroke(0.6.dp, SepiaBorder.copy(alpha = 0.65f))
        else -> null
    }

    val needsCardPadding = isEditMode || isActiveHighlight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerBg)
            .then(
                if (containerBorder != null) {
                    Modifier.border(containerBorder, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .then(
                if (!isEditMode && isCompareMode) {
                    Modifier.clickable(onClick = onActivate)
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = if (needsCardPadding) 10.dp else 2.dp,
                vertical = if (needsCardPadding) 7.dp else 2.dp
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
            // Live word-diff VisualTransformation inside BasicTextField when Compare Mode is ON!
            val visualTransformation = remember(isCompareMode, originalText) {
                if (isCompareMode && originalText.isNotEmpty()) {
                    TextDiffHelper.createLiveDiffVisualTransformation(originalText)
                } else {
                    VisualTransformation.None
                }
            }

            BasicTextField(
                value = text,
                onValueChange = { updated ->
                    onActivate()
                    onTextChange(updated)
                },
                visualTransformation = visualTransformation,
                textStyle = TextStyle(
                    fontSize = effectiveFontSize,
                    lineHeight = effectiveLineHeight,
                    fontWeight = effectiveWeight,
                    color = SepiaInk
                ),
                cursorBrush = SolidColor(SepiaAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        if (state.isFocused) {
                            onActivate()
                        }
                    }
            )
        } else {
            val renderedText = remember(
                text,
                originalText,
                isCompareMode,
                paragraphMatches,
                activeGlobalMatchIndex
            ) {
                if (isCompareMode) {
                    TextDiffHelper.buildCurrentHighlightedText(
                        original = originalText,
                        current = text,
                        searchMatches = paragraphMatches,
                        activeGlobalMatchIndex = activeGlobalMatchIndex,
                        searchBg = SepiaSearchHighlight,
                        activeSearchBg = SepiaSearchActive,
                        inkColor = SepiaInk
                    )
                } else if (paragraphMatches.isNotEmpty()) {
                    buildAnnotatedString {
                        append(text)
                        for ((globalIdx, m) in paragraphMatches) {
                            val s = m.startOffset.coerceIn(0, text.length)
                            val e = m.endOffset.coerceIn(s, text.length)
                            if (s < e) {
                                addStyle(
                                    style = SpanStyle(
                                        background = if (globalIdx == activeGlobalMatchIndex) {
                                            SepiaSearchActive
                                        } else {
                                            SepiaSearchHighlight
                                        },
                                        color = SepiaInk,
                                        fontWeight = if (globalIdx == activeGlobalMatchIndex) FontWeight.Bold else null
                                    ),
                                    start = s,
                                    end = e
                                )
                            }
                        }
                    }
                } else {
                    null
                }
            }

            if (renderedText != null) {
                Text(
                    text = renderedText,
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

@Composable
private fun ChapterNavFooter(
    chapterIndex: Int,
    totalChapters: Int,
    onSelectChapter: (Int) -> Unit
) {
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
                .clickable(enabled = chapterIndex > 0) { onSelectChapter(chapterIndex - 1) }
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
                .clickable(enabled = chapterIndex < totalChapters - 1) { onSelectChapter(chapterIndex + 1) }
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
    Spacer(modifier = Modifier.height(18.dp))
}

/**
 * Estimates screen-fitted page groupings of paragraph indices for HorizontalPager ("Lướt ngang").
 */
private fun paginateParagraphIndices(
    paragraphs: List<String>,
    fontSizeSp: Float,
    availableHeightDp: Float,
    availableWidthDp: Float
): List<List<Int>> {
    if (paragraphs.isEmpty()) return listOf(emptyList())

    val charsPerLine = ((availableWidthDp / (fontSizeSp * 0.54f)).toInt()).coerceAtLeast(18)
    val lineHeightDp = fontSizeSp * 1.65f
    val paragraphSpacingDp = 12f
    val maxPageHeight = (availableHeightDp - 20f).coerceAtLeast(lineHeightDp * 4f)

    val pages = mutableListOf<List<Int>>()
    var currentPage = mutableListOf<Int>()
    var usedHeight = 0f

    paragraphs.forEachIndexed { idx, para ->
        val estimatedLines = ceil((para.length.coerceAtLeast(1)).toFloat() / charsPerLine).toInt().coerceAtLeast(1)
        val paraHeight = estimatedLines * lineHeightDp + paragraphSpacingDp

        if (currentPage.isNotEmpty() && usedHeight + paraHeight > maxPageHeight) {
            pages.add(currentPage)
            currentPage = mutableListOf(idx)
            usedHeight = paraHeight
        } else {
            currentPage.add(idx)
            usedHeight += paraHeight
        }
    }

    if (currentPage.isNotEmpty()) {
        pages.add(currentPage)
    }
    return pages
}
