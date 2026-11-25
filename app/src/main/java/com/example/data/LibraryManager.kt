package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.pipeline.EpubMetadata
import com.example.pipeline.EpubMetadataEditor
import com.example.pipeline.EpubWorkspaceManager
import com.example.pipeline.FilePathResolver
import com.example.pipeline.InputFileType
import com.example.pipeline.PdfToXhtmlConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Lightweight library book metadata persisted in `filesDir/book_library/library_index.json`.
 * Chapter text is NEVER stored in the index — it is stored per-chapter on disk and loaded
 * on demand to guarantee smooth performance on 4GB RAM devices.
 */
data class LibraryBook(
    val id: String,
    val title: String,
    val author: String,
    val sourceType: String, // "EPUB" or "PDF"
    val coverPath: String?,
    val chapterCount: Int,
    val addedAtMillis: Long,
    val lastOpenedAtMillis: Long = 0L,
    val lastChapterIndex: Int = 0,
    val lastParagraphIndex: Int = 0
)

/**
 * User-created book collection inside Apple Books style Library.
 */
data class BookCollection(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val bookIds: List<String> = emptyList()
)

data class LibraryChapterMeta(
    val index: Int,
    val title: String,
    val fileName: String
)

data class LibraryChapterContent(
    val index: Int,
    val title: String,
    val paragraphs: List<String>
)

enum class ReaderPaperTheme(val storageKey: String, val label: String) {
    BOOK_BROWN("book_brown", "Nâu sách"),
    WARM_CREAM("warm_cream", "Vàng ngả"),
    PURE_WHITE("pure_white", "Trắng");

    companion object {
        fun fromKey(key: String?): ReaderPaperTheme {
            return entries.firstOrNull { it.storageKey == key } ?: BOOK_BROWN
        }
    }
}

/**
 * 3 curated Offline Vietnamese TTS voice profiles (2 Female, 1 Male).
 */
enum class ReaderVoiceProfile(
    val storageKey: String,
    val shortLabel: String,
    val pitch: Float,
    val rateFactor: Float,
    val isMale: Boolean
) {
    FEMALE_1(
        storageKey = "female_1",
        shortLabel = "Nữ 1",
        pitch = 1.04f,
        rateFactor = 1.00f,
        isMale = false
    ),
    FEMALE_2(
        storageKey = "female_2",
        shortLabel = "Nữ 2",
        pitch = 1.18f,
        rateFactor = 1.02f,
        isMale = false
    ),
    MALE_1(
        storageKey = "male_1",
        shortLabel = "Nam 1",
        pitch = 0.80f,
        rateFactor = 0.96f,
        isMale = true
    );

    fun next(): ReaderVoiceProfile {
        val list = entries
        return list[(ordinal + 1) % list.size]
    }

    companion object {
        fun fromKey(key: String?): ReaderVoiceProfile {
            return entries.firstOrNull { it.storageKey == key } ?: FEMALE_1
        }
    }
}

data class ReaderPreferences(
    val fontSizeSp: Float = 17f,
    val paperTheme: ReaderPaperTheme = ReaderPaperTheme.BOOK_BROWN,
    val speechRate: Float = 1.0f,
    val voiceProfile: ReaderVoiceProfile = ReaderVoiceProfile.FEMALE_1
)

/**
 * 100% Offline Book Library & Reading Progress Manager (`filesDir/book_library/`).
 * Extracts titles, compressed mini covers, and per-chapter JSON files for instant low-RAM reading.
 */
