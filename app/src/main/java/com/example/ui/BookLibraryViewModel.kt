package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AmbientSoundTrack
import com.example.audio.DetectedCharacter
import com.example.data.BookCollection
import com.example.data.LibraryBook
import com.example.data.LibraryChapterMeta
import com.example.data.LibraryManager
import com.example.data.ReaderPaperTheme
import com.example.data.ReaderPreferences
import com.example.data.ReaderScrollDirection
import com.example.data.ReaderVoiceProfile
import com.example.pipeline.FilePathResolver
import com.example.pipeline.PreparedWorkspace
import com.example.pipeline.SourceFileInfo
import com.example.service.AudioPlaybackStateHolder
import com.example.service.BookAudioPlaybackService
import com.example.service.BookAudioPlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class ActiveBookReaderState(
    val book: LibraryBook? = null,
    val toc: List<LibraryChapterMeta> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val paragraphs: List<String> = emptyList(),
    val targetParagraphIndex: Int = 0,
    val chapterLoadNonce: Long = 0L,
    val isLoadingChapter: Boolean = false,
    val autoContinueAudioOnChapterReady: Boolean = false,
    val isScanningCharacters: Boolean = false,
    val scannedCharacters: List<DetectedCharacter> = emptyList(),
    val showCharacterVoicePicker: Boolean = false,
    val characterVoiceAssignments: Map<String, ReaderVoiceProfile> = emptyMap()
)

sealed interface LibraryImportFlowState {
    data object Idle : LibraryImportFlowState
    data class AskCleanConfirmation(val uri: Uri, val source: SourceFileInfo) : LibraryImportFlowState
    data class ImportingDirect(val fileName: String) : LibraryImportFlowState
    data class CleaningInProgress(val source: SourceFileInfo) : LibraryImportFlowState
    data class FinalizingCleanImport(val bookTitle: String) : LibraryImportFlowState
    data class CleanCompleted(val book: LibraryBook, val totalFixes: Int) : LibraryImportFlowState
    data class Error(val message: String) : LibraryImportFlowState
}

class BookLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val libraryManager = LibraryManager(application.applicationContext)

    private val _books = MutableStateFlow<List<LibraryBook>>(emptyList())
    val books: StateFlow<List<LibraryBook>> = _books.asStateFlow()

    private val _collections = MutableStateFlow<List<BookCollection>>(emptyList())
    val collections: StateFlow<List<BookCollection>> = _collections.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importFlowState = MutableStateFlow<LibraryImportFlowState>(LibraryImportFlowState.Idle)
    val importFlowState: StateFlow<LibraryImportFlowState> = _importFlowState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _readerPrefs = MutableStateFlow(libraryManager.getReaderPreferences())
    val readerPrefs: StateFlow<ReaderPreferences> = _readerPrefs.asStateFlow()

    private val _readerState = MutableStateFlow(ActiveBookReaderState())
    val readerState: StateFlow<ActiveBookReaderState> = _readerState.asStateFlow()

    val audioPlaybackState: StateFlow<BookAudioPlaybackState> = AudioPlaybackStateHolder.state

    init {
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            libraryManager.ensureSampleBookSeeded()
            val (loadedBooks, loadedCollections) = libraryManager.loadLibraryIndex()
            _books.value = loadedBooks
            _collections.value = loadedCollections
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    // -------------------------------------------------------------------------
    // Import Flow: Pick File -> Ask Clean (Có / Không) -> Auto-Clean -> Sửa tiếp / OK
    // -------------------------------------------------------------------------

    fun onLibraryFilePicked(uri: Uri) {
        if (_isImporting.value) return
        val inspected = FilePathResolver.inspectSourceFile(getApplication(), uri)
        if (inspected == null) {
            _statusMessage.value = "Chỉ hỗ trợ nhập tệp .epub hoặc .pdf vào Thư viện sách."
            return
        }
        _importFlowState.value = LibraryImportFlowState.AskCleanConfirmation(
            uri = uri,
            source = inspected
        )
    }

    fun confirmDirectImportWithoutCleaning() {
        val current = _importFlowState.value as? LibraryImportFlowState.AskCleanConfirmation ?: return
        _isImporting.value = true
        _importFlowState.value = LibraryImportFlowState.ImportingDirect(current.source.displayName)
        viewModelScope.launch {
            val result = libraryManager.importBookFromUri(getApplication(), current.uri)
            result.onSuccess { imported ->
                val (loadedBooks, loadedCollections) = withContext(Dispatchers.IO) {
                    libraryManager.loadLibraryIndex()
                }
                _books.value = loadedBooks
                _collections.value = loadedCollections
                _importFlowState.value = LibraryImportFlowState.Idle
                _statusMessage.value = "Đã thêm \"${imported.title}\" vào Thư viện sách"
            }.onFailure { err ->
                _importFlowState.value = LibraryImportFlowState.Error(
                    err.message ?: "Không thể nhập tệp sách này."
                )
            }
            _isImporting.value = false
        }
    }

    fun startCleaningFlowForPickedFile(): SourceFileInfo? {
        val current = _importFlowState.value as? LibraryImportFlowState.AskCleanConfirmation ?: return null
        _isImporting.value = true
        _importFlowState.value = LibraryImportFlowState.CleaningInProgress(current.source)
        return current.source
    }

    fun onCleaningCompletedForLibrary(workspace: PreparedWorkspace, totalFixes: Int) {
        if (_importFlowState.value !is LibraryImportFlowState.CleaningInProgress) return
        _importFlowState.value = LibraryImportFlowState.FinalizingCleanImport(workspace.bookTitle)
        viewModelScope.launch {
            val cleanedFile = File(workspace.tempOutputEpubPath)
            val result = libraryManager.importCleanedEpubFromFile(
                context = getApplication(),
                cleanedEpubFile = cleanedFile,
                fallbackTitle = workspace.bookTitle
            )
            result.onSuccess { imported ->
                val (loadedBooks, loadedCollections) = withContext(Dispatchers.IO) {
                    libraryManager.loadLibraryIndex()
                }
                _books.value = loadedBooks
                _collections.value = loadedCollections
                _importFlowState.value = LibraryImportFlowState.CleanCompleted(
                    book = imported,
                    totalFixes = totalFixes
                )
            }.onFailure { err ->
                _importFlowState.value = LibraryImportFlowState.Error(
                    err.message ?: "Đã dọn sách nhưng không thể thêm vào Thư viện."
                )
            }
            _isImporting.value = false
        }
    }

    fun onCleaningFailedOrCancelled(message: String?) {
        if (_importFlowState.value !is LibraryImportFlowState.CleaningInProgress) return
        _isImporting.value = false
        if (message.isNullOrBlank()) {
            _importFlowState.value = LibraryImportFlowState.Idle
        } else {
            _importFlowState.value = LibraryImportFlowState.Error(message)
        }
    }

    fun dismissImportFlow() {
        _isImporting.value = false
        _importFlowState.value = LibraryImportFlowState.Idle
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val (updatedBooks, updatedCols) = libraryManager.deleteBook(bookId)
            _books.value = updatedBooks
            _collections.value = updatedCols
        }
    }

    fun createCollection(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.createCollection(clean)
        }
    }

    fun setCollectionBooks(collectionId: String, selectedBookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.setCollectionBooks(collectionId, selectedBookIds)
        }
    }

    fun addBookToCollection(collectionId: String, bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.addBookToCollection(collectionId, bookId)
            _statusMessage.value = "Đã thêm sách vào bộ sưu tập"
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.deleteCollection(collectionId)
        }
    }

    // -------------------------------------------------------------------------
    // Book Reader Session & On-Demand Chapter Loading
    // -------------------------------------------------------------------------

    fun openBookForReading(book: LibraryBook, onReady: () -> Unit) {
        _readerState.value = ActiveBookReaderState(
            book = book,
            isLoadingChapter = true
        )
        onReady()
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val updated = libraryManager.markBookOpened(book.id)
                val tocList = libraryManager.loadBookToc(book.id)
                val chapIdx = book.lastChapterIndex.coerceIn(0, (tocList.size - 1).coerceAtLeast(0))
                val chapContent = if (tocList.isNotEmpty()) {
                    libraryManager.loadChapterContent(book.id, tocList[chapIdx])
                } else {
                    null
                }
                val paras = chapContent?.paragraphs.orEmpty()
                val paraIdx = book.lastParagraphIndex.coerceIn(0, (paras.size - 1).coerceAtLeast(0))
                val savedCharVoices = libraryManager.loadCharacterVoiceAssignments(book.id)
                BookOpenBundle(updated, tocList, chapContent, chapIdx, paraIdx, savedCharVoices)
            }
            _books.value = loaded.updatedBooks
            _readerState.value = ActiveBookReaderState(
                book = loaded.updatedBooks.firstOrNull { it.id == book.id } ?: book,
                toc = loaded.toc,
                currentChapterIndex = loaded.chapterIndex,
                currentChapterTitle = loaded.content?.title
                    ?: loaded.toc.getOrNull(loaded.chapterIndex)?.title
                    ?: book.title,
                paragraphs = loaded.content?.paragraphs ?: listOf("(Sách chưa có nội dung hiển thị.)"),
                targetParagraphIndex = loaded.paragraphIndex,
                chapterLoadNonce = System.nanoTime(),
                isLoadingChapter = false,
                autoContinueAudioOnChapterReady = false,
                isScanningCharacters = false,
                scannedCharacters = emptyList(),
                showCharacterVoicePicker = false,
                characterVoiceAssignments = loaded.savedCharacterVoices
            )
        }
    }

    fun openCurrentlyPlayingBook(onReady: () -> Unit) {
        val active = AudioPlaybackStateHolder.state.value
        val activeBookId = active.bookId ?: return
        val existing = _books.value.firstOrNull { it.id == activeBookId }
        if (existing != null) {
            val syncedBook = existing.copy(
                lastChapterIndex = active.chapterIndex,
                lastParagraphIndex = active.paragraphIndex.coerceAtLeast(0)
            )
            openBookForReading(syncedBook, onReady)
        } else {
            viewModelScope.launch {
                val found = withContext(Dispatchers.IO) {
                    libraryManager.loadLibraryIndex().first.firstOrNull { it.id == activeBookId }
                }
                if (found != null) {
                    val syncedBook = found.copy(
                        lastChapterIndex = active.chapterIndex,
                        lastParagraphIndex = active.paragraphIndex.coerceAtLeast(0)
                    )
                    openBookForReading(syncedBook, onReady)
                }
            }
        }
    }

    fun selectChapter(
        chapterIndex: Int,
        startParagraphIndex: Int = 0,
        continueAudio: Boolean = false
    ) {
        val current = _readerState.value
        val book = current.book ?: return
        val toc = current.toc
        if (toc.isEmpty()) return
        val safeIdx = chapterIndex.coerceIn(0, toc.lastIndex)

        _readerState.update {
            it.copy(
                currentChapterIndex = safeIdx,
                isLoadingChapter = true,
                autoContinueAudioOnChapterReady = continueAudio
            )
        }

        viewModelScope.launch {
            val (content, updatedBooks) = withContext(Dispatchers.IO) {
                val loaded = libraryManager.loadChapterContent(book.id, toc[safeIdx])
                val safePara = startParagraphIndex.coerceIn(0, (loaded.paragraphs.size - 1).coerceAtLeast(0))
                val updated = libraryManager.updateReadingProgress(book.id, safeIdx, safePara)
                loaded to updated
            }
            _books.value = updatedBooks
            val safePara = startParagraphIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0))
            _readerState.update { state ->
                state.copy(
                    book = updatedBooks.firstOrNull { it.id == book.id } ?: state.book,
                    currentChapterIndex = safeIdx,
                    currentChapterTitle = content.title,
                    paragraphs = content.paragraphs,
                    targetParagraphIndex = safePara,
                    chapterLoadNonce = System.nanoTime(),
                    isLoadingChapter = false,
                    autoContinueAudioOnChapterReady = continueAudio
                )
            }
        }
    }

    fun consumeAutoContinueAudioFlag() {
        _readerState.update { it.copy(autoContinueAudioOnChapterReady = false) }
    }

    fun saveReadingProgress(chapterIndex: Int, paragraphIndex: Int) {
        val book = _readerState.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = libraryManager.updateReadingProgress(
                bookId = book.id,
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex
            )
            _books.value = updated
        }
    }

    // -------------------------------------------------------------------------
    // Reader Appearance, Layout & Audio Controls
    // -------------------------------------------------------------------------

    fun setReaderFontSize(newSizeSp: Float) {
        val clamped = newSizeSp.roundToInt().toFloat().coerceIn(
            LibraryManager.MIN_FONT_SIZE_SP,
            LibraryManager.MAX_FONT_SIZE_SP
        )
        val updated = _readerPrefs.value.copy(fontSizeSp = clamped)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun adjustReaderFontSize(delta: Float) {
        setReaderFontSize(_readerPrefs.value.fontSizeSp + delta)
    }

    fun selectReaderPaperTheme(theme: ReaderPaperTheme) {
        val updated = _readerPrefs.value.copy(paperTheme = theme)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun selectScrollDirection(direction: ReaderScrollDirection) {
        val updated = _readerPrefs.value.copy(scrollDirection = direction)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun adjustPageMargin(deltaDp: Float) {
        val next = (_readerPrefs.value.pageMarginDp + deltaDp)
            .coerceIn(LibraryManager.MIN_PAGE_MARGIN_DP, LibraryManager.MAX_PAGE_MARGIN_DP)
        val updated = _readerPrefs.value.copy(pageMarginDp = next)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun adjustLineHeightMultiplier(delta: Float) {
        val raw = _readerPrefs.value.lineHeightMultiplier + delta
        val rounded = (raw * 10f).roundToInt() / 10f
        val next = rounded.coerceIn(
            LibraryManager.MIN_LINE_HEIGHT_MULT,
            LibraryManager.MAX_LINE_HEIGHT_MULT
        )
        val updated = _readerPrefs.value.copy(lineHeightMultiplier = next)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun adjustLetterSpacing(deltaSp: Float) {
        val raw = _readerPrefs.value.letterSpacingSp + deltaSp
        val rounded = (raw * 4f).roundToInt() / 4f
        val next = rounded.coerceIn(
            LibraryManager.MIN_LETTER_SPACING_SP,
            LibraryManager.MAX_LETTER_SPACING_SP
        )
        val updated = _readerPrefs.value.copy(letterSpacingSp = next)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun cycleSpeechRate() {
        val nextRate = LibraryManager.nextSpeechRate(_readerPrefs.value.speechRate)
        val updated = _readerPrefs.value.copy(speechRate = nextRate)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun cycleVoiceProfile() {
        val nextVoice: ReaderVoiceProfile = _readerPrefs.value.voiceProfile.next()
        selectNarratorVoice(nextVoice)
    }

    fun selectNarratorVoice(profile: ReaderVoiceProfile) {
        val updated = _readerPrefs.value.copy(voiceProfile = profile)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun saveCustomVoicePitch(profile: ReaderVoiceProfile, pitch: Float) {
        val clamped = ((pitch * 20f).roundToInt() / 20f)
            .coerceIn(LibraryManager.MIN_VOICE_PITCH, LibraryManager.MAX_VOICE_PITCH)
        val updatedMap = _readerPrefs.value.customVoicePitches.toMutableMap().apply {
            put(profile.storageKey, clamped)
        }
        val updated = _readerPrefs.value.copy(customVoicePitches = updatedMap)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
        _statusMessage.value = "Đã lưu độ cao bổng cho ${profile.shortLabel}"
    }

    fun resetCustomVoicePitch(profile: ReaderVoiceProfile) {
        val updatedMap = _readerPrefs.value.customVoicePitches.toMutableMap().apply {
            remove(profile.storageKey)
        }
        val updated = _readerPrefs.value.copy(customVoicePitches = updatedMap)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
        _statusMessage.value = "Đã đặt lại mặc định cho ${profile.shortLabel}"
    }

    fun selectSystemDeviceVoice(voiceName: String?) {
        val cleanName = voiceName?.takeIf { it.isNotBlank() }
        val updated = _readerPrefs.value.copy(selectedSystemVoiceName = cleanName)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun setAdvancedVoiceCustomEnabled(enabled: Boolean) {
        val current = _readerPrefs.value
        val updated = current.copy(
            advancedCustomEnabled = enabled,
            speechRate = if (enabled) current.customSpeechRate else current.speechRate
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun setAdvancedCustomPitch(pitch: Float) {
        val snapped = LibraryManager.snapFloatStep(
            value = pitch,
            step = LibraryManager.STEP_ADVANCED_PITCH,
            min = LibraryManager.MIN_ADVANCED_PITCH,
            max = LibraryManager.MAX_ADVANCED_PITCH
        )
        val updated = _readerPrefs.value.copy(
            advancedCustomEnabled = true,
            customPitch = snapped
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun setAdvancedCustomSpeechRate(rate: Float) {
        val snapped = LibraryManager.snapFloatStep(
            value = rate,
            step = LibraryManager.STEP_ADVANCED_RATE,
            min = LibraryManager.MIN_ADVANCED_RATE,
            max = LibraryManager.MAX_ADVANCED_RATE
        )
        val updated = _readerPrefs.value.copy(
            advancedCustomEnabled = true,
            customSpeechRate = snapped,
            speechRate = snapped
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun setSentencePauseMs(pauseMs: Int) {
        val snapped = ((pauseMs / LibraryManager.STEP_SENTENCE_PAUSE_MS.toFloat()).roundToInt() *
            LibraryManager.STEP_SENTENCE_PAUSE_MS)
            .coerceIn(LibraryManager.MIN_SENTENCE_PAUSE_MS, LibraryManager.MAX_SENTENCE_PAUSE_MS)
        val updated = _readerPrefs.value.copy(sentencePauseMs = snapped)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun resetAdvancedVoiceSettings() {
        val updated = _readerPrefs.value.copy(
            selectedSystemVoiceName = null,
            advancedCustomEnabled = false,
            customPitch = LibraryManager.DEFAULT_ADVANCED_PITCH,
            customSpeechRate = LibraryManager.DEFAULT_ADVANCED_RATE,
            speechRate = LibraryManager.DEFAULT_ADVANCED_RATE,
            sentencePauseMs = LibraryManager.DEFAULT_SENTENCE_PAUSE_MS
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
        _statusMessage.value = "Đã khôi phục mặc định thông số giọng đọc"
    }

    fun selectAmbientSound(track: AmbientSoundTrack) {
        val updated = _readerPrefs.value.copy(ambientSound = track)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun setAmbientVolume(volume: Float) {
        val clamped = volume.coerceIn(LibraryManager.MIN_AMBIENT_VOLUME, LibraryManager.MAX_AMBIENT_VOLUME)
        val updated = _readerPrefs.value.copy(ambientVolume = clamped)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun scanCharactersForCurrentBook() {
        val book = _readerState.value.book ?: return
        if (_readerState.value.isScanningCharacters) return
        _readerState.update { it.copy(isScanningCharacters = true) }
        viewModelScope.launch {
            val detected = withContext(Dispatchers.IO) {
                libraryManager.scanTopCharactersInBook(book.id, maxCharacters = 7)
            }
            _readerState.update { state ->
                state.copy(
                    isScanningCharacters = false,
                    scannedCharacters = detected,
                    showCharacterVoicePicker = true
                )
            }
            if (detected.isEmpty()) {
                _statusMessage.value = "Chưa nhận diện được nhân vật nổi bật trong truyện này."
            }
        }
    }

    fun confirmCharacterVoiceAssignments(assignments: Map<String, ReaderVoiceProfile>) {
        val book = _readerState.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            libraryManager.saveCharacterVoiceAssignments(book.id, assignments)
            BookAudioPlaybackService.syncSettings(getApplication())
        }
        _readerState.update { state ->
            state.copy(
                characterVoiceAssignments = assignments,
                showCharacterVoicePicker = false
            )
        }
        _statusMessage.value = if (assignments.isNotEmpty()) {
            "Đã áp dụng giọng cho ${assignments.size} nhân vật"
        } else {
            "Tất cả nhân vật dùng giọng người dẫn truyện"
        }
    }

    fun hideCharacterVoicePicker() {
        _readerState.update { it.copy(showCharacterVoicePicker = false) }
    }

    private data class BookOpenBundle(
        val updatedBooks: List<LibraryBook>,
        val toc: List<LibraryChapterMeta>,
        val content: com.example.data.LibraryChapterContent?,
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val savedCharacterVoices: Map<String, ReaderVoiceProfile>
    )
}
