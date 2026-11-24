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
        ProcessingStateHolder.reset()
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
                val finalTempEpub = pipeline.rebuildEpubWithSelectedDiffs(workspace, snapshot.diffs)

                val result = withContext(Dispatchers.IO) {
                    FilePathResolver.saveCleanedEpub(
                        context = appContext,
                        tempEpubFile = finalTempEpub,
                        source = source,
                        mode = mode
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
}