class LibraryManager(
    private val libraryRootDir: File,
    private val prefs: SharedPreferences? = null
) {

    constructor(context: Context) : this(
        libraryRootDir = File(context.applicationContext.filesDir, LIBRARY_DIR_NAME),
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val indexFile: File
        get() = File(libraryRootDir, INDEX_FILE_NAME)

    private val booksRootDir: File
        get() = File(libraryRootDir, BOOKS_SUBDIR)

    // -------------------------------------------------------------------------
    // Index Load / Save (Books + Collections)
    // -------------------------------------------------------------------------

    @Synchronized
    fun loadLibraryIndex(): Pair<List<LibraryBook>, List<BookCollection>> {
        if (!indexFile.exists()) return emptyList<LibraryBook>() to emptyList()
        return runCatching {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val booksArr = root.optJSONArray("books") ?: JSONArray()
            val books = ArrayList<LibraryBook>(booksArr.length())
            for (i in 0 until booksArr.length()) {
                val obj = booksArr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isEmpty()) continue
                val rawCover = obj.optString("coverPath").takeIf { it.isNotBlank() }
                val validCover = rawCover?.takeIf { File(it).exists() }
                books.add(
                    LibraryBook(
                        id = id,
                        title = obj.optString("title").ifBlank { "Sách chưa đặt tên" },
                        author = obj.optString("author").ifBlank { "Không rõ tác giả" },
                        sourceType = obj.optString("sourceType").ifBlank { "EPUB" },
                        coverPath = validCover,
                        chapterCount = obj.optInt("chapterCount", 1).coerceAtLeast(1),
                        addedAtMillis = obj.optLong("addedAtMillis", 0L),
                        lastOpenedAtMillis = obj.optLong("lastOpenedAtMillis", 0L),
                        lastChapterIndex = obj.optInt("lastChapterIndex", 0).coerceAtLeast(0),
                        lastParagraphIndex = obj.optInt("lastParagraphIndex", 0).coerceAtLeast(0)
                    )
                )
            }

            val collectionsArr = root.optJSONArray("collections") ?: JSONArray()
            val validBookIds = books.map { it.id }.toSet()
            val collections = ArrayList<BookCollection>(collectionsArr.length())
            for (i in 0 until collectionsArr.length()) {
                val obj = collectionsArr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                if (id.isEmpty() || name.isEmpty()) continue
                val idsArr = obj.optJSONArray("bookIds") ?: JSONArray()
                val ids = mutableListOf<String>()
                for (j in 0 until idsArr.length()) {
                    val bid = idsArr.optString(j)
                    if (bid.isNotBlank() && bid in validBookIds && bid !in ids) {
                        ids.add(bid)
                    }
                }
                collections.add(
                    BookCollection(
                        id = id,
                        name = name,
                        createdAtMillis = obj.optLong("createdAtMillis", 0L),
                        bookIds = ids
                    )
                )
            }
            sortAllBooks(books) to collections.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList<LibraryBook>() to emptyList())
    }

    @Synchronized
    private fun saveLibraryIndex(
        books: List<LibraryBook>,
        collections: List<BookCollection>
    ) {
        libraryRootDir.mkdirs()
        val root = JSONObject()
        val booksArr = JSONArray()
        for (book in books) {
            booksArr.put(
                JSONObject().apply {
                    put("id", book.id)
                    put("title", book.title)
                    put("author", book.author)
                    put("sourceType", book.sourceType)
                    put("coverPath", book.coverPath.orEmpty())
                    put("chapterCount", book.chapterCount)
                    put("addedAtMillis", book.addedAtMillis)
                    put("lastOpenedAtMillis", book.lastOpenedAtMillis)
                    put("lastChapterIndex", book.lastChapterIndex)
                    put("lastParagraphIndex", book.lastParagraphIndex)
                }
            )
        }
        val collectionsArr = JSONArray()
        for (col in collections) {
            val idsArr = JSONArray()
            col.bookIds.forEach { idsArr.put(it) }
            collectionsArr.put(
                JSONObject().apply {
                    put("id", col.id)
                    put("name", col.name)
                    put("createdAtMillis", col.createdAtMillis)
                    put("bookIds", idsArr)
                }
            )
        }
        root.put("books", booksArr)
        root.put("collections", collectionsArr)

        val tmpFile = File(libraryRootDir, "$INDEX_FILE_NAME.tmp")
        tmpFile.writeText(root.toString(), Charsets.UTF_8)
        if (!tmpFile.renameTo(indexFile)) {
            indexFile.writeText(root.toString(), Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Book Import (EPUB & PDF -> local filesDir/book_library/books/{id}/)
    // -------------------------------------------------------------------------

    suspend fun importBookFromUri(context: Context, uri: Uri): Result<LibraryBook> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = FilePathResolver.inspectSourceFile(context, uri)
                    ?: throw IllegalArgumentException("Chỉ hỗ trợ nhập tệp .epub hoặc .pdf vào Thư viện sách.")

                val tempDir = File(context.cacheDir, "lib_import_${System.currentTimeMillis()}").apply {
                    mkdirs()
                }
                try {
                    val ext = if (source.fileType == InputFileType.PDF) "pdf" else "epub"
                    val copiedInput = File(tempDir, "input.$ext")
                    FilePathResolver.copyInputToWorkFile(context, source, copiedInput)

                    val book = when (source.fileType) {
                        InputFileType.EPUB -> importBookFromEpubFile(
                            epubFile = copiedInput,
                            fallbackTitle = source.baseNameWithoutExt,
                            tempSessionDir = File(tempDir, "work")
                        )

                        InputFileType.PDF -> importBookFromPdfFile(
                            context = context,
                            pdfFile = copiedInput,
                            fallbackTitle = source.baseNameWithoutExt,
                            tempSessionDir = File(tempDir, "work")
                        )
                    }
                    book
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        }

    /**
     * Pure-File EPUB importer (also directly testable in JVM unit tests).
     */
    fun importBookFromEpubFile(
        epubFile: File,
        fallbackTitle: String,
        tempSessionDir: File
    ): LibraryBook {
        val safeFallback = fallbackTitle.trim().ifBlank { epubFile.nameWithoutExtension.ifBlank { "Sách EPUB" } }
        val fallbackMeta = EpubMetadata(
            title = safeFallback,
            creator = "Không rõ tác giả",
            identifier = "urn:uuid:${UUID.randomUUID()}",
            language = "vi"
        )
        val meta = runCatching {
            EpubMetadataEditor.readMetadataFromEpubFile(epubFile, fallbackMeta)
        }.getOrDefault(fallbackMeta)

        val workspace = EpubWorkspaceManager.unpackAndStageEpub(
            sourceEpubFile = epubFile,
            fallbackTitle = meta.title.ifBlank { safeFallback },
            sessionDir = tempSessionDir
        )

        val bookId = UUID.randomUUID().toString()
        val bookDir = File(booksRootDir, bookId).apply { mkdirs() }
        val chaptersDir = File(bookDir, CHAPTERS_SUBDIR).apply { mkdirs() }

        val coverPath = meta.existingCoverBytes?.let { rawBytes ->
            saveCompressedCoverBytes(rawBytes, File(bookDir, COVER_FILE_NAME))
        }

        val tocList = mutableListOf<LibraryChapterMeta>()
        workspace.chapters.forEachIndexed { idx, chap ->
            val paragraphs = extractParagraphsFromXhtmlFile(File(chap.filePath))
            val chapFileName = "chap_%04d.json".format(idx + 1)
            val chapTitle = chap.title.trim().ifBlank { "Chương ${idx + 1}" }
            writeChapterJson(
                file = File(chaptersDir, chapFileName),
                title = chapTitle,
                paragraphs = paragraphs.ifEmpty { listOf("(Chương này chưa có nội dung văn bản.)") }
            )
            tocList.add(LibraryChapterMeta(index = idx, title = chapTitle, fileName = chapFileName))
        }

        if (tocList.isEmpty()) {
            bookDir.deleteRecursively()
            throw IllegalStateException("Không tìm thấy chương văn bản nào trong tệp EPUB.")
        }

        writeTocJson(File(bookDir, TOC_FILE_NAME), tocList)

        val resolvedTitle = meta.title.trim()
            .takeIf { it.isNotEmpty() && !it.equals("Sách chưa đặt tên", true) }
            ?: workspace.bookTitle.trim().ifBlank { safeFallback }
        val resolvedAuthor = meta.creator.trim().ifBlank { workspace.bookAuthor.ifBlank { "Không rõ tác giả" } }

        val newBook = LibraryBook(
            id = bookId,
            title = resolvedTitle,
            author = resolvedAuthor,
            sourceType = "EPUB",
            coverPath = coverPath,
            chapterCount = tocList.size,
            addedAtMillis = System.currentTimeMillis()
        )
        registerBookInIndex(newBook)
        return newBook
    }

    private suspend fun importBookFromPdfFile(
        context: Context,
        pdfFile: File,
        fallbackTitle: String,
        tempSessionDir: File
    ): LibraryBook {
        val safeTitle = fallbackTitle.trim().ifBlank { pdfFile.nameWithoutExtension.ifBlank { "Tài liệu PDF" } }
        val textDir = File(tempSessionDir, "pdf_text").apply { mkdirs() }
        val chapters = PdfToXhtmlConverter.convertToXhtmlChapters(
            context = context,
            pdfFile = pdfFile,
            bookTitle = safeTitle,
            outputTextDir = textDir,
            onPageProgress = { _, _ -> }
        )

        val bookId = UUID.randomUUID().toString()
        val bookDir = File(booksRootDir, bookId).apply { mkdirs() }
        val chaptersDir = File(bookDir, CHAPTERS_SUBDIR).apply { mkdirs() }

        val coverPath = renderPdfFirstPageCover(pdfFile, File(bookDir, COVER_FILE_NAME))

        val tocList = mutableListOf<LibraryChapterMeta>()
        chapters.forEachIndexed { idx, chap ->
            val paragraphs = extractParagraphsFromXhtmlFile(File(chap.filePath))
            val chapFileName = "chap_%04d.json".format(idx + 1)
            val chapTitle = chap.title.trim().ifBlank { "Phần ${idx + 1}" }
            writeChapterJson(
                file = File(chaptersDir, chapFileName),
                title = chapTitle,
                paragraphs = paragraphs.ifEmpty { listOf("(Trang này chưa có nội dung văn bản.)") }
            )
            tocList.add(LibraryChapterMeta(index = idx, title = chapTitle, fileName = chapFileName))
        }

        if (tocList.isEmpty()) {
            bookDir.deleteRecursively()
            throw IllegalStateException("Không trích xuất được nội dung từ tệp PDF.")
        }

        writeTocJson(File(bookDir, TOC_FILE_NAME), tocList)

        val newBook = LibraryBook(
            id = bookId,
            title = safeTitle,
            author = "Tài liệu PDF",
            sourceType = "PDF",
            coverPath = coverPath,
            chapterCount = tocList.size,
            addedAtMillis = System.currentTimeMillis()
        )
        registerBookInIndex(newBook)
        return newBook
    }

    @Synchronized
    private fun registerBookInIndex(book: LibraryBook) {
        val (books, collections) = loadLibraryIndex()
        val updated = listOf(book) + books.filterNot { it.id == book.id }
        saveLibraryIndex(updated, collections)
    }

    // -------------------------------------------------------------------------
    // Chapter Loading (On-Demand 1 Chapter at a Time for 4GB RAM)
    // -------------------------------------------------------------------------

    fun loadBookToc(bookId: String): List<LibraryChapterMeta> {
        val tocFile = File(File(booksRootDir, bookId), TOC_FILE_NAME)
        if (!tocFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(tocFile.readText(Charsets.UTF_8))
            val list = ArrayList<LibraryChapterMeta>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    LibraryChapterMeta(
                        index = obj.optInt("index", i),
                        title = obj.optString("title").ifBlank { "Chương ${i + 1}" },
                        fileName = obj.optString("fileName")
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun loadChapterContent(bookId: String, chapterMeta: LibraryChapterMeta): LibraryChapterContent {
        val chapFile = File(File(File(booksRootDir, bookId), CHAPTERS_SUBDIR), chapterMeta.fileName)
        if (!chapFile.exists()) {
            return LibraryChapterContent(
                index = chapterMeta.index,
                title = chapterMeta.title,
                paragraphs = listOf("(Không tìm thấy tệp nội dung chương này.)")
            )
        }
        return runCatching {
            val obj = JSONObject(chapFile.readText(Charsets.UTF_8))
            val title = obj.optString("title").ifBlank { chapterMeta.title }
            val arr = obj.optJSONArray("paragraphs") ?: JSONArray()
            val paragraphs = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val text = arr.optString(i).trim()
                if (text.isNotEmpty()) paragraphs.add(text)
            }
            LibraryChapterContent(
                index = chapterMeta.index,
                title = title,
                paragraphs = paragraphs.ifEmpty { listOf("(Chương trống.)") }
            )
        }.getOrElse {
            LibraryChapterContent(
                index = chapterMeta.index,
                title = chapterMeta.title,
                paragraphs = listOf("(Không thể đọc nội dung chương này.)")
            )
        }
    }

    // -------------------------------------------------------------------------
    // Reading Progress, Collections & Deletion
    // -------------------------------------------------------------------------

    @Synchronized
    fun markBookOpened(bookId: String, nowMillis: Long = System.currentTimeMillis()): List<LibraryBook> {
        val (books, collections) = loadLibraryIndex()
        val updated = books.map { b ->
            if (b.id == bookId) b.copy(lastOpenedAtMillis = nowMillis) else b
        }
        saveLibraryIndex(updated, collections)
        return sortAllBooks(updated)
    }

    @Synchronized
    fun updateReadingProgress(
        bookId: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<LibraryBook> {
        val (books, collections) = loadLibraryIndex()
        val updated = books.map { b ->
            if (b.id == bookId) {
                b.copy(
                    lastOpenedAtMillis = nowMillis,
                    lastChapterIndex = chapterIndex.coerceIn(0, (b.chapterCount - 1).coerceAtLeast(0)),
                    lastParagraphIndex = paragraphIndex.coerceAtLeast(0)
                )
            } else {
                b
            }
        }
        saveLibraryIndex(updated, collections)
        return sortAllBooks(updated)
    }

    @Synchronized
    fun deleteBook(bookId: String): Pair<List<LibraryBook>, List<BookCollection>> {
        val (books, collections) = loadLibraryIndex()
        val updatedBooks = books.filterNot { it.id == bookId }
        val updatedCollections = collections.map { col ->
            col.copy(bookIds = col.bookIds.filterNot { it == bookId })
        }
        File(booksRootDir, bookId).deleteRecursively()
        saveLibraryIndex(updatedBooks, updatedCollections)
        return sortAllBooks(updatedBooks) to updatedCollections
    }

    @Synchronized
    fun createCollection(rawName: String, nowMillis: Long = System.currentTimeMillis()): List<BookCollection> {
        val cleanName = rawName.trim()
        val (books, collections) = loadLibraryIndex()
        if (cleanName.isEmpty()) return collections
        val created = BookCollection(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAtMillis = nowMillis,
            bookIds = emptyList()
        )
        val updated = listOf(created) + collections
        saveLibraryIndex(books, updated)
        return updated
    }

    @Synchronized
    fun setCollectionBooks(collectionId: String, selectedBookIds: Set<String>): List<BookCollection> {
        val (books, collections) = loadLibraryIndex()
        val validIds = books.map { it.id }.toSet()
        val filtered = selectedBookIds.filter { it in validIds }
        val updated = collections.map { col ->
            if (col.id == collectionId) col.copy(bookIds = filtered) else col
        }
        saveLibraryIndex(books, updated)
        return updated
    }

    @Synchronized
    fun addBookToCollection(collectionId: String, bookId: String): List<BookCollection> {
        val (books, collections) = loadLibraryIndex()
        val updated = collections.map { col ->
            if (col.id == collectionId && bookId !in col.bookIds) {
                col.copy(bookIds = col.bookIds + bookId)
            } else {
                col
            }
        }
        saveLibraryIndex(books, updated)
        return updated
    }

    @Synchronized
    fun deleteCollection(collectionId: String): List<BookCollection> {
        val (books, collections) = loadLibraryIndex()
        val updated = collections.filterNot { it.id == collectionId }
        saveLibraryIndex(books, updated)
        return updated
    }

    // -------------------------------------------------------------------------
    // Reader Preferences (Font size 13..24sp, Paper Theme, TTS Speed & Voice)
    // -------------------------------------------------------------------------

    fun getReaderPreferences(): ReaderPreferences {
        val p = prefs ?: return ReaderPreferences()
        return ReaderPreferences(
            fontSizeSp = p.getFloat(KEY_FONT_SIZE, 17f).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP),
            paperTheme = ReaderPaperTheme.fromKey(p.getString(KEY_PAPER_THEME, null)),
            speechRate = p.getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(0.8f, 1.8f),
            voiceProfile = ReaderVoiceProfile.fromKey(p.getString(KEY_VOICE_PROFILE, null))
        )
    }

    fun saveReaderPreferences(readerPrefs: ReaderPreferences) {
        prefs?.edit()
            ?.putFloat(KEY_FONT_SIZE, readerPrefs.fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP))
            ?.putString(KEY_PAPER_THEME, readerPrefs.paperTheme.storageKey)
            ?.putFloat(KEY_SPEECH_RATE, readerPrefs.speechRate)
            ?.putString(KEY_VOICE_PROFILE, readerPrefs.voiceProfile.storageKey)
            ?.apply()
    }

    // -------------------------------------------------------------------------
    // Helpers (Cover Compression, PDF Thumbnail, XHTML Paragraph Extraction)
    // -------------------------------------------------------------------------

    private fun saveCompressedCoverBytes(rawBytes: ByteArray, destFile: File): String? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > COVER_THUMB_MAX_PX ||
                bounds.outHeight / sample > COVER_THUMB_MAX_PX
            ) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return null
            try {
                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                destFile.absolutePath
            } finally {
                bmp.recycle()
            }
        }.getOrNull()
    }

    private fun renderPdfFirstPageCover(pdfFile: File, destFile: File): String? {
        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        val targetW = 200
                        val aspect = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.45f
                        val targetH = (targetW * aspect).toInt().coerceIn(220, 340)
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                            }
                            destFile.absolutePath
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            }
        }.getOrNull()
    }

    private fun writeTocJson(file: File, toc: List<LibraryChapterMeta>) {
        val arr = JSONArray()
        for (item in toc) {
            arr.put(
                JSONObject().apply {
                    put("index", item.index)
                    put("title", item.title)
                    put("fileName", item.fileName)
                }
            )
        }
        file.writeText(arr.toString(), Charsets.UTF_8)
    }

    private fun writeChapterJson(file: File, title: String, paragraphs: List<String>) {
        val arr = JSONArray()
        for (p in paragraphs) arr.put(p)
        val obj = JSONObject().apply {
            put("title", title)
            put("paragraphs", arr)
        }
        file.writeText(obj.toString(), Charsets.UTF_8)
    }

    companion object {
        private const val LIBRARY_DIR_NAME = "book_library"
        private const val INDEX_FILE_NAME = "library_index.json"
        private const val BOOKS_SUBDIR = "books"
        private const val CHAPTERS_SUBDIR = "chapters"
        private const val TOC_FILE_NAME = "toc.json"
        private const val COVER_FILE_NAME = "cover.jpg"
        private const val COVER_THUMB_MAX_PX = 240

        private const val PREFS_NAME = "epub_clean_book_reader_prefs"
        private const val KEY_FONT_SIZE = "reader_font_size_sp"
        private const val KEY_PAPER_THEME = "reader_paper_theme"
        private const val KEY_SPEECH_RATE = "reader_speech_rate"
        private const val KEY_VOICE_PROFILE = "reader_voice_profile"

        const val MIN_FONT_SIZE_SP = 13f
        const val MAX_FONT_SIZE_SP = 24f

        val SPEECH_RATE_STEPS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.8f)

        fun nextSpeechRate(current: Float): Float {
            val idx = SPEECH_RATE_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.05f }
            return if (idx < 0) 1.0f else SPEECH_RATE_STEPS[(idx + 1) % SPEECH_RATE_STEPS.size]
        }

        fun formatSpeechRate(rate: Float): String {
            return String.format(java.util.Locale.US, "%.1fx", rate)
        }

        /**
         * "Tất cả sách": newest added books first.
         */
        fun sortAllBooks(books: List<LibraryBook>): List<LibraryBook> {
            return books.sortedByDescending { it.addedAtMillis }
        }

        /**
         * "Đọc gần đây": only books opened for reading, sorted by most recent open timestamp.
         */
        fun sortRecentlyRead(books: List<LibraryBook>): List<LibraryBook> {
            return books
                .filter { it.lastOpenedAtMillis > 0L }
                .sortedByDescending { it.lastOpenedAtMillis }
        }

        /**
         * Cleanly extracts readable paragraphs from an XHTML/HTML chapter file.
         */
        fun extractParagraphsFromXhtmlFile(file: File): List<String> {
            if (!file.exists()) return emptyList()
            val doc = Jsoup.parse(file, "UTF-8")
            val body = doc.body() ?: return emptyList()
            val blocks = body.select("h1, h2, h3, h4, p, blockquote, li")
            val paragraphs = mutableListOf<String>()
            if (blocks.isNotEmpty()) {
                for (el in blocks) {
                    // Avoid double-extracting <p> inside <blockquote> or <li>
                    if (el.tagName() in setOf("blockquote", "li") && el.select("p").isNotEmpty()) {
                        continue
                    }
                    val text = el.text().replace('\u00A0', ' ').trim()
                    if (text.isNotEmpty()) {
                        paragraphs.add(text)
                    }
                }
            }
            if (paragraphs.isEmpty()) {
                val raw = body.text().replace('\u00A0', ' ').trim()
                if (raw.isNotEmpty()) {
                    raw.split(Regex("""\n+"""))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { paragraphs.add(it) }
                }
            }
            return paragraphs
        }
    }
}
