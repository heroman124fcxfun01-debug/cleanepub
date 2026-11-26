package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CleanedFileHistory
import com.example.data.CustomReplaceManager
import com.example.data.CustomReplaceRule
import com.example.data.DraftManager
import com.example.data.ErrorMemoryManager
import com.example.data.FileEditStateSnapshot
import com.example.data.FileRecordStatus
import com.example.data.HistoryManager
import com.example.data.ManualEditorPreferences
import com.example.data.ThemePreferences
import com.example.pipeline.ChapterDescriptor
import com.example.pipeline.CleaningOptions
import com.example.pipeline.EditableTocItem
import com.example.pipeline.EpubCleanPipeline
import com.example.pipeline.EpubMetadata
import com.example.pipeline.EpubMetadataEditor
import com.example.pipeline.EpubWorkspaceManager
import com.example.pipeline.FilePathResolver
import com.example.pipeline.ManualChapterContent
import com.example.pipeline.ManualEditorMode
import com.example.pipeline.ManualReadingMode
import com.example.pipeline.ManualSearchMatch
import com.example.pipeline.ManualTocEntry
import com.example.pipeline.PreparedWorkspace
import com.example.pipeline.ProcessingPhase
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SaveMode
import com.example.pipeline.SaveResult
import com.example.pipeline.SourceFileInfo
import com.example.pipeline.TextCleaningEngine
import com.example.pipeline.TocGenerator
import com.example.service.EpubCleanForegroundService
import com.example.service.ProcessingStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "MainViewModel"

sealed interface SaveUiState {
    data object Idle : SaveUiState
    data object Saving : SaveUiState
    data class Completed(val result: SaveResult.Success) : SaveUiState
    data class Error(val message: String) : SaveUiState
}

data class PendingImportMetadata(
    val source: SourceFileInfo,
    val metadata: EpubMetadata
)

sealed interface BatchCleanUiState {
    data object Idle : BatchCleanUiState
    data class Running(
        val currentIndex: Int,
        val totalCount: Int,
        val currentBookName: String,
        val currentBookProgress: Float,
        val statusDetail: String,
        val completedCount: Int,
        val failedCount: Int,
        val totalFixesSoFar: Int
    ) : BatchCleanUiState

    data class Completed(
        val totalCount: Int,
        val successCount: Int,
        val failedCount: Int,
        val totalFixes: Int,
        val failedFileNames: List<String>,
        val wasCancelled: Boolean
    ) : BatchCleanUiState
}

