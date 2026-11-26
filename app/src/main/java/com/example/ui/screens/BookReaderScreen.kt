package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audio.AmbientSoundTrack
import com.example.audio.ClonedVoiceProfile
import com.example.audio.OnnxVoiceEngineMode
import com.example.data.CustomImportedFont
import com.example.data.HorizontalPageEffect
import com.example.data.LibraryChapterMeta
import com.example.data.LibraryManager
import com.example.data.ReaderPaperTheme
import com.example.data.ReaderPreferences
import com.example.data.ReaderScrollDirection
import com.example.data.ReaderVoiceProfile
import com.example.service.BookAudioPlaybackService
import com.example.service.BookAudioPlaybackState
import com.example.ui.ActiveBookReaderState
import com.example.ui.components.AnimatedAudioPlayingBars
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.ReaderAudioSettingsSheetContent
import com.example.ui.components.VoiceCloneDialog
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoHardShadowColor
import com.example.ui.components.neoInkBorderColor
import com.example.ui.components.neoPaperSurfaceColor
import com.example.ui.components.neoSecondaryPaperColor
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosOrange
import com.example.ui.theme.NeoInkDark
import com.example.ui.theme.NeoInkLight
import com.example.ui.theme.NeoShadowDark
import com.example.ui.theme.NeoShadowLight
import com.example.ui.theme.ReaderBrownInk
import com.example.ui.theme.ReaderBrownPaper
import com.example.ui.theme.ReaderCreamInk
import com.example.ui.theme.ReaderCreamPaper
import com.example.ui.theme.ReaderDarkInk
import com.example.ui.theme.ReaderDarkPaper
import com.example.ui.theme.ReaderFontRegistry
import com.example.ui.theme.ReaderWhiteInk
import com.example.ui.theme.ReaderWhitePaper
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

private data class ReaderPageSlice(
    val paragraphIndex: Int,
    val text: String
)

