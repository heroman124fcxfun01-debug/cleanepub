package com.example.ui.screens

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.CustomReplaceManager
import com.example.data.CustomReplaceRule
import java.util.Locale
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
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.SaveDialog
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.SepiaAccent
import com.example.ui.components.SepiaAccentSoft
import com.example.ui.components.SepiaActiveParagraphBg
import com.example.ui.components.SepiaAmber
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
    onAddNewChapter: (title: String) -> Unit,
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
    onSaveDraftAndExitToHome: () -> Unit,
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

    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val currListState = rememberLazyListState()
    val origListState = rememberLazyListState()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isUserDraggingVertical by currListState.interactionSource.collectIsDraggedAsState()
    var focusedParagraphIndex by remember(editorState.currentChapterIndex) {
        mutableStateOf<Int?>(null)
    }
    var userTappedParagraphInReadMode by remember(editorState.currentChapterIndex) {
        mutableStateOf(false)
    }

    // Upgrade 1.5: Offline Android TTS (RAM-lightweight with strict DisposableEffect cleanup)
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var speakingParagraphIndex by remember(editorState.currentChapterIndex) {
        mutableIntStateOf(-1)
    }

    DisposableEffect(context) {
        var localTts: TextToSpeech? = null
        localTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { speakingParagraphIndex = -1 }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { speakingParagraphIndex = -1 }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { speakingParagraphIndex = -1 }
                    }
                })
                mainHandler.post {
                    isTtsReady = true
                }
            }
        }
        ttsEngine = localTts
        onDispose {
            runCatching {
                localTts.stop()
                localTts.shutdown()
            }
            ttsEngine = null
            isTtsReady = false
            speakingParagraphIndex = -1
        }
    }

    fun stopTtsPlayback() {
        runCatching { ttsEngine?.stop() }
        if (speakingParagraphIndex != -1) {
            speakingParagraphIndex = -1
        }
    }

    // Stop TTS immediately when switching chapters
    LaunchedEffect(editorState.currentChapterIndex) {
        stopTtsPlayback()
    }

    // Upgrade 1.5: Auto-extract short (1..3 words) edited pair for 1-tap save to "Thư viện thay thế"
    var editBaselineKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editBaselineText by remember { mutableStateOf<String?>(null) }
    var dismissedSuggestionKey by remember { mutableStateOf<String?>(null) }
    var quickRuleSuggestion by remember { mutableStateOf<Pair<String, String>?>(null) }

    // Clear keyboard, focus & quick-rule suggestion when leaving Edit Mode
    LaunchedEffect(editorState.isEditMode) {
        if (!editorState.isEditMode) {
            focusedParagraphIndex = null
            editBaselineKey = null
            editBaselineText = null
            quickRuleSuggestion = null
            dismissedSuggestionKey = null
            focusManager.clearFocus()
        }
    }

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

    // Track first visible paragraph ONLY when the user manually drags vertically (never on IME auto-scroll)
    if (editorState.readingMode == ManualReadingMode.VERTICAL_SCROLL) {
        LaunchedEffect(currListState) {
            snapshotFlow { currListState.firstVisibleItemIndex to isUserDraggingVertical }
                .distinctUntilChanged()
                .collect { (firstVisible, dragging) ->
                    if (dragging) {
                        onActiveParagraphChanged(firstVisible)
                    }
                }
        }
    }

    // Keep the focused/tapped paragraph riding smoothly ABOVE the rising Android soft keyboard
    LaunchedEffect(
        editorState.isEditMode,
        editorState.readingMode,
        focusedParagraphIndex,
        imeBottomPx
    ) {
        val targetIdx = focusedParagraphIndex ?: return@LaunchedEffect
        if (!editorState.isEditMode || editorState.readingMode != ManualReadingMode.VERTICAL_SCROLL) {
            return@LaunchedEffect
        }
        if (imeBottomPx <= 0) return@LaunchedEffect

        val marginBottomPx = with(density) { 28.dp.roundToPx() }
        val marginTopPx = with(density) { 12.dp.roundToPx() }

        // Check layout immediately (tracks every IME animation frame) and once more after layout settles
        repeat(2) { pass ->
            if (pass == 1) delay(60L)
            val layoutInfo = currListState.layoutInfo
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                .coerceAtLeast(1)
            val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIdx }

            if (itemInfo == null) {
                // Paragraph was pushed off-screen by rising keyboard -> immediately bring it back into view
                runCatching { currListState.scrollToItem(targetIdx, scrollOffset = 0) }
            } else {
                val safeBottom = (viewportHeight - marginBottomPx).coerceAtLeast(viewportHeight / 2)
                val itemBottom = itemInfo.offset + itemInfo.size
                if (itemBottom > safeBottom) {
                    val neededScroll = itemBottom - safeBottom
                    // Never push the top of a tall paragraph above the top edge unless needed for cursor
                    val maxSafeScroll = (itemInfo.offset - marginTopPx).coerceAtLeast(0)
                    val delta = if (itemInfo.size <= safeBottom) {
                        neededScroll
                    } else {
                        minOf(neededScroll, maxSafeScroll)
                    }
                    if (delta > 0) {
                        runCatching { currListState.scrollBy(delta.toFloat()) }
                    }
                } else if (itemInfo.offset < marginTopPx && targetIdx > 0) {
                    val upDelta = itemInfo.offset - marginTopPx
                    runCatching { currListState.scrollBy(upDelta.toFloat()) }
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

    val focusedCurrentText = remember(activeChapter, focusedParagraphIndex) {
        val idx = focusedParagraphIndex ?: return@remember null
        activeChapter?.currentParagraphs?.getOrNull(idx)
    }

    // Debounced auto-detection of quick replacement rule while typing in Edit Mode
    LaunchedEffect(
        editorState.isEditMode,
        focusedParagraphIndex,
        editBaselineText,
        focusedCurrentText,
        customReplaceRules,
        dismissedSuggestionKey
    ) {
        val base = editBaselineText
        val current = focusedCurrentText
        if (!editorState.isEditMode || focusedParagraphIndex == null || base == null || current == null || base == current) {
            quickRuleSuggestion = null
            return@LaunchedEffect
        }
        delay(350L)
        val extracted = CustomReplaceManager.extractQuickRuleSuggestion(
            beforeParagraph = base,
            afterParagraph = current,
            existingRules = customReplaceRules
        )
        val key = extracted?.let { "${it.first}->${it.second}" }
        quickRuleSuggestion = if (key != null && key != dismissedSuggestionKey) extracted else null
    }

    fun toggleTtsForActiveParagraph() {
        if (speakingParagraphIndex >= 0) {
            stopTtsPlayback()
            return
        }
        val chapter = activeChapter ?: return
        val targetIdx = editorState.activeParagraphIndex
            .coerceIn(0, (chapter.currentParagraphs.lastIndex).coerceAtLeast(0))
        val textToSpeak = chapter.currentParagraphs.getOrNull(targetIdx)?.trim().orEmpty()
        if (textToSpeak.isEmpty()) {
            Toast.makeText(context, "Đoạn văn đang trống", Toast.LENGTH_SHORT).show()
            return
        }
        val engine = ttsEngine
        if (engine == null || !isTtsReady) {
            Toast.makeText(context, "Bộ đọc giọng nói trên máy đang khởi tạo…", Toast.LENGTH_SHORT).show()
            return
        }
        val desiredLocale = resolveTtsLocale(
            text = textToSpeak,
            bookLanguage = snapshot.metadata?.language
        )
        val langResult = runCatching { engine.setLanguage(desiredLocale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Fallback gracefully to English US or default system locale
            runCatching { engine.setLanguage(Locale.US) }
        }
        speakingParagraphIndex = targetIdx
        engine.speak(
            textToSpeak,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "epub_tts_${editorState.currentChapterIndex}_$targetIdx"
        )
    }

    fun handleSelectParagraph(pIdx: Int, fromExplicitTap: Boolean) {
        if (speakingParagraphIndex >= 0 && speakingParagraphIndex != pIdx) {
            stopTtsPlayback()
        }
        if (fromExplicitTap && !editorState.isEditMode) {
            userTappedParagraphInReadMode = true
        }
        onActiveParagraphChanged(pIdx)
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

    var acceptedDiffKeys by remember(snapshot.sourceFile) { mutableStateOf(setOf<String>()) }

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
            // 1. Swiss Neo-Brutalist 3D Keycap Editing Toolbar + Cobalt "Lưu" Pill
            SepiaTopBar(
                isEditMode = editorState.isEditMode,
                isSpeaking = speakingParagraphIndex >= 0,
                isCompareMode = editorState.isCompareMode,
                isSearchOpen = showSearchBar,
                isSaving = saveUiState is SaveUiState.Saving,
                canUndo = editorState.canUndo,
                fontSizeSp = editorState.fontSizeSp,
                readingMode = editorState.readingMode,
                showMoreMenu = showMoreMenu,
                onOpenToc = {
                    stopTtsPlayback()
                    showTocDialog = true
                },
                onSaveClick = {
                    stopTtsPlayback()
                    showSaveDialog = true
                },
                onSaveDraftClick = onSaveDraftClick,
                onHomeClick = {
                    stopTtsPlayback()
                    showExitDialog = true
                },
                onUndoClick = ::triggerUndoClick,
                onToggleEdit = onToggleEditMode,
                onToggleTts = ::toggleTtsForActiveParagraph,
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

            // 2. Animated 3D Art-Paper Find & Replace Card
            AnimatedVisibility(
                visible = showSearchBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
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

            // 3. Chapter Strip + Edit Mode "Tách chương từ đây" 3D Bar
            if (activeChapter != null) {
                SepiaChapterStrip(
                    chapterIndex = editorState.currentChapterIndex,
                    totalChapters = editorState.chapters.size,
                    chapterTitle = activeChapter.title,
                    activeParagraphIndex = editorState.activeParagraphIndex,
                    totalParagraphsInChapter = activeChapter.currentParagraphs.size,
                    isEditMode = editorState.isEditMode,
                    isCompareMode = editorState.isCompareMode,
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

                            val topPaneWeight = if (editorState.isCompareMode && editorState.isEditMode && imeBottomPx > 0) {
                                1.35f
                            } else {
                                1f
                            }
                            val bottomPaneWeight = if (editorState.isCompareMode && editorState.isEditMode && imeBottomPx > 0) {
                                0.65f
                            } else {
                                1f
                            }

                            val paperSheetShape = RoundedCornerShape(14.dp)
                            Box(
                                modifier = Modifier
                                    .weight(topPaneWeight)
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .padding(end = 3.dp, bottom = 3.dp)
                                    .neoHardOffsetShadow(
                                        cornerRadius = 14.dp,
                                        offsetX = 3.dp,
                                        offsetY = 3.dp,
                                        shadowColor = SepiaInk
                                    )
                                    .clip(paperSheetShape)
                                    .background(SepiaCard)
                                    .border(1.5.dp, SepiaInk, paperSheetShape)
                            ) {
                                if (editorState.readingMode == ManualReadingMode.VERTICAL_SCROLL) {
                                    SepiaVerticalBookPane(
                                        chapter = activeChapter,
                                        chapterIndex = editorState.currentChapterIndex,
                                        totalChapters = editorState.chapters.size,
                                        activeParagraphIndex = editorState.activeParagraphIndex,
                                        speakingParagraphIndex = speakingParagraphIndex,
                                        showReadSelectionOutline = userTappedParagraphInReadMode,
                                        fontSizeSp = editorState.fontSizeSp,
                                        isEditMode = editorState.isEditMode,
                                        isCompareMode = editorState.isCompareMode,
                                        imeBottomPx = imeBottomPx,
                                        listState = currListState,
                                        activeChapterBookmarks = activeChapterBookmarks,
                                        activeChapterMatches = activeChapterMatches,
                                        activeGlobalMatchIndex = editorState.activeMatchIndex,
                                        acceptedDiffKeys = acceptedDiffKeys,
                                        onAcceptDiff = { key -> acceptedDiffKeys = acceptedDiffKeys + key },
                                        onRejectDiff = { pIdx, origText ->
                                            onUpdateParagraph(editorState.currentChapterIndex, pIdx, origText)
                                        },
                                        onSelectParagraph = { pIdx -> handleSelectParagraph(pIdx, fromExplicitTap = true) },
                                        onParagraphFocusChanged = { pIdx, focused ->
                                            if (focused) {
                                                focusedParagraphIndex = pIdx
                                                val key = editorState.currentChapterIndex to pIdx
                                                if (editBaselineKey != key) {
                                                    editBaselineKey = key
                                                    editBaselineText = activeChapter.currentParagraphs.getOrNull(pIdx)
                                                    dismissedSuggestionKey = null
                                                }
                                                handleSelectParagraph(pIdx, fromExplicitTap = false)
                                            } else if (focusedParagraphIndex == pIdx) {
                                                focusedParagraphIndex = null
                                            }
                                        },
                                        onUpdateParagraph = { pIdx, text ->
                                            val key = editorState.currentChapterIndex to pIdx
                                            if (editBaselineKey != key) {
                                                editBaselineKey = key
                                                editBaselineText = activeChapter.currentParagraphs.getOrNull(pIdx)
                                                dismissedSuggestionKey = null
                                            }
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
                                        speakingParagraphIndex = speakingParagraphIndex,
                                        showReadSelectionOutline = userTappedParagraphInReadMode,
                                        scrollRequestId = editorState.scrollRequestId,
                                        scrollTargetParagraphIndex = editorState.scrollTargetParagraphIndex,
                                        fontSizeSp = editorState.fontSizeSp,
                                        isEditMode = editorState.isEditMode,
                                        isCompareMode = editorState.isCompareMode,
                                        imeBottomPx = imeBottomPx,
                                        activeChapterBookmarks = activeChapterBookmarks,
                                        activeChapterMatches = activeChapterMatches,
                                        activeGlobalMatchIndex = editorState.activeMatchIndex,
                                        acceptedDiffKeys = acceptedDiffKeys,
                                        onAcceptDiff = { key -> acceptedDiffKeys = acceptedDiffKeys + key },
                                        onRejectDiff = { pIdx, origText ->
                                            onUpdateParagraph(editorState.currentChapterIndex, pIdx, origText)
                                        },
                                        onSelectParagraph = { pIdx -> handleSelectParagraph(pIdx, fromExplicitTap = true) },
                                        onParagraphFocusChanged = { pIdx, focused ->
                                            if (focused) {
                                                focusedParagraphIndex = pIdx
                                                val key = editorState.currentChapterIndex to pIdx
                                                if (editBaselineKey != key) {
                                                    editBaselineKey = key
                                                    editBaselineText = activeChapter.currentParagraphs.getOrNull(pIdx)
                                                    dismissedSuggestionKey = null
                                                }
                                                handleSelectParagraph(pIdx, fromExplicitTap = false)
                                            } else if (focusedParagraphIndex == pIdx) {
                                                focusedParagraphIndex = null
                                            }
                                        },
                                        onUpdateParagraph = { pIdx, text ->
                                            val key = editorState.currentChapterIndex to pIdx
                                            if (editBaselineKey != key) {
                                                editBaselineKey = key
                                                editBaselineText = activeChapter.currentParagraphs.getOrNull(pIdx)
                                                dismissedSuggestionKey = null
                                            }
                                            onUpdateParagraph(editorState.currentChapterIndex, pIdx, text)
                                        },
                                        onSelectChapter = { nextChap -> onSelectChapter(nextChap, 0) }
                                    )
                                }
                            }

                            // BOTTOM HALF (Only when Compare Mode is ON): Original Reference Pane in 3D Archival Sheet
                            if (editorState.isCompareMode) {
                                SepiaOriginalCompareBottomPane(
                                    chapter = activeChapter,
                                    activeParagraphIndex = editorState.activeParagraphIndex,
                                    fontSizeSp = (editorState.fontSizeSp - 0.5f).coerceAtLeast(12f),
                                    origListState = origListState,
                                    onTapOriginalParagraph = { pIdx -> handleSelectParagraph(pIdx, fromExplicitTap = true) },
                                    modifier = Modifier
                                        .weight(bottomPaneWeight)
                                        .fillMaxWidth()
                                        .padding(start = 10.dp, end = 13.dp, bottom = 9.dp)
                                        .neoHardOffsetShadow(
                                            cornerRadius = 14.dp,
                                            offsetX = 3.dp,
                                            offsetY = 3.dp,
                                            shadowColor = SepiaInk
                                        )
                                        .clip(RoundedCornerShape(14.dp))
                                        .border(1.5.dp, SepiaInk, RoundedCornerShape(14.dp))
                                )
                            }
                        }
                    }
                }

                // Floating transient 3D feedback pill
                val feedback = editorState.feedbackMessage
                if (feedback != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp, start = 20.dp, end = 20.dp)
                            .neoHardOffsetShadow(
                                cornerRadius = 50.dp,
                                offsetX = 2.5.dp,
                                offsetY = 2.5.dp,
                                shadowColor = SepiaInk
                            )
                            .clip(RoundedCornerShape(50))
                            .background(SepiaAmber)
                            .border(1.5.dp, SepiaInk, RoundedCornerShape(50))
                    ) {
                        Text(
                            text = feedback,
                            fontSize = 12.sp,
                            color = SepiaInk,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }

            // Upgrade 1.5: 1-tap Quick-Save Rule Pill Chip directly above the keyboard / bottom bar
            val suggestion = quickRuleSuggestion
            if (editorState.isEditMode && suggestion != null) {
                QuickSaveReplaceRuleChipBar(
                    oldPhrase = suggestion.first,
                    newPhrase = suggestion.second,
                    onSaveRule = {
                        onAddCustomReplaceRule(suggestion.first, suggestion.second)
                        editBaselineText = focusedCurrentText
                        quickRuleSuggestion = null
                        dismissedSuggestionKey = null
                        Toast.makeText(
                            context,
                            "Đã lưu vào Thư viện thay thế",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDismiss = {
                        dismissedSuggestionKey = "${suggestion.first}->${suggestion.second}"
                        quickRuleSuggestion = null
                    }
                )
            }
        }
    }

    // Editable Table of Contents Overlay (with "+ Thêm chương" & dedicated "Sửa" toggle for Merge, Rename & Delete)
    ManualTocOverlayDialog(
        visible = showTocDialog,
        tocItems = editorState.editableTocItems,
        currentChapterIndex = editorState.currentChapterIndex,
        totalChapters = editorState.chapters.size,
        onSelectTarget = onSelectChapter,
        onAddNewChapter = onAddNewChapter,
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

    // Home Exit Dialog: 2-tap "Hủy sửa chữa" (discard & keep original) + "Về màn hình chính và lưu"
    ExitConfirmDialog(
        visible = showExitDialog,
        onDiscardAndExit = {
            showExitDialog = false
            onFinishToHome()
        },
        onSaveAndExit = {
            showExitDialog = false
            onSaveDraftAndExitToHome()
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
 * Floating iOS Rounded-Square Home Exit Dialog:
 * 1. "Hủy sửa chữa" (2-tap confirm): discards all unsaved edits, keeps the original untouched, returns Home.
 *    - Tap 1 -> switches to warning red (#FF3B30) "Bấm lần nữa để xác nhận hủy"
 *    - Tap 2 -> discards edits and returns to MainScreen
 * 2. "Về màn hình chính và lưu" (below): saves current editing progress as a Draft and returns Home.
 * - Tap outside dialog -> dismisses cleanly & resets tap counter to 0.
 */
@Composable
fun ExitConfirmDialog(
    visible: Boolean,
    onDiscardAndExit: () -> Unit,
    onSaveAndExit: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var confirmStep by remember { mutableIntStateOf(0) }
    val isArmed = confirmStep >= 1

    val discardButtonBg by animateColorAsState(
        targetValue = if (isArmed) Color(0xFFFF3B30) else Color(0xFFFFE5DC),
        label = "exit_confirm_btn_bg"
    )
    val discardButtonTextColor by animateColorAsState(
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
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 364.dp)
                .fillMaxWidth()
                .padding(end = 5.dp, bottom = 5.dp)
                .neoHardOffsetShadow(
                    cornerRadius = 18.dp,
                    offsetX = 5.dp,
                    offsetY = 5.dp,
                    shadowColor = SepiaInk
                )
                .clip(dialogShape)
                .background(SepiaBg)
                .border(1.8.dp, SepiaInk, dialogShape)
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
                        )
                        .border(1.5.dp, SepiaInk, CircleShape),
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
                    text = if (isArmed) {
                        "Không lưu bất kỳ thay đổi nào và giữ nguyên bản gốc."
                    } else {
                        "Bạn muốn hủy toàn bộ sửa chữa hay lưu lại trước khi về màn hình chính?"
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (isArmed) Color(0xFFC62828) else SepiaInkMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Option 1: 2-Step Confirmation Pill Button — "Hủy sửa chữa" (Discard, keep original)
                NeoBrutalistPressBox(
                    onClick = {
                        if (confirmStep == 0) {
                            confirmStep = 1
                        } else {
                            confirmStep = 0
                            onDiscardAndExit()
                        }
                    },
                    isPill = true,
                    shadowOffset = 3.dp,
                    pressDepth = 2.dp,
                    borderWidth = 1.5.dp,
                    containerColor = discardButtonBg,
                    borderColor = SepiaInk,
                    shadowColor = SepiaInk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(47.dp)
                        .testTag("exit_confirm_two_step_button")
                ) {
                    Text(
                        text = if (isArmed) "Bấm lần nữa để xác nhận hủy" else "Hủy sửa chữa (giữ bản gốc)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = discardButtonTextColor,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Option 2 (Below): Save current editing state as Draft and return to Home
                NeoBrutalistPressBox(
                    onClick = {
                        confirmStep = 0
                        onSaveAndExit()
                    },
                    isPill = true,
                    shadowOffset = 3.dp,
                    pressDepth = 2.dp,
                    borderWidth = 1.5.dp,
                    containerColor = SepiaAccent,
                    borderColor = SepiaInk,
                    shadowColor = SepiaInk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(47.dp)
                        .testTag("exit_save_and_home_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SaveAs,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Về màn hình chính và lưu",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

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
private fun Editor3DKeycapButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = SepiaCard,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    NeoBrutalistPressBox(
        onClick = onClick,
        enabled = enabled,
        cornerRadius = 9.dp,
        shadowOffset = 2.dp,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = if (enabled) containerColor else SepiaChrome,
        borderColor = if (enabled) SepiaInk else SepiaInkMuted.copy(alpha = 0.45f),
        shadowColor = if (enabled) SepiaInk else Color.Transparent,
        modifier = modifier.size(34.dp)
    ) {
        content()
    }
}

@Composable
private fun SepiaTopBar(
    isEditMode: Boolean,
    isSpeaking: Boolean,
    isCompareMode: Boolean,
    isSearchOpen: Boolean,
    isSaving: Boolean,
    canUndo: Boolean,
    fontSizeSp: Float,
    readingMode: ManualReadingMode,
    showMoreMenu: Boolean,
    onOpenToc: () -> Unit,
    onSaveClick: () -> Unit,
    onSaveDraftClick: () -> Unit,
    onHomeClick: () -> Unit,
    onUndoClick: () -> Unit,
    onToggleEdit: () -> Unit,
    onToggleTts: () -> Unit,
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
            .background(SepiaBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // 1. ☰ TOC 3D Keycap
            Editor3DKeycapButton(
                onClick = onOpenToc,
                modifier = Modifier.testTag("manual_toc_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Mở Mục lục",
                    tint = SepiaInk,
                    modifier = Modifier.size(17.dp)
                )
            }

            // 2. Prominent Cobalt Blue (#1D5CD6) 3D "Lưu" Pill
            NeoBrutalistPressBox(
                onClick = onSaveClick,
                enabled = !isSaving,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = SepiaAccent,
                borderColor = SepiaInk,
                shadowColor = SepiaInk,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("manual_save_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // 3. Save Draft 3D Keycap
            Editor3DKeycapButton(
                onClick = onSaveDraftClick,
                modifier = Modifier.testTag("manual_save_draft_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SaveAs,
                    contentDescription = "Lưu nháp",
                    tint = SepiaAccent,
                    modifier = Modifier.size(16.dp)
                )
            }

            // 4. Home 3D Keycap
            Editor3DKeycapButton(
                onClick = onHomeClick,
                modifier = Modifier.testTag("manual_home_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Home,
                    contentDescription = "Về màn hình chính",
                    tint = SepiaInk,
                    modifier = Modifier.size(17.dp)
                )
            }

            // 5. Undo 3D Keycap (Hoàn tác)
            Editor3DKeycapButton(
                onClick = onUndoClick,
                enabled = canUndo,
                containerColor = if (canUndo) SepiaAmber else SepiaChrome,
                modifier = Modifier.testTag("manual_undo_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = "Hoàn tác",
                    tint = if (canUndo) SepiaInk else SepiaInkMuted.copy(alpha = 0.45f),
                    modifier = Modifier.size(17.dp)
                )
            }

            // 6. "Sửa" Toggle 3D Pill
            NeoBrutalistPressBox(
                onClick = onToggleEdit,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = if (isEditMode) SepiaAccent else SepiaCard,
                borderColor = SepiaInk,
                shadowColor = SepiaInk,
                modifier = Modifier
                    .height(34.dp)
                    .testTag("manual_edit_toggle_button")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sửa",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isEditMode) Color.White else SepiaInk,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // 7. Offline Android TTS Speaker 3D Keycap
            Editor3DKeycapButton(
                onClick = onToggleTts,
                containerColor = if (isSpeaking) Color(0xFF16A34A) else SepiaCard,
                modifier = Modifier.testTag("manual_tts_button")
            ) {
                Icon(
                    imageVector = if (isSpeaking) Icons.Rounded.StopCircle else Icons.Rounded.VolumeUp,
                    contentDescription = if (isSpeaking) "Dừng đọc thử" else "Đọc thử đoạn văn",
                    tint = if (isSpeaking) Color.White else SepiaInk,
                    modifier = Modifier.size(17.dp)
                )
            }

            // 8. Standalone "So sánh" 3D Keycap
            Editor3DKeycapButton(
                onClick = onToggleCompare,
                containerColor = if (isCompareMode) SepiaAmber else SepiaCard,
                modifier = Modifier.testTag("manual_compare_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Splitscreen,
                    contentDescription = "Bật/tắt So sánh với bản gốc",
                    tint = SepiaInk,
                    modifier = Modifier.size(17.dp)
                )
            }

            // 9. Search (Find & Replace) 3D Keycap
            Editor3DKeycapButton(
                onClick = onToggleSearch,
                containerColor = if (isSearchOpen) SepiaAccent else SepiaCard,
                modifier = Modifier.testTag("manual_search_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Tìm kiếm và thay thế",
                    tint = if (isSearchOpen) Color.White else SepiaInk,
                    modifier = Modifier.size(17.dp)
                )
            }

            // 10. Add Manual TOC Bookmark 3D Keycap
            Editor3DKeycapButton(
                onClick = onAddTocClick,
                modifier = Modifier.testTag("manual_add_toc_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdd,
                    contentDescription = "Thêm mục lục thủ công",
                    tint = SepiaInk,
                    modifier = Modifier.size(17.dp)
                )
            }

            // 11. Exclamation Menu (!) 3D Keycap — Font A-/A+ + Vertical/Horizontal Reading Mode
            Box {
                Editor3DKeycapButton(
                    onClick = { onMoreMenuChange(true) },
                    containerColor = if (showMoreMenu) SepiaAmber else SepiaCard,
                    modifier = Modifier.testTag("manual_more_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = "Cỡ chữ và chế độ lật trang",
                        tint = SepiaInk,
                        modifier = Modifier.size(17.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { onMoreMenuChange(false) },
                    modifier = Modifier
                        .background(SepiaCard)
                        .border(1.5.dp, SepiaInk, RoundedCornerShape(10.dp))
                        .width(214.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Cỡ chữ (${fontSizeSp.toInt()}sp)",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = SepiaInkMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NeoBrutalistPressBox(
                                onClick = onDecreaseFont,
                                cornerRadius = 9.dp,
                                shadowOffset = 2.dp,
                                pressDepth = 2.dp,
                                borderWidth = 1.5.dp,
                                containerColor = SepiaBg,
                                borderColor = SepiaInk,
                                shadowColor = SepiaInk,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .testTag("manual_font_decrease")
                            ) {
                                Text(
                                    text = "A-",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SepiaInk
                                )
                            }
                            NeoBrutalistPressBox(
                                onClick = onIncreaseFont,
                                cornerRadius = 9.dp,
                                shadowOffset = 2.dp,
                                pressDepth = 2.dp,
                                borderWidth = 1.5.dp,
                                containerColor = SepiaBg,
                                borderColor = SepiaInk,
                                shadowColor = SepiaInk,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .testTag("manual_font_increase")
                            ) {
                                Text(
                                    text = "A+",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = SepiaInk
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = SepiaBorder,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Chế độ lật trang",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
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
        HorizontalDivider(thickness = 1.2.dp, color = SepiaInk.copy(alpha = 0.22f))
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
            .border(
                width = if (selected) 1.2.dp else 0.dp,
                color = if (selected) SepiaInk else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
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
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
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
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${chapterIndex + 1}/$totalChapters · $chapterTitle",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = SepiaInkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (isEditMode) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SepiaAmber)
                        .border(1.2.dp, SepiaInk, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isCompareMode) "Đang sửa · So sánh" else "Đang sửa",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SepiaInk,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Compact 3D Split Chapter action bar shown when Edit Mode is active
        if (isEditMode && totalParagraphsInChapter > 1) {
            val canSplitHere = activeParagraphIndex in 1 until totalParagraphsInChapter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SepiaChrome)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Đang chọn đoạn #${activeParagraphIndex + 1}/$totalParagraphsInChapter",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = SepiaInkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                NeoBrutalistPressBox(
                    onClick = onSplitChapterClick,
                    isPill = true,
                    shadowOffset = 2.dp,
                    pressDepth = 1.5.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (canSplitHere) SepiaAccent else SepiaCard,
                    borderColor = SepiaInk,
                    shadowColor = if (canSplitHere) SepiaInk else Color.Transparent,
                    modifier = Modifier.testTag("manual_split_chapter_button")
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
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canSplitHere) Color.White else SepiaInkMuted,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = SepiaBorder)
        }
    }
}

@Composable
private fun SepiaVerticalBookPane(
    chapter: ManualChapterContent,
    chapterIndex: Int,
    totalChapters: Int,
    activeParagraphIndex: Int,
    speakingParagraphIndex: Int,
    showReadSelectionOutline: Boolean,
    fontSizeSp: Float,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    imeBottomPx: Int,
    listState: LazyListState,
    activeChapterBookmarks: Map<Int, ManualTocEntry>,
    activeChapterMatches: Map<Int, List<Pair<Int, ManualSearchMatch>>>,
    activeGlobalMatchIndex: Int,
    acceptedDiffKeys: Set<String>,
    onAcceptDiff: (String) -> Unit,
    onRejectDiff: (Int, String) -> Unit,
    onSelectParagraph: (Int) -> Unit,
    onParagraphFocusChanged: (Int, Boolean) -> Unit,
    onUpdateParagraph: (Int, String) -> Unit,
    onSelectChapter: (Int) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 12.dp,
            bottom = if (isEditMode) 120.dp else 18.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = chapter.currentParagraphs,
            key = { pIdx, _ -> "${chapter.fileName}_p_$pIdx" }
        ) { pIdx, paragraph ->
            val origPara = chapter.originalParagraphs.getOrNull(pIdx).orEmpty()
            val diffKey = "${chapter.fileName}_$pIdx"
            val isActiveHighlightPara = (isCompareMode || isEditMode) && pIdx == activeParagraphIndex
            val isSpeakingPara = pIdx == speakingParagraphIndex
            val isReadSelectedPara = !isEditMode && !isCompareMode && showReadSelectionOutline && pIdx == activeParagraphIndex

            SepiaCurrentParagraphItem(
                text = paragraph,
                originalText = origPara,
                isHeading = pIdx == 0 && paragraph.length <= 120,
                fontSizeSp = fontSizeSp,
                isEditMode = isEditMode,
                isCompareMode = isCompareMode,
                isActiveHighlight = isActiveHighlightPara,
                isSpeaking = isSpeakingPara,
                isReadSelected = isReadSelectedPara,
                isAcceptedDiff = diffKey in acceptedDiffKeys,
                imeBottomPx = imeBottomPx,
                bookmark = activeChapterBookmarks[pIdx],
                paragraphMatches = activeChapterMatches[pIdx].orEmpty(),
                activeGlobalMatchIndex = activeGlobalMatchIndex,
                onAcceptDiff = { onAcceptDiff(diffKey) },
                onRejectDiff = { onRejectDiff(pIdx, origPara) },
                onActivate = { onSelectParagraph(pIdx) },
                onFocusChanged = { focused -> onParagraphFocusChanged(pIdx, focused) },
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
 * Automatically locks horizontal swipe when `isEditMode == true` and keeps page height stable
 * while the soft keyboard is visible so tapping text never causes mid-edit repagination.
 */
@Composable
private fun SepiaHorizontalPagerPane(
    chapter: ManualChapterContent,
    chapterIndex: Int,
    totalChapters: Int,
    activeParagraphIndex: Int,
    speakingParagraphIndex: Int,
    showReadSelectionOutline: Boolean,
    scrollRequestId: Long,
    scrollTargetParagraphIndex: Int,
    fontSizeSp: Float,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    imeBottomPx: Int,
    activeChapterBookmarks: Map<Int, ManualTocEntry>,
    activeChapterMatches: Map<Int, List<Pair<Int, ManualSearchMatch>>>,
    activeGlobalMatchIndex: Int,
    acceptedDiffKeys: Set<String>,
    onAcceptDiff: (String) -> Unit,
    onRejectDiff: (Int, String) -> Unit,
    onSelectParagraph: (Int) -> Unit,
    onParagraphFocusChanged: (Int, Boolean) -> Unit,
    onUpdateParagraph: (Int, String) -> Unit,
    onSelectChapter: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rawAvailableHeightDp: Dp = (maxHeight - 48.dp).coerceAtLeast(180.dp)
        val availableWidthDp: Dp = (maxWidth - 36.dp).coerceAtLeast(220.dp)

        // Freeze pagination height while keyboard is open so tapped paragraphs stay on the same page
        var stableHeightDp by remember(chapter.fileName, isCompareMode) {
            mutableStateOf(rawAvailableHeightDp)
        }
        if (imeBottomPx == 0 && rawAvailableHeightDp > 180.dp) {
            stableHeightDp = rawAvailableHeightDp
        } else if (rawAvailableHeightDp > stableHeightDp) {
            stableHeightDp = rawAvailableHeightDp
        }

        // Paginate paragraph indices dynamically to fit available screen height cleanly
        val pages: List<List<Int>> = remember(
            chapter.fileName,
            chapter.currentParagraphs.size,
            fontSizeSp,
            stableHeightDp,
            availableWidthDp
        ) {
            paginateParagraphIndices(
                paragraphs = chapter.currentParagraphs,
                fontSizeSp = fontSizeSp,
                availableHeightDp = stableHeightDp.value,
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
                        .padding(
                            start = 14.dp,
                            end = 14.dp,
                            top = 12.dp,
                            bottom = if (isEditMode) 120.dp else 14.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (pIdx in paraIndices) {
                        val paragraph = chapter.currentParagraphs.getOrNull(pIdx) ?: continue
                        val origPara = chapter.originalParagraphs.getOrNull(pIdx).orEmpty()
                        val diffKey = "${chapter.fileName}_$pIdx"
                        val isActiveHighlightPara = (isCompareMode || isEditMode) && pIdx == activeParagraphIndex
                        val isSpeakingPara = pIdx == speakingParagraphIndex
                        val isReadSelectedPara = !isEditMode && !isCompareMode && showReadSelectionOutline && pIdx == activeParagraphIndex

                        key("${chapter.fileName}_hp_$pIdx") {
                            SepiaCurrentParagraphItem(
                                text = paragraph,
                                originalText = origPara,
                                isHeading = pIdx == 0 && paragraph.length <= 120,
                                fontSizeSp = fontSizeSp,
                                isEditMode = isEditMode,
                                isCompareMode = isCompareMode,
                                isActiveHighlight = isActiveHighlightPara,
                                isSpeaking = isSpeakingPara,
                                isReadSelected = isReadSelectedPara,
                                isAcceptedDiff = diffKey in acceptedDiffKeys,
                                imeBottomPx = imeBottomPx,
                                bookmark = activeChapterBookmarks[pIdx],
                                paragraphMatches = activeChapterMatches[pIdx].orEmpty(),
                                activeGlobalMatchIndex = activeGlobalMatchIndex,
                                onAcceptDiff = { onAcceptDiff(diffKey) },
                                onRejectDiff = { onRejectDiff(pIdx, origPara) },
                                onActivate = { onSelectParagraph(pIdx) },
                                onFocusChanged = { focused -> onParagraphFocusChanged(pIdx, focused) },
                                onTextChange = { updated -> onUpdateParagraph(pIdx, updated) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = SepiaBorder)

            // Compact 3D Horizontal Page Indicator + Prev/Next Page
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SepiaBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val canPrevPage = pagerState.currentPage > 0 || chapterIndex > 0
                NeoBrutalistPressBox(
                    onClick = {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else if (chapterIndex > 0) {
                            onSelectChapter(chapterIndex - 1)
                        }
                    },
                    enabled = canPrevPage,
                    isPill = true,
                    shadowOffset = if (canPrevPage) 2.dp else 0.dp,
                    pressDepth = 1.5.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (canPrevPage) SepiaCard else Color.Transparent,
                    borderColor = if (canPrevPage) SepiaInk else SepiaBorder,
                    shadowColor = if (canPrevPage) SepiaInk else Color.Transparent
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
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canPrevPage) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Text(
                    text = buildString {
                        append("Trang ${pagerState.currentPage + 1}/$pageCount")
                        if (isEditMode) append(" · Khóa vuốt")
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isEditMode) SepiaAccent else SepiaInkMuted,
                    maxLines = 1
                )

                val canNextPage = pagerState.currentPage < pageCount - 1 || chapterIndex < totalChapters - 1
                NeoBrutalistPressBox(
                    onClick = {
                        if (pagerState.currentPage < pageCount - 1) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else if (chapterIndex < totalChapters - 1) {
                            onSelectChapter(chapterIndex + 1)
                        }
                    },
                    enabled = canNextPage,
                    isPill = true,
                    shadowOffset = if (canNextPage) 2.dp else 0.dp,
                    pressDepth = 1.5.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (canNextPage) SepiaCard else Color.Transparent,
                    borderColor = if (canNextPage) SepiaInk else SepiaBorder,
                    shadowColor = if (canNextPage) SepiaInk else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (pagerState.currentPage == pageCount - 1 && chapterIndex < totalChapters - 1) "Chương sau" else "Sau",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canNextPage) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                            maxLines = 1,
                            softWrap = false
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
    Column(modifier = modifier.background(SepiaBg)) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SepiaCurrentParagraphItem(
    text: String,
    originalText: String,
    isHeading: Boolean,
    fontSizeSp: Float,
    isEditMode: Boolean,
    isCompareMode: Boolean,
    isActiveHighlight: Boolean,
    isSpeaking: Boolean,
    isReadSelected: Boolean,
    isAcceptedDiff: Boolean,
    imeBottomPx: Int,
    bookmark: ManualTocEntry?,
    paragraphMatches: List<Pair<Int, ManualSearchMatch>>,
    activeGlobalMatchIndex: Int,
    onAcceptDiff: () -> Unit,
    onRejectDiff: () -> Unit,
    onActivate: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onTextChange: (String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    if (textFieldValue.text != text) {
        val safeStart = textFieldValue.selection.start.coerceIn(0, text.length)
        val safeEnd = textFieldValue.selection.end.coerceIn(0, text.length)
        textFieldValue = TextFieldValue(
            text = text,
            selection = TextRange(safeStart, safeEnd)
        )
    }

    // Bring exact cursor line into view above the rising soft keyboard whenever focused, IME resizes, or cursor moves
    LaunchedEffect(isFocused, imeBottomPx, textFieldValue.selection.end) {
        if (!isEditMode || !isFocused || imeBottomPx <= 0) return@LaunchedEffect
        val padPx = with(density) { 28.dp.toPx() }
        val layout = textLayoutResult
        val cursorOffset = textFieldValue.selection.end.coerceIn(0, textFieldValue.text.length)
        val targetRect = if (layout != null && cursorOffset <= layout.layoutInput.text.length) {
            runCatching {
                val r = layout.getCursorRect(cursorOffset)
                Rect(
                    left = r.left,
                    top = (r.top - padPx).coerceAtLeast(0f),
                    right = r.right,
                    bottom = r.bottom + padPx
                )
            }.getOrNull()
        } else {
            null
        }
        runCatching { bringIntoViewRequester.bringIntoView(targetRect) }
        delay(80L)
        runCatching { bringIntoViewRequester.bringIntoView(targetRect) }
    }

    val effectiveFontSize = if (isHeading) (fontSizeSp + 2.5f).sp else fontSizeSp.sp
    val effectiveLineHeight = if (isHeading) ((fontSizeSp + 2.5f) * 1.38f).sp else (fontSizeSp * 1.62f).sp
    val effectiveWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal

    val hasUnresolvedDiff = originalText.isNotBlank() && originalText != text && !isAcceptedDiff
    val showInlineDiffCard = hasUnresolvedDiff && !isFocused

    val ttsTintColor = Color(0xFFDCFCE7)
    val ttsBorderColor = Color(0xFF16A34A)

    val containerBg = when {
        isSpeaking -> ttsTintColor
        isActiveHighlight -> SepiaActiveParagraphBg
        isReadSelected -> SepiaActiveParagraphBg.copy(alpha = 0.65f)
        isEditMode && isFocused -> Color(0xFFFFFDF7)
        else -> Color.Transparent
    }

    val containerBorder = when {
        isSpeaking -> BorderStroke(1.5.dp, ttsBorderColor)
        isEditMode && isFocused -> BorderStroke(1.5.dp, SepiaAccent)
        isActiveHighlight -> BorderStroke(1.5.dp, SepiaInk)
        isReadSelected -> BorderStroke(1.2.dp, SepiaInk.copy(alpha = 0.65f))
        isEditMode -> BorderStroke(0.9.dp, SepiaBorder)
        else -> null
    }

    val needsCardPadding = isEditMode || isActiveHighlight || isSpeaking || isReadSelected || showInlineDiffCard

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
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
                if (!isEditMode) {
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
        if (isSpeaking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(bottom = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VolumeUp,
                    contentDescription = "Đang đọc thử",
                    tint = ttsBorderColor,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "Đang đọc thử…",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ttsBorderColor
                )
            }
        }

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
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = SepiaAccent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Crisp 3D Inline Diff Comparison Card (Old Red Strikethrough vs New Green Highlight + Accept/Reject)
        if (showInlineDiffCard && !isEditMode) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 2.dp, bottom = 4.dp)
                    .neoHardOffsetShadow(
                        cornerRadius = 10.dp,
                        offsetX = 2.dp,
                        offsetY = 2.dp,
                        shadowColor = SepiaInk
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .background(SepiaCard)
                    .border(1.5.dp, SepiaInk, RoundedCornerShape(10.dp))
                    .padding(9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Header + Chấp nhận / Từ chối quick actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ĐÃ CHUẨN HÓA / SỬA",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SepiaInkMuted,
                        letterSpacing = 0.4.sp
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NeoBrutalistPressBox(
                            onClick = onRejectDiff,
                            isPill = true,
                            shadowOffset = 1.5.dp,
                            pressDepth = 1.5.dp,
                            borderWidth = 1.2.dp,
                            containerColor = Color(0xFFFEE2E2),
                            borderColor = SepiaInk,
                            shadowColor = SepiaInk
                        ) {
                            Text(
                                text = "Từ chối",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFB91C1C),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        NeoBrutalistPressBox(
                            onClick = onAcceptDiff,
                            isPill = true,
                            shadowOffset = 1.5.dp,
                            pressDepth = 1.5.dp,
                            borderWidth = 1.2.dp,
                            containerColor = Color(0xFF16A34A),
                            borderColor = SepiaInk,
                            shadowColor = SepiaInk
                        ) {
                            Text(
                                text = "Chấp nhận",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Original old text: soft red background (#FEE2E2) with strikethrough
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFFEE2E2))
                        .border(1.dp, Color(0xFFDC2626).copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = originalText,
                        fontSize = (fontSizeSp - 1.5f).coerceAtLeast(12f).sp,
                        lineHeight = ((fontSizeSp - 1.5f).coerceAtLeast(12f) * 1.48f).sp,
                        color = Color(0xFFB91C1C),
                        textDecoration = TextDecoration.LineThrough
                    )
                }

                // Normalized / New text: soft green background (#DCFCE7) with thin green border (#16A34A)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFDCFCE7))
                        .border(1.dp, Color(0xFF16A34A), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = text,
                        fontSize = effectiveFontSize,
                        lineHeight = effectiveLineHeight,
                        fontWeight = effectiveWeight,
                        color = Color(0xFF14532D)
                    )
                }
            }
        } else if (isEditMode) {
            // Live word-diff VisualTransformation inside BasicTextField when Compare Mode is ON!
            val visualTransformation = remember(isCompareMode, originalText) {
                if (isCompareMode && originalText.isNotEmpty()) {
                    TextDiffHelper.createLiveDiffVisualTransformation(originalText)
                } else {
                    VisualTransformation.None
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = "Nhập nội dung đoạn văn…",
                        fontSize = effectiveFontSize,
                        lineHeight = effectiveLineHeight,
                        color = SepiaInkMuted.copy(alpha = 0.55f)
                    )
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { updated ->
                        textFieldValue = updated
                        onActivate()
                        if (updated.text != text) {
                            onTextChange(updated.text)
                        }
                    },
                    onTextLayout = { result -> textLayoutResult = result },
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
                            onFocusChanged(state.isFocused)
                        }
                )
            }
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
        val hasPrev = chapterIndex > 0
        NeoBrutalistPressBox(
            onClick = { onSelectChapter(chapterIndex - 1) },
            enabled = hasPrev,
            isPill = true,
            shadowOffset = if (hasPrev) 3.dp else 0.dp,
            pressDepth = 2.dp,
            borderWidth = 1.5.dp,
            containerColor = if (hasPrev) SepiaCard else Color.Transparent,
            borderColor = if (hasPrev) SepiaInk else SepiaBorder,
            shadowColor = if (hasPrev) SepiaInk else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.NavigateBefore,
                    contentDescription = null,
                    tint = if (hasPrev) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Chương trước",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasPrev) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f)
                )
            }
        }

        val hasNext = chapterIndex < totalChapters - 1
        NeoBrutalistPressBox(
            onClick = { onSelectChapter(chapterIndex + 1) },
            enabled = hasNext,
            isPill = true,
            shadowOffset = if (hasNext) 3.dp else 0.dp,
            pressDepth = 2.dp,
            borderWidth = 1.5.dp,
            containerColor = if (hasNext) SepiaCard else Color.Transparent,
            borderColor = if (hasNext) SepiaInk else SepiaBorder,
            shadowColor = if (hasNext) SepiaInk else Color.Transparent
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chương sau",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (hasNext) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.NavigateNext,
                    contentDescription = null,
                    tint = if (hasNext) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
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

/**
 * Upgrade 1.5: Compact iOS-style 1-tap quick-save rule chip rendered right above the keyboard
 * when the user manually fixes a short phrase (1..3 words) in Edit Mode.
 */
@Composable
private fun QuickSaveReplaceRuleChipBar(
    oldPhrase: String,
    newPhrase: String,
    onSaveRule: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SepiaChrome)
    ) {
        HorizontalDivider(thickness = 0.7.dp, color = SepiaBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            NeoBrutalistPressBox(
                onClick = onSaveRule,
                isPill = true,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = SepiaCard,
                borderColor = SepiaInk,
                shadowColor = SepiaInk,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag("manual_quick_save_rule_chip")
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "+ Lưu quy tắc: \"$oldPhrase\" -> \"$newPhrase\"",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SepiaAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(SepiaChrome)
                            .clickable(onClick = onDismiss)
                            .testTag("manual_quick_save_rule_dismiss"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Bỏ qua gợi ý quy tắc",
                            tint = SepiaInkMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private val vietnameseDiacriticRegex = Regex(
    """[ăâđêôơưĂÂĐÊÔƠƯàáảãạằắẳẵặầấẩẫậèéẻẽẹềếểễệìíỉĩịòóỏõọồốổỗộờớởỡợùúủũụừứửữựỳýỷỹỵÀÁẢÃẠẰẮẲẴẶẦẤẨẪẬÈÉẺẼẸỀẾỂỄỆÌÍỈĨỊÒÓỎÕỌỒỐỔỖỘỜỚỞỠỢÙÚỦŨỤỪỨỬỮỰỲÝỶỸỴ]"""
)

private fun resolveTtsLocale(text: String, bookLanguage: String?): Locale {
    if (vietnameseDiacriticRegex.containsMatchIn(text)) {
        return Locale("vi", "VN")
    }
    if (bookLanguage != null && bookLanguage.trim().startsWith("en", ignoreCase = true)) {
        return Locale.US
    }
    val asciiLetters = text.count { it in 'a'..'z' || it in 'A'..'Z' }
    return if (asciiLetters >= 8) Locale.US else Locale("vi", "VN")
}
