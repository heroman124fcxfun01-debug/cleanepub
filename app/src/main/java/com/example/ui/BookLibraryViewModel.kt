package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BookCollection
import com.example.data.LibraryBook
import com.example.data.LibraryChapterMeta
import com.example.data.LibraryManager
import com.example.data.ReaderPaperTheme
import com.example.data.ReaderPreferences
import com.example.data.ReaderVoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ActiveBookReaderState(
    val book: LibraryBook? = null,
    val toc: List<LibraryChapterMeta> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val paragraphs: List<String> = emptyList(),
    val targetParagraphIndex: Int = 0,
    val chapterLoadNonce: Long = 0L,
    val isLoadingChapter: Boolean = false,
    val autoContinueAudioOnChapterReady: Boolean = false
)

class BookLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val libraryManager = LibraryManager(application.applicationContext)

    private val _books = MutableStateFlow<List<LibraryBook>>(emptyList())
    val books: StateFlow<List<LibraryBook>> = _books.asStateFlow()

    private val _collections = MutableStateFlow<List<BookCollection>>(emptyList())
    val collections: StateFlow<List<BookCollection>> = _collections.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _readerPrefs = MutableStateFlow(libraryManager.getReaderPreferences())
    val readerPrefs: StateFlow<ReaderPreferences> = _readerPrefs.asStateFlow()

    private val _readerState = MutableStateFlow(ActiveBookReaderState())
    val readerState: StateFlow<ActiveBookReaderState> = _readerState.asStateFlow()

    init {
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val (loadedBooks, loadedCollections) = libraryManager.loadLibraryIndex()
            _books.value = loadedBooks
            _collections.value = loadedCollections
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun importBookFromUri(uri: Uri) {
        if (_isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            val result = libraryManager.importBookFromUri(getApplication(), uri)
            result.onSuccess { imported ->
                val (loadedBooks, loadedCollections) = withContext(Dispatchers.IO) {
                    libraryManager.loadLibraryIndex()
                }
                _books.value = loadedBooks
                _collections.value = loadedCollections
                _statusMessage.value = "Đã thêm \"${imported.title}\" vào Thư viện sách"
            }.onFailure { err ->
                _statusMessage.value = err.message ?: "Không thể nhập tệp sách này."
            }
            _isImporting.value = false
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val (updatedBooks, updatedCols) = libraryManager.deleteBook(bookId)
            _books.value = updatedBooks
            _collections.value = updatedCols
        }
    }

    fun createCollection(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.createCollection(clean)
        }
    }

    fun setCollectionBooks(collectionId: String, selectedBookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.setCollectionBooks(collectionId, selectedBookIds)
        }
    }

    fun addBookToCollection(collectionId: String, bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.addBookToCollection(collectionId, bookId)
            _statusMessage.value = "Đã thêm sách vào bộ sưu tập"
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _collections.value = libraryManager.deleteCollection(collectionId)
        }
    }

    // -------------------------------------------------------------------------
    // Book Reader Session & On-Demand Chapter Loading
    // -------------------------------------------------------------------------

    fun openBookForReading(book: LibraryBook, onReady: () -> Unit) {
        _readerState.value = ActiveBookReaderState(
            book = book,
            isLoadingChapter = true
        )
        onReady()
        viewModelScope.launch {
            val (updatedBooks, toc, content, safeChapIdx, safeParaIdx) = withContext(Dispatchers.IO) {
                val updated = libraryManager.markBookOpened(book.id)
                val tocList = libraryManager.loadBookToc(book.id)
                val chapIdx = book.lastChapterIndex.coerceIn(0, (tocList.size - 1).coerceAtLeast(0))
                val chapContent = if (tocList.isNotEmpty()) {
                    libraryManager.loadChapterContent(book.id, tocList[chapIdx])
                } else {
                    null
                }
                val paras = chapContent?.paragraphs.orEmpty()
                val paraIdx = book.lastParagraphIndex.coerceIn(0, (paras.size - 1).coerceAtLeast(0))
                Tuple5(updated, tocList, chapContent, chapIdx, paraIdx)
            }
            _books.value = updatedBooks
            _readerState.value = ActiveBookReaderState(
                book = updatedBooks.firstOrNull { it.id == book.id } ?: book,
                toc = toc,
                currentChapterIndex = safeChapIdx,
                currentChapterTitle = content?.title ?: toc.getOrNull(safeChapIdx)?.title ?: book.title,
                paragraphs = content?.paragraphs ?: listOf("(Sách chưa có nội dung hiển thị.)"),
                targetParagraphIndex = safeParaIdx,
                chapterLoadNonce = System.nanoTime(),
                isLoadingChapter = false,
                autoContinueAudioOnChapterReady = false
            )
        }
    }

    fun selectChapter(
        chapterIndex: Int,
        startParagraphIndex: Int = 0,
        continueAudio: Boolean = false
    ) {
        val current = _readerState.value
        val book = current.book ?: return
        val toc = current.toc
        if (toc.isEmpty()) return
        val safeIdx = chapterIndex.coerceIn(0, toc.lastIndex)

        _readerState.update {
            it.copy(
                currentChapterIndex = safeIdx,
                isLoadingChapter = true,
                autoContinueAudioOnChapterReady = continueAudio
            )
        }

        viewModelScope.launch {
            val (content, updatedBooks) = withContext(Dispatchers.IO) {
                val loaded = libraryManager.loadChapterContent(book.id, toc[safeIdx])
                val safePara = startParagraphIndex.coerceIn(0, (loaded.paragraphs.size - 1).coerceAtLeast(0))
                val updated = libraryManager.updateReadingProgress(book.id, safeIdx, safePara)
                loaded to updated
            }
            _books.value = updatedBooks
            val safePara = startParagraphIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0))
            _readerState.update { state ->
                state.copy(
                    book = updatedBooks.firstOrNull { it.id == book.id } ?: state.book,
                    currentChapterIndex = safeIdx,
                    currentChapterTitle = content.title,
                    paragraphs = content.paragraphs,
                    targetParagraphIndex = safePara,
                    chapterLoadNonce = System.nanoTime(),
                    isLoadingChapter = false,
                    autoContinueAudioOnChapterReady = continueAudio
                )
            }
        }
    }

    fun consumeAutoContinueAudioFlag() {
        _readerState.update { it.copy(autoContinueAudioOnChapterReady = false) }
    }

    fun saveReadingProgress(chapterIndex: Int, paragraphIndex: Int) {
        val book = _readerState.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = libraryManager.updateReadingProgress(
                bookId = book.id,
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex
            )
            _books.value = updated
        }
    }

    // -------------------------------------------------------------------------
    // Reader Appearance & Audio Controls
    // -------------------------------------------------------------------------

    fun adjustReaderFontSize(delta: Float) {
        val updated = _readerPrefs.value.copy(
            fontSizeSp = (_readerPrefs.value.fontSizeSp + delta).coerceIn(
                LibraryManager.MIN_FONT_SIZE_SP,
                LibraryManager.MAX_FONT_SIZE_SP
            )
        )
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun selectReaderPaperTheme(theme: ReaderPaperTheme) {
        val updated = _readerPrefs.value.copy(paperTheme = theme)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun cycleSpeechRate() {
        val nextRate = LibraryManager.nextSpeechRate(_readerPrefs.value.speechRate)
        val updated = _readerPrefs.value.copy(speechRate = nextRate)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    fun cycleVoiceProfile() {
        val nextVoice: ReaderVoiceProfile = _readerPrefs.value.voiceProfile.next()
        val updated = _readerPrefs.value.copy(voiceProfile = nextVoice)
        _readerPrefs.value = updated
        libraryManager.saveReaderPreferences(updated)
    }

    private data class Tuple5<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
