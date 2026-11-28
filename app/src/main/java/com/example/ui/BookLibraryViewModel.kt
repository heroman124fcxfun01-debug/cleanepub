package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AiDiagnosticTestResult
import com.example.audio.AiSystemDiagnosticReport
import com.example.audio.AmbientSoundTrack
import com.example.audio.AudioFileDecoder
import com.example.audio.ClonedVoiceProfile
import com.example.audio.ClonedVoiceRepository
import com.example.audio.DetectedCharacter
import com.example.audio.ImportedVoiceSample
import com.example.audio.OfflineOnnxVoiceEngine
import com.example.audio.OnnxAudioTrackPlayer
import com.example.audio.OnnxModelAssetHelper
import com.example.audio.OnnxVoiceEngineMode
import com.example.audio.VieNeuPresetVoice
import com.example.audio.VoicePreviewCache
import com.example.data.BookCollection
import com.example.data.CustomAmbientTrack
import com.example.data.CustomImportedFont
import com.example.data.HorizontalPageEffect
import com.example.data.LibraryBook
import com.example.data.LibraryChapterMeta
import com.example.data.LibraryManager
import com.example.data.ReaderPaperTheme
import com.example.data.ReaderPreferences
import com.example.data.ReaderScrollDirection
import com.example.data.ReaderVoiceProfile
import com.example.data.TtsEngineType
import com.example.pipeline.CleaningOptions
import com.example.pipeline.EpubCleanPipeline
import com.example.pipeline.EpubMetadataEditor
import com.example.pipeline.FilePathResolver
import com.example.pipeline.PreparedWorkspace
import com.example.pipeline.ProcessingPhase
import com.example.pipeline.SourceFileInfo
import com.example.service.AudioPlaybackStateHolder
import com.example.service.BookAudioPlaybackService
import com.example.service.BookAudioPlaybackState
import com.example.ui.theme.ReaderFontRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/**
 * Bản nháp giọng AI nhân bản vừa được bấm "Tạo" trong Studio (chưa lưu vĩnh viễn).
 * Khi tạo xong -> nút "Nghe thử" bên cạnh sáng lên để kiểm tra; bấm "Lưu" mới ghi xuống bộ nhớ.
 */
data class PendingDraftClonedVoice(
    val name: String,
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Float,
    val embedding: FloatArray,
    val referenceCodes: IntArray? = null,
    val refSpeechTokens: String = "",
    val referenceText: String = "",
    val referenceTextIpa: String = "",
    val first10Tokens: IntArray = IntArray(0),
    val minToken: Int = 0,
    val maxToken: Int = 0,
    val uniqueTokenCount: Int = 0,
    val encoderIoSummary: String = "",
    val usedOnnxEncoder: Boolean = true,
    val acousticSummary: String = "",
    val previewWavPath: String? = null,
    val synthesizedPreview: Boolean = false
)

sealed interface LibraryImportFlowState {
    data object Idle : LibraryImportFlowState
    data class AskCleanConfirmation(val uri: Uri, val source: SourceFileInfo) : LibraryImportFlowState
    data class AskBatchCleanConfirmation(val items: List<Pair<Uri, SourceFileInfo>>) : LibraryImportFlowState
    data class ImportingDirect(val fileName: String) : LibraryImportFlowState
    data class CleaningInProgress(val source: SourceFileInfo) : LibraryImportFlowState
    data class FinalizingCleanImport(val bookTitle: String) : LibraryImportFlowState
    data class CleanCompleted(val book: LibraryBook, val totalFixes: Int) : LibraryImportFlowState
    data class BatchProcessing(
        val currentIndex: Int,
        val totalCount: Int,
        val currentBookName: String,
        val currentBookProgress: Float,
        val statusDetail: String,
        val isCleaning: Boolean,
        val successCount: Int,
        val failedCount: Int
    ) : LibraryImportFlowState
    data class BatchCompleted(
        val totalCount: Int,
        val successCount: Int,
        val failedCount: Int,
        val totalFixes: Int,
        val wasCleaned: Boolean,
        val wasCancelled: Boolean
    ) : LibraryImportFlowState
    data class Error(val message: String) : LibraryImportFlowState
}

class BookLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val libraryManager = LibraryManager(application.applicationContext)
    private val clonedVoiceRepository = ClonedVoiceRepository(application.applicationContext)
    private val onnxVoiceEngineLazy = lazy { OfflineOnnxVoiceEngine.getShared(application.applicationContext) }
    private val onnxVoiceEngine: OfflineOnnxVoiceEngine get() = onnxVoiceEngineLazy.value

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

    private val _customFonts = MutableStateFlow<List<CustomImportedFont>>(emptyList())
    val customFonts: StateFlow<List<CustomImportedFont>> = _customFonts.asStateFlow()

    private val _customAmbientTracks = MutableStateFlow<List<CustomAmbientTrack>>(emptyList())
    val customAmbientTracks: StateFlow<List<CustomAmbientTrack>> = _customAmbientTracks.asStateFlow()

    private val _isImportingAmbient = MutableStateFlow(false)
    val isImportingAmbient: StateFlow<Boolean> = _isImportingAmbient.asStateFlow()

    private val _clonedVoices = MutableStateFlow<List<ClonedVoiceProfile>>(emptyList())
    val clonedVoices: StateFlow<List<ClonedVoiceProfile>> = _clonedVoices.asStateFlow()

    private val _presetAiVoices = MutableStateFlow<List<VieNeuPresetVoice>>(emptyList())
    val presetAiVoices: StateFlow<List<VieNeuPresetVoice>> = _presetAiVoices.asStateFlow()

    private val _aiModelStatusBadge = MutableStateFlow("Model: Chờ kiểm tra | Voices: 0")
    val aiModelStatusBadge: StateFlow<String> = _aiModelStatusBadge.asStateFlow()

    private val _voiceCloneEngineMode = MutableStateFlow(OnnxVoiceEngineMode.UNINITIALIZED)
    val voiceCloneEngineMode: StateFlow<OnnxVoiceEngineMode> = _voiceCloneEngineMode.asStateFlow()

    private val _isExtractingVoiceClone = MutableStateFlow(false)
    val isExtractingVoiceClone: StateFlow<Boolean> = _isExtractingVoiceClone.asStateFlow()

    private val _isImportingVoiceFile = MutableStateFlow(false)
    val isImportingVoiceFile: StateFlow<Boolean> = _isImportingVoiceFile.asStateFlow()

    private val _draftClonedVoice = MutableStateFlow<PendingDraftClonedVoice?>(null)
    val draftClonedVoice: StateFlow<PendingDraftClonedVoice?> = _draftClonedVoice.asStateFlow()

    private val _lastAiVerbatimError = MutableStateFlow<String?>(null)
    val lastAiVerbatimError: StateFlow<String?> = _lastAiVerbatimError.asStateFlow()

    private val _aiDiagnosticReport = MutableStateFlow<AiSystemDiagnosticReport?>(null)
    val aiDiagnosticReport: StateFlow<AiSystemDiagnosticReport?> = _aiDiagnosticReport.asStateFlow()

    private val _isRunningAiDiagnostics = MutableStateFlow(false)
    val isRunningAiDiagnostics: StateFlow<Boolean> = _isRunningAiDiagnostics.asStateFlow()

    private val _aiDiagnosticTestResult = MutableStateFlow<AiDiagnosticTestResult?>(null)
    val aiDiagnosticTestResult: StateFlow<AiDiagnosticTestResult?> = _aiDiagnosticTestResult.asStateFlow()

    private val diagnosticAudioPlayer = OnnxAudioTrackPlayer()
    private var diagnosticAudioJob: Job? = null

    val voiceCloneTargetSampleRate: Int
        get() = com.example.audio.NeuCodecOnnxEngine.ENCODER_SAMPLE_RATE

    private val _readerState = MutableStateFlow(ActiveBookReaderState())
    val readerState: StateFlow<ActiveBookReaderState> = _readerState.asStateFlow()

    val audioPlaybackState: StateFlow<BookAudioPlaybackState> = AudioPlaybackStateHolder.state

    private var batchImportJob: Job? = null
    private var presetPreviewPreRenderJob: Job? = null

    init {
        // Chỉ nạp danh mục sách nhẹ trên Dispatchers.IO.
        // Tuyệt đối KHÔNG giải nén hay nạp model AI (~300MB) lúc mở app — chỉ nạp lười khi người dùng
        // bấm tạo giọng AI, phát sách nói AI hoặc mở hộp thoại Kiểm tra hệ thống AI.
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            libraryManager.ensureSampleBookSeeded()
            val (loadedBooks, loadedCollections) = libraryManager.loadLibraryIndex()
            val loadedFonts = libraryManager.loadCustomImportedFonts()
            val loadedCustomAmbient = libraryManager.loadCustomAmbientTracks()
            val loadedClones = clonedVoiceRepository.loadAll()
            val catalog = OnnxModelAssetHelper.inspectLightweightAiCatalog(getApplication())
            _books.value = loadedBooks
            _collections.value = loadedCollections
            _customFonts.value = loadedFonts
            _customAmbientTracks.value = loadedCustomAmbient
            _clonedVoices.value = loadedClones
            _presetAiVoices.value = catalog.presetVoices
            _aiModelStatusBadge.value = if (onnxVoiceEngineLazy.isInitialized() &&
                onnxVoiceEngine.engineMode != OnnxVoiceEngineMode.UNINITIALIZED
            ) {
                onnxVoiceEngine.getStatusBadgeSummary()
            } else {
                catalog.statusBadgeText
            }
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

    /**
     * Multi-book entry point from Book Library (`OpenMultipleDocuments`):
     * - 1 book -> preserves single-book workflow (Ask Clean -> Sửa tiếp / OK)
     * - 2+ books -> opens batch confirmation (Có, dọn tự động | Không, nhập trực tiếp)
     */
    fun onLibraryFilesPicked(uris: List<Uri>) {
        if (_isImporting.value || uris.isEmpty()) return
        val valid = uris.mapNotNull { uri ->
            FilePathResolver.inspectSourceFile(getApplication(), uri)?.let { source ->
                uri to source
            }
        }
        if (valid.isEmpty()) {
            _statusMessage.value = "Chỉ hỗ trợ nhập tệp .epub hoặc .pdf vào Thư viện sách."
            return
        }
        if (valid.size == 1) {
            _importFlowState.value = LibraryImportFlowState.AskCleanConfirmation(
                uri = valid.first().first,
                source = valid.first().second
            )
        } else {
            _importFlowState.value = LibraryImportFlowState.AskBatchCleanConfirmation(valid)
        }
    }

    fun startBatchImportIntoLibrary(
        cleanBeforeImport: Boolean,
        cleaningOptions: CleaningOptions
    ) {
        val current = _importFlowState.value as? LibraryImportFlowState.AskBatchCleanConfirmation ?: return
        val items = current.items
        val total = items.size
        _isImporting.value = true

        batchImportJob?.cancel()
        batchImportJob = viewModelScope.launch {
            var successCount = 0
            var failedCount = 0
            var totalFixesAccum = 0
            var cancelled = false
            val appCtx = getApplication<Application>()

            try {
                for ((idx, pair) in items.withIndex()) {
                    coroutineContext.ensureActive()
                    val (uri, source) = pair
                    _importFlowState.value = LibraryImportFlowState.BatchProcessing(
                        currentIndex = idx,
                        totalCount = total,
                        currentBookName = source.displayName,
                        currentBookProgress = 0.05f,
                        statusDetail = if (cleanBeforeImport) {
                            "Đang dọn sách ${idx + 1}/$total…"
                        } else {
                            "Đang nhập sách ${idx + 1}/$total…"
                        },
                        isCleaning = cleanBeforeImport,
                        successCount = successCount,
                        failedCount = failedCount
                    )

                    if (!cleanBeforeImport) {
                        val res = libraryManager.importBookFromUri(appCtx, uri)
                        if (res.isSuccess) {
                            successCount++
                            val (loadedBooks, loadedCollections) = withContext(Dispatchers.IO) {
                                libraryManager.loadLibraryIndex()
                            }
                            _books.value = loadedBooks
                            _collections.value = loadedCollections
                        } else {
                            failedCount++
                        }
                    } else {
                        var sessionDirToClean: File? = null
                        try {
                            val pipeline = EpubCleanPipeline(appCtx)
                            val meta = withContext(Dispatchers.IO) {
                                runCatching {
                                    EpubMetadataEditor.readMetadataFromSource(appCtx, source)
                                }.getOrNull()
                            }
                            coroutineContext.ensureActive()

                            val snap = pipeline.run(
                                source = source,
                                metadata = meta,
                                options = cleaningOptions,
                                onUpdate = { update ->
                                    _importFlowState.value = LibraryImportFlowState.BatchProcessing(
                                        currentIndex = idx,
                                        totalCount = total,
                                        currentBookName = source.displayName,
                                        currentBookProgress = update.progressFraction.coerceIn(0.05f, 0.88f),
                                        statusDetail = update.statusDetail.ifBlank { "Đang làm sạch tự động…" },
                                        isCleaning = true,
                                        successCount = successCount,
                                        failedCount = failedCount
                                    )
                                }
                            )
                            coroutineContext.ensureActive()

                            val workspace = snap.workspace
                            if (snap.phase != ProcessingPhase.COMPLETED || workspace == null) {
                                failedCount++
                                continue
                            }
                            sessionDirToClean = File(workspace.sessionDirPath)

                            _importFlowState.value = LibraryImportFlowState.BatchProcessing(
                                currentIndex = idx,
                                totalCount = total,
                                currentBookName = source.displayName,
                                currentBookProgress = 0.94f,
                                statusDetail = "Đang lưu vào Thư viện sách…",
                                isCleaning = true,
                                successCount = successCount,
                                failedCount = failedCount
                            )

                            val finalTempEpub = pipeline.rebuildEpubWithSelectedDiffs(
                                workspace = workspace,
                                diffs = snap.diffs,
                                regenerateToc = snap.regenerateToc
                            )

                            val importRes = libraryManager.importCleanedEpubFromFile(
                                context = appCtx,
                                cleanedEpubFile = finalTempEpub,
                                fallbackTitle = workspace.bookTitle
                            )
                            if (importRes.isSuccess) {
                                successCount++
                                totalFixesAccum += snap.stats.junkLinksRemoved +
                                    snap.stats.whitespaceFixes +
                                    snap.diffs.count { it.accepted } +
                                    snap.stats.mergedParagraphs +
                                    snap.stats.headersFootersRemoved +
                                    snap.stats.footnotesRemoved +
                                    snap.stats.unicodeSanitized +
                                    snap.stats.dialoguesFormatted

                                val (loadedBooks, loadedCollections) = withContext(Dispatchers.IO) {
                                    libraryManager.loadLibraryIndex()
                                }
                                _books.value = loadedBooks
                                _collections.value = loadedCollections
                            } else {
                                failedCount++
                            }
                        } catch (ce: CancellationException) {
                            cancelled = true
                            throw ce
                        } catch (_: Exception) {
                            failedCount++
                        } finally {
                            if (sessionDirToClean != null) {
                                withContext(Dispatchers.IO) {
                                    runCatching { sessionDirToClean.deleteRecursively() }
                                }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } finally {
                _isImporting.value = false
                _importFlowState.value = LibraryImportFlowState.BatchCompleted(
                    totalCount = total,
                    successCount = successCount,
                    failedCount = failedCount,
                    totalFixes = totalFixesAccum,
                    wasCleaned = cleanBeforeImport,
                    wasCancelled = cancelled
                )
            }
        }
    }

    fun cancelBatchImport() {
        batchImportJob?.cancel()
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
                val savedScannedChars = libraryManager.loadScannedCharacters(book.id)
                BookOpenBundle(updated, tocList, chapContent, chapIdx, paraIdx, savedCharVoices, savedScannedChars)
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
                scannedCharacters = loaded.savedScannedCharacters,
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
        val prefs = _readerPrefs.value
        if (prefs.ttsEngineType == TtsEngineType.AI_TTS) {
            // Chế độ AI Nhân bản -> chuyển qua lại giữa giọng nhân bản đã lưu và các giọng mẫu trong voices.json
            val allAiVoiceIds = _clonedVoices.value.map { it.id } + _presetAiVoices.value.map { it.storageId }
            if (allAiVoiceIds.isEmpty()) {
                _statusMessage.value = "Chưa có giọng AI. Hãy kiểm tra voices.json hoặc tạo giọng mới trong Studio."
                return
            }
            val idx = allAiVoiceIds.indexOfFirst { it == prefs.selectedClonedVoiceId }
            val nextId = allAiVoiceIds[(idx + 1).coerceAtLeast(0) % allAiVoiceIds.size]
            selectClonedVoice(nextId)
            return
        }
        // Chế độ Hệ thống -> CHỈ chuyển qua lại trong danh sách giọng Hệ thống
        val nextVoice: ReaderVoiceProfile = prefs.voiceProfile.next()
        selectNarratorVoice(nextVoice)
    }

    fun selectNarratorVoice(profile: ReaderVoiceProfile) {
        val updated = _readerPrefs.value.copy(voiceProfile = profile)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun selectClonedVoice(voiceId: String?) {
        val validId = voiceId?.takeIf { id ->
            _clonedVoices.value.any { it.id == id } ||
                _presetAiVoices.value.any { it.storageId == id } ||
                OnnxModelAssetHelper.extractPresetId(id) != null
        }
        val changed = _readerPrefs.value.selectedClonedVoiceId != validId
        val updated = _readerPrefs.value.copy(selectedClonedVoiceId = validId)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        if (changed && onnxVoiceEngineLazy.isInitialized()) {
            onnxVoiceEngine.clearVoiceKvCache("select_voice:$validId")
        }
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun importVoiceAudioFile(uri: Uri, onDecoded: (ImportedVoiceSample) -> Unit) {
        if (_isImportingVoiceFile.value) return
        _isImportingVoiceFile.value = true
        _lastAiVerbatimError.value = null
        viewModelScope.launch {
            try {
                val decoded = AudioFileDecoder.decodeAudioUriToMonoFloat(
                    context = getApplication(),
                    uri = uri,
                    targetSampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
                )
                decoded.onSuccess { sample ->
                    _draftClonedVoice.value = null
                    onDecoded(sample)
                    _statusMessage.value = "Đã nhập & chuẩn hóa 16kHz Mono: \"${sample.displayName}\""
                }.onFailure { err ->
                    val fullErr = OnnxModelAssetHelper.formatFullThrowable(err)
                    _lastAiVerbatimError.value = "Lỗi MediaCodec / giải mã tệp âm thanh:\n$fullErr"
                    _statusMessage.value = err.message ?: "Không thể đọc tệp âm thanh này."
                }
            } finally {
                _isImportingVoiceFile.value = false
            }
        }
    }

    /**
     * Bước "Tạo" trong Studio (nhân bản giọng thật 100% offline bằng `neucodec_encoder.onnx`):
     * 1. Cắt đoạn nói sạch nhất 3–8 giây (tối ưu 6 giây), giải mã 16 kHz Mono PCM.
     * 2. Chạy `neucodec_encoder.onnx` (+ `speaker_encoder.onnx` nếu có) để trích xuất `ref_codes` & `speaker_embedding` thật.
     * 3. Tổng hợp sẵn câu nghe thử bằng GGUF (`temperature = 0.8`) + `neucodec_decoder.onnx` 24kHz và lưu cache WAV.
     */
    fun createDraftClonedVoice(
        name: String,
        samples: FloatArray,
        sampleRate: Int,
        referenceText: String,
        onCreated: () -> Unit = {}
    ) {
        if (_isExtractingVoiceClone.value || samples.isEmpty()) return
        val userRefText = referenceText.trim()
        _isExtractingVoiceClone.value = true
        _lastAiVerbatimError.value = null
        viewModelScope.launch {
            try {
                // Chặn ngay âm thanh im lặng (RMS < 0.001f) trước khi chuẩn hóa & mã hóa
                val rawRms = withContext(Dispatchers.IO) { AudioFileDecoder.computeRms(samples) }
                if (rawRms < AudioFileDecoder.MIN_VALID_AUDIO_RMS) {
                    _draftClonedVoice.value = null
                    _lastAiVerbatimError.value = AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
                    _statusMessage.value = AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
                    return@launch
                }

                val cleanSamples16k = withContext(Dispatchers.IO) {
                    val alreadyPrepared16k = sampleRate == AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE &&
                        samples.size in AudioFileDecoder.CLONE_MIN_SAMPLES_16K..AudioFileDecoder.CLONE_MAX_SAMPLES_16K
                    if (alreadyPrepared16k) {
                        samples
                    } else {
                        val mono16k = if (sampleRate != AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE) {
                            AudioFileDecoder.normalizeAndResampleMonoFloat(
                                monoFloat = samples,
                                sourceRate = sampleRate,
                                targetRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
                            )
                        } else {
                            samples
                        }
                        AudioFileDecoder.extractCleanestSpeechWindow16k(
                            samples = mono16k,
                            sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE,
                            minSeconds = AudioFileDecoder.CLONE_MIN_WINDOW_SECONDS,
                            maxSeconds = AudioFileDecoder.CLONE_MAX_WINDOW_SECONDS,
                            trimEdgeMicNoiseSeconds = AudioFileDecoder.MAX_EDGE_SILENCE_TRIM_SECONDS
                        )
                    }
                }
                val extracted = onnxVoiceEngine.extractSpeakerEmbedding(
                    samples = cleanSamples16k,
                    sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE,
                    alreadyPreprocessed = true
                )
                _voiceCloneEngineMode.value = onnxVoiceEngine.engineMode
                _presetAiVoices.value = onnxVoiceEngine.getPresetVoices()
                _aiModelStatusBadge.value = onnxVoiceEngine.getStatusBadgeSummary()

                val refCodes = extracted.referenceCodes
                val invalidCodes = !extracted.usedOnnxEncoder ||
                    extracted.embedding.isEmpty() ||
                    refCodes == null ||
                    refCodes.size < com.example.audio.NeuCodecOnnxEngine.MIN_VALID_REF_CODES ||
                    refCodes.all { it == 0 } ||
                    extracted.uniqueTokenCount < com.example.audio.NeuCodecOnnxEngine.MIN_UNIQUE_REF_CODES
                if (invalidCodes) {
                    _draftClonedVoice.value = null
                    val err = extracted.errorMessage ?: AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
                    _lastAiVerbatimError.value = err
                    _statusMessage.value = err.lineSequence().firstOrNull() ?: AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
                    return@launch
                }

                val computedRefTextIpa = withContext(Dispatchers.IO) {
                    onnxVoiceEngine.phonemizeReferenceText(userRefText)
                }
                val canonicalRefSpeechTokens = extracted.refSpeechTokens.ifBlank {
                    com.example.audio.VieNeuGgufRunner.formatRefSpeechTokens(refCodes)
                }

                val cleanName = name.trim().ifBlank {
                    "Giọng nhân bản #${_clonedVoices.value.size + 1}"
                }.take(36)
                val durationSec = extracted.durationSeconds.takeIf { it > 0.5f }
                    ?: (cleanSamples16k.size.toFloat() / AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE.toFloat())
                        .coerceIn(4.0f, 7.0f)

                var synthPreviewNote: String? = null
                var usedSynthPreview = false
                val draftVoiceId = "draft_${System.currentTimeMillis()}"
                val draftWavPath = withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "voice_previews").apply { mkdirs() }
                    val outWav = File(dir, "$draftVoiceId.wav")
                    // Xóa sạch KV-Cache của giọng cũ trước khi tổng hợp câu nghe thử cho giọng mới!
                    onnxVoiceEngine.clearVoiceKvCache("create_draft_$draftVoiceId")
                    if (onnxVoiceEngine.isFullOnnxSynthesisReady()) {
                        val synthRes = onnxVoiceEngine.synthesizeMultiSentencePreviewDetailed(
                            passage = OnnxModelAssetHelper.AI_VOICE_QUICK_PREVIEW_SENTENCE,
                            speakerEmbedding = extracted.embedding,
                            speed = 1.0f,
                            referenceCodes = refCodes,
                            referenceText = userRefText,
                            matchedPresetId = null,
                            voiceId = draftVoiceId
                        )
                        val synthAudio = synthRes.audio
                        if (synthAudio != null && synthAudio.pcmFloat.isNotEmpty()) {
                            AudioFileDecoder.writePcm16MonoWav(outWav, synthAudio.pcmFloat, synthAudio.sampleRate)
                            usedSynthPreview = true
                            return@withContext outWav.absolutePath
                        } else {
                            synthPreviewNote = synthRes.errorMessage ?: "GGUF không sinh ra sóng âm 24kHz hợp lệ."
                        }
                    } else {
                        synthPreviewNote = onnxVoiceEngine.getReadinessDiagnostic()
                    }
                    AudioFileDecoder.writePcm16MonoWav(
                        outWav,
                        cleanSamples16k,
                        AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
                    )
                    outWav.absolutePath
                }

                _draftClonedVoice.value = PendingDraftClonedVoice(
                    name = cleanName,
                    samples = cleanSamples16k,
                    sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE,
                    durationSeconds = durationSec,
                    embedding = extracted.embedding,
                    referenceCodes = refCodes,
                    refSpeechTokens = canonicalRefSpeechTokens,
                    referenceText = userRefText,
                    referenceTextIpa = computedRefTextIpa,
                    first10Tokens = extracted.first10Tokens,
                    minToken = extracted.minToken,
                    maxToken = extracted.maxToken,
                    uniqueTokenCount = extracted.uniqueTokenCount,
                    encoderIoSummary = extracted.encoderIoSummary,
                    usedOnnxEncoder = true,
                    acousticSummary = extracted.acousticSummary,
                    previewWavPath = draftWavPath,
                    synthesizedPreview = usedSynthPreview
                )

                val combinedDiagErr = listOfNotNull(
                    extracted.errorMessage?.takeIf { it.isNotBlank() },
                    synthPreviewNote?.takeIf { it.isNotBlank() }
                ).joinToString("\n\n")
                _lastAiVerbatimError.value = combinedDiagErr.ifBlank { null }

                val codeCount = refCodes.size
                _statusMessage.value = when {
                    usedSynthPreview ->
                        "Đã nhân bản giọng thật \"$cleanName (${codeCount}c)\" — Bấm Nghe thử rồi bấm Lưu."
                    !synthPreviewNote.isNullOrBlank() ->
                        "Đã trích xuất ${codeCount}c mã giọng thật, tổng hợp GGUF báo lỗi (xem log bên dưới)."
                    else ->
                        "Đã tạo giọng thật \"$cleanName (${codeCount}c)\" — Bấm Nghe thử rồi bấm Lưu."
                }
                onCreated()
            } catch (e: Throwable) {
                val fullErr = OnnxModelAssetHelper.formatFullThrowable(e)
                _lastAiVerbatimError.value = "Lỗi tạo giọng VieNeu GGUF + NeuCodec:\n$fullErr"
                _statusMessage.value = "Không thể tạo giọng nhân bản: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isExtractingVoiceClone.value = false
            }
        }
    }

    fun clearDraftClonedVoice() {
        _draftClonedVoice.value = null
    }

    fun clearLastAiVerbatimError() {
        _lastAiVerbatimError.value = null
    }

    // -------------------------------------------------------------------------
    // AI System Diagnostics ("Kiểm tra hệ thống AI")
    // -------------------------------------------------------------------------

    fun runAiSystemDiagnostics(forceResync: Boolean = false) {
        if (_isRunningAiDiagnostics.value) return
        _isRunningAiDiagnostics.value = true
        viewModelScope.launch {
            try {
                val report = onnxVoiceEngine.runFullSystemDiagnostics(forceResync = forceResync)
                _aiDiagnosticReport.value = report
                _voiceCloneEngineMode.value = report.engineMode
                _presetAiVoices.value = onnxVoiceEngine.getPresetVoices()
                _aiModelStatusBadge.value = onnxVoiceEngine.getStatusBadgeSummary()
            } catch (e: Throwable) {
                _aiDiagnosticTestResult.value = AiDiagnosticTestResult(
                    title = "Quét hệ thống AI",
                    success = false,
                    elapsedMs = 0L,
                    detailLog = "NGOẠI LỆ NGUYÊN VĂN:\n${OnnxModelAssetHelper.formatFullThrowable(e)}"
                )
            } finally {
                _isRunningAiDiagnostics.value = false
            }
        }
    }

    fun runAiDiagnosticMediaCodecTest(uri: Uri) {
        if (_isRunningAiDiagnostics.value) return
        _isRunningAiDiagnostics.value = true
        viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            try {
                val res = AudioFileDecoder.decodeAudioUriToMonoFloat(
                    context = getApplication(),
                    uri = uri,
                    targetSampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
                )
                val elapsed = System.currentTimeMillis() - startMs
                res.onSuccess { sample ->
                    val wavSize = File(sample.previewWavPath).length()
                    _aiDiagnosticTestResult.value = AiDiagnosticTestResult(
                        title = "Thử MediaCodec (${sample.displayName})",
                        success = true,
                        elapsedMs = elapsed,
                        detailLog = buildString {
                            appendLine("Trạng thái: THÀNH CÔNG")
                            appendLine("Thời gian giải mã: ${elapsed} ms")
                            appendLine("Tên file: ${sample.displayName}")
                            appendLine("Đầu ra chuẩn hóa: ${sample.samples.size} mẫu Float32 Mono @ ${sample.sampleRate} Hz")
                            appendLine("Thời lượng: ${String.format(java.util.Locale.US, "%.2f", sample.durationSeconds)} giây")
                            appendLine("File WAV 16-bit PCM: ${sample.previewWavPath} ($wavSize bytes)")
                        }.trimEnd()
                    )
                }.onFailure { err ->
                    _aiDiagnosticTestResult.value = AiDiagnosticTestResult(
                        title = "Thử MediaCodec (giải mã âm thanh)",
                        success = false,
                        elapsedMs = elapsed,
                        detailLog = "LỖI MEDIACODEC / EXTRACTOR NGUYÊN VĂN:\n${OnnxModelAssetHelper.formatFullThrowable(err)}"
                    )
                }
            } finally {
                _isRunningAiDiagnostics.value = false
            }
        }
    }

    fun runAiDiagnosticVoiceCreateTest() {
        if (_isRunningAiDiagnostics.value) return
        _isRunningAiDiagnostics.value = true
        viewModelScope.launch {
            try {
                val draftSamples = _draftClonedVoice.value?.samples
                val res = onnxVoiceEngine.runDiagnosticVoiceCreateTest(
                    sampleFloat16k = draftSamples,
                    sampleRate = AudioFileDecoder.SPEAKER_ENCODER_SAMPLE_RATE
                )
                _aiDiagnosticTestResult.value = res
                _aiDiagnosticReport.value = onnxVoiceEngine.runFullSystemDiagnostics(forceResync = false)
            } finally {
                _isRunningAiDiagnostics.value = false
            }
        }
    }

    fun runAiDiagnosticSentenceReadTest() {
        if (_isRunningAiDiagnostics.value) return
        stopAiDiagnosticAudio()
        _isRunningAiDiagnostics.value = true
        viewModelScope.launch {
            try {
                data class DiagVoiceInput(
                    val emb: FloatArray?,
                    val codes: IntArray?,
                    val refText: String?,
                    val presetId: String?
                )
                val voiceInput = withContext(Dispatchers.IO) {
                    val draft = _draftClonedVoice.value
                    if (draft != null) {
                        DiagVoiceInput(
                            emb = draft.embedding,
                            codes = draft.referenceCodes,
                            refText = draft.referenceText,
                            presetId = null
                        )
                    } else {
                        val selId = _readerPrefs.value.selectedClonedVoiceId
                        val presetId = OnnxModelAssetHelper.extractPresetId(selId)
                        if (presetId != null) {
                            onnxVoiceEngine.ensureInitialized()
                            val preset = onnxVoiceEngine.findPresetVoiceById(presetId)
                            DiagVoiceInput(
                                emb = preset?.voiceEmbedding,
                                codes = preset?.referenceCodes,
                                refText = preset?.referenceText,
                                presetId = preset?.id
                            )
                        } else {
                            val prof = selId?.let { id -> clonedVoiceRepository.findById(id) }
                            if (prof != null) {
                                DiagVoiceInput(
                                    emb = clonedVoiceRepository.loadEmbedding(prof),
                                    codes = clonedVoiceRepository.loadReferenceCodes(prof),
                                    refText = prof.referenceText,
                                    presetId = prof.matchedPresetId.takeIf { it.isNotBlank() }
                                )
                            } else {
                                DiagVoiceInput(null, null, null, null)
                            }
                        }
                    }
                }
                val (res, audio) = onnxVoiceEngine.runDiagnosticSentenceReadTest(
                    sentence = OnnxModelAssetHelper.AI_VOICE_TEST_SENTENCE,
                    speakerEmbedding = voiceInput.emb,
                    referenceCodes = voiceInput.codes,
                    referenceText = voiceInput.refText,
                    matchedPresetId = voiceInput.presetId
                )
                _aiDiagnosticTestResult.value = res
                _aiDiagnosticReport.value = onnxVoiceEngine.runFullSystemDiagnostics(forceResync = false)
                _presetAiVoices.value = onnxVoiceEngine.getPresetVoices()
                _aiModelStatusBadge.value = onnxVoiceEngine.getStatusBadgeSummary()

                if (audio != null) {
                    diagnosticAudioJob = viewModelScope.launch(Dispatchers.IO) {
                        diagnosticAudioPlayer.playBlocking(
                            samples = audio.pcmFloat,
                            sampleRate = audio.sampleRate,
                            speed = 1.0f
                        )
                    }
                }
            } finally {
                _isRunningAiDiagnostics.value = false
            }
        }
    }

    fun stopAiDiagnosticAudio() {
        diagnosticAudioJob?.cancel()
        diagnosticAudioJob = null
        diagnosticAudioPlayer.stopImmediately()
    }

    /**
     * Bước "Lưu" trong Studio: lưu vĩnh viễn giọng nhân bản vừa tạo cùng toàn bộ danh sách giọng đọc.
     */
    fun commitSaveClonedVoices(onSaved: () -> Unit = {}) {
        if (_isExtractingVoiceClone.value) return
        val draft = _draftClonedVoice.value
        if (draft == null) {
            libraryManager.saveReaderPreferences(_readerPrefs.value)
            BookAudioPlaybackService.syncSettings(getApplication())
            _statusMessage.value = "Đã lưu cài đặt tất cả giọng đọc (${_clonedVoices.value.size} giọng AI)"
            onSaved()
            return
        }
        val draftCodes = draft.referenceCodes
        if (draftCodes == null || draftCodes.size < com.example.audio.NeuCodecOnnxEngine.MIN_VALID_REF_CODES || draftCodes.all { it == 0 }) {
            _draftClonedVoice.value = null
            _statusMessage.value = AudioFileDecoder.INVALID_AUDIO_USER_MESSAGE
            return
        }
        _isExtractingVoiceClone.value = true
        viewModelScope.launch {
            try {
                val savedProfile = withContext(Dispatchers.IO) {
                    val saved = clonedVoiceRepository.saveVoice(
                        rawName = draft.name,
                        samples = draft.samples,
                        sampleRate = draft.sampleRate,
                        embedding = draft.embedding,
                        usedOnnxEncoder = draft.usedOnnxEncoder,
                        referenceCodes = draftCodes,
                        referenceText = draft.referenceText,
                        referenceTextIpa = draft.referenceTextIpa,
                        matchedPresetId = ""
                    )
                    if (draft.synthesizedPreview) {
                        VoicePreviewCache.copyDraftPreviewToSavedVoice(
                            context = getApplication(),
                            draftWavPath = draft.previewWavPath,
                            savedVoiceId = saved.id
                        )
                    }
                    saved
                }
                val refreshed = withContext(Dispatchers.IO) { clonedVoiceRepository.loadAll() }
                _clonedVoices.value = refreshed
                _draftClonedVoice.value = null
                val updatedPrefs = _readerPrefs.value.copy(selectedClonedVoiceId = savedProfile.id)
                _readerPrefs.value = updatedPrefs
                libraryManager.saveReaderPreferences(updatedPrefs)
                if (onnxVoiceEngineLazy.isInitialized()) {
                    onnxVoiceEngine.clearVoiceKvCache("saved_new_voice:${savedProfile.id}")
                }
                BookAudioPlaybackService.syncSettings(getApplication())
                if (!VoicePreviewCache.hasValidPreview(getApplication(), savedProfile.id)) {
                    ensureClonedVoicePreviewCachedAsync(savedProfile)
                }
                _statusMessage.value = "Đã lưu giọng \"${savedProfile.displayNameWithCodeCount}\" và cập nhật tất cả giọng đọc"
                onSaved()
            } catch (e: Exception) {
                _statusMessage.value = "Không thể lưu giọng nhân bản: ${e.message ?: "lỗi xử lý"}"
            } finally {
                _isExtractingVoiceClone.value = false
            }
        }
    }

    /**
     * Tạo sẵn (pre-render) tệp nghe thử `preview_<voice_id>.wav` ở luồng nền (Dispatchers.IO)
     * cho các giọng mẫu `voices.json` và các giọng nhân bản chưa có cache để khi người dùng bấm
     * "Nghe thử" sẽ phát tiếng NGAY LẬP TỨC (<50ms). Tự động nhường tài nguyên khi đang đọc sách hoặc tạo giọng.
     */
    fun preRenderPresetPreviewsIfNeeded() {
        if (presetPreviewPreRenderJob?.isActive == true) return
        presetPreviewPreRenderJob = viewModelScope.launch(Dispatchers.IO) {
            val appCtx = getApplication<Application>()
            val presets = _presetAiVoices.value.ifEmpty {
                OnnxModelAssetHelper.inspectLightweightAiCatalog(appCtx).presetVoices
            }
            val clones = _clonedVoices.value.ifEmpty {
                clonedVoiceRepository.loadAll()
            }
            val missingPresets = presets.filter { !VoicePreviewCache.hasValidPreview(appCtx, it.storageId) }
            val missingClones = clones.filter { !VoicePreviewCache.hasValidPreview(appCtx, it.id) }
            if (missingPresets.isEmpty() && missingClones.isEmpty()) return@launch

            // Chờ nhẹ để hiệu ứng mở tab/sheet hoàn tất mượt mà trước khi nạp lười model trên IO
            delay(500L)

            fun shouldYield(): Boolean {
                val playState = AudioPlaybackStateHolder.state.value
                return playState.isPlaying ||
                    playState.isPreviewingVoice ||
                    _isExtractingVoiceClone.value ||
                    _isRunningAiDiagnostics.value ||
                    onnxVoiceEngine.isBusy
            }

            if (shouldYield()) return@launch
            onnxVoiceEngine.ensureInitialized()
            if (!onnxVoiceEngine.isFullOnnxSynthesisReady()) return@launch

            for (clone in missingClones) {
                coroutineContext.ensureActive()
                if (shouldYield()) return@launch
                if (VoicePreviewCache.hasValidPreview(appCtx, clone.id)) continue
                val emb = clonedVoiceRepository.loadEmbedding(clone) ?: FloatArray(0)
                val codes = clonedVoiceRepository.loadReferenceCodes(clone)
                val synth = onnxVoiceEngine.synthesizeMultiSentencePreviewDetailed(
                    passage = VoicePreviewCache.INSTANT_PREVIEW_SENTENCE,
                    speakerEmbedding = emb,
                    speed = 1.0f,
                    referenceCodes = codes,
                    referenceText = clone.referenceText,
                    matchedPresetId = clone.matchedPresetId.takeIf { it.isNotBlank() },
                    voiceId = clone.id
                )
                synth.audio?.let { audio ->
                    VoicePreviewCache.savePreviewPcm(appCtx, clone.id, audio.pcmFloat, audio.sampleRate)
                }
                delay(150L)
            }

            for (preset in missingPresets) {
                coroutineContext.ensureActive()
                if (shouldYield()) return@launch
                if (VoicePreviewCache.hasValidPreview(appCtx, preset.storageId)) continue
                onnxVoiceEngine.ensurePresetPreviewCached(preset.id)
                delay(150L)
            }
        }
    }

    private fun ensureClonedVoicePreviewCachedAsync(profile: ClonedVoiceProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val appCtx = getApplication<Application>()
            if (VoicePreviewCache.hasValidPreview(appCtx, profile.id)) return@launch
            onnxVoiceEngine.ensureInitialized()
            if (!onnxVoiceEngine.isFullOnnxSynthesisReady()) return@launch
            val emb = clonedVoiceRepository.loadEmbedding(profile) ?: FloatArray(0)
            val codes = clonedVoiceRepository.loadReferenceCodes(profile)
            val synth = onnxVoiceEngine.synthesizeMultiSentencePreviewDetailed(
                passage = VoicePreviewCache.INSTANT_PREVIEW_SENTENCE,
                speakerEmbedding = emb,
                speed = 1.0f,
                referenceCodes = codes,
                referenceText = profile.referenceText,
                matchedPresetId = profile.matchedPresetId.takeIf { it.isNotBlank() },
                voiceId = profile.id
            )
            synth.audio?.let { audio ->
                VoicePreviewCache.savePreviewPcm(appCtx, profile.id, audio.pcmFloat, audio.sampleRate)
            }
        }
    }

    fun deleteClonedVoice(voiceId: String) {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                VoicePreviewCache.deletePreview(getApplication(), voiceId)
                clonedVoiceRepository.deleteVoice(voiceId)
            }
            _clonedVoices.value = remaining
            if (_readerPrefs.value.selectedClonedVoiceId == voiceId) {
                val nextSelected = remaining.firstOrNull()?.id
                val updated = _readerPrefs.value.copy(selectedClonedVoiceId = nextSelected)
                _readerPrefs.value = updated
                libraryManager.saveReaderPreferences(updated)
                BookAudioPlaybackService.syncSettings(getApplication())
            }
            _statusMessage.value = "Đã xóa giọng nhân bản"
        }
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

    fun selectReaderFont(fontId: String) {
        val cleanId = fontId.ifBlank { LibraryManager.DEFAULT_FONT_ID }
        val updated = _readerPrefs.value.copy(fontId = cleanId)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun importCustomFont(uri: Uri) {
        viewModelScope.launch {
            val result = libraryManager.importCustomFontFromUri(getApplication(), uri)
            result.onSuccess { imported ->
                val refreshed = withContext(Dispatchers.IO) {
                    libraryManager.loadCustomImportedFonts()
                }
                _customFonts.value = refreshed
                selectReaderFont(imported.id)
                _statusMessage.value = "Đã nhập font \"${imported.displayName}\""
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Không thể nhập tệp font này (.ttf / .otf)."
            }
        }
    }

    fun deleteCustomFont(fontId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetPath = _customFonts.value.firstOrNull { it.id == fontId }?.filePath
            val remaining = libraryManager.deleteCustomImportedFont(fontId)
            if (targetPath != null) {
                ReaderFontRegistry.evictCachedFont(targetPath)
            }
            _customFonts.value = remaining
            if (_readerPrefs.value.fontId == fontId) {
                val updated = _readerPrefs.value.copy(fontId = LibraryManager.DEFAULT_FONT_ID)
                _readerPrefs.value = updated
                libraryManager.saveReaderPreferences(updated)
            }
        }
    }

    fun selectHorizontalPageEffect(effect: HorizontalPageEffect) {
        val updated = _readerPrefs.value.copy(horizontalPageEffect = effect)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun setCharacterVoicesEnabled(enabled: Boolean) {
        val updated = _readerPrefs.value.copy(characterVoicesEnabled = enabled)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun selectTtsEngineType(engineType: TtsEngineType) {
        BookAudioPlaybackService.stopPreview(getApplication())
        val current = _readerPrefs.value
        val resolvedCloneId = if (engineType == TtsEngineType.AI_TTS) {
            val existing = current.selectedClonedVoiceId
            val isExistingValid = existing != null && (
                _clonedVoices.value.any { it.id == existing } ||
                    _presetAiVoices.value.any { it.storageId == existing }
                )
            when {
                isExistingValid -> existing
                _clonedVoices.value.isNotEmpty() -> _clonedVoices.value.first().id
                _presetAiVoices.value.isNotEmpty() -> _presetAiVoices.value.first().storageId
                else -> null
            }
        } else {
            current.selectedClonedVoiceId
        }
        val updated = current.copy(
            ttsEngineType = engineType,
            selectedClonedVoiceId = resolvedCloneId
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
        if (engineType == TtsEngineType.AI_TTS && _clonedVoices.value.isEmpty() && _presetAiVoices.value.isEmpty()) {
            _statusMessage.value = "Đã chọn AI Nhân bản (tắt giọng hệ thống). Hãy kiểm tra voices.json hoặc tạo giọng mẫu trong Studio."
        }
    }

    fun setAutoPlayAmbientWithAudio(enabled: Boolean) {
        val updated = _readerPrefs.value.copy(autoPlayAmbientWithAudio = enabled)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun selectAmbientSound(track: AmbientSoundTrack) {
        val updated = _readerPrefs.value.copy(
            ambientSound = track,
            selectedCustomAmbientId = null
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun selectCustomAmbientTrack(trackId: String) {
        val updated = _readerPrefs.value.copy(
            ambientSound = AmbientSoundTrack.NONE,
            selectedCustomAmbientId = trackId
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
        BookAudioPlaybackService.syncSettings(getApplication())
    }

    fun importCustomAmbientTrack(uri: Uri) {
        if (_isImportingAmbient.value) return
        _isImportingAmbient.value = true
        viewModelScope.launch {
            try {
                val result = libraryManager.importCustomAmbientFromUri(getApplication(), uri)
                result.onSuccess { imported ->
                    val refreshed = withContext(Dispatchers.IO) {
                        libraryManager.loadCustomAmbientTracks()
                    }
                    _customAmbientTracks.value = refreshed
                    selectCustomAmbientTrack(imported.id)
                    _statusMessage.value = "Đã import nhạc nền \"${imported.displayName}\""
                }.onFailure { err ->
                    _statusMessage.value = err.message ?: "Không thể import tệp nhạc nền này."
                }
            } finally {
                _isImportingAmbient.value = false
            }
        }
    }

    fun deleteCustomAmbientTrack(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val remaining = libraryManager.deleteCustomAmbientTrack(trackId)
            _customAmbientTracks.value = remaining
            if (_readerPrefs.value.selectedCustomAmbientId == trackId) {
                val updated = _readerPrefs.value.copy(
                    selectedCustomAmbientId = null,
                    ambientSound = AmbientSoundTrack.NONE
                )
                _readerPrefs.value = updated
                libraryManager.saveReaderPreferences(updated)
                BookAudioPlaybackService.syncSettings(getApplication())
            }
        }
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

    fun toggleCharacterVoicePicker() {
        _readerState.update { state ->
            if (state.scannedCharacters.isEmpty()) {
                state
            } else {
                state.copy(showCharacterVoicePicker = !state.showCharacterVoicePicker)
            }
        }
    }

    fun hideCharacterVoicePicker() {
        _readerState.update { it.copy(showCharacterVoicePicker = false) }
    }

    override fun onCleared() {
        super.onCleared()
        presetPreviewPreRenderJob?.cancel()
        stopAiDiagnosticAudio()
        if (onnxVoiceEngineLazy.isInitialized()) {
            runCatching { onnxVoiceEngine.cancelActiveGeneration() }
            runCatching { onnxVoiceEngine.releaseEncoderMemory() }
        }
    }

    private data class BookOpenBundle(
        val updatedBooks: List<LibraryBook>,
        val toc: List<LibraryChapterMeta>,
        val content: com.example.data.LibraryChapterContent?,
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val savedCharacterVoices: Map<String, ReaderVoiceProfile>,
        val savedScannedCharacters: List<DetectedCharacter>
    )
}