data class ManualEditorUiState(
    val isLoading: Boolean = false,
    val mode: ManualEditorMode = ManualEditorMode.KEEP_CLEANED,
    val chapters: List<ManualChapterContent> = emptyList(),
    val currentChapterIndex: Int = 0,
    val activeParagraphIndex: Int = 0,
    val scrollTargetParagraphIndex: Int = 0,
    val scrollRequestId: Long = 0L,
    val manualTocEntries: List<ManualTocEntry> = emptyList(),
    val editableTocItems: List<EditableTocItem> = emptyList(),
    val fontSizeSp: Float = 17f,
    val readingMode: ManualReadingMode = ManualReadingMode.VERTICAL_SCROLL,
    val skipUndoConfirm: Boolean = false,
    val isEditMode: Boolean = false,
    val isCompareMode: Boolean = false,
    val searchQuery: String = "",
    val replaceQuery: String = "",
    val searchMatches: List<ManualSearchMatch> = emptyList(),
    val activeMatchIndex: Int = -1,
    val canUndo: Boolean = false,
    val manualEditCount: Int = 0,
    val feedbackMessage: String? = null
) {
    val activeChapter: ManualChapterContent?
        get() = chapters.getOrNull(currentChapterIndex)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val themePreferences = ThemePreferences(appContext)
    private val manualEditorPreferences = ManualEditorPreferences(appContext)
    private val historyManager = HistoryManager(appContext)
    private val draftManager = DraftManager(appContext)
    private val customReplaceManager = CustomReplaceManager(appContext)
    private val errorMemoryManager = ErrorMemoryManager(appContext)

    private val _recentFiles = MutableStateFlow(historyManager.getHistory())
    val recentFiles: StateFlow<List<CleanedFileHistory>> = _recentFiles.asStateFlow()

    val processingSnapshot: StateFlow<ProcessingSnapshot> = ProcessingStateHolder.snapshot

    private val _isDarkMode = MutableStateFlow(themePreferences.isDarkMode())
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _autoSaveDraft = MutableStateFlow(manualEditorPreferences.isAutoSaveDraft())
    val autoSaveDraft: StateFlow<Boolean> = _autoSaveDraft.asStateFlow()

    private val _isResumingDraft = MutableStateFlow(false)
    val isResumingDraft: StateFlow<Boolean> = _isResumingDraft.asStateFlow()

    private val _customReplaceRules = MutableStateFlow(customReplaceManager.getRules())
    val customReplaceRules: StateFlow<List<CustomReplaceRule>> = _customReplaceRules.asStateFlow()

    private val _errorMemoryAutoApply = MutableStateFlow(errorMemoryManager.isAutoApplyEnabled())
    val errorMemoryAutoApply: StateFlow<Boolean> = _errorMemoryAutoApply.asStateFlow()

    private val _errorMemoryRuleCount = MutableStateFlow(errorMemoryManager.getRuleCount())
    val errorMemoryRuleCount: StateFlow<Int> = _errorMemoryRuleCount.asStateFlow()

    private val _statusToastMessage = MutableStateFlow<String?>(null)
    val statusToastMessage: StateFlow<String?> = _statusToastMessage.asStateFlow()

    private val _cleaningOptions = MutableStateFlow(CleaningOptions())
    val cleaningOptions: StateFlow<CleaningOptions> = _cleaningOptions.asStateFlow()

    private val _hasAllFilesAccess = MutableStateFlow(FilePathResolver.hasAllFilesAccess(appContext))
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _saveUiState = MutableStateFlow<SaveUiState>(SaveUiState.Idle)
    val saveUiState: StateFlow<SaveUiState> = _saveUiState.asStateFlow()

    private val _homeErrorBanner = MutableStateFlow<String?>(null)
    val homeErrorBanner: StateFlow<String?> = _homeErrorBanner.asStateFlow()

    private val _isLoadingMetadata = MutableStateFlow(false)
    val isLoadingMetadata: StateFlow<Boolean> = _isLoadingMetadata.asStateFlow()

    private val _pendingImport = MutableStateFlow<PendingImportMetadata?>(null)
    val pendingImport: StateFlow<PendingImportMetadata?> = _pendingImport.asStateFlow()

    private val _customSaveTreeUri = MutableStateFlow<Uri?>(null)
    private val _customSaveFolderLabel = MutableStateFlow<String?>(null)
    val customSaveFolderLabel: StateFlow<String?> = _customSaveFolderLabel.asStateFlow()

    private val _manualEditorState = MutableStateFlow(
        ManualEditorUiState(
            readingMode = manualEditorPreferences.getReadingMode(),
            skipUndoConfirm = manualEditorPreferences.isSkipUndoConfirm()
        )
    )
    val manualEditorState: StateFlow<ManualEditorUiState> = _manualEditorState.asStateFlow()

    private val _batchCleanState = MutableStateFlow<BatchCleanUiState>(BatchCleanUiState.Idle)
    val batchCleanState: StateFlow<BatchCleanUiState> = _batchCleanState.asStateFlow()

    private val manualUndoStack = ArrayDeque<List<ManualChapterContent>>()
    private var lastUndoChapterIdx: Int = -1
    private var lastUndoParaIdx: Int = -1
    private var lastUndoTimestampMs: Long = 0L
    private var manualTocCounter: Int = 0
    private var activeSessionFileId: String? = null
    private var batchCleanJob: Job? = null

    private fun getOrCreateActiveFileId(): String {
        val current = activeSessionFileId
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString()
        activeSessionFileId = generated
        return generated
    }

    fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
        _isDarkMode.value = enabled
    }

    fun setAutoSaveDraft(enabled: Boolean) {
        manualEditorPreferences.setAutoSaveDraft(enabled)
        _autoSaveDraft.value = enabled
    }

    fun addCustomReplaceRule(findText: String, replaceText: String) {
        if (findText.isEmpty()) return
        _customReplaceRules.value = customReplaceManager.addRule(findText, replaceText)
    }

    fun deleteCustomReplaceRule(id: String) {
        _customReplaceRules.value = customReplaceManager.removeRule(id)
    }

    fun setErrorMemoryAutoApply(enabled: Boolean) {
        errorMemoryManager.setAutoApplyEnabled(enabled)
        _errorMemoryAutoApply.value = enabled
    }

    fun clearStatusToast() {
        _statusToastMessage.value = null
    }

    /**
     * Upgrade 1.3: Analyzes saved `(original, final)` paragraph diffs for a history file
     * and memorizes valid error-correction / junk-deletion rules into `error_memory_lib.json`.
     */
    fun learnErrorsFromHistory(entry: CleanedFileHistory) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                var pairs = historyManager.loadDiffPairsForEntry(entry)
                if (pairs.isEmpty()) {
                    val savedState = draftManager.loadEditState(entry.id)
                    if (savedState != null) {
                        pairs = EpubCleanPipeline(appContext).extractDiffPairsFromManualChapters(savedState.chapters)
                    }
                }
                if (pairs.isEmpty()) {
                    null
                } else {
                    errorMemoryManager.learnFromDiffPairs(pairs)
                }
            }
            if (outcome == null) {
                _statusToastMessage.value =
                    "File này chưa có bản đối chiếu lỗi (chỉ áp dụng cho file đã sửa có bản gốc)."
                return@launch
            }
            _errorMemoryRuleCount.value = outcome.totalRulesCount
            _statusToastMessage.value = if (outcome.newRulesAdded > 0) {
                "Đã ghi nhớ thêm ${outcome.newRulesAdded} lỗi mới (Tổng: ${outcome.totalRulesCount} lỗi)"
            } else {
                "Không có lỗi mới cần ghi nhớ (Đang lưu: ${outcome.totalRulesCount} lỗi)"
            }
        }
    }

    fun exportErrorMemoryLibrary(uri: Uri) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                errorMemoryManager.exportToUri(uri)
            }
            _statusToastMessage.value = if (count >= 0) {
                "Đã xuất $count lỗi ra tệp sao lưu JSON"
            } else {
                "Không thể xuất thư viện lỗi. Vui lòng thử lại."
            }
        }
    }

    fun importErrorMemoryLibrary(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                errorMemoryManager.importAndMergeFromUri(uri)
            }
            if (result == null) {
                _statusToastMessage.value = "Tệp JSON không hợp lệ hoặc không thể đọc."
                return@launch
            }
            _errorMemoryRuleCount.value = result.totalRulesCount
            _statusToastMessage.value = when {
                result.importedNewCount > 0 && result.skippedDuplicateCount > 0 ->
                    "Đã nhập thêm ${result.importedNewCount} lỗi mới (bỏ qua ${result.skippedDuplicateCount} trùng lặp)"
                result.importedNewCount > 0 ->
                    "Đã nhập thêm ${result.importedNewCount} lỗi mới (Tổng: ${result.totalRulesCount})"
                else ->
                    "Tất cả mục đã có trong thư viện (Tổng: ${result.totalRulesCount} lỗi)"
            }
        }
    }

    fun updateCleaningOptions(options: CleaningOptions) {
        _cleaningOptions.value = options
    }

    fun refreshStoragePermission() {
        _hasAllFilesAccess.value = FilePathResolver.hasAllFilesAccess(appContext)
    }

    fun clearHomeError() {
        _homeErrorBanner.value = null
    }

    /**
     * Inspects the picked .epub/.pdf file and reads its OPF metadata + existing cover
     * so the user can edit metadata/cover before launching the cleaning pipeline.
     */
    fun onFilePicked(uri: Uri) {
        _homeErrorBanner.value = null
        val inspected = FilePathResolver.inspectSourceFile(appContext, uri)
        if (inspected == null) {
            _homeErrorBanner.value = "Vui lòng chọn tệp định dạng .epub hoặc .pdf hợp lệ."
            return
        }

        viewModelScope.launch {
            _isLoadingMetadata.value = true
            try {
                val meta = withContext(Dispatchers.IO) {
                    EpubMetadataEditor.readMetadataFromSource(appContext, inspected)
                }
                _pendingImport.value = PendingImportMetadata(
                    source = inspected,
                    metadata = meta
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed reading metadata on import", e)
                _homeErrorBanner.value = e.localizedMessage ?: "Không thể đọc thông tin tệp đã chọn."
            } finally {
                _isLoadingMetadata.value = false
            }
        }
    }

    /**
     * Re-opens a file from the offline history list for re-processing.
     */
    fun reprocessHistoryItem(entry: CleanedFileHistory) {
        val localFile = entry.filePath.takeIf { it.isNotBlank() }?.let { File(it) }
        val targetUri: Uri? = when {
            localFile != null && localFile.exists() && localFile.canRead() -> Uri.fromFile(localFile)
            !entry.uriString.isNullOrBlank() -> runCatching { Uri.parse(entry.uriString) }.getOrNull()
            else -> null
        }

        if (targetUri == null) {
            _homeErrorBanner.value = "Không tìm thấy tệp gốc tại đường dẫn đã lưu."
            return
        }
        onFilePicked(targetUri)
    }

    /**
     * Replaces the cover image on the pending import (or active review workspace) from PickVisualMedia.
     */
    fun onCoverImagePicked(imageUri: Uri, forReviewScreen: Boolean = false) {
        viewModelScope.launch {
            val coverFile = withContext(Dispatchers.IO) {
                val coversDir = File(appContext.cacheDir, "epub_covers").apply { mkdirs() }
                val dest = File(coversDir, "cover_${System.currentTimeMillis()}.jpg")
                EpubMetadataEditor.prepareCoverImageFromUri(appContext, imageUri, dest)
            }
            if (coverFile == null) {
                _homeErrorBanner.value = "Không thể đọc ảnh bìa đã chọn."
                return@launch
            }

            if (forReviewScreen) {
                val currentMeta = processingSnapshot.value.metadata ?: return@launch
                ProcessingStateHolder.updateActiveMetadata(
                    currentMeta.copy(newCoverImagePath = coverFile.absolutePath)
                )
            } else {
                val current = _pendingImport.value ?: return@launch
                _pendingImport.value = current.copy(
                    metadata = current.metadata.copy(newCoverImagePath = coverFile.absolutePath)
                )
            }
        }
    }

    fun dismissPendingImport() {
        _pendingImport.value = null
    }

    fun confirmImportAndStartProcessing(editedMetadata: EpubMetadata): Boolean {
        val current = _pendingImport.value ?: return false
        _pendingImport.value = null
        startProcessing(current.source, editedMetadata)
        return true
    }

    fun updateReviewMetadata(editedMetadata: EpubMetadata) {
        ProcessingStateHolder.updateActiveMetadata(editedMetadata)
    }

    fun startProcessing(source: SourceFileInfo, metadata: EpubMetadata? = null) {
        _saveUiState.value = SaveUiState.Idle
        _customSaveTreeUri.value = null
        _customSaveFolderLabel.value = null
        activeSessionFileId = UUID.randomUUID().toString()
        EpubCleanForegroundService.start(
            context = appContext,
            source = source,
            metadata = metadata,
            options = _cleaningOptions.value
        )
    }

    /**
     * Triggered from BookLibraryScreen when the user imports a book and chooses "Có" to auto-clean it
     * using the current cleaning options before adding it to the Book Library.
     */
    fun startAutoCleanForLibraryImport(source: SourceFileInfo) {
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching {
                    EpubMetadataEditor.readMetadataFromSource(appContext, source)
                }.getOrNull()
            }
            startProcessing(source, meta)
        }
    }

    fun retryProcessing() {
        val lastSource = processingSnapshot.value.sourceFile ?: return
        val lastMeta = processingSnapshot.value.metadata
        startProcessing(lastSource, lastMeta)
    }

    fun cancelProcessing() {
        EpubCleanForegroundService.cancel(appContext)
    }

    /**
     * Cleanbook Batch Mode: Automatically cleans & saves multiple selected EPUB/PDF books
     * sequentially (4GB RAM safe) WITHOUT prompting "Sửa tiếp". Every book is recorded into
     * "Tất cả các file" with full edit-state saved so the user can manually edit any book later.
     */
    fun startBatchAutoClean(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_batchCleanState.value is BatchCleanUiState.Running) return

        _homeErrorBanner.value = null
        val validSources = uris.mapNotNull { uri ->
            FilePathResolver.inspectSourceFile(appContext, uri)
        }
        if (validSources.isEmpty()) {
            _homeErrorBanner.value = "Vui lòng chọn ít nhất một tệp .epub hoặc .pdf hợp lệ."
            return
        }

        val options = _cleaningOptions.value
        val customTree = _customSaveTreeUri.value
        val total = validSources.size

        batchCleanJob?.cancel()
        batchCleanJob = viewModelScope.launch {
            var successCount = 0
            var failedCount = 0
            var totalFixesAccum = 0
            val failedNames = mutableListOf<String>()
            var cancelled = false

            try {
                for ((idx, source) in validSources.withIndex()) {
                    coroutineContext.ensureActive()
                    _batchCleanState.value = BatchCleanUiState.Running(
                        currentIndex = idx,
                        totalCount = total,
                        currentBookName = source.displayName,
                        currentBookProgress = 0.02f,
                        statusDetail = "Đang chuẩn bị sách ${idx + 1}/$total…",
                        completedCount = successCount,
                        failedCount = failedCount,
                        totalFixesSoFar = totalFixesAccum
                    )

                    var sessionDirToClean: File? = null
                    try {
                        val pipeline = EpubCleanPipeline(appContext)
                        val meta = withContext(Dispatchers.IO) {
                            runCatching {
                                EpubMetadataEditor.readMetadataFromSource(appContext, source)
                            }.getOrNull()
                        }
                        coroutineContext.ensureActive()

                        val snap = pipeline.run(
                            source = source,
                            metadata = meta,
                            options = options,
                            onUpdate = { update ->
                                _batchCleanState.value = BatchCleanUiState.Running(
                                    currentIndex = idx,
                                    totalCount = total,
                                    currentBookName = source.displayName,
                                    currentBookProgress = update.progressFraction.coerceIn(0.02f, 0.90f),
                                    statusDetail = update.statusDetail.ifBlank { "Đang làm sạch tự động…" },
                                    completedCount = successCount,
                                    failedCount = failedCount,
                                    totalFixesSoFar = totalFixesAccum
                                )
                            }
                        )
                        coroutineContext.ensureActive()

                        val workspace = snap.workspace
                        if (snap.phase != ProcessingPhase.COMPLETED || workspace == null) {
                            failedCount++
                            failedNames.add(source.displayName)
                            continue
                        }
                        sessionDirToClean = File(workspace.sessionDirPath)

                        _batchCleanState.value = BatchCleanUiState.Running(
                            currentIndex = idx,
                            totalCount = total,
                            currentBookName = source.displayName,
                            currentBookProgress = 0.94f,
                            statusDetail = "Đang đóng gói & lưu sách sạch…",
                            completedCount = successCount,
                            failedCount = failedCount,
                            totalFixesSoFar = totalFixesAccum
                        )

                        val finalTempEpub = pipeline.rebuildEpubWithSelectedDiffs(
                            workspace = workspace,
                            diffs = snap.diffs,
                            regenerateToc = snap.regenerateToc
                        )

                        val saveResult = withContext(Dispatchers.IO) {
                            FilePathResolver.saveCleanedEpub(
                                context = appContext,
                                tempEpubFile = finalTempEpub,
                                source = source,
                                mode = SaveMode.KEEP_ORIGINAL,
                                customTreeUri = customTree
                            )
                        }

                        if (saveResult is SaveResult.Success) {
                            val acceptedSpellingCount = snap.diffs.count { it.accepted }
                            val bookFixes = snap.stats.junkLinksRemoved +
                                snap.stats.whitespaceFixes +
                                acceptedSpellingCount +
                                snap.stats.mergedParagraphs +
                                snap.stats.headersFootersRemoved +
                                snap.stats.footnotesRemoved +
                                snap.stats.unicodeSanitized +
                                snap.stats.dialoguesFormatted
                            val fileId = UUID.randomUUID().toString()
                            val diffPairs = pipeline.extractDiffPairsFromReview(
                                workspace = workspace,
                                diffs = snap.diffs
                            )

                            val updatedHistory = withContext(Dispatchers.IO) {
                                val chapters = pipeline.loadManualChapters(
                                    workspace = workspace,
                                    diffs = snap.diffs,
                                    mode = ManualEditorMode.KEEP_CLEANED
                                )
                                val tocList = TocGenerator.buildEditableTocItems(
                                    workspace = workspace,
                                    regenerateToc = snap.regenerateToc
                                )
                                val savedStateFile = draftManager.saveEditState(
                                    fileId = fileId,
                                    sourceFile = source,
                                    workspace = workspace,
                                    metadata = snap.metadata,
                                    mode = ManualEditorMode.KEEP_CLEANED,
                                    chapters = chapters,
                                    tocList = tocList,
                                    manualTocEntries = emptyList(),
                                    lastChapterIndex = 0,
                                    lastParagraphIndex = 0,
                                    savedFileUri = saveResult.savedUriString,
                                    savedAbsolutePath = saveResult.savedAbsolutePath,
                                    regenerateToc = snap.regenerateToc,
                                    manualEditCount = 0
                                )

                                historyManager.addEntry(
                                    entry = CleanedFileHistory(
                                        id = fileId,
                                        fileName = saveResult.outputFileName,
                                        filePath = saveResult.savedAbsolutePath ?: saveResult.outputDisplayPath,
                                        uriString = saveResult.savedUriString,
                                        sourceType = source.fileType.label,
                                        totalFixes = bookFixes,
                                        junkLinksFixed = snap.stats.junkLinksRemoved,
                                        whitespaceFixed = snap.stats.whitespaceFixes,
                                        spellingFixed = acceptedSpellingCount,
                                        timestampMillis = System.currentTimeMillis(),
                                        status = FileRecordStatus.SAVED,
                                        lastChapterIndex = 0,
                                        totalChapters = chapters.size,
                                        lastParagraphIndex = 0,
                                        editStateFile = savedStateFile?.name
                                    ),
                                    diffPairs = diffPairs
                                )
                            }

                            _recentFiles.value = updatedHistory
                            successCount++
                            totalFixesAccum += bookFixes
                        } else {
                            failedCount++
                            failedNames.add(source.displayName)
                        }
                    } catch (ce: CancellationException) {
                        cancelled = true
                        throw ce
                    } catch (e: Exception) {
                        Log.e(TAG, "Batch clean failed for ${source.displayName}", e)
                        failedCount++
                        failedNames.add(source.displayName)
                    } finally {
                        // Release temp session immediately after each book to keep 4GB RAM & storage clean
                        if (sessionDirToClean != null) {
                            withContext(Dispatchers.IO) {
                                runCatching { sessionDirToClean.deleteRecursively() }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } finally {
                _batchCleanState.value = BatchCleanUiState.Completed(
                    totalCount = total,
                    successCount = successCount,
                    failedCount = failedCount,
                    totalFixes = totalFixesAccum,
                    failedFileNames = failedNames,
                    wasCancelled = cancelled
                )
            }
        }
    }

    fun cancelBatchAutoClean() {
        batchCleanJob?.cancel()
    }

    fun dismissBatchCleanState() {
        if (_batchCleanState.value !is BatchCleanUiState.Running) {
            _batchCleanState.value = BatchCleanUiState.Idle
        }
    }

    fun resetProcessingSession() {
        _saveUiState.value = SaveUiState.Idle
        _customSaveTreeUri.value = null
        _customSaveFolderLabel.value = null
        activeSessionFileId = null
        manualUndoStack.clear()
        _manualEditorState.value = ManualEditorUiState(
            readingMode = manualEditorPreferences.getReadingMode(),
            skipUndoConfirm = manualEditorPreferences.isSkipUndoConfirm()
        )
        ProcessingStateHolder.reset()
    }

    fun setRegenerateToc(enabled: Boolean) {
        ProcessingStateHolder.setRegenerateToc(enabled)
    }

    fun onSaveFolderPicked(treeUri: Uri) {
        try {
            appContext.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers do not support persistable grants; session access still works.
        }
        _customSaveTreeUri.value = treeUri
        _customSaveFolderLabel.value = FilePathResolver.resolveTreeUriDisplayPath(treeUri)
    }

    fun toggleDiffAccepted(diffId: String) {
        ProcessingStateHolder.toggleDiffAccepted(diffId)
    }

    fun setAllDiffsAccepted(accepted: Boolean) {
        ProcessingStateHolder.setAllDiffsAccepted(accepted)
    }

    fun saveProcessedBook(mode: SaveMode) {
        val snapshot = processingSnapshot.value
        val source = snapshot.sourceFile ?: return
        val workspace = snapshot.workspace ?: return

        viewModelScope.launch {
            _saveUiState.value = SaveUiState.Saving
            try {
                val pipeline = EpubCleanPipeline(appContext)
                val finalTempEpub = pipeline.rebuildEpubWithSelectedDiffs(
                    workspace = workspace,
                    diffs = snapshot.diffs,
                    regenerateToc = snapshot.regenerateToc
                )

                val result = withContext(Dispatchers.IO) {
                    FilePathResolver.saveCleanedEpub(
                        context = appContext,
                        tempEpubFile = finalTempEpub,
                        source = source,
                        mode = mode,
                        customTreeUri = _customSaveTreeUri.value
                    )
                }

                when (result) {
                    is SaveResult.Success -> {
                        val acceptedSpellingCount = snapshot.diffs.count { it.accepted }
                        val totalFixes = snapshot.stats.junkLinksRemoved +
                            snapshot.stats.whitespaceFixes +
                            acceptedSpellingCount
                        val fileId = getOrCreateActiveFileId()
                        val diffPairs = pipeline.extractDiffPairsFromReview(
                            workspace = workspace,
                            diffs = snapshot.diffs
                        )

                        val updatedHistory = withContext(Dispatchers.IO) {
                            // Also persist full edit_states/{fileId}.json so "Sửa tiếp" & "So sánh" work later
                            val chapters = pipeline.loadManualChapters(
                                workspace = workspace,
                                diffs = snapshot.diffs,
                                mode = ManualEditorMode.KEEP_CLEANED
                            )
                            val tocList = TocGenerator.buildEditableTocItems(
                                workspace = workspace,
                                regenerateToc = snapshot.regenerateToc
                            )
                            val savedStateFile = draftManager.saveEditState(
                                fileId = fileId,
                                sourceFile = source,
                                workspace = workspace,
                                metadata = snapshot.metadata,
                                mode = ManualEditorMode.KEEP_CLEANED,
                                chapters = chapters,
                                tocList = tocList,
                                manualTocEntries = emptyList(),
                                lastChapterIndex = 0,
                                lastParagraphIndex = 0,
                                savedFileUri = result.savedUriString,
                                savedAbsolutePath = result.savedAbsolutePath,
                                regenerateToc = snapshot.regenerateToc,
                                manualEditCount = 0
                            )

                            historyManager.addEntry(
                                entry = CleanedFileHistory(
                                    id = fileId,
                                    fileName = result.outputFileName,
                                    filePath = result.savedAbsolutePath ?: result.outputDisplayPath,
                                    uriString = result.savedUriString,
                                    sourceType = source.fileType.label,
                                    totalFixes = totalFixes,
                                    junkLinksFixed = snapshot.stats.junkLinksRemoved,
                                    whitespaceFixed = snapshot.stats.whitespaceFixes,
                                    spellingFixed = acceptedSpellingCount,
                                    timestampMillis = System.currentTimeMillis(),
                                    status = FileRecordStatus.SAVED,
                                    lastChapterIndex = 0,
                                    totalChapters = chapters.size,
                                    lastParagraphIndex = 0,
                                    editStateFile = savedStateFile?.name
                                ),
                                diffPairs = diffPairs
                            )
                        }
                        _recentFiles.value = updatedHistory
                        _saveUiState.value = SaveUiState.Completed(result)
                    }

                    is SaveResult.Failure -> {
                        _saveUiState.value = SaveUiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finalize and save EPUB", e)
                _saveUiState.value = SaveUiState.Error(
                    e.localizedMessage ?: "Không thể lưu tệp EPUB."
                )
            }
        }
    }

    fun dismissSaveState() {
        _saveUiState.value = SaveUiState.Idle
    }

    fun deleteHistoryItem(id: String) {
        _recentFiles.value = historyManager.removeEntry(id)
        viewModelScope.launch(Dispatchers.IO) {
            draftManager.deleteEditState(id)
        }
    }

    fun clearAllHistory() {
        _recentFiles.value = historyManager.clearAll()
        viewModelScope.launch(Dispatchers.IO) {
            draftManager.clearAllEditStates()
        }
    }

    fun isProcessingActive(): Boolean {
        return processingSnapshot.value.phase == ProcessingPhase.RUNNING
    }

    // ------------------------------------------------------------------
    // Manual Sepia Reader & Editor (Upgrade 1.1)
    // ------------------------------------------------------------------

    fun openManualEditor(mode: ManualEditorMode) {
        val snapshot = processingSnapshot.value
        val workspace = snapshot.workspace ?: return
        _saveUiState.value = SaveUiState.Idle
        manualUndoStack.clear()
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1
        lastUndoTimestampMs = 0L
        manualTocCounter = 0

        _manualEditorState.value = ManualEditorUiState(
            isLoading = true,
            mode = mode,
            fontSizeSp = _manualEditorState.value.fontSizeSp,
            readingMode = manualEditorPreferences.getReadingMode(),
            skipUndoConfirm = manualEditorPreferences.isSkipUndoConfirm()
        )

        viewModelScope.launch {
            try {
                val pipeline = EpubCleanPipeline(appContext)
                val chapters = pipeline.loadManualChapters(
                    workspace = workspace,
                    diffs = snapshot.diffs,
                    mode = mode
                )
                val initialToc = withContext(Dispatchers.IO) {
                    TocGenerator.buildEditableTocItems(
                        workspace = workspace,
                        regenerateToc = snapshot.regenerateToc
                    )
                }
                _manualEditorState.value = _manualEditorState.value.copy(
                    isLoading = false,
                    chapters = chapters,
                    currentChapterIndex = 0,
                    activeParagraphIndex = 0,
                    scrollTargetParagraphIndex = 0,
                    scrollRequestId = System.currentTimeMillis(),
                    manualTocEntries = emptyList(),
                    editableTocItems = initialToc,
                    isEditMode = false,
                    isCompareMode = false,
                    searchQuery = "",
                    replaceQuery = "",
                    searchMatches = emptyList(),
                    activeMatchIndex = -1,
                    canUndo = false,
                    manualEditCount = 0,
                    feedbackMessage = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open manual editor", e)
                _manualEditorState.value = _manualEditorState.value.copy(
                    isLoading = false,
                    feedbackMessage = e.localizedMessage ?: "Không thể nạp nội dung sách."
                )
            }
        }
    }

    fun selectManualChapter(chapterIndex: Int, targetParagraphIndex: Int = 0) {
        val cur = _manualEditorState.value
        if (cur.chapters.isEmpty()) return
        val clampedChap = chapterIndex.coerceIn(0, cur.chapters.lastIndex)
        val maxPara = (cur.chapters[clampedChap].currentParagraphs.lastIndex).coerceAtLeast(0)
        val clampedPara = targetParagraphIndex.coerceIn(0, maxPara)
        val chapterChanged = clampedChap != cur.currentChapterIndex
        _manualEditorState.value = cur.copy(
            currentChapterIndex = clampedChap,
            activeParagraphIndex = clampedPara,
            scrollTargetParagraphIndex = clampedPara,
            scrollRequestId = cur.scrollRequestId + 1L
        )
        if (chapterChanged && _autoSaveDraft.value) {
            saveCurrentManualDraft(showFeedback = false)
        }
    }

    fun setActiveParagraphIndex(paragraphIndex: Int) {
        val cur = _manualEditorState.value
        val maxPara = (cur.activeChapter?.currentParagraphs?.lastIndex ?: 0).coerceAtLeast(0)
        val clamped = paragraphIndex.coerceIn(0, maxPara)
        if (cur.activeParagraphIndex != clamped) {
            _manualEditorState.value = cur.copy(activeParagraphIndex = clamped)
        }
    }

    fun setManualReadingMode(mode: ManualReadingMode) {
        manualEditorPreferences.setReadingMode(mode)
        val cur = _manualEditorState.value
        _manualEditorState.value = cur.copy(
            readingMode = mode,
            scrollTargetParagraphIndex = cur.activeParagraphIndex,
            scrollRequestId = cur.scrollRequestId + 1L
        )
    }

    fun setSkipUndoConfirm(skip: Boolean) {
        manualEditorPreferences.setSkipUndoConfirm(skip)
        _manualEditorState.value = _manualEditorState.value.copy(skipUndoConfirm = skip)
    }

    fun toggleManualEditMode() {
        val cur = _manualEditorState.value
        _manualEditorState.value = cur.copy(isEditMode = !cur.isEditMode)
    }

    fun toggleManualCompareMode() {
        val cur = _manualEditorState.value
        _manualEditorState.value = cur.copy(isCompareMode = !cur.isCompareMode)
    }

    fun adjustManualFontSize(deltaSp: Float) {
        val cur = _manualEditorState.value
        val next = (cur.fontSizeSp + deltaSp).coerceIn(12f, 28f)
        _manualEditorState.value = cur.copy(fontSizeSp = next)
    }

    fun updateManualParagraph(chapterIndex: Int, paragraphIndex: Int, newText: String) {
        val cur = _manualEditorState.value
        val chapter = cur.chapters.getOrNull(chapterIndex) ?: return
        val oldText = chapter.currentParagraphs.getOrNull(paragraphIndex) ?: return
        if (oldText == newText) return

        // Group rapid keystrokes in the same paragraph within 1.5s into one undo checkpoint
        val now = System.currentTimeMillis()
        val isNewUndoGroup = chapterIndex != lastUndoChapterIdx ||
            paragraphIndex != lastUndoParaIdx ||
            (now - lastUndoTimestampMs) > 1500L
        if (isNewUndoGroup) {
            pushUndoSnapshot(cur.chapters)
            lastUndoChapterIdx = chapterIndex
            lastUndoParaIdx = paragraphIndex
        }
        lastUndoTimestampMs = now

        val updatedParagraphs = chapter.currentParagraphs.toMutableList()
        updatedParagraphs[paragraphIndex] = newText
        val updatedChapters = cur.chapters.toMutableList()
        updatedChapters[chapterIndex] = chapter.copy(currentParagraphs = updatedParagraphs)

        val refreshedMatches = if (cur.searchQuery.isNotBlank()) {
            computeSearchMatches(updatedChapters, cur.searchQuery)
        } else {
            emptyList()
        }
        val nextActiveMatch = if (refreshedMatches.isEmpty()) {
            -1
        } else {
            cur.activeMatchIndex.coerceIn(0, refreshedMatches.lastIndex)
        }

        _manualEditorState.value = cur.copy(
            chapters = updatedChapters,
            searchMatches = refreshedMatches,
            activeMatchIndex = nextActiveMatch,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + 1
        )
    }

    fun undoManualEdit() {
        if (manualUndoStack.isEmpty()) return
        val previousChapters = manualUndoStack.removeLast()
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1
        val cur = _manualEditorState.value
        val clampedChap = cur.currentChapterIndex.coerceIn(0, (previousChapters.lastIndex).coerceAtLeast(0))
        val clampedPara = cur.activeParagraphIndex.coerceIn(
            0,
            (previousChapters.getOrNull(clampedChap)?.currentParagraphs?.lastIndex ?: 0).coerceAtLeast(0)
        )
        val refreshedMatches = if (cur.searchQuery.isNotBlank()) {
            computeSearchMatches(previousChapters, cur.searchQuery)
        } else {
            emptyList()
        }
        _manualEditorState.value = cur.copy(
            chapters = previousChapters,
            currentChapterIndex = clampedChap,
            activeParagraphIndex = clampedPara,
            searchMatches = refreshedMatches,
            activeMatchIndex = if (refreshedMatches.isEmpty()) -1 else cur.activeMatchIndex.coerceIn(0, refreshedMatches.lastIndex),
            canUndo = manualUndoStack.isNotEmpty(),
            feedbackMessage = "Đã hoàn tác chỉnh sửa gần nhất"
        )
    }

    /**
     * Upgrade 1.3: Splits the currently open chapter at `activeParagraphIndex`, moving all
     * paragraphs from `activeParagraphIndex` to the end into a brand-new `.xhtml` chapter
     * placed immediately after the current chapter and syncing the TOC automatically.
     */
    fun splitCurrentChapterAtActiveParagraph() {
        val cur = _manualEditorState.value
        val chapIdx = cur.currentChapterIndex
        val chapter = cur.chapters.getOrNull(chapIdx) ?: return
        val splitIdx = cur.activeParagraphIndex

        if (splitIdx <= 0 || splitIdx >= chapter.currentParagraphs.size) {
            _manualEditorState.value = cur.copy(
                feedbackMessage = "Hãy chọn từ đoạn thứ 2 trở đi để tách thành chương mới"
            )
            return
        }

        pushUndoSnapshot(cur.chapters)
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1

        val topParas = chapter.currentParagraphs.subList(0, splitIdx).toList()
        val bottomParas = chapter.currentParagraphs.subList(splitIdx, chapter.currentParagraphs.size).toList()

        val origSplitIdx = splitIdx.coerceIn(0, chapter.originalParagraphs.size)
        val topOrig = chapter.originalParagraphs.subList(0, origSplitIdx).toList()
        val bottomOrig = chapter.originalParagraphs.subList(origSplitIdx, chapter.originalParagraphs.size).toList()

        val newChapterTitle = bottomParas.firstOrNull()
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(80)
            ?.ifBlank { null }
            ?: "Chương ${chapIdx + 2}"

        val newFileName = "chapter_split_${System.currentTimeMillis()}_${chapIdx + 2}.xhtml"
        val stageDirPath = processingSnapshot.value.workspace?.stageDirPath
        val newFilePath = if (!stageDirPath.isNullOrBlank()) {
            File(File(stageDirPath, "OEBPS/Text"), newFileName).absolutePath
        } else {
            ""
        }

        val mutableChapters = cur.chapters.toMutableList()
        mutableChapters[chapIdx] = chapter.copy(
            currentParagraphs = topParas,
            originalParagraphs = topOrig
        )
        mutableChapters.add(
            chapIdx + 1,
            ManualChapterContent(
                chapterIndex = chapIdx + 1,
                fileName = newFileName,
                filePath = newFilePath,
                title = newChapterTitle,
                originalParagraphs = bottomOrig,
                currentParagraphs = bottomParas
            )
        )
        val reindexedChapters = mutableChapters.mapIndexed { idx, ch -> ch.copy(chapterIndex = idx) }

        val remappedManualToc = cur.manualTocEntries.map { entry ->
            when {
                entry.chapterIndex > chapIdx -> entry.copy(chapterIndex = entry.chapterIndex + 1)
                entry.chapterIndex == chapIdx && entry.paragraphIndex >= splitIdx -> entry.copy(
                    chapterIndex = chapIdx + 1,
                    chapterFileName = newFileName,
                    paragraphIndex = (entry.paragraphIndex - splitIdx).coerceAtLeast(0)
                )
                else -> entry
            }
        }
        val updatedEditableToc = TocGenerator.updateTocAfterChapterSplit(
            entries = cur.editableTocItems,
            splitChapterIndex = chapIdx,
            newChapterFileName = newFileName,
            newChapterTitle = newChapterTitle,
            splitParagraphIndex = splitIdx
        )

        val refreshedMatches = if (cur.searchQuery.isNotBlank()) {
            computeSearchMatches(reindexedChapters, cur.searchQuery)
        } else {
            emptyList()
        }

        _manualEditorState.value = cur.copy(
            chapters = reindexedChapters,
            currentChapterIndex = chapIdx + 1,
            activeParagraphIndex = 0,
            scrollTargetParagraphIndex = 0,
            scrollRequestId = cur.scrollRequestId + 1L,
            manualTocEntries = remappedManualToc,
            editableTocItems = updatedEditableToc,
            searchMatches = refreshedMatches,
            activeMatchIndex = if (refreshedMatches.isEmpty()) -1 else 0,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + 1,
            feedbackMessage = "Đã tách chương mới: \"$newChapterTitle\""
        )
    }

    /**
     * Upgrade 1.3: Merges `chapters[sourceChapterIndex]` seamlessly onto the top of the chapter
     * directly below it (`sourceChapterIndex + 1`) and updates the TOC automatically.
     */
    fun mergeChapterWithNext(sourceChapterIndex: Int) {
        val cur = _manualEditorState.value
        if (sourceChapterIndex !in 0 until cur.chapters.lastIndex) return

        val sourceChapter = cur.chapters[sourceChapterIndex]
        val targetChapter = cur.chapters[sourceChapterIndex + 1]
        val sourceParaCount = sourceChapter.currentParagraphs.size

        pushUndoSnapshot(cur.chapters)
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1

        val mergedChapter = targetChapter.copy(
            chapterIndex = sourceChapterIndex,
            currentParagraphs = sourceChapter.currentParagraphs + targetChapter.currentParagraphs,
            originalParagraphs = sourceChapter.originalParagraphs + targetChapter.originalParagraphs
        )

        val mutableChapters = cur.chapters.toMutableList()
        mutableChapters.removeAt(sourceChapterIndex)
        mutableChapters[sourceChapterIndex] = mergedChapter
        val reindexedChapters = mutableChapters.mapIndexed { idx, ch -> ch.copy(chapterIndex = idx) }

        val remappedManualToc = cur.manualTocEntries.map { entry ->
            when {
                entry.chapterIndex == sourceChapterIndex -> entry.copy(
                    chapterFileName = targetChapter.fileName
                )
                entry.chapterIndex == sourceChapterIndex + 1 -> entry.copy(
                    chapterIndex = sourceChapterIndex,
                    paragraphIndex = entry.paragraphIndex + sourceParaCount
                )
                entry.chapterIndex > sourceChapterIndex + 1 -> entry.copy(
                    chapterIndex = entry.chapterIndex - 1
                )
                else -> entry
            }
        }
        val updatedEditableToc = TocGenerator.updateTocAfterChapterMerge(
            entries = cur.editableTocItems,
            sourceChapterIndex = sourceChapterIndex,
            targetChapterIndex = sourceChapterIndex + 1,
            targetChapterFileName = targetChapter.fileName,
            sourceParagraphCount = sourceParaCount
        )

        val nextChapIdx = when {
            cur.currentChapterIndex > sourceChapterIndex -> (cur.currentChapterIndex - 1).coerceAtLeast(0)
            else -> cur.currentChapterIndex
        }.coerceIn(0, reindexedChapters.lastIndex)
        val nextParaIdx = cur.activeParagraphIndex.coerceIn(
            0,
            (reindexedChapters[nextChapIdx].currentParagraphs.lastIndex).coerceAtLeast(0)
        )

        val refreshedMatches = if (cur.searchQuery.isNotBlank()) {
            computeSearchMatches(reindexedChapters, cur.searchQuery)
        } else {
            emptyList()
        }

        _manualEditorState.value = cur.copy(
            chapters = reindexedChapters,
            currentChapterIndex = nextChapIdx,
            activeParagraphIndex = nextParaIdx,
            scrollTargetParagraphIndex = nextParaIdx,
            scrollRequestId = cur.scrollRequestId + 1L,
            manualTocEntries = remappedManualToc,
            editableTocItems = updatedEditableToc,
            searchMatches = refreshedMatches,
            activeMatchIndex = if (refreshedMatches.isEmpty()) -1 else 0,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + 1,
            feedbackMessage = "Đã gộp chương vào \"${targetChapter.title}\""
        )
    }

    /**
     * Upgrade 1.5: Creates and inserts a brand-new `.xhtml` chapter from the Manual Editor TOC
     * panel, updates the TOC automatically, and jumps straight into the new chapter for writing.
     */
    fun addNewChapterInManualEditor(rawTitle: String) {
        val cur = _manualEditorState.value
        val nextIndex = cur.chapters.size
        val cleanTitle = rawTitle.trim().take(120).ifBlank { "Chương ${nextIndex + 1}" }

        pushUndoSnapshot(cur.chapters)
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1

        val newFileName = "chapter_new_${System.currentTimeMillis()}_${nextIndex + 1}.xhtml"
        val stageDirPath = processingSnapshot.value.workspace?.stageDirPath
        val newFilePath = if (!stageDirPath.isNullOrBlank()) {
            File(File(stageDirPath, "OEBPS/Text"), newFileName).absolutePath
        } else {
            ""
        }

        val initialParagraphs = listOf(cleanTitle, "")
        val newChapter = ManualChapterContent(
            chapterIndex = nextIndex,
            fileName = newFileName,
            title = cleanTitle,
            filePath = newFilePath,
            originalParagraphs = initialParagraphs,
            currentParagraphs = initialParagraphs
        )
        val updatedChapters = cur.chapters + newChapter
        val updatedEditableToc = TocGenerator.addNewChapterEntry(
            entries = cur.editableTocItems,
            newChapterIndex = nextIndex,
            newChapterFileName = newFileName,
            newChapterTitle = cleanTitle
        )

        _manualEditorState.value = cur.copy(
            chapters = updatedChapters,
            currentChapterIndex = nextIndex,
            activeParagraphIndex = 1,
            scrollTargetParagraphIndex = 0,
            scrollRequestId = cur.scrollRequestId + 1L,
            isEditMode = true,
            editableTocItems = updatedEditableToc,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + 1,
            feedbackMessage = "Đã thêm chương mới: \"$cleanTitle\""
        )
    }

    fun addManualTocBookmark(chapterIndex: Int, paragraphIndex: Int, rawTitle: String) {
        val cur = _manualEditorState.value
        val chapter = cur.chapters.getOrNull(chapterIndex) ?: return
        val cleanTitle = rawTitle.trim().take(100)
        if (cleanTitle.isEmpty()) return

        manualTocCounter++
        val entry = ManualTocEntry(
            id = "mtoc_${System.currentTimeMillis()}_$manualTocCounter",
            title = cleanTitle,
            chapterIndex = chapterIndex,
            chapterFileName = chapter.fileName,
            paragraphIndex = paragraphIndex.coerceIn(0, (chapter.currentParagraphs.lastIndex).coerceAtLeast(0)),
            anchorId = "manual_toc_$manualTocCounter"
        )

        val updatedManuals = (cur.manualTocEntries + entry)
            .sortedWith(compareBy<ManualTocEntry> { it.chapterIndex }.thenBy { it.paragraphIndex })
        val updatedEditableToc = TocGenerator.insertManualEntry(cur.editableTocItems, entry)

        _manualEditorState.value = cur.copy(
            manualTocEntries = updatedManuals,
            editableTocItems = updatedEditableToc,
            feedbackMessage = "Đã thêm vào Mục lục: \"$cleanTitle\""
        )
    }

    fun renameTocItem(entryId: String, newTitle: String) {
        val cur = _manualEditorState.value
        val cleaned = newTitle.trim().take(120)
        if (cleaned.isEmpty()) return
        val updatedEditable = TocGenerator.renameEntry(cur.editableTocItems, entryId, cleaned)
        val updatedManuals = cur.manualTocEntries.map {
            if (it.id == entryId) it.copy(title = cleaned) else it
        }
        _manualEditorState.value = cur.copy(
            editableTocItems = updatedEditable,
            manualTocEntries = updatedManuals,
            feedbackMessage = "Đã đổi tên mục lục"
        )
    }

    fun deleteTocItem(entryId: String) {
        val cur = _manualEditorState.value
        _manualEditorState.value = cur.copy(
            editableTocItems = TocGenerator.deleteEntry(cur.editableTocItems, entryId),
            manualTocEntries = cur.manualTocEntries.filterNot { it.id == entryId },
            feedbackMessage = "Đã xóa mục khỏi Mục lục"
        )
    }

    fun removeManualTocBookmark(entryId: String) {
        deleteTocItem(entryId)
    }

    fun updateManualSearchQuery(query: String) {
        val cur = _manualEditorState.value
        val matches = if (query.isBlank()) {
            emptyList()
        } else {
            computeSearchMatches(cur.chapters, query)
        }
        val firstIdxInCurrent = matches.indexOfFirst { it.chapterIndex == cur.currentChapterIndex }
        val activeIdx = when {
            matches.isEmpty() -> -1
            firstIdxInCurrent >= 0 -> firstIdxInCurrent
            else -> 0
        }
        val targetMatch = matches.getOrNull(activeIdx)
        _manualEditorState.value = cur.copy(
            searchQuery = query,
            searchMatches = matches,
            activeMatchIndex = activeIdx,
            currentChapterIndex = targetMatch?.chapterIndex ?: cur.currentChapterIndex,
            scrollTargetParagraphIndex = targetMatch?.paragraphIndex ?: cur.scrollTargetParagraphIndex,
            scrollRequestId = if (targetMatch != null) cur.scrollRequestId + 1L else cur.scrollRequestId
        )
    }

    fun updateManualReplaceQuery(query: String) {
        _manualEditorState.value = _manualEditorState.value.copy(replaceQuery = query)
    }

    fun navigateSearchMatch(step: Int) {
        val cur = _manualEditorState.value
        if (cur.searchMatches.isEmpty()) return
        val count = cur.searchMatches.size
        val nextIdx = ((cur.activeMatchIndex + step) % count + count) % count
        val match = cur.searchMatches[nextIdx]
        _manualEditorState.value = cur.copy(
            activeMatchIndex = nextIdx,
            currentChapterIndex = match.chapterIndex,
            scrollTargetParagraphIndex = match.paragraphIndex,
            scrollRequestId = cur.scrollRequestId + 1L
        )
    }

    fun replaceCurrentSearchMatch() {
        val cur = _manualEditorState.value
        if (cur.searchQuery.isBlank() || cur.searchMatches.isEmpty()) return
        val match = cur.searchMatches.getOrNull(cur.activeMatchIndex) ?: return
        val chapter = cur.chapters.getOrNull(match.chapterIndex) ?: return
        val paragraph = chapter.currentParagraphs.getOrNull(match.paragraphIndex) ?: return
        if (match.startOffset < 0 || match.endOffset > paragraph.length || match.startOffset > match.endOffset) return

        pushUndoSnapshot(cur.chapters)
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1

        val replacedPara = buildString {
            append(paragraph.substring(0, match.startOffset))
            append(cur.replaceQuery)
            append(paragraph.substring(match.endOffset))
        }

        val updatedParagraphs = chapter.currentParagraphs.toMutableList()
        updatedParagraphs[match.paragraphIndex] = replacedPara
        val updatedChapters = cur.chapters.toMutableList()
        updatedChapters[match.chapterIndex] = chapter.copy(currentParagraphs = updatedParagraphs)

        val nextMatches = computeSearchMatches(updatedChapters, cur.searchQuery)
        val nextActive = if (nextMatches.isEmpty()) -1 else cur.activeMatchIndex.coerceIn(0, nextMatches.lastIndex)
        val nextTarget = nextMatches.getOrNull(nextActive)

        _manualEditorState.value = cur.copy(
            chapters = updatedChapters,
            searchMatches = nextMatches,
            activeMatchIndex = nextActive,
            currentChapterIndex = nextTarget?.chapterIndex ?: cur.currentChapterIndex,
            scrollTargetParagraphIndex = nextTarget?.paragraphIndex ?: cur.scrollTargetParagraphIndex,
            scrollRequestId = if (nextTarget != null) cur.scrollRequestId + 1L else cur.scrollRequestId,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + 1,
            feedbackMessage = "Đã thay thế 1 vị trí"
        )
    }

    fun replaceAllSearchMatches() {
        val cur = _manualEditorState.value
        val query = cur.searchQuery
        if (query.isBlank() || cur.searchMatches.isEmpty()) return

        pushUndoSnapshot(cur.chapters)
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1

        val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        var totalReplaced = 0
        val updatedChapters = cur.chapters.map { chap ->
            var changed = false
            val newParas = chap.currentParagraphs.map { para ->
                val count = regex.findAll(para).count()
                if (count > 0) {
                    changed = true
                    totalReplaced += count
                    regex.replace(para, Regex.escapeReplacement(cur.replaceQuery))
                } else {
                    para
                }
            }
            if (changed) chap.copy(currentParagraphs = newParas) else chap
        }

        val nextMatches = computeSearchMatches(updatedChapters, query)
        _manualEditorState.value = cur.copy(
            chapters = updatedChapters,
            searchMatches = nextMatches,
            activeMatchIndex = if (nextMatches.isEmpty()) -1 else 0,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + totalReplaced,
            feedbackMessage = "Đã thay thế tất cả $totalReplaced vị trí"
        )
    }

    fun clearManualFeedbackMessage() {
        _manualEditorState.value = _manualEditorState.value.copy(feedbackMessage = null)
    }

    /**
     * Upgrade 1.2: Batch-applies every saved Personal Quick-Replace Rule across all chapters
     * in 1 tap, with full Undo snapshot support.
     */
    fun applyAllCustomReplaceRulesToBook() {
        val cur = _manualEditorState.value
        val rules = _customReplaceRules.value
        if (cur.chapters.isEmpty() || rules.isEmpty()) return

        val (updatedChapters, totalReplaced) = CustomReplaceManager.applyRulesToChapters(
            chapters = cur.chapters,
            rules = rules
        )
        if (totalReplaced <= 0) {
            _manualEditorState.value = cur.copy(
                feedbackMessage = "Không tìm thấy từ nào khớp bộ thay thế nhanh"
            )
            return
        }

        pushUndoSnapshot(cur.chapters)
        lastUndoChapterIdx = -1
        lastUndoParaIdx = -1

        val refreshedMatches = if (cur.searchQuery.isNotBlank()) {
            computeSearchMatches(updatedChapters, cur.searchQuery)
        } else {
            emptyList()
        }

        _manualEditorState.value = cur.copy(
            chapters = updatedChapters,
            searchMatches = refreshedMatches,
            activeMatchIndex = if (refreshedMatches.isEmpty()) -1 else 0,
            canUndo = manualUndoStack.isNotEmpty(),
            manualEditCount = cur.manualEditCount + totalReplaced,
            feedbackMessage = "Đã thay nhanh $totalReplaced vị trí toàn sách"
        )
    }

    /**
     * Saves the active manual editing session into `filesDir/edit_states/{fileId}.json`
     * and upserts the book into "Tất cả các file" with status `DRAFT` ("Bản nháp") at the top
     * without creating duplicates.
     */
    fun saveCurrentManualDraft(
        showFeedback: Boolean = true,
        onComplete: (() -> Unit)? = null
    ) {
        val snapshot = processingSnapshot.value
        val source = snapshot.sourceFile
        val workspace = snapshot.workspace
        val editorState = _manualEditorState.value
        if (source == null || workspace == null || editorState.chapters.isEmpty()) {
            onComplete?.invoke()
            return
        }

        val fileId = getOrCreateActiveFileId()

        viewModelScope.launch {
            val updatedHistory = withContext(Dispatchers.IO) {
                val existing = historyManager.getHistory().firstOrNull { it.id == fileId }
                val existingSavedUri = existing?.uriString?.takeIf { it.isNotBlank() }
                val existingSavedPath = existing?.filePath?.takeIf { it.isNotBlank() }

                val stateFile = draftManager.saveEditState(
                    fileId = fileId,
                    sourceFile = source,
                    workspace = workspace,
                    metadata = snapshot.metadata,
                    mode = editorState.mode,
                    chapters = editorState.chapters,
                    tocList = editorState.editableTocItems,
                    manualTocEntries = editorState.manualTocEntries,
                    lastChapterIndex = editorState.currentChapterIndex,
                    lastParagraphIndex = editorState.activeParagraphIndex,
                    savedFileUri = existingSavedUri,
                    savedAbsolutePath = existingSavedPath,
                    regenerateToc = snapshot.regenerateToc,
                    manualEditCount = editorState.manualEditCount
                ) ?: return@withContext null

                val pipeline = EpubCleanPipeline(appContext)
                val diffPairs = pipeline.extractDiffPairsFromManualChapters(editorState.chapters)
                val acceptedSpellingCount = snapshot.diffs.count { it.accepted } + editorState.manualEditCount
                val totalFixes = snapshot.stats.junkLinksRemoved +
                    snapshot.stats.whitespaceFixes +
                    acceptedSpellingCount
                val displayName = existing?.fileName?.takeIf { it.isNotBlank() }
                    ?: snapshot.metadata?.title?.trim()?.takeIf { it.isNotBlank() }
                    ?: source.displayName

                historyManager.addEntry(
                    entry = CleanedFileHistory(
                        id = fileId,
                        fileName = displayName,
                        filePath = existingSavedPath ?: (source.resolvedFilePath ?: source.displayName),
                        uriString = existingSavedUri ?: source.sourceUriString,
                        sourceType = source.fileType.label,
                        totalFixes = totalFixes,
                        junkLinksFixed = snapshot.stats.junkLinksRemoved,
                        whitespaceFixed = snapshot.stats.whitespaceFixes,
                        spellingFixed = acceptedSpellingCount,
                        timestampMillis = System.currentTimeMillis(),
                        status = FileRecordStatus.DRAFT,
                        lastChapterIndex = editorState.currentChapterIndex,
                        totalChapters = editorState.chapters.size,
                        lastParagraphIndex = editorState.activeParagraphIndex,
                        editStateFile = stateFile.name
                    ),
                    diffPairs = diffPairs
                )
            }
            if (updatedHistory != null) {
                _recentFiles.value = updatedHistory
                if (onComplete != null) {
                    _statusToastMessage.value = "Đã lưu bản nháp và về màn hình chính"
                } else if (showFeedback) {
                    _manualEditorState.value = _manualEditorState.value.copy(
                        feedbackMessage = "Đã lưu bản nháp"
                    )
                }
            } else if (showFeedback) {
                _manualEditorState.value = _manualEditorState.value.copy(
                    feedbackMessage = "Không thể lưu nháp lúc này"
                )
            }
            onComplete?.invoke()
        }
    }

    /**
     * Unified "Sửa tiếp" handler for BOTH "Bản nháp" and "Đã lưu" entries in "Tất cả các file":
     * 1. First loads `filesDir/edit_states/{fileId}.json` (if present) restoring `currentParagraphs`,
     *    `originalParagraphs`, `tocList`, `lastChapterIndex`, `lastParagraphIndex`, cover & images.
     * 2. Otherwise falls back to reading directly from `savedFileUri` / `filePath` on device storage.
     * Opens `ManualEditorScreen` directly at the exact chapter & paragraph with Compare & Error Memory intact.
     */
    fun continueEditingHistoryItem(entry: CleanedFileHistory, onReady: () -> Unit) {
        if (_isResumingDraft.value) return
        viewModelScope.launch {
            _isResumingDraft.value = true
            try {
                val restored = withContext(Dispatchers.IO) {
                    val savedState = draftManager.loadEditState(entry.id)
                    if (savedState != null) {
                        rebuildSessionFromEditState(savedState)
                    } else {
                        rebuildSessionDirectlyFromStoredFile(entry)
                    }
                }

                if (restored == null) {
                    _homeErrorBanner.value = "Không tìm thấy tệp hoặc dữ liệu biên tập để mở sửa tiếp."
                    return@launch
                }

                val (snapshotState, workspace, wiredChapters) = restored
                _saveUiState.value = SaveUiState.Idle
                activeSessionFileId = entry.id
                manualUndoStack.clear()
                lastUndoChapterIdx = -1
                lastUndoParaIdx = -1
                lastUndoTimestampMs = 0L
                manualTocCounter = snapshotState.manualTocEntries.size

                ProcessingStateHolder.publish(
                    ProcessingSnapshot(
                        phase = ProcessingPhase.COMPLETED,
                        sourceFile = snapshotState.sourceFile,
                        workspace = workspace,
                        metadata = snapshotState.metadata,
                        regenerateToc = snapshotState.regenerateToc
                    )
                )

                val tocItems = snapshotState.tocList.ifEmpty {
                    TocGenerator.buildEditableTocItems(workspace, regenerateToc = true)
                }
                val targetChap = snapshotState.lastChapterIndex.coerceIn(0, wiredChapters.lastIndex)
                val targetPara = snapshotState.lastParagraphIndex.coerceIn(
                    0,
                    (wiredChapters[targetChap].currentParagraphs.lastIndex).coerceAtLeast(0)
                )

                _manualEditorState.value = ManualEditorUiState(
                    isLoading = false,
                    mode = snapshotState.mode,
                    chapters = wiredChapters,
                    currentChapterIndex = targetChap,
                    activeParagraphIndex = targetPara,
                    scrollTargetParagraphIndex = targetPara,
                    scrollRequestId = System.currentTimeMillis(),
                    manualTocEntries = snapshotState.manualTocEntries,
                    editableTocItems = tocItems,
                    fontSizeSp = _manualEditorState.value.fontSizeSp,
                    readingMode = manualEditorPreferences.getReadingMode(),
                    skipUndoConfirm = manualEditorPreferences.isSkipUndoConfirm(),
                    manualEditCount = snapshotState.manualEditCount,
                    feedbackMessage = if (entry.status == FileRecordStatus.DRAFT) {
                        "Đã mở bản nháp tại Chương ${targetChap + 1}"
                    } else {
                        "Đang sửa tiếp tại Chương ${targetChap + 1}"
                    }
                )
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open history entry for editing", e)
                _homeErrorBanner.value = e.localizedMessage ?: "Không thể mở tệp để sửa tiếp."
            } finally {
                _isResumingDraft.value = false
            }
        }
    }

    private fun rebuildSessionFromEditState(
        savedState: FileEditStateSnapshot
    ): Triple<FileEditStateSnapshot, PreparedWorkspace, List<ManualChapterContent>> {
        val sessionDir = EpubWorkspaceManager.createSessionDir(appContext)
        val stageDir = File(sessionDir, "stage").apply { mkdirs() }
        val textDir = File(stageDir, "OEBPS/Text").apply { mkdirs() }
        val stylesDir = File(stageDir, "OEBPS/Styles").apply { mkdirs() }
        val imagesDir = File(stageDir, "OEBPS/Images").apply { mkdirs() }
        val originalTextDir = File(sessionDir, "original_text").apply { mkdirs() }

        File(stylesDir, "style.css").writeText(
            TextCleaningEngine.UNIFIED_STYLE_CSS,
            Charsets.UTF_8
        )

        val imageRelativePaths = mutableListOf<String>()
        val savedImgDir = savedState.savedImagesDirPath?.let { File(it) }
        if (savedImgDir != null && savedImgDir.exists()) {
            savedImgDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { img ->
                val dest = File(imagesDir, img.name)
                img.copyTo(dest, overwrite = true)
                imageRelativePaths.add("Images/${img.name}")
            }
        }

        val descriptors = ArrayList<ChapterDescriptor>(savedState.chapters.size)
        val wiredChapters = ArrayList<ManualChapterContent>(savedState.chapters.size)
        savedState.chapters.forEachIndexed { idx, chap ->
            val stagedFile = File(textDir, chap.fileName)
            val bodyHtml = buildString {
                chap.currentParagraphs.forEachIndexed { pIdx, para ->
                    val tag = if (pIdx == 0 && para.length <= 120) "h2" else "p"
                    append("<$tag>${escapeSimpleXml(para)}</$tag>\n")
                }
            }
            stagedFile.writeText(
                EpubWorkspaceManager.buildValidXhtmlDocument(chap.title, bodyHtml),
                Charsets.UTF_8
            )

            val origBodyHtml = buildString {
                chap.originalParagraphs.forEachIndexed { pIdx, para ->
                    val tag = if (pIdx == 0 && para.length <= 120) "h2" else "p"
                    append("<$tag>${escapeSimpleXml(para)}</$tag>\n")
                }
            }
            File(originalTextDir, chap.fileName).writeText(
                EpubWorkspaceManager.buildValidXhtmlDocument(chap.title, origBodyHtml),
                Charsets.UTF_8
            )

            descriptors.add(
                ChapterDescriptor(
                    id = "chap_${idx + 1}",
                    fileName = chap.fileName,
                    title = chap.title,
                    filePath = stagedFile.absolutePath
                )
            )
            wiredChapters.add(chap.copy(filePath = stagedFile.absolutePath))
        }

        val workspace = PreparedWorkspace(
            sessionDirPath = sessionDir.absolutePath,
            stageDirPath = stageDir.absolutePath,
            tempOutputEpubPath = File(sessionDir, "cleaned_output.epub").absolutePath,
            bookTitle = savedState.bookTitle,
            bookAuthor = savedState.bookAuthor,
            chapters = descriptors,
            imageRelativePaths = imageRelativePaths,
            metadata = savedState.metadata,
            tocInspection = null,
            originalTextDirPath = originalTextDir.absolutePath
        )
        return Triple(savedState, workspace, wiredChapters)
    }

    private suspend fun rebuildSessionDirectlyFromStoredFile(
        entry: CleanedFileHistory
    ): Triple<FileEditStateSnapshot, PreparedWorkspace, List<ManualChapterContent>>? {
        val localFile = entry.filePath.takeIf { it.isNotBlank() }?.let { File(it) }
        val targetUri: Uri = when {
            localFile != null && localFile.exists() && localFile.canRead() -> Uri.fromFile(localFile)
            !entry.uriString.isNullOrBlank() -> runCatching { Uri.parse(entry.uriString) }.getOrNull()
            else -> null
        } ?: return null

        val source = FilePathResolver.inspectSourceFile(appContext, targetUri) ?: return null
        val sessionDir = EpubWorkspaceManager.createSessionDir(appContext)
        val inputWorkFile = File(sessionDir, "reopen_${source.displayName}")
        FilePathResolver.copyInputToWorkFile(appContext, source, inputWorkFile)

        val meta = EpubMetadataEditor.readMetadataFromSource(appContext, source)
        val workspace = EpubWorkspaceManager.unpackAndStageEpub(
            sourceEpubFile = inputWorkFile,
            fallbackTitle = meta.title.ifBlank { source.baseNameWithoutExt },
            sessionDir = sessionDir
        ).copy(metadata = meta)

        val pipeline = EpubCleanPipeline(appContext)
        val chapters = pipeline.loadManualChapters(
            workspace = workspace,
            diffs = emptyList(),
            mode = ManualEditorMode.KEEP_CLEANED
        )
        if (chapters.isEmpty()) return null

        val tocList = TocGenerator.buildEditableTocItems(workspace, regenerateToc = false)
        val fallbackState = FileEditStateSnapshot(
            fileId = entry.id,
            sourceFile = source,
            metadata = meta,
            bookTitle = meta.title.ifBlank { workspace.bookTitle },
            bookAuthor = meta.creator.ifBlank { workspace.bookAuthor },
            mode = ManualEditorMode.KEEP_CLEANED,
            chapters = chapters,
            tocList = tocList,
            manualTocEntries = emptyList(),
            lastChapterIndex = entry.lastChapterIndex.coerceIn(0, chapters.lastIndex),
            lastParagraphIndex = entry.lastParagraphIndex.coerceAtLeast(0),
            savedFileUri = entry.uriString,
            savedAbsolutePath = entry.filePath,
            regenerateToc = false,
            manualEditCount = 0
        )
        return Triple(fallbackState, workspace, chapters)
    }

    private fun escapeSimpleXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    fun saveFromManualEditor(mode: SaveMode) {
        val snapshot = processingSnapshot.value
        val source = snapshot.sourceFile ?: return
        val workspace = snapshot.workspace ?: return
        val editorState = _manualEditorState.value
        val fileId = getOrCreateActiveFileId()

        viewModelScope.launch {
            _saveUiState.value = SaveUiState.Saving
            try {
                val pipeline = EpubCleanPipeline(appContext)
                val finalTempEpub = pipeline.writeManualEditsAndPackEpub(
                    workspace = workspace,
                    chapters = editorState.chapters,
                    manualTocEntries = editorState.manualTocEntries,
                    regenerateToc = snapshot.regenerateToc,
                    editableTocItems = editorState.editableTocItems.ifEmpty { null }
                )

                val result = withContext(Dispatchers.IO) {
                    FilePathResolver.saveCleanedEpub(
                        context = appContext,
                        tempEpubFile = finalTempEpub,
                        source = source,
                        mode = mode,
                        customTreeUri = _customSaveTreeUri.value
                    )
                }

                when (result) {
                    is SaveResult.Success -> {
                        val acceptedSpellingCount = snapshot.diffs.count { it.accepted } + editorState.manualEditCount
                        val totalFixes = snapshot.stats.junkLinksRemoved +
                            snapshot.stats.whitespaceFixes +
                            acceptedSpellingCount
                        val diffPairs = pipeline.extractDiffPairsFromManualChapters(editorState.chapters)

                        val updatedHistory = withContext(Dispatchers.IO) {
                            // Persist edit_states/{fileId}.json WITH savedFileUri so "Sửa tiếp" & "So sánh" stay intact
                            val savedStateFile = draftManager.saveEditState(
                                fileId = fileId,
                                sourceFile = source,
                                workspace = workspace,
                                metadata = snapshot.metadata,
                                mode = editorState.mode,
                                chapters = editorState.chapters,
                                tocList = editorState.editableTocItems,
                                manualTocEntries = editorState.manualTocEntries,
                                lastChapterIndex = editorState.currentChapterIndex,
                                lastParagraphIndex = editorState.activeParagraphIndex,
                                savedFileUri = result.savedUriString,
                                savedAbsolutePath = result.savedAbsolutePath,
                                regenerateToc = snapshot.regenerateToc,
                                manualEditCount = editorState.manualEditCount
                            )

                            // Promote existing Draft (same fileId) -> SAVED at top of "Tất cả các file" (zero duplicates)
                            historyManager.addEntry(
                                entry = CleanedFileHistory(
                                    id = fileId,
                                    fileName = result.outputFileName,
                                    filePath = result.savedAbsolutePath ?: result.outputDisplayPath,
                                    uriString = result.savedUriString,
                                    sourceType = source.fileType.label,
                                    totalFixes = totalFixes,
                                    junkLinksFixed = snapshot.stats.junkLinksRemoved,
                                    whitespaceFixed = snapshot.stats.whitespaceFixes,
                                    spellingFixed = acceptedSpellingCount,
                                    timestampMillis = System.currentTimeMillis(),
                                    status = FileRecordStatus.SAVED,
                                    lastChapterIndex = editorState.currentChapterIndex,
                                    totalChapters = editorState.chapters.size,
                                    lastParagraphIndex = editorState.activeParagraphIndex,
                                    editStateFile = savedStateFile?.name
                                ),
                                diffPairs = diffPairs
                            )
                        }
                        _recentFiles.value = updatedHistory
                        _saveUiState.value = SaveUiState.Completed(result)
                    }

                    is SaveResult.Failure -> {
                        _saveUiState.value = SaveUiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save EPUB from manual editor", e)
                _saveUiState.value = SaveUiState.Error(
                    e.localizedMessage ?: "Không thể lưu tệp EPUB."
                )
            }
        }
    }

    private fun pushUndoSnapshot(chapters: List<ManualChapterContent>) {
        if (manualUndoStack.size >= 30) {
            manualUndoStack.removeFirst()
        }
        manualUndoStack.addLast(chapters)
    }

    private fun computeSearchMatches(
        chapters: List<ManualChapterContent>,
        query: String
    ): List<ManualSearchMatch> {
        if (query.isBlank()) return emptyList()
        val matches = mutableListOf<ManualSearchMatch>()
        val queryLen = query.length
        for (chapIdx in chapters.indices) {
            val paras = chapters[chapIdx].currentParagraphs
            for (pIdx in paras.indices) {
                val text = paras[pIdx]
                var start = text.indexOf(query, startIndex = 0, ignoreCase = true)
                while (start >= 0) {
                    matches.add(
                        ManualSearchMatch(
                            chapterIndex = chapIdx,
                            paragraphIndex = pIdx,
                            startOffset = start,
                            endOffset = start + queryLen
                        )
                    )
                    if (matches.size >= 2000) return matches
                    start = text.indexOf(query, startIndex = start + queryLen.coerceAtLeast(1), ignoreCase = true)
                }
            }
        }
        return matches
    }
}
