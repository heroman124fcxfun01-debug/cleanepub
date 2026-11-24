package com.example.service

import com.example.pipeline.CleaningOptions
import com.example.pipeline.EpubMetadata
import com.example.pipeline.ProcessingSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-scoped state holder bridging EpubCleanForegroundService and MainViewModel
 * so progress survives screen lock, backgrounding, and configuration changes.
 */
object ProcessingStateHolder {
    private val _snapshot = MutableStateFlow(ProcessingSnapshot())
    val snapshot: StateFlow<ProcessingSnapshot> = _snapshot.asStateFlow()

    @Volatile
    var pendingMetadata: EpubMetadata? = null

    @Volatile
    var pendingOptions: CleaningOptions = CleaningOptions()

    fun publish(newSnapshot: ProcessingSnapshot) {
        _snapshot.value = newSnapshot
    }

    fun updateActiveMetadata(metadata: EpubMetadata) {
        pendingMetadata = metadata
        _snapshot.update { current ->
            current.copy(
                metadata = metadata,
                workspace = current.workspace?.copy(
                    bookTitle = metadata.title,
                    bookAuthor = metadata.creator,
                    metadata = metadata
                )
            )
        }
    }

    fun toggleDiffAccepted(diffId: String) {
        _snapshot.update { current ->
            current.copy(
                diffs = current.diffs.map { item ->
                    if (item.id == diffId) item.copy(accepted = !item.accepted) else item
                }
            )
        }
    }

    fun setAllDiffsAccepted(accepted: Boolean) {
        _snapshot.update { current ->
            current.copy(
                diffs = current.diffs.map { it.copy(accepted = accepted) }
            )
        }
    }

    fun reset() {
        pendingMetadata = null
        _snapshot.value = ProcessingSnapshot()
    }
}
