package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CleanedFileHistory
import com.example.data.HistoryManager
import com.example.data.ThemePreferences
import com.example.pipeline.CleaningOptions
import com.example.pipeline.EpubCleanPipeline
import com.example.pipeline.EpubMetadata
import com.example.pipeline.EpubMetadataEditor
import com.example.pipeline.FilePathResolver
import com.example.pipeline.ManualChapterContent
import com.example.pipeline.ManualEditorMode
import com.example.pipeline.ManualSearchMatch
import com.example.pipeline.ManualTocEntry
import com.example.pipeline.ProcessingPhase
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SaveMode
import com.example.pipeline.SaveResult
import com.example.pipeline.SourceFileInfo
import com.example.service.EpubCleanForegroundService
import com.example.service.ProcessingStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

data class ManualEditorUiState(
    val isLoading: Boolean = false,
    val mode: ManualEditorMode = ManualEditorMode.KEEP_CLEANED,
    val chapters: List<ManualChapterContent> = emptyList(),
    val currentChapterIndex: Int = 0,
    val scrollTargetParagraphIndex: Int = 0,
    val scrollRequestId: Long = 0L,
    val manualTocEntries: List<ManualTocEntry> = emptyList(),
    val fontSizeSp: Float = 17f,
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
    private val historyManager = HistoryManager(appContext)

    private val _recentFiles = MutableStateFlow(historyManager.getHistory())
    val recentFiles: StateFlow<List<CleanedFileHistory>> = _recentFiles.asStateFlow()

    val processingSnapshot: StateFlow<ProcessingSnapshot> = ProcessingStateHolder.snapshot

    private val _isDarkMode = MutableStateFlow(themePreferences.isDarkMode())
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

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

    private val _manualEditorState = MutableStateFlow(ManualEditorUiState())
    val manualEditorState: StateFlow<ManualEditorUiState> = _manualEditorState.asStateFlow()

    private val manualUndoStack = ArrayDeque<List<ManualChapterContent>>()
    private var lastUndoChapterIdx: Int = -1
    private var lastUndoParaIdx: Int = -1
    private var lastUndoTimestampMs: Long = 0L
    private var manualTocCounter: Int = 0

    fun setDarkMode(enabled: Boolean) {
        themePreferences.setDarkMode(enabled)
        _isDarkMode.value = enabled
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
        EpubCleanForegroundService.start(
            context = appContext,
            source = source,
            metadata = metadata,
            options = _cleaningOptions.value
        )
    }

    fun retryProcessing() {
        val lastSource = processingSnapshot.value.sourceFile ?: return
        val lastMeta = processingSnapshot.value.metadata
        startProcessing(lastSource, lastMeta)
    }

    fun cancelProcessing() {
        EpubCleanForegroundService.cancel(appContext)
    }

    fun resetProcessingSession() {
        _saveUiState.value = SaveUiState.Idle
        _customSaveTreeUri.value = null
        _customSaveFolderLabel.value = null
        manualUndoStack.clear()
        _manualEditorState.value = ManualEditorUiState()
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

                        val updatedHistory = historyManager.addEntry(
                            CleanedFileHistory(
                                fileName = result.outputFileName,
                                filePath = result.savedAbsolutePath ?: result.outputDisplayPath,
                                uriString = result.savedUriString,
                                sourceType = source.fileType.label,
                                totalFixes = totalFixes,
                                junkLinksFixed = snapshot.stats.junkLinksRemoved,
                                whitespaceFixed = snapshot.stats.whitespaceFixes,
                                spellingFixed = acceptedSpellingCount,
                                timestampMillis = System.currentTimeMillis()
                            )
                        )
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
    }

    fun clearAllHistory() {
        _recentFiles.value = historyManager.clearAll()
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
            fontSizeSp = _manualEditorState.value.fontSizeSp
        )

        viewModelScope.launch {
            try {
                val pipeline = EpubCleanPipeline(appContext)
                val chapters = pipeline.loadManualChapters(
                    workspace = workspace,
                    diffs = snapshot.diffs,
                    mode = mode
                )
                _manualEditorState.value = _manualEditorState.value.copy(
                    isLoading = false,
                    chapters = chapters,
                    currentChapterIndex = 0,
                    scrollTargetParagraphIndex = 0,
                    scrollRequestId = System.currentTimeMillis(),
                    manualTocEntries = emptyList(),
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
        _manualEditorState.value = cur.copy(
            currentChapterIndex = clampedChap,
            scrollTargetParagraphIndex = clampedPara,
            scrollRequestId = cur.scrollRequestId + 1L
        )
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
        val refreshedMatches = if (cur.searchQuery.isNotBlank()) {
            computeSearchMatches(previousChapters, cur.searchQuery)
        } else {
            emptyList()
        }
        _manualEditorState.value = cur.copy(
            chapters = previousChapters,
            searchMatches = refreshedMatches,
            activeMatchIndex = if (refreshedMatches.isEmpty()) -1 else cur.activeMatchIndex.coerceIn(0, refreshedMatches.lastIndex),
            canUndo = manualUndoStack.isNotEmpty(),
            feedbackMessage = "Đã hoàn tác chỉnh sửa gần nhất"
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

        val updated = (cur.manualTocEntries + entry)
            .sortedWith(compareBy<ManualTocEntry> { it.chapterIndex }.thenBy { it.paragraphIndex })

        _manualEditorState.value = cur.copy(
            manualTocEntries = updated,
            feedbackMessage = "Đã thêm vào Mục lục: \"$cleanTitle\""
        )
    }

    fun removeManualTocBookmark(entryId: String) {
        val cur = _manualEditorState.value
        _manualEditorState.value = cur.copy(
            manualTocEntries = cur.manualTocEntries.filterNot { it.id == entryId }
        )
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

    fun saveFromManualEditor(mode: SaveMode) {
        val snapshot = processingSnapshot.value
        val source = snapshot.sourceFile ?: return
        val workspace = snapshot.workspace ?: return
        val editorState = _manualEditorState.value

        viewModelScope.launch {
            _saveUiState.value = SaveUiState.Saving
            try {
                val pipeline = EpubCleanPipeline(appContext)
                val finalTempEpub = pipeline.writeManualEditsAndPackEpub(
                    workspace = workspace,
                    chapters = editorState.chapters,
                    manualTocEntries = editorState.manualTocEntries,
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
                        val acceptedSpellingCount = snapshot.diffs.count { it.accepted } + editorState.manualEditCount
                        val totalFixes = snapshot.stats.junkLinksRemoved +
                            snapshot.stats.whitespaceFixes +
                            acceptedSpellingCount

                        val updatedHistory = historyManager.addEntry(
                            CleanedFileHistory(
                                fileName = result.outputFileName,
                                filePath = result.savedAbsolutePath ?: result.outputDisplayPath,
                                uriString = result.savedUriString,
                                sourceType = source.fileType.label,
                                totalFixes = totalFixes,
                                junkLinksFixed = snapshot.stats.junkLinksRemoved,
                                whitespaceFixed = snapshot.stats.whitespaceFixes,
                                spellingFixed = acceptedSpellingCount,
                                timestampMillis = System.currentTimeMillis()
                            )
                        )
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
