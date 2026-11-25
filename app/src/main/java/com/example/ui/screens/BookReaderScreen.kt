package com.example.ui.screens

import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LibraryChapterMeta
import com.example.data.LibraryManager
import com.example.data.ReaderPaperTheme
import com.example.data.ReaderPreferences
import com.example.data.ReaderVoiceProfile
import com.example.ui.ActiveBookReaderState
import com.example.ui.theme.IosBlue
import com.example.ui.theme.ReaderBrownInk
import com.example.ui.theme.ReaderBrownPaper
import com.example.ui.theme.ReaderCreamInk
import com.example.ui.theme.ReaderCreamPaper
import com.example.ui.theme.ReaderWhiteInk
import com.example.ui.theme.ReaderWhitePaper
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun BookReaderScreen(
    readerState: ActiveBookReaderState,
    readerPrefs: ReaderPreferences,
    onBackClick: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onSelectChapter: (chapterIndex: Int, startParagraphIndex: Int, continueAudio: Boolean) -> Unit,
    onConsumeAutoContinueAudio: () -> Unit,
    onSaveProgress: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onSelectPaperTheme: (ReaderPaperTheme) -> Unit,
    onCycleSpeechRate: () -> Unit,
    onCycleVoiceProfile: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val listState = rememberLazyListState()

    var showTocSheet by remember { mutableStateOf(false) }
    var showAppearancePopover by remember { mutableStateOf(false) }
    var showAudioBar by remember { mutableStateOf(false) }

    // Offline Android TextToSpeech state
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isPlayingAudio by remember { mutableStateOf(false) }
    var speakingParagraphIndex by remember { mutableIntStateOf(-1) }
    var activeUtteranceId by remember { mutableStateOf("") }

    // Latest reactive references for UtteranceProgressListener callbacks
    val latestState by rememberUpdatedState(readerState)
    val latestPrefs by rememberUpdatedState(readerPrefs)
    val latestPlaying by rememberUpdatedState(isPlayingAudio)
    val latestSpeakingIdx by rememberUpdatedState(speakingParagraphIndex)
    val latestUtteranceId by rememberUpdatedState(activeUtteranceId)

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

    fun currentVisibleParagraphIndex(): Int {
        // Item 0 is the chapter title header; paragraphs start at LazyColumn item index 1
        val firstIdx = listState.firstVisibleItemIndex
        return (firstIdx - 1).coerceIn(0, (readerState.paragraphs.size - 1).coerceAtLeast(0))
    }

    fun stopAudio() {
        isPlayingAudio = false
        activeUtteranceId = ""
        runCatching { ttsEngine?.stop() }
    }

    fun speakParagraphAt(index: Int) {
        val engine = ttsEngine ?: return
        if (!isTtsReady) return
        val paragraphs = latestState.paragraphs
        if (paragraphs.isEmpty()) return

        val safeIndex = index.coerceIn(0, paragraphs.lastIndex)
        val text = paragraphs[safeIndex].trim()
        if (text.isEmpty()) {
            if (safeIndex + 1 < paragraphs.size) {
                speakParagraphAt(safeIndex + 1)
            } else {
                stopAudio()
            }
            return
        }

        // Configure Offline Vietnamese voice + custom profile pitch/rate
        applyOfflineVietnameseVoiceProfile(
            tts = engine,
            profile = latestPrefs.voiceProfile,
            baseSpeechRate = latestPrefs.speechRate
        )

        val uttId = "reader_${latestState.currentChapterIndex}_${safeIndex}_${System.nanoTime()}"
        activeUtteranceId = uttId
        speakingParagraphIndex = safeIndex
        isPlayingAudio = true

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId)
    }

    // Initialize Offline Android TTS & strictly clean up in DisposableEffect
    DisposableEffect(context) {
        var localTts: TextToSpeech? = null
        localTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            if (utteranceId != latestUtteranceId || !latestPlaying) return@post
                            val paras = latestState.paragraphs
                            val nextIdx = latestSpeakingIdx + 1
                            if (nextIdx < paras.size) {
                                speakParagraphAt(nextIdx)
                            } else if (latestState.currentChapterIndex + 1 < latestState.toc.size) {
                                // Auto-advance to the next chapter and continue reading seamlessly
                                onSelectChapter(latestState.currentChapterIndex + 1, 0, true)
                            } else {
                                isPlayingAudio = false
                                speakingParagraphIndex = -1
                                activeUtteranceId = ""
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            if (utteranceId == latestUtteranceId) {
                                isPlayingAudio = false
                            }
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post {
                            if (utteranceId == latestUtteranceId) {
                                isPlayingAudio = false
                            }
                        }
                    }
                })
                mainHandler.post {
                    isTtsReady = true
                }
            }
        }
        ttsEngine = localTts
        onDispose {
            onSaveProgress(
                latestState.currentChapterIndex,
                if (latestSpeakingIdx >= 0) latestSpeakingIdx else currentVisibleParagraphIndex()
            )
            runCatching {
                localTts.stop()
                localTts.shutdown()
            }
            ttsEngine = null
            isTtsReady = false
            isPlayingAudio = false
        }
    }

    // Scroll to saved/target paragraph whenever a chapter finishes loading
    LaunchedEffect(readerState.chapterLoadNonce) {
        if (readerState.chapterLoadNonce == 0L || readerState.isLoadingChapter) return@LaunchedEffect
        val targetItem = (readerState.targetParagraphIndex + 1)
            .coerceIn(0, readerState.paragraphs.size)
        listState.scrollToItem(targetItem)

        if (readerState.autoContinueAudioOnChapterReady && isTtsReady) {
            onConsumeAutoContinueAudio()
            speakParagraphAt(readerState.targetParagraphIndex)
        }
    }

    // Smoothly scroll to the active spoken paragraph when TTS advances
    LaunchedEffect(speakingParagraphIndex, isPlayingAudio) {
        if (isPlayingAudio && speakingParagraphIndex >= 0 && speakingParagraphIndex < readerState.paragraphs.size) {
            val targetItemIndex = speakingParagraphIndex + 1
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            if (targetItemIndex !in visibleIndices) {
                runCatching { listState.animateScrollToItem(targetItemIndex) }
            }
            onSaveProgress(readerState.currentChapterIndex, speakingParagraphIndex)
        }
    }

    // Re-apply speed / voice immediately if user changes them while playing
    LaunchedEffect(readerPrefs.speechRate, readerPrefs.voiceProfile) {
        if (isPlayingAudio && speakingParagraphIndex >= 0) {
            speakParagraphAt(speakingParagraphIndex)
        }
    }

    // Debounced auto-save scroll progress as the user reads
    LaunchedEffect(listState, readerState.currentChapterIndex, readerState.book?.id) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(900L)
            .collect { firstIdx ->
                if (!readerState.isLoadingChapter && readerState.paragraphs.isNotEmpty()) {
                    val paraIdx = (firstIdx - 1).coerceIn(0, readerState.paragraphs.lastIndex)
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
        stopAudio()
        onBackClick(readerState.currentChapterIndex, paraToSave)
    }

    BackHandler {
        when {
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
                            contentDescription = "Tùy chỉnh chữ và màu giấy",
                            tint = if (showAppearancePopover) IosBlue else inkColor,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            showAppearancePopover = false
                            if (showAudioBar) {
                                stopAudio()
                                showAudioBar = false
                            } else {
                                showAudioBar = true
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reader_audio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Headphones,
                            contentDescription = "Nghe đọc sách",
                            tint = if (showAudioBar || isPlayingAudio) IosBlue else inkColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 2. Floating iOS Dynamic Island / Pill Audio Bar (right below Top Bar)
            AnimatedVisibility(
                visible = showAudioBar,
                enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IosFloatingAudioPill(
                        isPlaying = isPlayingAudio,
                        speechRate = readerPrefs.speechRate,
                        voiceProfile = readerPrefs.voiceProfile,
                        onPrevParagraph = {
                            val currentIdx = if (speakingParagraphIndex >= 0) {
                                speakingParagraphIndex
                            } else {
                                currentVisibleParagraphIndex()
                            }
                            if (currentIdx > 0) {
                                speakParagraphAt(currentIdx - 1)
                            } else if (readerState.currentChapterIndex > 0) {
                                onSelectChapter(readerState.currentChapterIndex - 1, 0, true)
                            }
                        },
                        onTogglePlayPause = {
                            if (isPlayingAudio) {
                                stopAudio()
                            } else {
                                val startIdx = if (speakingParagraphIndex in readerState.paragraphs.indices) {
                                    speakingParagraphIndex
                                } else {
                                    currentVisibleParagraphIndex()
                                }
                                speakParagraphAt(startIdx)
                            }
                        },
                        onNextParagraph = {
                            val currentIdx = if (speakingParagraphIndex >= 0) {
                                speakingParagraphIndex
                            } else {
                                currentVisibleParagraphIndex()
                            }
                            if (currentIdx + 1 < readerState.paragraphs.size) {
                                speakParagraphAt(currentIdx + 1)
                            } else if (readerState.currentChapterIndex + 1 < readerState.toc.size) {
                                onSelectChapter(readerState.currentChapterIndex + 1, 0, true)
                            }
                        },
                        onCycleSpeed = onCycleSpeechRate,
                        onCycleVoice = onCycleVoiceProfile
                    )
                }
            }

            // 3. Reading Content Area (distraction-free Apple Books typography)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
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
                    val lineHeightSp = (fontSize * 1.56f).sp

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("reader_paragraphs_list"),
                        contentPadding = PaddingValues(
                            start = 22.dp,
                            end = 22.dp,
                            top = 10.dp,
                            bottom = 44.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Chapter Title Header
                        item(key = "chapter_header_${readerState.currentChapterIndex}") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = readerState.book?.title.orEmpty(),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = subtleInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = readerState.currentChapterTitle.ifBlank {
                                        "Chương ${readerState.currentChapterIndex + 1}"
                                    },
                                    fontSize = (fontSize + 3f).coerceAtMost(26f).sp,
                                    lineHeight = ((fontSize + 3f) * 1.3f).sp,
                                    fontWeight = FontWeight.Bold,
                                    color = inkColor
                                )
                            }
                        }

                        // Chapter Paragraphs
                        itemsIndexed(
                            items = readerState.paragraphs,
                            key = { idx, _ -> "p_${readerState.currentChapterIndex}_$idx" }
                        ) { index, paragraph ->
                            val isSpeakingThis = speakingParagraphIndex == index &&
                                (isPlayingAudio || showAudioBar)
                            val highlightBg = if (isSpeakingThis) {
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
                                        indication = null
                                    ) {
                                        if (showAppearancePopover) {
                                            showAppearancePopover = false
                                        } else if (showAudioBar) {
                                            speakParagraphAt(index)
                                        }
                                    }
                                    .padding(
                                        horizontal = if (isSpeakingThis) 8.dp else 0.dp,
                                        vertical = if (isSpeakingThis) 5.dp else 0.dp
                                    )
                            ) {
                                Text(
                                    text = paragraph,
                                    fontSize = fontSize.sp,
                                    lineHeight = lineHeightSp,
                                    color = inkColor,
                                    textAlign = TextAlign.Justify
                                )
                            }
                        }

                        // Minimalist Chapter Navigation Footer
                        item(key = "chapter_footer_${readerState.currentChapterIndex}") {
                            val hasPrev = readerState.currentChapterIndex > 0
                            val hasNext = readerState.currentChapterIndex + 1 < readerState.toc.size
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
                                            .clickable {
                                                stopAudio()
                                                onSelectChapter(readerState.currentChapterIndex - 1, 0, false)
                                            }
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
                                    text = "${readerState.currentChapterIndex + 1} / ${readerState.toc.size.coerceAtLeast(1)}",
                                    fontSize = 11.sp,
                                    color = subtleInk
                                )

                                if (hasNext) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = subtleSurface,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .clickable {
                                                stopAudio()
                                                onSelectChapter(readerState.currentChapterIndex + 1, 0, false)
                                            }
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
                    }
                }
            }
        }

        // 4. Floating iOS Popover for "+" (EXACTLY 2 options: Font size & 3 Paper colors)
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
                        fontSizeSp = readerPrefs.fontSizeSp,
                        selectedTheme = readerPrefs.paperTheme,
                        onDecreaseFont = { onAdjustFontSize(-1f) },
                        onIncreaseFont = { onAdjustFontSize(1f) },
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
                        stopAudio()
                        onSelectChapter(idx, 0, false)
                    }
                )
            }
        }
    }
}

