package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessedBookDao {
    @Query("SELECT * FROM processed_books ORDER BY processedAtMillis DESC LIMIT 50")
    fun observeRecentBooks(): Flow<List<ProcessedBookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: ProcessedBookEntity): Long

    @Query("DELETE FROM processed_books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM processed_books")
    suspend fun clearAll()
}