private data class ReaderPageData(
    val isFirstPageOfChapter: Boolean,
    val slices: List<ReaderPageSlice>
) {
    val firstParagraphIndex: Int
        get() = slices.firstOrNull()?.paragraphIndex ?: 0
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun BookReaderScreen(
    readerState: ActiveBookReaderState,
    readerPrefs: ReaderPreferences,
    customFonts: List<CustomImportedFont>,
    audioPlaybackState: BookAudioPlaybackState,
    onBackClick: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onSelectChapter: (chapterIndex: Int, startParagraphIndex: Int, continueAudio: Boolean) -> Unit,
    onConsumeAutoContinueAudio: () -> Unit,
    onSaveProgress: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onSelectFont: (String) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onSelectPaperTheme: (ReaderPaperTheme) -> Unit,
    onSelectScrollDirection: (ReaderScrollDirection) -> Unit,
    onSelectHorizontalPageEffect: (HorizontalPageEffect) -> Unit,
    onAdjustPageMargin: (Float) -> Unit,
    onAdjustLineHeight: (Float) -> Unit,
    onAdjustLetterSpacing: (Float) -> Unit,
    onCycleSpeechRate: () -> Unit,
    onCycleVoiceProfile: () -> Unit,
    onSelectNarratorVoice: (ReaderVoiceProfile) -> Unit,
    onSelectSystemVoice: (String?) -> Unit,
    onToggleAdvancedCustom: (Boolean) -> Unit,
    onAdvancedPitchChanged: (Float) -> Unit,
    onAdvancedRateChanged: (Float) -> Unit,
    onSentencePauseChanged: (Int) -> Unit,
    onResetAdvancedDefaults: () -> Unit,
    onToggleCharacterVoicesEnabled: (Boolean) -> Unit,
    onScanCharacters: () -> Unit,
    onConfirmCharacterAssignments: (Map<String, ReaderVoiceProfile>) -> Unit,
    onHideCharacterPicker: () -> Unit,
    onSelectAmbientSound: (AmbientSoundTrack) -> Unit,
    onChangeAmbientVolume: (Float) -> Unit,
    clonedVoices: List<ClonedVoiceProfile> = emptyList(),
    voiceCloneEngineMode: OnnxVoiceEngineMode = OnnxVoiceEngineMode.SAFE_TTS_FALLBACK,
    voiceCloneTargetSampleRate: Int = 16_000,
    isExtractingVoiceClone: Boolean = false,
    onSaveClonedVoice: (name: String, samples: FloatArray, sampleRate: Int, onDone: () -> Unit) -> Unit = { _, _, _, _ -> },
    onSelectClonedVoice: (String?) -> Unit = {},
    onDeleteClonedVoice: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val activeFontFamily = remember(readerPrefs.fontId, customFonts) {
        ReaderFontRegistry.resolveFontFamily(readerPrefs.fontId, customFonts)
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Service continues cleanly regardless */ }
    )

    var showTopChrome by remember { mutableStateOf(true) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showAppearancePopover by remember { mutableStateOf(false) }
    var showAudioBar by remember { mutableStateOf(false) }
    var showAudioSettingsSheet by remember { mutableStateOf(false) }
    var showVoiceCloneDialog by remember { mutableStateOf(false) }

    // Track current visible paragraph cleanly across BOTH Vertical and Horizontal reading modes
    var currentTrackedParagraphIndex by remember(readerState.currentChapterIndex) {
        mutableIntStateOf(readerState.targetParagraphIndex)
    }

    // Derive reactive audio states from persistent background BookAudioPlaybackService
    val isThisBookActiveInService = readerState.book?.id != null &&
        audioPlaybackState.bookId == readerState.book.id
    val isPlayingAudio = audioPlaybackState.isPlaying && isThisBookActiveInService
    val isAnyAudioPlaying = audioPlaybackState.isPlaying
    val speakingParagraphIndex = if (
        isThisBookActiveInService &&
        audioPlaybackState.chapterIndex == readerState.currentChapterIndex
    ) {
        audioPlaybackState.paragraphIndex
    } else {
        -1
    }
    val speakingSentenceText = if (
        isThisBookActiveInService &&
        audioPlaybackState.chapterIndex == readerState.currentChapterIndex
    ) {
        audioPlaybackState.activeSentenceText
    } else {
        ""
    }
    val activeSpeakerName = if (isThisBookActiveInService) audioPlaybackState.activeSpeakerName else null
    val activeClonedVoice = remember(readerPrefs.selectedClonedVoiceId, clonedVoices) {
        clonedVoices.firstOrNull { it.id == readerPrefs.selectedClonedVoiceId }
    }

    val latestState by rememberUpdatedState(readerState)
    val latestSpeakingIdx by rememberUpdatedState(speakingParagraphIndex)
    val latestTrackedPara by rememberUpdatedState(currentTrackedParagraphIndex)

    // Resolve paper & ink colors from selected theme (3 warm archival papers + Dark Night)
    val isDarkReaderPaper = readerPrefs.paperTheme == ReaderPaperTheme.DARK_NIGHT
    val paperBg = when (readerPrefs.paperTheme) {
        ReaderPaperTheme.BOOK_BROWN -> ReaderBrownPaper
        ReaderPaperTheme.WARM_CREAM -> ReaderCreamPaper
        ReaderPaperTheme.PURE_WHITE -> ReaderWhitePaper
        ReaderPaperTheme.DARK_NIGHT -> ReaderDarkPaper
    }
    val inkColor = when (readerPrefs.paperTheme) {
        ReaderPaperTheme.BOOK_BROWN -> ReaderBrownInk
        ReaderPaperTheme.WARM_CREAM -> ReaderCreamInk
        ReaderPaperTheme.PURE_WHITE -> ReaderWhiteInk
        ReaderPaperTheme.DARK_NIGHT -> ReaderDarkInk
    }
    val subtleInk = inkColor.copy(alpha = 0.60f)
    val subtleSurface = when (readerPrefs.paperTheme) {
        ReaderPaperTheme.BOOK_BROWN -> Color(0xFFDFD3BE)
        ReaderPaperTheme.WARM_CREAM -> Color(0xFFEBE0C8)
        ReaderPaperTheme.PURE_WHITE -> Color(0xFFEDE8DC)
        ReaderPaperTheme.DARK_NIGHT -> Color(0xFF1F1F23)
    }
    val keycapBg = if (isDarkReaderPaper) Color(0xFF1F1F23) else Color(0xFFFAF7F0)
    val keycapBorder = if (isDarkReaderPaper) NeoInkDark else NeoInkLight
    val keycapShadow = if (isDarkReaderPaper) NeoShadowDark else NeoShadowLight
    val keycapIconTint = if (isDarkReaderPaper) ReaderDarkInk else NeoInkLight

    fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun currentVisibleParagraphIndex(): Int {
        val maxIdx = (readerState.paragraphs.size - 1).coerceAtLeast(0)
        return if (readerPrefs.scrollDirection == ReaderScrollDirection.VERTICAL) {
            (listState.firstVisibleItemIndex - 1).coerceIn(0, maxIdx)
        } else {
            latestTrackedPara.coerceIn(0, maxIdx)
        }
    }

    fun playFromParagraph(paraIdx: Int) {
        val bookId = readerState.book?.id ?: return
        requestNotifPermissionIfNeeded()
        BookAudioPlaybackService.playAt(
            context = context,
            bookId = bookId,
            chapterIndex = readerState.currentChapterIndex,
            paragraphIndex = paraIdx.coerceAtLeast(0)
        )
    }

    // Save reading progress on leaving reader WITHOUT stopping background playback!
    DisposableEffect(Unit) {
        onDispose {
            BookAudioPlaybackService.setAmbientPreview(context, false)
            onSaveProgress(
                latestState.currentChapterIndex,
                if (latestSpeakingIdx >= 0) latestSpeakingIdx else currentVisibleParagraphIndex()
            )
        }
    }

    // Live ambient sound preview & device offline voice scan whenever Audio Settings sheet opens
    LaunchedEffect(showAudioSettingsSheet) {
        if (showAudioSettingsSheet) {
            BookAudioPlaybackService.refreshDeviceVoices(context)
        }
        BookAudioPlaybackService.setAmbientPreview(context, showAudioSettingsSheet)
    }

    // Auto-sync visual reader chapter whenever background service advances to another chapter
    LaunchedEffect(
        audioPlaybackState.bookId,
        audioPlaybackState.chapterIndex,
        audioPlaybackState.isPlaying,
        readerState.book?.id
    ) {
        val openBookId = readerState.book?.id
        if (openBookId != null &&
            audioPlaybackState.bookId == openBookId &&
            audioPlaybackState.isPlaying &&
            audioPlaybackState.chapterIndex != readerState.currentChapterIndex &&
            !readerState.isLoadingChapter
        ) {
            onSelectChapter(
                audioPlaybackState.chapterIndex,
                audioPlaybackState.paragraphIndex.coerceAtLeast(0),
                false
            )
        }
    }

    // Scroll to saved/target paragraph whenever a chapter finishes loading (Vertical mode)
    LaunchedEffect(readerState.chapterLoadNonce, readerPrefs.scrollDirection) {
        if (readerState.chapterLoadNonce == 0L || readerState.isLoadingChapter) return@LaunchedEffect
        currentTrackedParagraphIndex = readerState.targetParagraphIndex
        if (readerPrefs.scrollDirection == ReaderScrollDirection.VERTICAL) {
            val targetItem = (readerState.targetParagraphIndex + 1)
                .coerceIn(0, readerState.paragraphs.size)
            listState.scrollToItem(targetItem)
        }
        if (readerState.autoContinueAudioOnChapterReady) {
            onConsumeAutoContinueAudio()
            playFromParagraph(readerState.targetParagraphIndex)
        }
    }

    // Smoothly scroll to the active spoken paragraph when background TTS advances (Vertical mode)
    LaunchedEffect(speakingParagraphIndex, isPlayingAudio, readerPrefs.scrollDirection) {
        if (isPlayingAudio &&
            speakingParagraphIndex >= 0 &&
            speakingParagraphIndex < readerState.paragraphs.size
        ) {
            currentTrackedParagraphIndex = speakingParagraphIndex
            if (readerPrefs.scrollDirection == ReaderScrollDirection.VERTICAL) {
                val targetItemIndex = speakingParagraphIndex + 1
                val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
                if (targetItemIndex !in visibleIndices) {
                    runCatching { listState.animateScrollToItem(targetItemIndex) }
                }
            }
        }
    }

    // Debounced auto-save scroll progress as the user reads (Vertical mode)
    LaunchedEffect(listState, readerState.currentChapterIndex, readerState.book?.id, readerPrefs.scrollDirection) {
        if (readerPrefs.scrollDirection != ReaderScrollDirection.VERTICAL) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(900L)
            .collect { firstIdx ->
                if (!readerState.isLoadingChapter && readerState.paragraphs.isNotEmpty()) {
                    val paraIdx = (firstIdx - 1).coerceIn(0, readerState.paragraphs.lastIndex)
                    currentTrackedParagraphIndex = paraIdx
                    onSaveProgress(readerState.currentChapterIndex, paraIdx)
                }
            }
    }

    fun exitReader() {
        val paraToSave = if (speakingParagraphIndex >= 0) {
            speakingParagraphIndex
        } else {
            currentVisibleParagraphIndex()
        }
        // Keep background audio running smoothly when returning to Library / Main Screen!
        BookAudioPlaybackService.setAmbientPreview(context, false)
        onBackClick(readerState.currentChapterIndex, paraToSave)
    }

    BackHandler {
        when {
            showAudioSettingsSheet -> {
                showAudioSettingsSheet = false
                onHideCharacterPicker()
            }
            showAppearancePopover -> showAppearancePopover = false
            showTocSheet -> showTocSheet = false
            else -> exitReader()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paperBg),
        contentAlignment = Alignment.TopCenter
    ) {
        // 1. Reading Content Area (Full-bleed archival page; center tap toggles top 3D keycap bar)
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (showAppearancePopover || showAudioBar) {
                                showAppearancePopover = false
                                showAudioBar = false
                            } else {
                                showTopChrome = !showTopChrome
                            }
                        }
                    )
            ) {
                val viewportWidthDp = maxWidth.value
                val viewportHeightDp = maxHeight.value

                if (readerState.isLoadingChapter && readerState.paragraphs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = inkColor.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    val fontSize = readerPrefs.fontSizeSp
                    val lineHeightSp = (fontSize * readerPrefs.lineHeightMultiplier).sp
                    val letterSpacingSp = readerPrefs.letterSpacingSp.sp
                    val pageMarginDp = readerPrefs.pageMarginDp.dp
                    val paragraphGapDp = (fontSize * 0.55f).coerceIn(8f, 22f).dp

                    if (readerPrefs.scrollDirection == ReaderScrollDirection.VERTICAL) {
                        // --- VERTICAL SCROLL READING MODE ---
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("reader_paragraphs_list"),
                            contentPadding = PaddingValues(
                                start = pageMarginDp,
                                end = pageMarginDp,
                                top = 56.dp,
                                bottom = 68.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(paragraphGapDp)
                        ) {
                            item(key = "chapter_header_${readerState.currentChapterIndex}") {
                                ChapterTitleBlock(
                                    bookTitle = readerState.book?.title.orEmpty(),
                                    chapterTitle = readerState.currentChapterTitle.ifBlank {
                                        "Chương ${readerState.currentChapterIndex + 1}"
                                    },
                                    fontSize = fontSize,
                                    letterSpacingSp = readerPrefs.letterSpacingSp,
                                    fontFamily = activeFontFamily,
                                    inkColor = inkColor,
                                    subtleInk = subtleInk
                                )
                            }

                            itemsIndexed(
                                items = readerState.paragraphs,
                                key = { idx, _ -> "p_${readerState.currentChapterIndex}_$idx" }
                            ) { index, paragraph ->
                                val isSpeakingThis = speakingParagraphIndex == index &&
                                    (isPlayingAudio || showAudioBar)
                                ReaderParagraphBox(
                                    text = paragraph,
                                    fontSize = fontSize,
                                    lineHeightSp = lineHeightSp,
                                    letterSpacingSp = letterSpacingSp,
                                    fontFamily = activeFontFamily,
                                    inkColor = inkColor,
                                    highlighted = isSpeakingThis,
                                    activeSentenceText = if (isSpeakingThis) speakingSentenceText else null,
                                    onClick = {
                                        if (showAppearancePopover || showAudioBar) {
                                            showAppearancePopover = false
                                            showAudioBar = false
                                        } else {
                                            showTopChrome = !showTopChrome
                                        }
                                    }
                                )
                            }

                            item(key = "chapter_footer_${readerState.currentChapterIndex}") {
                                ChapterFooterNavigation(
                                    currentChapterIndex = readerState.currentChapterIndex,
                                    totalChapters = readerState.toc.size.coerceAtLeast(1),
                                    inkColor = inkColor,
                                    subtleInk = subtleInk,
                                    subtleSurface = subtleSurface,
                                    onPrevChapter = {
                                        val keepPlaying = isPlayingAudio
                                        onSelectChapter(readerState.currentChapterIndex - 1, 0, keepPlaying)
                                    },
                                    onNextChapter = {
                                        val keepPlaying = isPlayingAudio
                                        onSelectChapter(readerState.currentChapterIndex + 1, 0, keepPlaying)
                                    }
                                )
                            }
                        }
                    } else {
                        // --- HORIZONTAL PAGE-FLIPPING MODE (Apple Books HorizontalPager) ---
                        val pages = remember(
                            readerState.paragraphs,
                            readerState.currentChapterIndex,
                            viewportWidthDp,
                            viewportHeightDp,
                            readerPrefs.fontSizeSp,
                            readerPrefs.pageMarginDp,
                            readerPrefs.lineHeightMultiplier,
                            readerPrefs.letterSpacingSp
                        ) {
                            paginateChapterIntoPages(
                                paragraphs = readerState.paragraphs,
                                viewportWidthDp = viewportWidthDp,
                                viewportHeightDp = viewportHeightDp,
                                marginDp = readerPrefs.pageMarginDp,
                                fontSizeSp = readerPrefs.fontSizeSp,
                                lineHeightMultiplier = readerPrefs.lineHeightMultiplier,
                                letterSpacingSp = readerPrefs.letterSpacingSp
                            )
                        }

                        val initialPage = remember(readerState.chapterLoadNonce, pages.size) {
                            findPageIndexForParagraph(pages, readerState.targetParagraphIndex)
                        }
                        val pagerState = rememberPagerState(
                            initialPage = initialPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
                            pageCount = { pages.size.coerceAtLeast(1) }
                        )

                        // Jump to saved paragraph when chapter changes or layout re-paginates
                        LaunchedEffect(readerState.chapterLoadNonce, pages.size) {
                            val targetPage = findPageIndexForParagraph(
                                pages,
                                if (isPlayingAudio && speakingParagraphIndex >= 0) {
                                    speakingParagraphIndex
                                } else {
                                    currentTrackedParagraphIndex
                                }
                            )
                            val clamped = targetPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                            if (pagerState.currentPage != clamped) {
                                pagerState.scrollToPage(clamped)
                            }
                        }

                        // Auto-turn page when TTS advances to a paragraph on another page
                        LaunchedEffect(speakingParagraphIndex, isPlayingAudio, pages.size, readerPrefs.horizontalPageEffect) {
                            if (isPlayingAudio && speakingParagraphIndex >= 0) {
                                val targetPage = findPageIndexForParagraph(pages, speakingParagraphIndex)
                                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                                if (pagerState.currentPage != targetPage) {
                                    runCatching {
                                        if (readerPrefs.horizontalPageEffect == HorizontalPageEffect.NONE) {
                                            pagerState.scrollToPage(targetPage)
                                        } else {
                                            pagerState.animateScrollToPage(targetPage)
                                        }
                                    }
                                }
                            }
                        }

                        // Save progress whenever user swipes to a new horizontal page
                        LaunchedEffect(pagerState, pages, readerState.currentChapterIndex) {
                            snapshotFlow { pagerState.currentPage }
                                .distinctUntilChanged()
                                .debounce(500L)
                                .collect { pageIdx ->
                                    val paraIdx = pages.getOrNull(pageIdx)?.firstParagraphIndex ?: 0
                                    currentTrackedParagraphIndex = paraIdx
                                    onSaveProgress(readerState.currentChapterIndex, paraIdx)
                                }
                        }

                        val pageEffect = readerPrefs.horizontalPageEffect
                        Column(modifier = Modifier.fillMaxSize()) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .pointerInput(pageEffect, pages.size) {
                                        detectTapGestures { offset ->
                                            if (showAppearancePopover || showAudioBar) {
                                                showAppearancePopover = false
                                                showAudioBar = false
                                                return@detectTapGestures
                                            }
                                            val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                            val tapFraction = offset.x / widthPx
                                            // Hỗ trợ chạm mép trái/phải để chuyển trang khi dùng "Chạm lướt mờ" hoặc "Không hiệu ứng"
                                            if (pageEffect != HorizontalPageEffect.BOOK_FLIP) {
                                                when {
                                                    tapFraction < 0.28f && pagerState.currentPage > 0 -> {
                                                        coroutineScope.launch {
                                                            if (pageEffect == HorizontalPageEffect.NONE) {
                                                                pagerState.scrollToPage(pagerState.currentPage - 1)
                                                            } else {
                                                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                                            }
                                                        }
                                                    }
                                                    tapFraction > 0.72f && pagerState.currentPage < pages.lastIndex -> {
                                                        coroutineScope.launch {
                                                            if (pageEffect == HorizontalPageEffect.NONE) {
                                                                pagerState.scrollToPage(pagerState.currentPage + 1)
                                                            } else {
                                                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        showTopChrome = !showTopChrome
                                                    }
                                                }
                                            } else {
                                                showTopChrome = !showTopChrome
                                            }
                                        }
                                    }
                                    .testTag("reader_horizontal_pager")
                            ) { pageIndex ->
                                val page = pages.getOrNull(pageIndex)
                                val pageOffset = (
                                    (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                                ).coerceIn(-1f, 1f)

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            when (pageEffect) {
                                                HorizontalPageEffect.BOOK_FLIP -> {
                                                    // Hiệu ứng lật sách 3D mềm mại quanh gáy trái
                                                    cameraDistance = 14f * density
                                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                                    rotationY = -pageOffset * 28f
                                                    val depthScale = 1f - abs(pageOffset) * 0.04f
                                                    scaleX = depthScale
                                                    scaleY = depthScale
                                                    alpha = (1f - abs(pageOffset) * 0.25f).coerceIn(0.65f, 1f)
                                                }
                                                HorizontalPageEffect.TAP_FADE -> {
                                                    // Chạm lướt mờ mượt mà (cross-fade nhẹ)
                                                    alpha = (1f - abs(pageOffset) * 0.85f).coerceIn(0.15f, 1f)
                                                    translationX = -pageOffset * size.width * 0.25f
                                                }
                                                HorizontalPageEffect.NONE -> {
                                                    // Không hiệu ứng: giữ trang phẳng tuyệt đối
                                                    alpha = 1f
                                                    rotationY = 0f
                                                }
                                            }
                                        }
                                        .padding(
                                            start = pageMarginDp,
                                            end = pageMarginDp,
                                            top = 56.dp,
                                            bottom = 8.dp
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(paragraphGapDp)
                                ) {
                                    if (page != null && page.isFirstPageOfChapter) {
                                        ChapterTitleBlock(
                                            bookTitle = readerState.book?.title.orEmpty(),
                                            chapterTitle = readerState.currentChapterTitle.ifBlank {
                                                "Chương ${readerState.currentChapterIndex + 1}"
                                            },
                                            fontSize = fontSize,
                                            letterSpacingSp = readerPrefs.letterSpacingSp,
                                            fontFamily = activeFontFamily,
                                            inkColor = inkColor,
                                            subtleInk = subtleInk
                                        )
                                    }

                                    page?.slices?.forEach { slice ->
                                        val isSpeakingThis = speakingParagraphIndex == slice.paragraphIndex &&
                                            (isPlayingAudio || showAudioBar)
                                        ReaderParagraphBox(
                                            text = slice.text,
                                            fontSize = fontSize,
                                            lineHeightSp = lineHeightSp,
                                            letterSpacingSp = letterSpacingSp,
                                            fontFamily = activeFontFamily,
                                            inkColor = inkColor,
                                            highlighted = isSpeakingThis,
                                            activeSentenceText = if (isSpeakingThis) speakingSentenceText else null,
                                            onClick = {
                                                if (showAppearancePopover || showAudioBar) {
                                                    showAppearancePopover = false
                                                    showAudioBar = false
                                                } else {
                                                    showTopChrome = !showTopChrome
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            // Compact Horizontal Page Indicator + Prev/Next Chapter pills on edge pages
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val hasPrevChap = readerState.currentChapterIndex > 0
                                val hasNextChap = readerState.currentChapterIndex + 1 < readerState.toc.size
                                val onFirstPage = pagerState.currentPage == 0
                                val onLastPage = pagerState.currentPage >= pages.lastIndex

                                if (onFirstPage && hasPrevChap) {
                                    NeoBrutalistPressBox(
                                        onClick = {
                                            val keepPlaying = isPlayingAudio
                                            onSelectChapter(readerState.currentChapterIndex - 1, 0, keepPlaying)
                                        },
                                        isPill = true,
                                        shadowOffset = 3.dp,
                                        pressDepth = 2.dp,
                                        borderWidth = 1.5.dp,
                                        containerColor = paperBg,
                                        borderColor = inkColor,
                                        shadowColor = inkColor
                                    ) {
                                        Text(
                                            text = "← Chương trước",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = inkColor,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(64.dp))
                                }

                                Text(
                                    text = "Trang ${pagerState.currentPage + 1} / ${pages.size.coerceAtLeast(1)}  •  Ch.${readerState.currentChapterIndex + 1}/${readerState.toc.size.coerceAtLeast(1)}",
                                    fontSize = 11.sp,
                                    color = subtleInk,
                                    maxLines = 1
                                )

                                if (onLastPage && hasNextChap) {
                                    NeoBrutalistPressBox(
                                        onClick = {
                                            val keepPlaying = isPlayingAudio
                                            onSelectChapter(readerState.currentChapterIndex + 1, 0, keepPlaying)
                                        },
                                        isPill = true,
                                        shadowOffset = 3.dp,
                                        pressDepth = 2.dp,
                                        borderWidth = 1.5.dp,
                                        containerColor = paperBg,
                                        borderColor = inkColor,
                                        shadowColor = inkColor
                                    ) {
                                        Text(
                                            text = "Chương sau →",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = inkColor,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(64.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Auto-hiding Swiss Neo-Brutalist 3D Keycap Top Toolbar (32dp square 3D keycaps)
        AnimatedVisibility(
            visible = showTopChrome || showAppearancePopover,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Control 1: 3D Keycap Back (<)
                Reader3DKeycapButton(
                    onClick = ::exitReader,
                    containerColor = keycapBg,
                    borderColor = keycapBorder,
                    shadowColor = keycapShadow,
                    modifier = Modifier.testTag("reader_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBackIosNew,
                        contentDescription = "Quay lại thư viện",
                        tint = keycapIconTint,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Controls 2..5: ☰ TOC | + Appearance | 🎧 Listen TTS | ⚙️ Audio Settings
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Reader3DKeycapButton(
                        onClick = {
                            showAppearancePopover = false
                            showTocSheet = true
                        },
                        containerColor = if (showTocSheet) IosOrange else keycapBg,
                        borderColor = keycapBorder,
                        shadowColor = keycapShadow,
                        modifier = Modifier.testTag("reader_toc_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Mục lục",
                            tint = if (showTocSheet) NeoInkLight else keycapIconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Reader3DKeycapButton(
                        onClick = { showAppearancePopover = !showAppearancePopover },
                        containerColor = if (showAppearancePopover) IosBlue else keycapBg,
                        borderColor = keycapBorder,
                        shadowColor = keycapShadow,
                        modifier = Modifier.testTag("reader_appearance_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Tùy chỉnh chữ, lề và kiểu lật trang",
                            tint = if (showAppearancePopover) Color.White else keycapIconTint,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 🎧 Headphones 3D keycap (expands neatly with live bars when playing!)
                    val audioActive = showAudioBar || isAnyAudioPlaying
                    NeoBrutalistPressBox(
                        onClick = {
                            showAppearancePopover = false
                            if (!showAudioBar) {
                                showAudioBar = true
                                if (!isPlayingAudio) {
                                    val startIdx = if (speakingParagraphIndex in readerState.paragraphs.indices) {
                                        speakingParagraphIndex
                                    } else {
                                        currentVisibleParagraphIndex()
                                    }
                                    playFromParagraph(startIdx)
                                }
                            } else {
                                showAudioBar = false
                            }
                        },
                        cornerRadius = 9.dp,
                        shadowOffset = 2.dp,
                        pressDepth = 2.dp,
                        borderWidth = 1.5.dp,
                        containerColor = if (audioActive) IosBlue else keycapBg,
                        borderColor = keycapBorder,
                        shadowColor = keycapShadow,
                        modifier = Modifier
                            .height(34.dp)
                            .widthIn(min = 34.dp)
                            .testTag("reader_audio_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = if (isAnyAudioPlaying) 8.dp else 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Headphones,
                                contentDescription = "Nghe đọc sách",
                                tint = if (audioActive) Color.White else keycapIconTint,
                                modifier = Modifier.size(18.dp)
                            )
                            if (isAnyAudioPlaying) {
                                AnimatedAudioPlayingBars(
                                    color = Color.White,
                                    maxHeight = 12.dp,
                                    barWidth = 2.2.dp,
                                    modifier = Modifier.testTag("reader_audio_running_indicator")
                                )
                            }
                        }
                    }

                    // ⚙️ Audio Settings 3D Keycap
                    val settingsActive = showAudioSettingsSheet ||
                        readerPrefs.ambientSound != AmbientSoundTrack.NONE ||
                        readerState.characterVoiceAssignments.isNotEmpty()
                    Reader3DKeycapButton(
                        onClick = {
                            showAppearancePopover = false
                            showTocSheet = false
                            showAudioSettingsSheet = true
                        },
                        containerColor = if (settingsActive) IosOrange else keycapBg,
                        borderColor = keycapBorder,
                        shadowColor = keycapShadow,
                        modifier = Modifier.testTag("reader_audio_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Cài đặt nghe sách",
                            tint = if (settingsActive) NeoInkLight else keycapIconTint,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        // 3. Thẻ điều khiển Nghe Sách Mini nổi khối 3D ở PHÍA DƯỚI GIỮA MÀN HÌNH
        AnimatedVisibility(
            visible = showAudioBar,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            IosFloatingAudioPill(
                isDarkReaderPaper = isDarkReaderPaper,
                isPlaying = isPlayingAudio,
                speechRate = readerPrefs.speechRate,
                voiceLabel = activeClonedVoice?.let { "✦ ${it.name}" }
                    ?: activeSpeakerName
                    ?: readerPrefs.voiceProfile.shortLabel,
                hasClonedVoiceActive = activeClonedVoice != null,
                sleepTimerActive = audioPlaybackState.hasActiveSleepTimer,
                sleepTimerBadge = when {
                    audioPlaybackState.sleepTimerEndOfChapter -> "Hết ch."
                    audioPlaybackState.sleepTimerRemainingMinutes > 0 -> "${audioPlaybackState.sleepTimerRemainingMinutes}p"
                    else -> null
                },
                onPrevParagraph = {
                    if (isThisBookActiveInService) {
                        BookAudioPlaybackService.prevParagraph(context)
                    } else {
                        val prev = (currentVisibleParagraphIndex() - 1).coerceAtLeast(0)
                        playFromParagraph(prev)
                    }
                },
                onTogglePlayPause = {
                    if (isPlayingAudio) {
                        BookAudioPlaybackService.pause(context)
                    } else {
                        val startIdx = if (speakingParagraphIndex in readerState.paragraphs.indices) {
                            speakingParagraphIndex
                        } else {
                            currentVisibleParagraphIndex()
                        }
                        playFromParagraph(startIdx)
                    }
                },
                onNextParagraph = {
                    if (isThisBookActiveInService) {
                        BookAudioPlaybackService.nextParagraph(context)
                    } else {
                        val next = (currentVisibleParagraphIndex() + 1)
                            .coerceAtMost((readerState.paragraphs.size - 1).coerceAtLeast(0))
                        playFromParagraph(next)
                    }
                },
                onCycleSpeed = onCycleSpeechRate,
                onCycleVoice = onCycleVoiceProfile,
                onOpenVoiceCloneDialog = {
                    if (isAnyAudioPlaying) {
                        BookAudioPlaybackService.pause(context)
                    }
                    showVoiceCloneDialog = true
                },
                onSelectSleepTimerMinutes = { mins ->
                    BookAudioPlaybackService.setSleepTimer(context, mins)
                },
                onStopAndClose = {
                    BookAudioPlaybackService.stop(context)
                    showAudioBar = false
                }
            )
        }

        // 4. Floating 3D Paper Popover for "+" (Font, A-/A+ Cỡ chữ, Lật trang, Lề/Giãn dòng, 4 thẻ vuông màu giấy)
        if (showAppearancePopover) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showAppearancePopover = false }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 680.dp)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 52.dp, end = 14.dp)
                        .align(Alignment.TopCenter),
                    contentAlignment = Alignment.TopEnd
                ) {
                    IosReaderAppearancePopover(
                        prefs = readerPrefs,
                        customFonts = customFonts,
                        onSelectFont = onSelectFont,
                        onSetFontSize = onSetFontSize,
                        onDecreaseFont = { onAdjustFontSize(-1f) },
                        onIncreaseFont = { onAdjustFontSize(1f) },
                        onSelectScrollDirection = onSelectScrollDirection,
                        onSelectHorizontalPageEffect = onSelectHorizontalPageEffect,
                        onDecreaseMargin = { onAdjustPageMargin(-4f) },
                        onIncreaseMargin = { onAdjustPageMargin(4f) },
                        onDecreaseLineHeight = { onAdjustLineHeight(-0.1f) },
                        onIncreaseLineHeight = { onAdjustLineHeight(0.1f) },
                        onDecreaseLetterSpacing = { onAdjustLetterSpacing(-0.25f) },
                        onIncreaseLetterSpacing = { onAdjustLetterSpacing(0.25f) },
                        onSelectTheme = onSelectPaperTheme
                    )
                }
            }
        }

        // 5. 3D Notebook Left-Edge Sliding TOC Drawer (Bảng Mục lục dạng sổ tay trượt cạnh trái)
        AnimatedVisibility(
            visible = showTocSheet,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showTocSheet = false }
                    )
            )
        }
        AnimatedVisibility(
            visible = showTocSheet,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
        ) {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale.coerceAtMost(1.0f)
                )
            ) {
                ReaderTocNotebookDrawer(
                    bookTitle = readerState.book?.title.orEmpty(),
                    chapters = readerState.toc,
                    currentChapterIndex = readerState.currentChapterIndex,
                    isDarkPaper = isDarkReaderPaper,
                    onSelectChapter = { idx ->
                        showTocSheet = false
                        val keepPlaying = isPlayingAudio
                        onSelectChapter(idx, 0, keepPlaying)
                    },
                    onClose = { showTocSheet = false }
                )
            }
        }
    }

    // 6. iOS Audio Settings Sheet (Narrator, All Voices + Pitch Lưu/Reset, Scan 7 Characters -> OK, Ambient Sound)
    if (showAudioSettingsSheet) {
        val audioSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showAudioSettingsSheet = false
                onHideCharacterPicker()
            },
            sheetState = audioSheetState,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale.coerceAtMost(1.0f)
                )
            ) {
                ReaderAudioSettingsSheetContent(
                    readerPrefs = readerPrefs,
                    availableDeviceVoices = audioPlaybackState.availableDeviceVoices,
                    isScanningCharacters = readerState.isScanningCharacters,
                    scannedCharacters = readerState.scannedCharacters,
                    showCharacterVoicePicker = readerState.showCharacterVoicePicker,
                    savedCharacterAssignments = readerState.characterVoiceAssignments,
                    onSelectNarratorVoice = onSelectNarratorVoice,
                    onSelectSystemVoice = onSelectSystemVoice,
                    onRefreshDeviceVoices = {
                        BookAudioPlaybackService.refreshDeviceVoices(context)
                    },
                    onPreviewSystemVoice = { voiceName ->
                        BookAudioPlaybackService.previewVoice(
                            context = context,
                            profile = readerPrefs.voiceProfile,
                            pitch = readerPrefs.resolvePlaybackPitch(readerPrefs.voiceProfile),
                            rate = readerPrefs.resolvePlaybackRate(readerPrefs.voiceProfile),
                            pauseMs = readerPrefs.sentencePauseMs,
                            systemVoiceName = voiceName
                        )
                    },
                    onToggleAdvancedCustom = { enabled ->
                        if (enabled && isAnyAudioPlaying) {
                            BookAudioPlaybackService.pause(context)
                        }
                        onToggleAdvancedCustom(enabled)
                    },
                    onAdvancedPitchChanged = onAdvancedPitchChanged,
                    onAdvancedRateChanged = onAdvancedRateChanged,
                    onSentencePauseChanged = onSentencePauseChanged,
                    onPreviewAdvanced = { pitch, rate, pauseMs ->
                        BookAudioPlaybackService.previewVoice(
                            context = context,
                            profile = readerPrefs.voiceProfile,
                            pitch = pitch,
                            rate = rate,
                            pauseMs = pauseMs,
                            systemVoiceName = readerPrefs.selectedSystemVoiceName
                        )
                    },
                    onResetAdvancedDefaults = onResetAdvancedDefaults,
                    onPreviewVoice = { profile, pitch ->
                        BookAudioPlaybackService.previewVoice(
                            context = context,
                            profile = profile,
                            pitch = pitch,
                            pauseMs = readerPrefs.sentencePauseMs
                        )
                    },
                    onToggleCharacterVoicesEnabled = onToggleCharacterVoicesEnabled,
                    onScanCharactersClick = onScanCharacters,
                    onConfirmCharacterAssignments = onConfirmCharacterAssignments,
                    onSelectAmbientSound = onSelectAmbientSound,
                    onChangeAmbientVolume = onChangeAmbientVolume,
                    clonedVoices = clonedVoices,
                    onSelectClonedVoice = onSelectClonedVoice,
                    onOpenVoiceCloneDialog = {
                        showAudioSettingsSheet = false
                        if (isAnyAudioPlaying) {
                            BookAudioPlaybackService.pause(context)
                        }
                        showVoiceCloneDialog = true
                    },
                    onClose = {
                        showAudioSettingsSheet = false
                        onHideCharacterPicker()
                    }
                )
            }
        }
    }

    // 7. Hộp thoại Tạo & Quản lý Giọng đọc nhân bản AI (100% Offline ONNX / Dự phòng thiết bị)
    if (showVoiceCloneDialog) {
        VoiceCloneDialog(
            clonedVoices = clonedVoices,
            activeClonedVoiceId = readerPrefs.selectedClonedVoiceId,
            engineMode = voiceCloneEngineMode,
            targetSampleRate = voiceCloneTargetSampleRate,
            isExtractingEmbedding = isExtractingVoiceClone,
            onSaveClonedVoice = onSaveClonedVoice,
            onSelectClonedVoice = onSelectClonedVoice,
            onDeleteClonedVoice = onDeleteClonedVoice,
            onDismiss = { showVoiceCloneDialog = false }
        )
    }
}

@Composable
private fun ChapterTitleBlock(
    bookTitle: String,
    chapterTitle: String,
    fontSize: Float,
    letterSpacingSp: Float,
    fontFamily: FontFamily,
    inkColor: Color,
    subtleInk: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = bookTitle,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontFamily = fontFamily,
            color = subtleInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = chapterTitle,
            fontSize = (fontSize + 3f).coerceAtMost(50f).sp,
            lineHeight = ((fontSize + 3f).coerceAtMost(50f) * 1.25f).sp,
            letterSpacing = letterSpacingSp.sp,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            color = inkColor
        )
    }
}

@Composable
private fun ReaderParagraphBox(
    text: String,
    fontSize: Float,
    lineHeightSp: androidx.compose.ui.unit.TextUnit,
    letterSpacingSp: androidx.compose.ui.unit.TextUnit,
    fontFamily: FontFamily,
    inkColor: Color,
    highlighted: Boolean,
    activeSentenceText: String? = null,
    onClick: () -> Unit
) {
    val highlightBg = if (highlighted) {
        IosBlue.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val sentenceHighlightBg = IosOrange.copy(alpha = 0.34f)
    val annotated = remember(text, highlighted, activeSentenceText, sentenceHighlightBg) {
        if (!highlighted || activeSentenceText.isNullOrBlank()) {
            null
        } else {
            val matchRange = findActiveSentenceRange(text, activeSentenceText)
            if (matchRange != null) {
                buildAnnotatedString {
                    append(text)
                    addStyle(
                        style = SpanStyle(
                            background = sentenceHighlightBg,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = matchRange.first,
                        end = matchRange.last + 1
                    )
                }
            } else {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(highlightBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (highlighted) 8.dp else 0.dp,
                vertical = if (highlighted) 5.dp else 0.dp
            )
    ) {
        if (annotated != null) {
            Text(
                text = annotated,
                fontSize = fontSize.sp,
                lineHeight = lineHeightSp,
                letterSpacing = letterSpacingSp,
                fontFamily = fontFamily,
                color = inkColor,
                textAlign = TextAlign.Justify
            )
        } else {
            Text(
                text = text,
                fontSize = fontSize.sp,
                lineHeight = lineHeightSp,
                letterSpacing = letterSpacingSp,
                fontFamily = fontFamily,
                color = inkColor,
                textAlign = TextAlign.Justify
            )
        }
    }
}

/**
 * Định vị câu đang đọc trong đoạn văn gốc để tô sáng chính xác từng câu.
 */
private fun findActiveSentenceRange(paragraph: String, sentence: String): IntRange? {
    val query = sentence.trim()
    if (query.isEmpty() || paragraph.isEmpty()) return null

    // 1. Khớp trực tiếp (không phân biệt hoa/thường)
    val directIdx = paragraph.indexOf(query, ignoreCase = true)
    if (directIdx >= 0) {
        return directIdx until (directIdx + query.length).coerceAtMost(paragraph.length)
    }

    // 2. Khớp theo tiền tố 3-4 từ đầu tiên (hữu ích khi văn bản đã qua chuẩn hóa chữ viết tắt)
    val words = query.split(Regex("\\s+")).filter { it.length >= 2 }
    if (words.isEmpty()) return null
    val anchor = words.take(3).joinToString(" ")
    val startIdx = paragraph.indexOf(anchor, ignoreCase = true)
        .takeIf { it >= 0 }
        ?: paragraph.indexOf(words.first(), ignoreCase = true)
    if (startIdx < 0) return null

    var endIdx = startIdx + anchor.length
    while (endIdx < paragraph.length) {
        val ch = paragraph[endIdx]
        endIdx++
        if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '\n') break
    }
    val safeEnd = (endIdx - 1).coerceIn(startIdx, paragraph.lastIndex)
    return startIdx..safeEnd
}

@Composable
private fun ChapterFooterNavigation(
    currentChapterIndex: Int,
    totalChapters: Int,
    inkColor: Color,
    subtleInk: Color,
    subtleSurface: Color,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit
) {
    val hasPrev = currentChapterIndex > 0
    val hasNext = currentChapterIndex + 1 < totalChapters
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasPrev) {
            NeoBrutalistPressBox(
                onClick = onPrevChapter,
                isPill = true,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = subtleSurface,
                borderColor = inkColor,
                shadowColor = inkColor,
                modifier = Modifier.testTag("reader_prev_chapter_button")
            ) {
                Text(
                    text = "← Chương trước",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = inkColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }

        Text(
            text = "${currentChapterIndex + 1} / $totalChapters",
            fontSize = 11.sp,
            color = subtleInk
        )

        if (hasNext) {
            NeoBrutalistPressBox(
                onClick = onNextChapter,
                isPill = true,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = subtleSurface,
                borderColor = inkColor,
                shadowColor = inkColor,
                modifier = Modifier.testTag("reader_next_chapter_button")
            ) {
                Text(
                    text = "Chương sau →",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = inkColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.width(1.dp))
        }
    }
}

/**
 * 32dp x 32dp Swiss Neo-Brutalist 3D Rounded-Square Keycap Icon Button
 * (`1.5.dp` solid ink border, `2.dp` hard offset shadow, tactile `translate(2dp, 2dp)` press depression).
 */
@Composable
private fun Reader3DKeycapButton(
    onClick: () -> Unit,
    containerColor: Color,
    borderColor: Color,
    shadowColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    NeoBrutalistPressBox(
        onClick = onClick,
        cornerRadius = 9.dp,
        shadowOffset = 2.dp,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = containerColor,
        borderColor = borderColor,
        shadowColor = shadowColor,
        modifier = modifier.size(34.dp)
    ) {
        content()
    }
}

/**
 * Thẻ điều khiển Nghe Sách Mini nổi khối 3D (Swiss Neo-Brutalist Art-Paper Floating Card)
 * đặt gọn gàng ở phía dưới giữa màn hình:
 * - 3 nút phím vuông nổi khối 3D: Lùi | Phát/Tạm dừng (Xanh Cobalt #1D5CD6) | Tiến
 * - Nút viên thuốc 3D chỉnh Tốc độ (1.0x...) & Giọng đọc (Nữ 1, Nam...)
 * - Nút Đồng hồ hẹn giờ tự tắt & Nút Dừng X
 */
@Composable
private fun IosFloatingAudioPill(
    isDarkReaderPaper: Boolean,
    isPlaying: Boolean,
    speechRate: Float,
    voiceLabel: String,
    hasClonedVoiceActive: Boolean,
    sleepTimerActive: Boolean,
    sleepTimerBadge: String?,
    onPrevParagraph: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleVoice: () -> Unit,
    onOpenVoiceCloneDialog: () -> Unit,
    onSelectSleepTimerMinutes: (Int) -> Unit,
    onStopAndClose: () -> Unit
) {
    val cardShape = RoundedCornerShape(16.dp)
    val cardBg = if (isDarkReaderPaper) Color(0xFF1F1F23) else Color(0xFFFAF7F0)
    val keyBg = if (isDarkReaderPaper) Color(0xFF28282E) else Color(0xFFF3EFE6)
    val inkColor = if (isDarkReaderPaper) Color(0xFFF4F1EA) else NeoInkLight
    val borderColor = if (isDarkReaderPaper) NeoInkDark else NeoInkLight
    val shadowColor = if (isDarkReaderPaper) NeoShadowDark else NeoShadowLight
    var showTimerMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(
                cornerRadius = 16.dp,
                offsetX = 3.dp,
                offsetY = 3.dp,
                shadowColor = shadowColor
            )
            .clip(cardShape)
            .background(cardBg)
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = cardShape
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("reader_floating_audio_pill"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // Row 1: 3 Tactile 3D Keycaps (Lùi | Phát/Tạm dừng Xanh Cobalt | Tiến) + Speed + Voice + Timer + Stop
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NeoBrutalistPressBox(
                onClick = onPrevParagraph,
                cornerRadius = 9.dp,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = keyBg,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("audio_prev_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Lùi đoạn",
                    tint = inkColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            NeoBrutalistPressBox(
                onClick = onTogglePlayPause,
                cornerRadius = 10.dp,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = IosBlue,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("audio_play_pause_button")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Tạm dừng" else "Phát",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            NeoBrutalistPressBox(
                onClick = onNextParagraph,
                cornerRadius = 9.dp,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = keyBg,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .size(34.dp)
                    .testTag("audio_next_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Tới đoạn",
                    tint = inkColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(borderColor.copy(alpha = 0.28f))
            )

            // 3D Speed Pill
            NeoBrutalistPressBox(
                onClick = onCycleSpeed,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = keyBg,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .height(31.dp)
                    .testTag("audio_speed_button")
            ) {
                Text(
                    text = LibraryManager.formatSpeechRate(speechRate),
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = inkColor,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 9.dp)
                )
            }

            // 3D Voice Pill
            NeoBrutalistPressBox(
                onClick = onCycleVoice,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = keyBg,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .height(31.dp)
                    .widthIn(max = 88.dp)
                    .testTag("audio_voice_button")
            ) {
                Text(
                    text = voiceLabel,
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = inkColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 9.dp)
                )
            }

            // 3D Sleep Timer Keycap/Pill
            Box {
                NeoBrutalistPressBox(
                    onClick = { showTimerMenu = true },
                    isPill = true,
                    shadowOffset = 2.dp,
                    pressDepth = 2.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (sleepTimerActive) IosOrange else keyBg,
                    borderColor = borderColor,
                    shadowColor = shadowColor,
                    modifier = Modifier
                        .height(31.dp)
                        .testTag("audio_sleep_timer_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = if (sleepTimerBadge != null) 8.dp else 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = "Hẹn giờ tự tắt nghe sách",
                            tint = if (sleepTimerActive) NeoInkLight else inkColor,
                            modifier = Modifier.size(14.dp)
                        )
                        if (sleepTimerBadge != null) {
                            Text(
                                text = sleepTimerBadge,
                                fontSize = 10.5.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NeoInkLight,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                DropdownMenu(
                    expanded = showTimerMenu,
                    onDismissRequest = { showTimerMenu = false },
                    containerColor = cardBg
                ) {
                    val options = listOf(
                        BookAudioPlaybackService.SLEEP_TIMER_OFF to "Tắt hẹn giờ",
                        15 to "Tắt sau 15 phút",
                        30 to "Tắt sau 30 phút",
                        45 to "Tắt sau 45 phút",
                        60 to "Tắt sau 60 phút",
                        BookAudioPlaybackService.SLEEP_TIMER_END_OF_CHAPTER to "Tắt khi hết chương này"
                    )
                    options.forEach { (mins, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = inkColor
                                )
                            },
                            onClick = {
                                showTimerMenu = false
                                onSelectSleepTimerMinutes(mins)
                            }
                        )
                    }
                }
            }

            // 3D Stop Keycap
            NeoBrutalistPressBox(
                onClick = onStopAndClose,
                cornerRadius = 9.dp,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = keyBg,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .size(31.dp)
                    .testTag("audio_stop_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Dừng đọc sách",
                    tint = inkColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Row 2: Nút viên thuốc nhỏ gọn "+ Tạo giọng nhân bản" (100% Offline ONNX)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NeoBrutalistPressBox(
                onClick = onOpenVoiceCloneDialog,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 1.5.dp,
                borderWidth = 1.5.dp,
                containerColor = if (hasClonedVoiceActive) IosOrange else keyBg,
                borderColor = borderColor,
                shadowColor = shadowColor,
                modifier = Modifier
                    .height(27.dp)
                    .testTag("audio_clone_voice_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "Tạo giọng nhân bản AI",
                        tint = if (hasClonedVoiceActive) NeoInkLight else IosBlue,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "+ Tạo giọng nhân bản",
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (hasClonedVoiceActive) NeoInkLight else inkColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Floating iOS Popover (`RoundedCornerShape(16.dp)`, frosted card, soft shadow):
 * - Chọn Font chữ (font tích hợp + font đã nhập)
 * - Cỡ chữ (13..48sp) with `-`/`+` & quick Slider
 * - Kiểu lật trang (`Cuộn dọc` | `Lật ngang` + 3 hiệu ứng ngang)
 * - Lề trang (`8..44 dp`)
 * - Giãn dòng (`1.2x..2.4x`)
 * - Giãn chữ (`0.0..3.0 sp`)
 * - 3 màu trang giấy (`#E6D5B8`, `#FAF3DD`, `#FFFFFF`)
 */
@Composable
private fun IosReaderAppearancePopover(
    prefs: ReaderPreferences,
    customFonts: List<CustomImportedFont>,
    onSelectFont: (String) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit,
    onSelectScrollDirection: (ReaderScrollDirection) -> Unit,
    onSelectHorizontalPageEffect: (HorizontalPageEffect) -> Unit,
    onDecreaseMargin: () -> Unit,
    onIncreaseMargin: () -> Unit,
    onDecreaseLineHeight: () -> Unit,
    onIncreaseLineHeight: () -> Unit,
    onDecreaseLetterSpacing: () -> Unit,
    onIncreaseLetterSpacing: () -> Unit,
    onSelectTheme: (ReaderPaperTheme) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val cardBg = neoPaperSurfaceColor()
    val trayBg = neoSecondaryPaperColor()
    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .width(264.dp)
            .heightIn(max = 468.dp)
            .padding(end = 4.dp, bottom = 4.dp)
            .neoHardOffsetShadow(
                cornerRadius = 16.dp,
                offsetX = 4.dp,
                offsetY = 4.dp,
                shadowColor = hardShadow
            )
            .clip(shape)
            .background(cardBg)
            .border(1.5.dp, inkBorder, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .testTag("reader_appearance_popover")
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 0. Chọn Font chữ (Dropdown hiện toàn bộ font tích hợp + font đã nhập)
            var fontMenuExpanded by remember { mutableStateOf(false) }
            val allFontOptions = remember(customFonts) { ReaderFontRegistry.allFontOptions(customFonts) }
            val currentFontName = remember(prefs.fontId, customFonts) {
                ReaderFontRegistry.findFontDisplayName(prefs.fontId, customFonts)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Font chữ",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk
                )
                Box {
                    NeoBrutalistPressBox(
                        onClick = { fontMenuExpanded = true },
                        isPill = true,
                        shadowOffset = 2.dp,
                        pressDepth = 1.5.dp,
                        borderWidth = 1.5.dp,
                        containerColor = trayBg,
                        borderColor = inkBorder,
                        shadowColor = hardShadow,
                        modifier = Modifier.testTag("reader_font_family_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = currentFontName,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = IosBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 112.dp)
                            )
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Chọn font chữ",
                                tint = IosBlue,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = fontMenuExpanded,
                        onDismissRequest = { fontMenuExpanded = false },
                        containerColor = cardBg
                    ) {
                        allFontOptions.forEach { fontOpt ->
                            val selected = prefs.fontId == fontOpt.id
                            val itemFamily = remember(fontOpt.id, customFonts) {
                                ReaderFontRegistry.resolveFontFamily(fontOpt.id, customFonts)
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = fontOpt.displayName,
                                            fontSize = 12.sp,
                                            fontFamily = itemFamily,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) IosBlue else primaryInk
                                        )
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = IosBlue,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    fontMenuExpanded = false
                                    onSelectFont(fontOpt.id)
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outline)

            // 1. Font Size (13..48 sp) with 3D A- / A+ keycaps AND quick slider
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cỡ chữ (${prefs.fontSizeSp.toInt()}sp)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val canDecrease = prefs.fontSizeSp > LibraryManager.MIN_FONT_SIZE_SP
                        val canIncrease = prefs.fontSizeSp < LibraryManager.MAX_FONT_SIZE_SP
                        NeoBrutalistPressBox(
                            onClick = onDecreaseFont,
                            enabled = canDecrease,
                            cornerRadius = 9.dp,
                            shadowOffset = 2.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = trayBg,
                            borderColor = inkBorder,
                            shadowColor = hardShadow,
                            modifier = Modifier
                                .width(44.dp)
                                .height(32.dp)
                                .testTag("reader_font_decrease_button")
                        ) {
                            Text(
                                text = "A-",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (canDecrease) primaryInk else secondaryInk.copy(alpha = 0.4f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        NeoBrutalistPressBox(
                            onClick = onIncreaseFont,
                            enabled = canIncrease,
                            cornerRadius = 9.dp,
                            shadowOffset = 2.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = trayBg,
                            borderColor = inkBorder,
                            shadowColor = hardShadow,
                            modifier = Modifier
                                .width(44.dp)
                                .height(32.dp)
                                .testTag("reader_font_increase_button")
                        ) {
                            Text(
                                text = "A+",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (canIncrease) primaryInk else secondaryInk.copy(alpha = 0.4f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Slider(
                    value = prefs.fontSizeSp,
                    onValueChange = onSetFontSize,
                    valueRange = LibraryManager.MIN_FONT_SIZE_SP..LibraryManager.MAX_FONT_SIZE_SP,
                    colors = SliderDefaults.colors(
                        thumbColor = IosBlue,
                        activeTrackColor = IosBlue,
                        inactiveTrackColor = trayBg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .testTag("reader_font_slider")
                )
            }

            HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outline)

            // 2. Scroll Direction (Cuộn dọc | Lật ngang) + Hiệu ứng lật ngang
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Kiểu lật trang",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReaderScrollDirection.entries.forEach { dir ->
                        val active = prefs.scrollDirection == dir
                        NeoBrutalistPressBox(
                            onClick = { onSelectScrollDirection(dir) },
                            isPill = true,
                            shadowOffset = 2.5.dp,
                            pressDepth = 1.8.dp,
                            borderWidth = 1.5.dp,
                            containerColor = if (active) IosBlue else trayBg,
                            borderColor = inkBorder,
                            shadowColor = hardShadow,
                            modifier = Modifier
                                .weight(1f)
                                .height(33.dp)
                                .testTag("reader_scroll_${dir.storageKey}")
                        ) {
                            Text(
                                text = dir.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (active) Color.White else primaryInk,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (prefs.scrollDirection == ReaderScrollDirection.HORIZONTAL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        HorizontalPageEffect.entries.forEach { effect ->
                            val selected = prefs.horizontalPageEffect == effect
                            val shortName = when (effect) {
                                HorizontalPageEffect.BOOK_FLIP -> "Lật sách"
                                HorizontalPageEffect.TAP_FADE -> "Lướt mờ"
                                HorizontalPageEffect.NONE -> "Không"
                            }
                            NeoBrutalistPressBox(
                                onClick = { onSelectHorizontalPageEffect(effect) },
                                isPill = true,
                                shadowOffset = 2.dp,
                                pressDepth = 1.5.dp,
                                borderWidth = 1.2.dp,
                                containerColor = if (selected) IosBlue else trayBg,
                                borderColor = inkBorder,
                                shadowColor = hardShadow,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .testTag("popover_horizontal_effect_${effect.storageKey}")
                            ) {
                                Text(
                                    text = shortName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) Color.White else primaryInk,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outline)

            // 3. Page Margin (Lề trang)
            PopoverStepperRow(
                label = "Lề trang",
                valueText = "${prefs.pageMarginDp.toInt()}",
                canDecrease = prefs.pageMarginDp > LibraryManager.MIN_PAGE_MARGIN_DP,
                canIncrease = prefs.pageMarginDp < LibraryManager.MAX_PAGE_MARGIN_DP,
                decreaseTag = "reader_margin_decrease",
                increaseTag = "reader_margin_increase",
                onDecrease = onDecreaseMargin,
                onIncrease = onIncreaseMargin
            )

            // 4. Line Spacing (Giãn dòng)
            PopoverStepperRow(
                label = "Giãn dòng",
                valueText = String.format(Locale.US, "%.1fx", prefs.lineHeightMultiplier),
                canDecrease = prefs.lineHeightMultiplier > LibraryManager.MIN_LINE_HEIGHT_MULT + 0.01f,
                canIncrease = prefs.lineHeightMultiplier < LibraryManager.MAX_LINE_HEIGHT_MULT - 0.01f,
                decreaseTag = "reader_line_decrease",
                increaseTag = "reader_line_increase",
                onDecrease = onDecreaseLineHeight,
                onIncrease = onIncreaseLineHeight
            )

            // 5. Letter Spacing (Giãn chữ)
            PopoverStepperRow(
                label = "Giãn chữ",
                valueText = String.format(Locale.US, "%.2f", prefs.letterSpacingSp),
                canDecrease = prefs.letterSpacingSp > LibraryManager.MIN_LETTER_SPACING_SP + 0.01f,
                canIncrease = prefs.letterSpacingSp < LibraryManager.MAX_LETTER_SPACING_SP - 0.01f,
                decreaseTag = "reader_letter_decrease",
                increaseTag = "reader_letter_increase",
                onDecrease = onDecreaseLetterSpacing,
                onIncrease = onIncreaseLetterSpacing
            )

            HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outline)

            // 6. Paper Color (4 square 3D paper cards: Nâu sách cũ, Vàng ngả, Trắng ngà, Nền tối)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    text = "Màu nền giấy",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaperSwatchSquareCard(
                        color = ReaderBrownPaper,
                        label = "Nâu",
                        labelInk = ReaderBrownInk,
                        selected = prefs.paperTheme == ReaderPaperTheme.BOOK_BROWN,
                        contentDescription = "Màu nâu sách cũ",
                        tag = "paper_theme_brown",
                        onClick = { onSelectTheme(ReaderPaperTheme.BOOK_BROWN) },
                        modifier = Modifier.weight(1f)
                    )
                    PaperSwatchSquareCard(
                        color = ReaderCreamPaper,
                        label = "Vàng",
                        labelInk = ReaderCreamInk,
                        selected = prefs.paperTheme == ReaderPaperTheme.WARM_CREAM,
                        contentDescription = "Màu vàng ngả",
                        tag = "paper_theme_cream",
                        onClick = { onSelectTheme(ReaderPaperTheme.WARM_CREAM) },
                        modifier = Modifier.weight(1f)
                    )
                    PaperSwatchSquareCard(
                        color = ReaderWhitePaper,
                        label = "Trắng",
                        labelInk = ReaderWhiteInk,
                        selected = prefs.paperTheme == ReaderPaperTheme.PURE_WHITE,
                        contentDescription = "Màu trắng ngà",
                        tag = "paper_theme_white",
                        onClick = { onSelectTheme(ReaderPaperTheme.PURE_WHITE) },
                        modifier = Modifier.weight(1f)
                    )
                    PaperSwatchSquareCard(
                        color = ReaderDarkPaper,
                        label = "Tối",
                        labelInk = ReaderDarkInk,
                        selected = prefs.paperTheme == ReaderPaperTheme.DARK_NIGHT,
                        contentDescription = "Chế độ nền tối",
                        tag = "paper_theme_dark",
                        onClick = { onSelectTheme(ReaderPaperTheme.DARK_NIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PopoverStepperRow(
    label: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    decreaseTag: String,
    increaseTag: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant
    val trayBg = neoSecondaryPaperColor()
    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = primaryInk
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            NeoBrutalistPressBox(
                onClick = onDecrease,
                enabled = canDecrease,
                cornerRadius = 8.dp,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = trayBg,
                borderColor = inkBorder,
                shadowColor = hardShadow,
                modifier = Modifier
                    .size(30.dp)
                    .testTag(decreaseTag)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = "Giảm $label",
                    tint = if (canDecrease) primaryInk else secondaryInk.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
            }

            Text(
                text = valueText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = primaryInk,
                modifier = Modifier.width(36.dp),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            NeoBrutalistPressBox(
                onClick = onIncrease,
                enabled = canIncrease,
                cornerRadius = 8.dp,
                shadowOffset = 2.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = trayBg,
                borderColor = inkBorder,
                shadowColor = hardShadow,
                modifier = Modifier
                    .size(30.dp)
                    .testTag(increaseTag)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Tăng $label",
                    tint = if (canIncrease) primaryInk else secondaryInk.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun PaperSwatchSquareCard(
    color: Color,
    label: String,
    labelInk: Color,
    selected: Boolean,
    contentDescription: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NeoBrutalistPressBox(
        onClick = onClick,
        cornerRadius = 9.dp,
        shadowOffset = 2.dp,
        pressDepth = 2.dp,
        borderWidth = if (selected) 2.dp else 1.5.dp,
        containerColor = color,
        borderColor = neoInkBorderColor(),
        shadowColor = neoHardShadowColor(),
        modifier = modifier
            .height(38.dp)
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(IosGreen)
                        .border(1.2.dp, NeoInkLight, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = contentDescription,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                }
            } else {
                Text(
                    text = label,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelInk,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * 3D Notebook Left-Edge Sliding TOC Drawer (Bảng Mục lục dạng sổ tay mở từ cạnh trái):
 * - Art-paper page with crisp 1.5dp right ink border & 4dp solid offset shadow
 * - Thin ink horizontal dividers between chapters
 * - Active reading chapter highlighted as a raised 3D Amber Gold (#F5B82E) card block
 */
@Composable
private fun ReaderTocNotebookDrawer(
    bookTitle: String,
    chapters: List<LibraryChapterMeta>,
    currentChapterIndex: Int,
    isDarkPaper: Boolean,
    onSelectChapter: (Int) -> Unit,
    onClose: () -> Unit
) {
    val drawerBg = if (isDarkPaper) Color(0xFF1F1F23) else Color(0xFFFAF7F0)
    val drawerHeaderBg = if (isDarkPaper) Color(0xFF141416) else Color(0xFFF3EFE6)
    val primaryInk = if (isDarkPaper) ReaderDarkInk else NeoInkLight
    val secondaryInk = if (isDarkPaper) Color(0xFFA1A1AA) else Color(0xFF52525B)
    val borderColor = if (isDarkPaper) NeoInkDark else NeoInkLight
    val shadowColor = if (isDarkPaper) NeoShadowDark else NeoShadowLight
    val dividerColor = if (isDarkPaper) Color(0xFF3F3F46) else Color(0xFFD6D0C4)
    val drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)

    Box(
        modifier = Modifier
            .widthIn(min = 270.dp, max = 312.dp)
            .fillMaxHeight()
            .padding(end = 4.dp)
            .neoHardOffsetShadow(
                cornerRadius = 16.dp,
                offsetX = 4.dp,
                offsetY = 0.dp,
                shadowColor = shadowColor
            )
            .clip(drawerShape)
            .background(drawerBg)
            .border(1.5.dp, borderColor, drawerShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Notebook Drawer Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(drawerHeaderBg)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Mục lục (${chapters.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryInk,
                        maxLines = 1
                    )
                    if (bookTitle.isNotBlank()) {
                        Text(
                            text = bookTitle,
                            fontSize = 11.sp,
                            color = secondaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Reader3DKeycapButton(
                    onClick = onClose,
                    containerColor = drawerBg,
                    borderColor = borderColor,
                    shadowColor = shadowColor,
                    modifier = Modifier.testTag("reader_toc_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng mục lục",
                        tint = primaryInk,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(thickness = 1.5.dp, color = borderColor)

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("reader_toc_list"),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = chapters,
                    key = { _, c -> "toc_${c.index}" }
                ) { idx, chapter ->
                    val isCurrent = idx == currentChapterIndex
                    if (isCurrent) {
                        // Raised 3D Amber Gold block for currently reading chapter
                        NeoBrutalistPressBox(
                            onClick = { onSelectChapter(idx) },
                            cornerRadius = 11.dp,
                            shadowOffset = 2.5.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = IosOrange,
                            borderColor = NeoInkLight,
                            shadowColor = shadowColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 11.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}.",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeoInkLight,
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = chapter.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeoInkLight,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Chương đang đọc",
                                    tint = NeoInkLight,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelectChapter(idx) }
                                    .padding(horizontal = 8.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${idx + 1}.",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = secondaryInk,
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = chapter.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryInk,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (idx < chapters.lastIndex) {
                                HorizontalDivider(thickness = 0.8.dp, color = dividerColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Dynamic Horizontal Pagination (Adapts to screen size, font 13..48sp, margins, line & letter spacing)
// -----------------------------------------------------------------------------

private fun paginateChapterIntoPages(
    paragraphs: List<String>,
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    marginDp: Float,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    letterSpacingSp: Float
): List<ReaderPageData> {
    if (paragraphs.isEmpty()) {
        return listOf(ReaderPageData(isFirstPageOfChapter = true, slices = emptyList()))
    }
    val contentWidth = (viewportWidthDp - marginDp * 2f).coerceAtLeast(120f)
    // Reserve ~44dp for bottom page bar & padding
    val usableHeight = (viewportHeightDp - 52f).coerceAtLeast(140f)
    val avgCharWidth = (fontSizeSp * 0.54f + letterSpacingSp).coerceAtLeast(6f)
    val charsPerLine = (contentWidth / avgCharWidth).toInt().coerceAtLeast(6)
    val lineHeightDp = (fontSizeSp * lineHeightMultiplier).coerceAtLeast(16f)
    val paraGapDp = (fontSizeSp * 0.55f).coerceIn(8f, 22f)
    val firstPageHeaderReserveDp = (fontSizeSp * 2.6f + 36f).coerceAtMost(usableHeight * 0.45f)

    val pages = mutableListOf<ReaderPageData>()
    var currentSlices = mutableListOf<ReaderPageSlice>()
    var isFirstPage = true
    var usedHeight = firstPageHeaderReserveDp

    fun flushPage() {
        if (currentSlices.isNotEmpty() || isFirstPage) {
            pages.add(ReaderPageData(isFirstPageOfChapter = isFirstPage, slices = currentSlices))
            currentSlices = mutableListOf()
            isFirstPage = false
            usedHeight = 0f
        }
    }

    for (paraIdx in paragraphs.indices) {
        var remaining = paragraphs[paraIdx].trim()
        if (remaining.isEmpty()) continue

        while (remaining.isNotEmpty()) {
            val estLines = ceil(remaining.length.toDouble() / charsPerLine).toInt().coerceAtLeast(1)
            val estHeight = estLines * lineHeightDp + paraGapDp
            val spaceLeft = usableHeight - usedHeight

            if (estHeight <= spaceLeft) {
                currentSlices.add(ReaderPageSlice(paragraphIndex = paraIdx, text = remaining))
                usedHeight += estHeight
                remaining = ""
            } else {
                val linesThatFit = ((spaceLeft - paraGapDp) / lineHeightDp).toInt()
                if (linesThatFit >= 2) {
                    val maxChars = (linesThatFit * charsPerLine).coerceAtLeast(24)
                    if (maxChars >= remaining.length) {
                        currentSlices.add(ReaderPageSlice(paragraphIndex = paraIdx, text = remaining))
                        usedHeight += estHeight
                        remaining = ""
                    } else {
                        var cut = remaining.lastIndexOf(' ', startIndex = maxChars)
                        if (cut < maxChars / 2) cut = maxChars
                        val head = remaining.substring(0, cut).trim()
                        if (head.isNotEmpty()) {
                            currentSlices.add(ReaderPageSlice(paragraphIndex = paraIdx, text = head))
                        }
                        remaining = remaining.substring(cut).trimStart()
                        flushPage()
                    }
                } else {
                    if (currentSlices.isNotEmpty() || isFirstPage) {
                        flushPage()
                    } else {
                        // Even on an empty page (e.g. 48sp on small screen), force at least 1 slice
                        val forceChars = (charsPerLine * 2).coerceAtLeast(20).coerceAtMost(remaining.length)
                        var cut = if (forceChars < remaining.length) {
                            remaining.lastIndexOf(' ', startIndex = forceChars)
                        } else {
                            remaining.length
                        }
                        if (cut <= 0) cut = forceChars
                        currentSlices.add(
                            ReaderPageSlice(
                                paragraphIndex = paraIdx,
                                text = remaining.substring(0, cut).trim()
                            )
                        )
                        remaining = remaining.substring(cut).trimStart()
                        flushPage()
                    }
                }
            }
        }
    }

    if (currentSlices.isNotEmpty() || pages.isEmpty()) {
        pages.add(ReaderPageData(isFirstPageOfChapter = isFirstPage, slices = currentSlices))
    }
    return pages
}

private fun findPageIndexForParagraph(pages: List<ReaderPageData>, paragraphIndex: Int): Int {
    if (pages.isEmpty()) return 0
    val exact = pages.indexOfFirst { page ->
        page.slices.any { it.paragraphIndex == paragraphIndex }
    }
    if (exact >= 0) return exact
    val nearest = pages.indexOfLast { it.firstParagraphIndex <= paragraphIndex }
    return nearest.coerceAtLeast(0)
}
