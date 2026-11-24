package com.example.service

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

    fun publish(newSnapshot: ProcessingSnapshot) {
        _snapshot.value = newSnapshot
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
        _snapshot.value = ProcessingSnapshot()
    }
}
