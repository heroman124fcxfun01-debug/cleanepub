package com.example

import com.example.pipeline.EpubWorkspaceManager
import com.example.pipeline.TextCleaningEngine
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class EpubCleanPipelineUnitTest {

    @Test
    fun step1_stripsUrlsAndVietnameseSourceSignatures() {
        val html = """
            <html><body>
                <h1>Chương 1: Khởi đầu</h1>
                <p>Sưu tầm từ thư viện sách https://truyenfull.vn</p>
                <p>Chúc các bạn đọc truyện vui vẻ tại webtruyen.com!</p>
                <p>Trời thu mát mẻ ở Hà Nội. Truy cập www.sachvui.vn để tải thêm.</p>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val result = TextCleaningEngine.cleanJunkAndLinks(doc)

        assertTrue("Expected junk links/lines removed", result.junkRemovedCount >= 3)
        val bodyText = doc.body().text()
        assertFalse(bodyText.contains("truyenfull.vn", ignoreCase = true))
        assertFalse(bodyText.contains("webtruyen.com", ignoreCase = true))
        assertFalse(bodyText.contains("www.sachvui.vn", ignoreCase = true))
        assertFalse(bodyText.contains("Chúc các bạn đọc truyện vui vẻ", ignoreCase = true))
        assertTrue(bodyText.contains("Trời thu mát mẻ ở Hà Nội."))
    }

    @Test
    fun step2_removesOldStylesItalicsAndBindsSingleCleanCss() {
        val html = """
            <html>
            <head>
                <style>p { font-style: italic; color: red; }</style>
                <link rel="stylesheet" href="old.css" />
            </head>
            <body>
                <h2 style="color: blue; font-family: Comic Sans">Chương 2</h2>
                <p style="font-style: italic; color: #9900ff">Anh <i>lặng lẽ</i> bước đi trong <em>màn mưa</em> và <u>nhớ nhà</u>.</p>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        TextCleaningEngine.enforceUnifiedTypography(doc)

        assertEquals(0, doc.select("style").size)
        assertEquals(0, doc.select("i, em, u, font").size)
        assertEquals(1, doc.select("link[rel=stylesheet]").size)
        assertEquals("../Styles/style.css", doc.selectFirst("link[rel=stylesheet]")?.attr("href"))
        assertEquals("", doc.selectFirst("p")?.attr("style"))
        assertEquals("Anh lặng lẽ bước đi trong màn mưa và nhớ nhà.", doc.selectFirst("p")?.text())
    }

    @Test
    fun step3_normalizesWhitespaceAndPunctuationStrictly() {
        val tab = "\t\t"
        val html = "<html><body>" +
            "<p>   &nbsp;&nbsp;Xin   chào${tab}thế   giới   ,   đây  là   tiếng   Việt   !  </p>" +
            "<p>   &nbsp;   </p>" +
            "</body></html>"

        val doc = Jsoup.parse(html)
        val result = TextCleaningEngine.normalizeWhitespace(doc)

        assertTrue(result.whitespaceFixesCount >= 2)
        assertEquals(1, doc.select("p").size)
        assertEquals("Xin chào thế giới, đây là tiếng Việt!", doc.selectFirst("p")?.text())
    }

    @Test
    fun epubPackager_writesUncompressedMimetypeFirstAndValidStructure() {
        val tempRoot = java.nio.file.Files.createTempDirectory("epubclean_test_").toFile()
        try {
            val textDir = File(tempRoot, "stage/OEBPS/Text").apply { mkdirs() }
            val chapFile = File(textDir, "chapter_0001.xhtml")
            chapFile.writeText(
                EpubWorkspaceManager.buildValidXhtmlDocument("Chương 1", "<h1>Chương 1</h1><p>Nội dung thử nghiệm.</p>"),
                Charsets.UTF_8
            )
            val workspace = EpubWorkspaceManager.preparePdfWorkspace(
                bookTitle = "Sách Thử Nghiệm",
                sessionDir = tempRoot,
                chapters = listOf(
                    com.example.pipeline.ChapterDescriptor(
                        id = "chap_1",
                        fileName = "chapter_0001.xhtml",
                        title = "Chương 1",
                        filePath = chapFile.absolutePath
                    )
                )
            )
            val epub = EpubWorkspaceManager.packStandardEpub(workspace)
            assertTrue(epub.exists() && epub.length() > 0L)

            ZipFile(epub).use { zip ->
                val entries = zip.entries().toList()
                assertTrue(entries.isNotEmpty())
                val first = entries.first()
                assertEquals("mimetype", first.name)
                assertEquals(ZipEntry.STORED, first.method)
                val mimeText = zip.getInputStream(first).bufferedReader().readText()
                assertEquals("application/epub+zip", mimeText)

                val names = entries.map { it.name }.toSet()
                assertTrue("META-INF/container.xml" in names)
                assertTrue("OEBPS/content.opf" in names)
                assertTrue("OEBPS/toc.ncx" in names)
                assertTrue("OEBPS/nav.xhtml" in names)
                assertTrue("OEBPS/Styles/style.css" in names)
                assertTrue("OEBPS/Text/chapter_0001.xhtml" in names)
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
