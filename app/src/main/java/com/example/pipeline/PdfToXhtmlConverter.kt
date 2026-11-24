package com.example.pipeline

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

object PdfToXhtmlConverter {

    private val chapterHeadingRegex = Regex(
        """^(CHƯƠNG|Chương|PHẦN|Phần|HỒI|Hồi|CHAPTER|Chapter|MỤC|Mục)\s+([0-9IVXLCDM]+)(\b.*)?$"""
    )
    private val pageNumberOnlyRegex = Regex(
        """^[-–—•·\s]*(?:trang|page|p\.?)?\s*\d{1,5}(?:\s*/\s*\d{1,5})?[-–—•·\s]*$""",
        RegexOption.IGNORE_CASE
    )
    private val sentenceTerminalChars = setOf('.', '!', '?', ':', ';', '…', '”', '"', '»', ')', ']')

    suspend fun convertToXhtmlChapters(
        context: Context,
        pdfFile: File,
        bookTitle: String,
        outputTextDir: File,
        onPageProgress: (currentPage: Int, totalPages: Int) -> Unit
    ): List<ChapterDescriptor> {
        PDFBoxResourceLoader.init(context.applicationContext)
        outputTextDir.mkdirs()

        // Stream PDF with temp-file backing to stay well within 4GB RAM device limits
        PDDocument.load(pdfFile, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val totalPages = document.numberOfPages
            if (totalPages <= 0) {
                throw IllegalStateException("Tệp PDF không có trang nội dung nào.")
            }

            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }

            // Pass 1 (lightweight sample): detect repeating headers & footers
            val sampleStep = (totalPages / 24).coerceAtLeast(1)
            val topLineFreq = mutableMapOf<String, Int>()
            val bottomLineFreq = mutableMapOf<String, Int>()
            var sampledCount = 0

            var samplePage = 1
            while (samplePage <= totalPages) {
                coroutineContext.ensureActive()
                stripper.startPage = samplePage
                stripper.endPage = samplePage
                val lines = stripper.getText(document)
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    sampledCount++
                    val first = normalizeHeaderKey(lines.first())
                    val last = normalizeHeaderKey(lines.last())
                    if (first.length in 2..90) {
                        topLineFreq[first] = (topLineFreq[first] ?: 0) + 1
                    }
                    if (last.length in 2..90 && lines.size > 1) {
                        bottomLineFreq[last] = (bottomLineFreq[last] ?: 0) + 1
                    }
                }
                samplePage += sampleStep
            }

            val repeatThreshold = (sampledCount * 0.35).toInt().coerceAtLeast(3)
            val repeatingHeaders = topLineFreq.filterValues { it >= repeatThreshold }.keys
            val repeatingFooters = bottomLineFreq.filterValues { it >= repeatThreshold }.keys

            // Pass 2: Stream page-by-page, join broken lines, flush chapters directly to disk
            val chapters = mutableListOf<ChapterDescriptor>()
            var currentChapterTitle = bookTitle
            val currentParagraphs = mutableListOf<String>()
            val paragraphBuffer = StringBuilder()
            var pagesInCurrentChapter = 0
            var chapterCounter = 0

            fun flushParagraph() {
                val text = paragraphBuffer.toString().trim()
                if (text.isNotEmpty()) {
                    currentParagraphs.add(text)
                }
                paragraphBuffer.clear()
            }

            fun flushChapterToDisk() {
                flushParagraph()
                if (currentParagraphs.isEmpty()) return
                chapterCounter++
                val fileName = "chapter_%04d.xhtml".format(chapterCounter)
                val outFile = File(outputTextDir, fileName)
                writeXhtmlChapter(outFile, currentChapterTitle, currentParagraphs)
                chapters.add(
                    ChapterDescriptor(
                        id = "chap_$chapterCounter",
                        fileName = fileName,
                        title = currentChapterTitle,
                        filePath = outFile.absolutePath
                    )
                )
                currentParagraphs.clear()
                pagesInCurrentChapter = 0
            }

