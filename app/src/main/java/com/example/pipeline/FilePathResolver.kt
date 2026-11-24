package com.example.pipeline

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "FilePathResolver"

object FilePathResolver {

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            readGranted && writeGranted
        }
    }

    fun buildAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun inspectSourceFile(context: Context, uri: Uri): SourceFileInfo? {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers do not grant persistable write permissions; still usable.
        } catch (_: Exception) {
            // Ignore non-persistable URI providers.
        }

        var displayName = "book.epub"
        var fileSize = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }

        val resolvedPath = resolveAbsolutePath(context, uri)
        if (resolvedPath != null) {
            val f = File(resolvedPath)
            if (displayName == "book.epub" && f.name.isNotBlank()) {
                displayName = f.name
            }
            if (fileSize <= 0L && f.exists()) {
                fileSize = f.length()
            }
        }

        val mimeType = context.contentResolver.getType(uri).orEmpty().lowercase()
        val lowerName = displayName.lowercase()
        val fileType = when {
            lowerName.endsWith(".epub") || mimeType.contains("epub") -> InputFileType.EPUB
            lowerName.endsWith(".pdf") || mimeType.contains("pdf") -> InputFileType.PDF
            else -> return null
        }

        val parentDir = resolvedPath?.let { File(it).parent }

        return SourceFileInfo(
            displayName = displayName,
            sourceUriString = uri.toString(),
            resolvedFilePath = resolvedPath,
            resolvedParentDir = parentDir,
            fileType = fileType,
            fileSizeBytes = fileSize
        )
    }

    fun copyInputToWorkFile(context: Context, source: SourceFileInfo, destFile: File): File {
        destFile.parentFile?.mkdirs()
        val directFile = source.resolvedFilePath?.let { File(it) }
        if (directFile != null && directFile.exists() && directFile.canRead()) {
            FileInputStream(directFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
            return destFile
        }

        val uri = Uri.parse(source.sourceUriString)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 32 * 1024)
            }
        } ?: throw IllegalStateException("Không thể đọc dữ liệu từ tệp đã chọn.")
        return destFile
    }

    fun saveCleanedEpub(
        context: Context,
        tempEpubFile: File,
        source: SourceFileInfo,
        mode: SaveMode
    ): SaveResult {
        if (!tempEpubFile.exists() || tempEpubFile.length() == 0L) {
            return SaveResult.Failure("Tệp EPUB đầu ra không tồn tại hoặc rỗng.")
        }

        return try {
            when (mode) {
                SaveMode.REPLACE_ORIGINAL -> saveReplaceOriginal(context, tempEpubFile, source)
                SaveMode.KEEP_ORIGINAL -> saveKeepOriginal(context, tempEpubFile, source)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving EPUB", e)
            SaveResult.Failure(e.localizedMessage ?: "Không thể ghi tệp vào bộ nhớ.")
        }
    }

    private fun saveReplaceOriginal(
        context: Context,
        tempEpubFile: File,
        source: SourceFileInfo
    ): SaveResult {
        val originalPath = source.resolvedFilePath
        val parentDir = source.resolvedParentDir

        // 1. Direct filesystem write (enabled by MANAGE_EXTERNAL_STORAGE)
        if (originalPath != null && parentDir != null) {
            val dir = File(parentDir)
            if (dir.exists() && dir.canWrite()) {
                val targetFile = if (source.fileType == InputFileType.EPUB) {
                    File(originalPath)
                } else {
                    // Input was PDF -> output must always be valid .epub in same folder; remove old PDF if replacing
                    File(dir, "${source.baseNameWithoutExt}.epub")
                }

                val stagingFile = File(dir, ".tmp_${System.currentTimeMillis()}_${targetFile.name}")
                FileInputStream(tempEpubFile).use { input ->
                    FileOutputStream(stagingFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                val renamed = stagingFile.renameTo(targetFile)
                if (!renamed) {
                    stagingFile.copyTo(targetFile, overwrite = true)
                    stagingFile.delete()
                }

                if (source.fileType == InputFileType.PDF) {
                    val originalPdf = File(originalPath)
                    if (originalPdf.exists() && originalPdf.absolutePath != targetFile.absolutePath) {
                        originalPdf.delete()
                    }
                }

                return SaveResult.Success(
                    outputFileName = targetFile.name,
                    outputDisplayPath = formatFriendlyPath(targetFile.absolutePath),
                    savedAbsolutePath = targetFile.absolutePath,
                    savedUriString = Uri.fromFile(targetFile).toString(),
                    mode = SaveMode.REPLACE_ORIGINAL
                )
            }
        }

        // 2. Fallback: overwrite directly via ContentResolver if EPUB URI allows write
        if (source.fileType == InputFileType.EPUB) {
            val uri = Uri.parse(source.sourceUriString)
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                FileInputStream(tempEpubFile).use { input ->
                    input.copyTo(out, bufferSize = 32 * 1024)
                }
                return SaveResult.Success(
                    outputFileName = source.displayName,
                    outputDisplayPath = source.displayParentFolder + "/" + source.displayName,
                    savedAbsolutePath = source.resolvedFilePath,
                    savedUriString = source.sourceUriString,
                    mode = SaveMode.REPLACE_ORIGINAL
                )
            }
        }

        // 3. Final safe fallback if storage manager wasn't granted yet
        return saveToFallbackDocumentsDir(context, tempEpubFile, "${source.baseNameWithoutExt}.epub", SaveMode.REPLACE_ORIGINAL)
    }

    private fun saveKeepOriginal(
        context: Context,
        tempEpubFile: File,
        source: SourceFileInfo
    ): SaveResult {
        val outputName = source.cleanCopyFileName
        val parentDir = source.resolvedParentDir

        if (parentDir != null) {
            val dir = File(parentDir)
            if (dir.exists() && dir.canWrite()) {
                val targetFile = resolveUniqueFile(dir, outputName)
                FileInputStream(tempEpubFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }
                return SaveResult.Success(
                    outputFileName = targetFile.name,
                    outputDisplayPath = formatFriendlyPath(targetFile.absolutePath),
                    savedAbsolutePath = targetFile.absolutePath,
                    savedUriString = Uri.fromFile(targetFile).toString(),
                    mode = SaveMode.KEEP_ORIGINAL
                )
            }
        }

        return saveToFallbackDocumentsDir(context, tempEpubFile, outputName, SaveMode.KEEP_ORIGINAL)
    }

    private fun saveToFallbackDocumentsDir(
        context: Context,
        tempEpubFile: File,
        desiredName: String,
        mode: SaveMode
    ): SaveResult {
        val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val appDir = File(publicDocs, "EpubCleanAI").apply { mkdirs() }
        val targetDir = if (appDir.exists() && appDir.canWrite()) {
            appDir
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "EpubCleanAI")
                .apply { mkdirs() }
        }
        val targetFile = resolveUniqueFile(targetDir, desiredName)
        FileInputStream(tempEpubFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, bufferSize = 32 * 1024)
            }
        }
        return SaveResult.Success(
            outputFileName = targetFile.name,
            outputDisplayPath = formatFriendlyPath(targetFile.absolutePath),
            savedAbsolutePath = targetFile.absolutePath,
            savedUriString = Uri.fromFile(targetFile).toString(),
            mode = mode
        )
    }

    private fun resolveUniqueFile(dir: File, fileName: String): File {
        val direct = File(dir, fileName)
        if (!direct.exists()) return direct
        val base = fileName.removeSuffix(".epub")
        var counter = 2
        while (counter < 999) {
            val candidate = File(dir, "${base}_$counter.epub")
            if (!candidate.exists()) return candidate
            counter++
        }
        return File(dir, "${base}_${System.currentTimeMillis()}.epub")
    }

    fun formatFriendlyPath(absolutePath: String): String {
        return absolutePath.removePrefix("/storage/emulated/0")
            .ifBlank { absolutePath }
    }

    fun buildOpenEpubIntent(context: Context, absolutePath: String?, uriString: String?): Intent? {
        val targetUri = when {
            !absolutePath.isNullOrBlank() && File(absolutePath).exists() -> {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(absolutePath)
                )
            }
            !uriString.isNullOrBlank() -> Uri.parse(uriString)
            else -> return null
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "application/epub+zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun resolveAbsolutePath(context: Context, uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        if (DocumentsContract.isDocumentUri(context, uri)) {
            val authority = uri.authority
            val docId = DocumentsContract.getDocumentId(uri)

            if (authority == "com.android.externalstorage.documents") {
                val parts = docId.split(":")
                if (parts.size >= 2) {
                    val volume = parts[0]
                    val relPath = parts.drop(1).joinToString(":")
                    return if ("primary".equals(volume, ignoreCase = true)) {
                        "${Environment.getExternalStorageDirectory().absolutePath}/$relPath"
                    } else {
                        "/storage/$volume/$relPath"
                    }
                }
            }

            if (authority == "com.android.providers.downloads.documents") {
                if (docId.startsWith("raw:")) {
                    return docId.removePrefix("raw:")
                }
                if (docId.startsWith("msf:") || docId.all { it.isDigit() }) {
                    val numericId = docId.removePrefix("msf:").toLongOrNull()
                    if (numericId != null) {
                        val mediaPath = queryMediaStorePath(context, numericId)
                        if (mediaPath != null) return mediaPath
                    }
                }
                // Fallback: try matching file in standard Download directory by display name
                val downloadFile = findInDownloadsByName(context, uri)
                if (downloadFile != null) return downloadFile
            }

            if (authority == "com.android.providers.media.documents") {
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val numericId = parts[1].toLongOrNull()
                    if (numericId != null) {
                        return queryMediaStorePath(context, numericId)
                    }
                }
            }
        }

        return queryDataColumn(context, uri)
    }

    private fun queryMediaStorePath(context: Context, id: Long): String? {
        val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        } else {
            ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), id)
        }
        return queryDataColumn(context, contentUri)
    }

    private fun queryDataColumn(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun findInDownloadsByName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
        if (name.isNullOrBlank()) return null
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidate = File(dlDir, name!!)
        return if (candidate.exists()) candidate.absolutePath else null
    }
}
