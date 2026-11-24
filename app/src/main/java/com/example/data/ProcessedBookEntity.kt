package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processed_books")
data class ProcessedBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalFileName: String,
    val outputFileName: String,
    val outputFilePath: String,
    val outputUriString: String?,
    val sourceFileType: String, // "EPUB" or "PDF"
    val detectedGenre: String,
    val junkLinksFixed: Int,
    val whitespaceFixed: Int,
    val spellingFixed: Int,
    val saveMode: String, // "REPLACE" or "KEEP_COPY"
    val processedAtMillis: Long = System.currentTimeMillis()
)