/**
 * Floating iOS Dynamic Island / Pill Audio Bar (`RoundedCornerShape(50)`, height `38.dp`).
 * Contains strictly: Prev | Play/Pause | Next | Speed (`1.0x`) | Voice (`Nữ 1` / `Nữ 2` / `Nam 1`).
 */
@Composable
private fun IosFloatingAudioPill(
    isPlaying: Boolean,
    speechRate: Float,
    voiceProfile: ReaderVoiceProfile,
    onPrevParagraph: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleVoice: () -> Unit
) {
    val pillShape = RoundedCornerShape(50)

    Row(
        modifier = Modifier
            .height(38.dp)
            .shadow(
                elevation = 10.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.24f),
                spotColor = Color.Black.copy(alpha = 0.34f)
            )
            .clip(pillShape)
            .background(Color(0xF21C1C1E))
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
        // Prev paragraph
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

        // Play / Pause
        Box(
            modifier = Modifier
                .size(28.dp)
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
                modifier = Modifier.size(17.dp)
            )
        }

        // Next paragraph
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

        // Speed pill (0.8x -> 1.0x -> 1.2x -> 1.5x -> 1.8x)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onCycleSpeed)
                .padding(horizontal = 8.dp, vertical = 4.dp)
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

        // Voice switch pill (Nữ 1 -> Nữ 2 -> Nam 1)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onCycleVoice)
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .testTag("audio_voice_button"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = voiceProfile.shortLabel,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Floating iOS Popover (`RoundedCornerShape(16.dp)`, subtle frosted card, soft shadow)
 * containing strictly 2 controls:
 * 1. Font size (`-` / value / `+`, `13.sp` – `24.sp`)
 * 2. Paper theme (3 circular color buttons with `#007AFF` active ring)
 */
@Composable
private fun IosReaderAppearancePopover(
    fontSizeSp: Float,
    selectedTheme: ReaderPaperTheme,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit,
    onSelectTheme: (ReaderPaperTheme) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    Surface(
        modifier = Modifier
            .width(214.dp)
            .shadow(
                elevation = 14.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.20f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .testTag("reader_appearance_popover"),
        shape = shape,
        color = Color(0xF5FFFFFF),
        border = BorderStroke(0.5.dp, Color(0xFFE5E5EA))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Option 1: Font size `-` | number | `+`
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cỡ chữ",
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(
                                enabled = fontSizeSp > LibraryManager.MIN_FONT_SIZE_SP,
                                onClick = onDecreaseFont
                            )
                            .testTag("reader_font_decrease_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Remove,
                            contentDescription = "Giảm cỡ chữ",
                            tint = if (fontSizeSp > LibraryManager.MIN_FONT_SIZE_SP) {
                                Color(0xFF1C1C1E)
                            } else {
                                Color(0xFFAEAEB2)
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Text(
                        text = fontSizeSp.toInt().toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1C1C1E),
                        modifier = Modifier.width(22.dp),
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(
                                enabled = fontSizeSp < LibraryManager.MAX_FONT_SIZE_SP,
                                onClick = onIncreaseFont
                            )
                            .testTag("reader_font_increase_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Tăng cỡ chữ",
                            tint = if (fontSizeSp < LibraryManager.MAX_FONT_SIZE_SP) {
                                Color(0xFF1C1C1E)
                            } else {
                                Color(0xFFAEAEB2)
                            },
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFE5E5EA))

            // Option 2: 3 circular paper theme swatches with #007AFF ring when active
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
                        selected = selectedTheme == ReaderPaperTheme.BOOK_BROWN,
                        contentDescription = "Màu nâu sách",
                        tag = "paper_theme_brown",
                        onClick = { onSelectTheme(ReaderPaperTheme.BOOK_BROWN) }
                    )
                    PaperSwatchCircle(
                        color = ReaderCreamPaper,
                        selected = selectedTheme == ReaderPaperTheme.WARM_CREAM,
                        contentDescription = "Màu vàng ngả",
                        tag = "paper_theme_cream",
                        onClick = { onSelectTheme(ReaderPaperTheme.WARM_CREAM) }
                    )
                    PaperSwatchCircle(
                        color = ReaderWhitePaper,
                        selected = selectedTheme == ReaderPaperTheme.PURE_WHITE,
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

/**
 * Selects the best available 100% Offline Vietnamese `Voice` from `tts.voices`
 * (`!voice.isNetworkConnectionRequired`) for each of the 3 curated voice profiles
 * (`Nữ 1`, `Nữ 2`, `Nam 1`) and applies each profile's natural pitch & cadence tuning.
 */
private fun applyOfflineVietnameseVoiceProfile(
    tts: TextToSpeech,
    profile: ReaderVoiceProfile,
    baseSpeechRate: Float
) {
    val viLocale = Locale("vi", "VN")
    runCatching { tts.language = viLocale }

    val offlineViVoices: List<Voice> = runCatching {
        tts.voices
            ?.filter { v ->
                v.locale?.language.equals("vi", ignoreCase = true) &&
                    !v.isNetworkConnectionRequired
            }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()
    }.getOrDefault(emptyList())

    if (offlineViVoices.isNotEmpty()) {
        val chosen = pickBestOfflineVietnameseVoice(offlineViVoices, profile)
        if (chosen != null) {
            runCatching { tts.voice = chosen }
        }
    }

    val effectiveRate = (baseSpeechRate * profile.rateFactor).coerceIn(0.65f, 2.2f)
    runCatching {
        tts.setPitch(profile.pitch)
        tts.setSpeechRate(effectiveRate)
    }
}

private fun pickBestOfflineVietnameseVoice(
    voices: List<Voice>,
    profile: ReaderVoiceProfile
): Voice? {
    if (voices.isEmpty()) return null
    val maleHints = listOf("male", "-vic-", "-vie-", "nam", "_m_")
    val femaleHints = listOf("female", "-gft-", "-vif-", "-vid-", "nu", "_f_")

    val maleCandidates = voices.filter { v ->
        val n = v.name.lowercase(Locale.ROOT)
        maleHints.any { n.contains(it) }
    }
    val femaleCandidates = voices.filter { v ->
        val n = v.name.lowercase(Locale.ROOT)
        femaleHints.any { n.contains(it) } || v !in maleCandidates
    }

    return when (profile) {
        ReaderVoiceProfile.FEMALE_1 -> femaleCandidates.getOrNull(0) ?: voices.first()
        ReaderVoiceProfile.FEMALE_2 -> femaleCandidates.getOrNull(1)
            ?: voices.getOrNull(1)
            ?: voices.first()
        ReaderVoiceProfile.MALE_1 -> maleCandidates.firstOrNull()
            ?: voices.lastOrNull()
            ?: voices.first()
    }
}