            for (pageNum in 1..totalPages) {
                coroutineContext.ensureActive()
                onPageProgress(pageNum, totalPages)

                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val rawPageText = stripper.getText(document)
                val rawLines = rawPageText.lines()

                val cleanedLines = rawLines.mapIndexedNotNull { idx, rawLine ->
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty()) return@mapIndexedNotNull ""
                    if (pageNumberOnlyRegex.matches(trimmed)) return@mapIndexedNotNull null
                    val key = normalizeHeaderKey(trimmed)
                    if (idx <= 1 && key in repeatingHeaders) return@mapIndexedNotNull null
                    if (idx >= rawLines.size - 2 && key in repeatingFooters) return@mapIndexedNotNull null
                    trimmed
                }

                for (line in cleanedLines) {
                    if (line.isEmpty()) {
                        // Explicit blank line separates paragraphs when buffer ends a sentence
                        val bufStr = paragraphBuffer.toString().trimEnd()
                        if (bufStr.isNotEmpty() && endsSentence(bufStr)) {
                            flushParagraph()
                        }
                        continue
                    }

                    if (chapterHeadingRegex.matches(line) && line.length <= 100) {
                        flushChapterToDisk()
                        currentChapterTitle = line
                        continue
                    }

                    if (paragraphBuffer.isEmpty()) {
                        paragraphBuffer.append(line)
                    } else {
                        val prevText = paragraphBuffer.toString().trimEnd()
                        if (shouldJoinHardWrappedLine(prevText, line)) {
                            if (prevText.endsWith("-") && !prevText.endsWith(" -")) {
                                paragraphBuffer.setLength(prevText.length - 1)
                                paragraphBuffer.append(line)
                            } else {
                                paragraphBuffer.append(' ').append(line)
                            }
                        } else {
                            flushParagraph()
                            paragraphBuffer.append(line)
                        }
                    }
                }

                pagesInCurrentChapter++
                // Split into manageable chapter chunks if PDF has no explicit chapter headings
                if (pagesInCurrentChapter >= 16 && chapters.isEmpty() && pageNum < totalPages) {
                    flushChapterToDisk()
                    currentChapterTitle = "Phần ${chapterCounter + 1}"
                } else if (pagesInCurrentChapter >= 24 && pageNum < totalPages) {
                    flushChapterToDisk()
                    currentChapterTitle = "Phần ${chapterCounter + 1}"
                }
            }

            flushChapterToDisk()

            if (chapters.isEmpty()) {
                throw IllegalStateException("Không trích xuất được văn bản từ tệp PDF (có thể là PDF dạng ảnh scan).")
            }

            return chapters
        }
    }

    private fun normalizeHeaderKey(line: String): String {
        return line.replace(Regex("""\d+"""), "#")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
    }

    private fun endsSentence(text: String): Boolean {
        val lastChar = text.lastOrNull() ?: return true
        return lastChar in sentenceTerminalChars
    }

    private fun shouldJoinHardWrappedLine(prevLine: String, nextLine: String): Boolean {
        if (prevLine.isEmpty() || nextLine.isEmpty()) return false
        val firstNext = nextLine.first()
        // Dialogue dash always starts a new paragraph
        if (firstNext == '-' || firstNext == '–' || firstNext == '—' || firstNext == '•') {
            return false
        }
        // If previous line did not finish a sentence, join mid-sentence wrap
        if (!endsSentence(prevLine)) {
            return true
        }
        // If next line starts with a lowercase Vietnamese letter, it is a continuation
        return firstNext.isLowerCase()
    }

    private fun writeXhtmlChapter(file: File, title: String, paragraphs: List<String>) {
        val escapedTitle = escapeXml(title)
        val sb = StringBuilder(paragraphs.size * 120)
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<!DOCTYPE html>""")
        sb.appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="vi" lang="vi">""")
        sb.appendLine("<head>")
        sb.appendLine("""  <meta charset="UTF-8" />""")
        sb.appendLine("""  <title>$escapedTitle</title>""")
        sb.appendLine("""  <link rel="stylesheet" type="text/css" href="../Styles/style.css" />""")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("""  <h1>$escapedTitle</h1>""")
        for (p in paragraphs) {
            sb.appendLine("""  <p>${escapeXml(p)}</p>""")
        }
        sb.appendLine("</body>")
        sb.appendLine("</html>")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
