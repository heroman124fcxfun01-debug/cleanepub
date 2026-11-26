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
import androidx.compose.animation.slideInVertically
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.audio.AmbientSoundTrack
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
import com.example.ui.components.ReaderAudioSettingsSheetContent
import com.example.ui.theme.IosBlue
import com.example.ui.theme.ReaderBrownInk
import com.example.ui.theme.ReaderBrownPaper
import com.example.ui.theme.ReaderCreamInk
import com.example.ui.theme.ReaderCreamPaper
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
    onChangeAmbientVolume: (Float) -> Unit
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

    var showTocSheet by remember { mutableStateOf(false) }
    var showAppearancePopover by remember { mutableStateOf(false) }
    var showAudioBar by remember { mutableStateOf(false) }
    var showAudioSettingsSheet by remember { mutableStateOf(false) }

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
    val activeSpeakerName = if (isThisBookActiveInService) audioPlaybackState.activeSpeakerName else null

    val latestState by rememberUpdatedState(readerState)
    val latestSpeakingIdx by rememberUpdatedState(speakingParagraphIndex)
    val latestTrackedPara by rememberUpdatedState(currentTrackedParagraphIndex)

    // Resolve paper & ink colors from selected theme
    val paperBg = when (readerPrefs.paperTheme) {
        ReaderPaperTheme.BOOK_BROWN -> ReaderBrownPaper
        ReaderPaperTheme.WARM_CREAM -> ReaderCreamPaper
        ReaderPaperTheme.PURE_WHITE -> ReaderWhitePaper
    }
    val inkColor = when (readerPrefs.paperTheme) {
        ReaderPaperTheme.BOOK_BROWN -> ReaderBrownInk
        ReaderPaperTheme.WARM_CREAM -> ReaderCreamInk
        ReaderPaperTheme.PURE_WHITE -> ReaderWhiteInk
    }
    val subtleInk = inkColor.copy(alpha = 0.58f)
    val subtleSurface = when (readerPrefs.paperTheme) {
        ReaderPaperTheme.BOOK_BROWN -> Color(0xFFDAC5A0)
        ReaderPaperTheme.WARM_CREAM -> Color(0xFFF1E7C8)
        ReaderPaperTheme.PURE_WHITE -> Color(0xFFF2F2F7)
    }

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
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 1. Ultra-Minimalist Top Bar — STRICTLY 4 controls: Back | ☰ | + | 🎧
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Control 1: iOS Back arrow
                IconButton(
                    onClick = ::exitReader,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("reader_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBackIosNew,
                        contentDescription = "Quay lại thư viện",
                        tint = inkColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Controls 2, 3, 4: ☰ TOC | + Appearance Popover | 🎧 Audio Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = {
                            showAppearancePopover = false
                            showTocSheet = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reader_toc_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = "Mục lục",
                            tint = inkColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { showAppearancePopover = !showAppearancePopover },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reader_appearance_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Tùy chỉnh chữ, lề và kiểu lật trang",
                            tint = if (showAppearancePopover) IosBlue else inkColor,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    // Headphones button + live animated running indicator right next to it when audio is active
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(50))
                            .clickable {
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
                            }
                            .padding(horizontal = if (isAnyAudioPlaying) 10.dp else 14.dp)
                            .testTag("reader_audio_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Headphones,
                            contentDescription = "Nghe đọc sách",
                            tint = if (showAudioBar || isAnyAudioPlaying) IosBlue else inkColor,
                            modifier = Modifier.size(20.dp)
                        )
                        if (isAnyAudioPlaying) {
                            AnimatedAudioPlayingBars(
                                color = IosBlue,
                                maxHeight = 13.dp,
                                barWidth = 2.3.dp,
                                modifier = Modifier.testTag("reader_audio_running_indicator")
                            )
                        }
                    }

                    // Audio Settings button right beside Listen button: Gear icon + mini Headphones corner badge
                    IconButton(
                        onClick = {
                            showAppearancePopover = false
                            showTocSheet = false
                            showAudioSettingsSheet = true
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reader_audio_settings_button")
                    ) {
                        val settingsTint = if (
                            showAudioSettingsSheet ||
                            readerPrefs.ambientSound != AmbientSoundTrack.NONE ||
                            readerState.characterVoiceAssignments.isNotEmpty()
                        ) {
                            IosBlue
                        } else {
                            inkColor
                        }
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Cài đặt nghe sách",
                                tint = settingsTint,
                                modifier = Modifier.size(20.dp)
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(paperBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Headphones,
                                    contentDescription = null,
                                    tint = settingsTint,
                                    modifier = Modifier.size(9.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Reading Content Area (Vertical Scroll OR Horizontal Page-Flip)
            // Floating audio pill is now anchored cleanly at Bottom Center of the screen!
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (showAppearancePopover) showAppearancePopover = false
                            if (showAudioBar) showAudioBar = false
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
                                top = 10.dp,
                                bottom = 44.dp
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
                                    onClick = {
                                        if (showAppearancePopover) {
                                            showAppearancePopover = false
                                        }
                                        if (showAudioBar) {
                                            showAudioBar = false
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
                                            if (showAppearancePopover) {
                                                showAppearancePopover = false
                                                return@detectTapGestures
                                            }
                                            if (showAudioBar) {
                                                showAudioBar = false
                                                return@detectTapGestures
                                            }
                                            // Hỗ trợ chạm mép trái/phải để chuyển trang khi dùng "Chạm lướt mờ" hoặc "Không hiệu ứng"
                                            if (pageEffect != HorizontalPageEffect.BOOK_FLIP) {
                                                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                                val tapFraction = offset.x / widthPx
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
                                                }
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
                                            horizontal = pageMarginDp,
                                            vertical = 8.dp
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
                                            onClick = {
                                                if (showAppearancePopover) {
                                                    showAppearancePopover = false
                                                }
                                                if (showAudioBar) {
                                                    showAudioBar = false
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
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = subtleSurface,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                val keepPlaying = isPlayingAudio
                                                onSelectChapter(readerState.currentChapterIndex - 1, 0, keepPlaying)
                                            }
                                    ) {
                                        Text(
                                            text = "← Chương trước",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
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
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = subtleSurface,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                val keepPlaying = isPlayingAudio
                                                onSelectChapter(readerState.currentChapterIndex + 1, 0, keepPlaying)
                                            }
                                    ) {
                                        Text(
                                            text = "Chương sau →",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
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

        // 3. Viên thuốc điều khiển nghe sách nổi ở PHÍA DƯỚI GIỮA MÀN HÌNH (kèm icon đồng hồ hẹn giờ tự tắt)
        AnimatedVisibility(
            visible = showAudioBar,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            IosFloatingAudioPill(
                isPlaying = isPlayingAudio,
                speechRate = readerPrefs.speechRate,
                voiceLabel = activeSpeakerName ?: readerPrefs.voiceProfile.shortLabel,
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
                onSelectSleepTimerMinutes = { mins ->
                    BookAudioPlaybackService.setSleepTimer(context, mins)
                },
                onStopAndClose = {
                    BookAudioPlaybackService.stop(context)
                    showAudioBar = false
                }
            )
        }

        // 4. Floating iOS Popover for "+" (Font chữ, Cỡ chữ 13..48sp, Scroll/Page mode + 3 hiệu ứng ngang, Margin, Line/Letter Spacing, 3 Paper themes)
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
                        .padding(top = 50.dp, end = 14.dp)
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
    }

    // 5. iOS Bottom Sheet TOC (`RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)`)
    if (showTocSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTocSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            containerColor = Color(0xFFFFFFFF),
            contentColor = Color(0xFF1C1C1E)
        ) {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = currentDensity.fontScale.coerceAtMost(1.0f)
                )
            ) {
                ReaderTocBottomSheetContent(
                    bookTitle = readerState.book?.title.orEmpty(),
                    chapters = readerState.toc,
                    currentChapterIndex = readerState.currentChapterIndex,
                    onSelectChapter = { idx ->
                        showTocSheet = false
                        val keepPlaying = isPlayingAudio
                        onSelectChapter(idx, 0, keepPlaying)
                    }
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
            containerColor = Color(0xFFFFFFFF),
            contentColor = Color(0xFF1C1C1E)
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
                    onToggleAdvancedCustom = onToggleAdvancedCustom,
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
                    onClose = {
                        showAudioSettingsSheet = false
                        onHideCharacterPicker()
                    }
                )
            }
        }
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
    onClick: () -> Unit
) {
    val highlightBg = if (highlighted) {
        IosBlue.copy(alpha = 0.14f)
    } else {
        Color.Transparent
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
            Surface(
                shape = RoundedCornerShape(50),
                color = subtleSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onPrevChapter)
                    .testTag("reader_prev_chapter_button")
            ) {
                Text(
                    text = "← Chương trước",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
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
            Surface(
                shape = RoundedCornerShape(50),
                color = subtleSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onNextChapter)
                    .testTag("reader_next_chapter_button")
            ) {
                Text(
                    text = "Chương sau →",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
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
 * Floating iOS Dynamic Island / Pill Audio Bar đặt ở phía dưới giữa màn hình.
 * Gồm: Prev | Play/Pause | Next | Speed | Voice | Icon Đồng hồ hẹn giờ tự tắt | Stop.
 */
@Composable
private fun IosFloatingAudioPill(
    isPlaying: Boolean,
    speechRate: Float,
    voiceLabel: String,
    sleepTimerActive: Boolean,
    sleepTimerBadge: String?,
    onPrevParagraph: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleVoice: () -> Unit,
    onSelectSleepTimerMinutes: (Int) -> Unit,
    onStopAndClose: () -> Unit
) {
    val pillShape = RoundedCornerShape(50)
    var showTimerMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .height(42.dp)
            .shadow(
                elevation = 12.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.28f),
                spotColor = Color.Black.copy(alpha = 0.38f)
            )
            .clip(pillShape)
            .background(Color(0xF51C1C1E))
            .border(
                width = 0.6.dp,
                color = Color.White.copy(alpha = 0.18f),
                shape = pillShape
            )
            .padding(horizontal = 8.dp)
            .testTag("reader_floating_audio_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onPrevParagraph)
                .testTag("audio_prev_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "Lùi đoạn",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(IosBlue)
                .clickable(onClick = onTogglePlayPause)
                .testTag("audio_play_pause_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Tạm dừng" else "Phát",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onNextParagraph)
                .testTag("audio_next_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Tới đoạn",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        Box(
            modifier = Modifier
                .width(0.8.dp)
                .height(16.dp)
                .background(Color.White.copy(alpha = 0.22f))
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onCycleSpeed)
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .testTag("audio_speed_button"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = LibraryManager.formatSpeechRate(speechRate),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                softWrap = false
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onCycleVoice)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .widthIn(max = 76.dp)
                .testTag("audio_voice_button"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = voiceLabel,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Icon Đồng hồ hẹn giờ tự tắt nghe sách (Sleep Timer)
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (sleepTimerActive) IosBlue else Color.White.copy(alpha = 0.14f)
                    )
                    .clickable { showTimerMenu = true }
                    .padding(
                        horizontal = if (sleepTimerBadge != null) 7.dp else 6.dp,
                        vertical = 4.dp
                    )
                    .testTag("audio_sleep_timer_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = "Hẹn giờ tự tắt nghe sách",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                if (sleepTimerBadge != null) {
                    Text(
                        text = sleepTimerBadge,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }

            DropdownMenu(
                expanded = showTimerMenu,
                onDismissRequest = { showTimerMenu = false },
                containerColor = Color(0xFF2C2C2E)
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
                                color = Color.White
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

        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onStopAndClose)
                .testTag("audio_stop_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dừng đọc sách",
                tint = Color.White,
                modifier = Modifier.size(13.dp)
            )
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

    Surface(
        modifier = Modifier
            .width(256.dp)
            .heightIn(max = 460.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.22f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .testTag("reader_appearance_popover"),
        shape = shape,
        color = Color(0xF7FFFFFF),
        border = BorderStroke(0.5.dp, Color(0xFFE5E5EA))
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
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1C1C1E)
                )
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFF2F2F7))
                            .clickable { fontMenuExpanded = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("reader_font_family_selector"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = currentFontName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
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

                    DropdownMenu(
                        expanded = fontMenuExpanded,
                        onDismissRequest = { fontMenuExpanded = false },
                        containerColor = Color.White
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
                                            color = if (selected) IosBlue else Color(0xFF1C1C1E)
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

            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))

            // 1. Font Size (13..48 sp) with `-`/`+` AND quick slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PopoverStepperRow(
                    label = "Cỡ chữ",
                    valueText = prefs.fontSizeSp.toInt().toString(),
                    canDecrease = prefs.fontSizeSp > LibraryManager.MIN_FONT_SIZE_SP,
                    canIncrease = prefs.fontSizeSp < LibraryManager.MAX_FONT_SIZE_SP,
                    decreaseTag = "reader_font_decrease_button",
                    increaseTag = "reader_font_increase_button",
                    onDecrease = onDecreaseFont,
                    onIncrease = onIncreaseFont
                )
                Slider(
                    value = prefs.fontSizeSp,
                    onValueChange = onSetFontSize,
                    valueRange = LibraryManager.MIN_FONT_SIZE_SP..LibraryManager.MAX_FONT_SIZE_SP,
                    colors = SliderDefaults.colors(
                        thumbColor = IosBlue,
                        activeTrackColor = IosBlue,
                        inactiveTrackColor = Color(0xFFE5E5EA)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .testTag("reader_font_slider")
                )
            }

            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))

            // 2. Scroll Direction (Cuộn dọc | Lật ngang) + Hiệu ứng lật ngang
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Kiểu lật trang",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1C1C1E)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFFE5E5EA))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ReaderScrollDirection.entries.forEach { dir ->
                        val active = prefs.scrollDirection == dir
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .then(
                                    if (active) {
                                        Modifier.shadow(2.dp, RoundedCornerShape(7.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (active) Color.White else Color.Transparent)
                                .clickable { onSelectScrollDirection(dir) }
                                .testTag("reader_scroll_${dir.storageKey}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = dir.label,
                                fontSize = 11.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) Color(0xFF1C1C1E) else Color(0xFF6E6E73),
                                maxLines = 1
                            )
                        }
                    }
                }

                if (prefs.scrollDirection == ReaderScrollDirection.HORIZONTAL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HorizontalPageEffect.entries.forEach { effect ->
                            val selected = prefs.horizontalPageEffect == effect
                            val shortName = when (effect) {
                                HorizontalPageEffect.BOOK_FLIP -> "Lật sách"
                                HorizontalPageEffect.TAP_FADE -> "Lướt mờ"
                                HorizontalPageEffect.NONE -> "Không"
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(50))
                                    .background(if (selected) IosBlue else Color(0xFFF2F2F7))
                                    .clickable { onSelectHorizontalPageEffect(effect) }
                                    .padding(vertical = 5.dp)
                                    .testTag("popover_horizontal_effect_${effect.storageKey}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = shortName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (selected) Color.White else Color(0xFF1C1C1E),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))

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

            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))

            // 6. Paper Color (3 circular swatches with #007AFF ring when active)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Màu giấy",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1C1C1E)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaperSwatchCircle(
                        color = ReaderBrownPaper,
                        selected = prefs.paperTheme == ReaderPaperTheme.BOOK_BROWN,
                        contentDescription = "Màu nâu sách",
                        tag = "paper_theme_brown",
                        onClick = { onSelectTheme(ReaderPaperTheme.BOOK_BROWN) }
                    )
                    PaperSwatchCircle(
                        color = ReaderCreamPaper,
                        selected = prefs.paperTheme == ReaderPaperTheme.WARM_CREAM,
                        contentDescription = "Màu vàng ngả",
                        tag = "paper_theme_cream",
                        onClick = { onSelectTheme(ReaderPaperTheme.WARM_CREAM) }
                    )
                    PaperSwatchCircle(
                        color = ReaderWhitePaper,
                        selected = prefs.paperTheme == ReaderPaperTheme.PURE_WHITE,
                        contentDescription = "Màu trắng",
                        tag = "paper_theme_white",
                        onClick = { onSelectTheme(ReaderPaperTheme.PURE_WHITE) }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1C1C1E)
        )

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFF2F2F7))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canDecrease, onClick = onDecrease)
                    .testTag(decreaseTag),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = "Giảm $label",
                    tint = if (canDecrease) Color(0xFF1C1C1E) else Color(0xFFAEAEB2),
                    modifier = Modifier.size(15.dp)
                )
            }

            Text(
                text = valueText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1C1C1E),
                modifier = Modifier.width(34.dp),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canIncrease, onClick = onIncrease)
                    .testTag(increaseTag),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Tăng $label",
                    tint = if (canIncrease) Color(0xFF1C1C1E) else Color(0xFFAEAEB2),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun PaperSwatchCircle(
    color: Color,
    selected: Boolean,
    contentDescription: String,
    tag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.2.dp else 0.8.dp,
                color = if (selected) IosBlue else Color(0xFFD1D1D6),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = contentDescription,
                tint = IosBlue,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
private fun ReaderTocBottomSheetContent(
    bookTitle: String,
    chapters: List<LibraryChapterMeta>,
    currentChapterIndex: Int,
    onSelectChapter: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = "Mục lục",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1C1C1E)
        )
        if (bookTitle.isNotBlank()) {
            Text(
                text = bookTitle,
                fontSize = 11.sp,
                color = Color(0xFF6E6E73),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .testTag("reader_toc_list"),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(
                items = chapters,
                key = { _, c -> "toc_${c.index}" }
            ) { idx, chapter ->
                val isCurrent = idx == currentChapterIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable { onSelectChapter(idx) }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapter.title,
                        fontSize = 13.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCurrent) IosBlue else Color(0xFF1C1C1E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Chương đang đọc",
                            tint = IosBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (idx < chapters.lastIndex) {
                    HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
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
