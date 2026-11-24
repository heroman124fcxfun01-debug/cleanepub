package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.HistoryRepository
import com.example.data.ProcessedBookEntity
import com.example.pipeline.EpubCleanPipeline
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MainViewModel"

sealed interface SaveUiState {
    data object Idle : SaveUiState
    data object Saving : SaveUiState
    data class Completed(val result: SaveResult.Success) : SaveUiState
    data class Error(val message: String) : SaveUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val historyRepository = HistoryRepository(
        AppDatabase.getInstance(appContext).processedBookDao()
    )

    val recentBooks: StateFlow<List<ProcessedBookEntity>> = historyRepository.recentBooks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val processingSnapshot: StateFlow<ProcessingSnapshot> = ProcessingStateHolder.snapshot

    private val _hasAllFilesAccess = MutableStateFlow(FilePathResolver.hasAllFilesAccess(appContext))
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _saveUiState = MutableStateFlow<SaveUiState>(SaveUiState.Idle)
    val saveUiState: StateFlow<SaveUiState> = _saveUiState.asStateFlow()

    private val _homeErrorBanner = MutableStateFlow<String?>(null)
    val homeErrorBanner: StateFlow<String?> = _homeErrorBanner.asStateFlow()

    fun refreshStoragePermission() {
        _hasAllFilesAccess.value = FilePathResolver.hasAllFilesAccess(appContext)
    }

    fun clearHomeError() {
        _homeErrorBanner.value = null
    }

    fun onFilePicked(uri: Uri): Boolean {
        _homeErrorBanner.value = null
        val inspected = FilePathResolver.inspectSourceFile(appContext, uri)
        if (inspected == null) {
            _homeErrorBanner.value = "Vui lòng chọn tệp định dạng .epub hoặc .pdf hợp lệ."
            return false
        }
        startProcessing(inspected)
        return true
    }

    fun startProcessing(source: SourceFileInfo) {
        _saveUiState.value = SaveUiState.Idle
        EpubCleanForegroundService.start(appContext, source)
    }

    fun retryProcessing() {
        val lastSource = processingSnapshot.value.sourceFile ?: return
        startProcessing(lastSource)
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
                        historyRepository.recordProcessedBook(
                            ProcessedBookEntity(
                                originalFileName = source.displayName,
                                outputFileName = result.outputFileName,
                                outputFilePath = result.savedAbsolutePath ?: result.outputDisplayPath,
                                outputUriString = result.savedUriString,
                                sourceFileType = source.fileType.label,
                                detectedGenre = snapshot.bookContext?.genre.orEmpty().ifBlank { "Sách tiếng Việt" },
                                junkLinksFixed = snapshot.stats.junkLinksRemoved,
                                whitespaceFixed = snapshot.stats.whitespaceFixes,
                                spellingFixed = acceptedSpellingCount,
                                saveMode = mode.code
                            )
                        )
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

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteEntry(id)
        }
    }

    fun isProcessingActive(): Boolean {
        return processingSnapshot.value.phase == ProcessingPhase.RUNNING
    }
}
