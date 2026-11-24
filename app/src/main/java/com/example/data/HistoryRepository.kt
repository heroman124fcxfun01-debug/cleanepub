package com.example.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: ProcessedBookDao) {
    val recentBooks: Flow<List<ProcessedBookEntity>> = dao.observeRecentBooks()

    suspend fun recordProcessedBook(entity: ProcessedBookEntity) {
        dao.insert(entity)
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }
}
