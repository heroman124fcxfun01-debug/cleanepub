package com.example

import com.example.pipeline.EpubWorkspaceManager
import com.example.pipeline.OfflineVietnameseDict
import com.example.pipeline.TextCleaner
import com.example.pipeline.TextCleaningEngine
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class EpubCleanPipelineUnitTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadOfflineVietnameseDictionaries() {
            val sylFile = listOf(
                File("src/main/assets/vi_syllables.txt"),
                File("app/src/main/assets/vi_syllables.txt")
            ).firstOrNull { it.exists() }
                ?: error("Could not locate vi_syllables.txt asset for unit tests")

            val wordsFile = listOf(
                File("src/main/assets/vi_words.txt"),
                File("app/src/main/assets/vi_words.txt")
            ).firstOrNull { it.exists() }
                ?: error("Could not locate vi_words.txt asset for unit tests")

            OfflineVietnameseDict.loadFromFiles(sylFile, wordsFile)
            assertTrue("OfflineVietnameseDict should be loaded", OfflineVietnameseDict.isLoaded)
            assertTrue("Expected > 7000 syllables", OfflineVietnameseDict.syllableCount > 7_000)
            assertTrue("Expected > 40000 words", OfflineVietnameseDict.wordCount > 40_000)
        }
    }

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
    fun offlineDictAndTextCleaner_fixesCase1FakeWordSplit() {
        val cleaner = TextCleaner(OfflineVietnameseDict)

        val res1 = cleaner.cleanParagraph("Củ  a  tâm lí")
        assertEquals("Của tâm lí", res1.cleanedText)
        assertTrue(res1.fakeSplitFixes >= 1)

        val res2 = cleaner.cleanParagraph("Khô ng có gì thay đổi trong ngà y hôm qua.")
        assertEquals("Không có gì thay đổi trong ngày hôm qua.", res2.cleanedText)

        val res3 = cleaner.cleanParagraph("Tô i nghĩ điều đó đượ c tạo ra từ nỗ lực củ a mỗi người.")
        assertEquals("Tôi nghĩ điều đó được tạo ra từ nỗ lực của mỗi người.", res3.cleanedText)
    }

    @Test
    fun offlineDictAndTextCleaner_fixesCase2SlidingSpacesAndMultiTokenDp() {
        val cleaner = TextCleaner(OfflineVietnameseDict)

        val res1 = cleaner.cleanParagraph("Anh cần chú ýt ới từng chi tiết nhỏ.")
        assertEquals("Anh cần chú ý tới từng chi tiết nhỏ.", res1.cleanedText)
        assertTrue(res1.slidingSpaceFixes >= 1)

        val res2 = cleaner.cleanParagraph("t ôi đi học vào n gày mai.")
        assertEquals("tôi đi học vào ngày mai.", res2.cleanedText)

        val res3 = cleaner.cleanParagraph("n ga y ma i trời sẽ sáng.")
        assertEquals("ngay mai trời sẽ sáng.", res3.cleanedText)

        val res4 = cleaner.cleanParagraph("n gà y ma i trời sẽ sáng.")
        assertEquals("ngày mai trời sẽ sáng.", res4.cleanedText)

        val res5 = cleaner.cleanParagraph("Hãy chú ýtới cảm xúc củ atâm lí học.")
        assertEquals("Hãy chú ý tới cảm xúc của tâm lí học.", res5.cleanedText)

        val res6 = cleaner.cleanParagraph("Nhữ ng người bạ n cũ đã quay về.")
        assertEquals("Những người bạn cũ đã quay về.", res6.cleanedText)
    }

    @Test
    fun offlineDictAndTextCleaner_protectsValidPhrasesAndExceptions() {
        val cleaner = TextCleaner(OfflineVietnameseDict)

        val safeSentences = listOf(
            "Ông ấy nói trời ơi sao mà lạnh thế!",
            "Cô ấy chú ý rất kỹ khi bước vào khu A.",
            "Chương I: Anh cho ai mượn cuốn sách đó à?",
            "Em à, cái ao trước nhà to à?",
            "Lương y như từ mẫu, củ, a đều khác nhau."
        )

        for (sentence in safeSentences) {
            val res = cleaner.cleanParagraph(sentence)
            assertEquals("Should not corrupt valid sentence: $sentence", sentence, res.cleanedText)
        }
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

    @Test
    fun onDeviceProofreader_fixesVietnameseDiacriticsOcrAndProtectsProperNouns() {
        val engine = com.example.pipeline.OnDeviceVietnameseProofreader()
        val sample = """
            Trương Vô Kỵ vận dụng nội công và kiếm khí trong tông môn.
            Sau đó Trương Vô Kỵ gặp lão giả tu luyện đan điền.
        """.trimIndent()
        val ctx = engine.analyzeGlobalContext(sample)
        assertEquals("Kiếm hiệp / Tiên hiệp", ctx.genre)
        assertTrue("Proper noun should be detected", ctx.properNouns.contains("Trương Vô Kỵ"))

        val input = "Trương Vô Kỵ rất vui vẽ khi sử lý công việc một cách dể dàng, hoà bình và khoẻ mạnh."
        val fix = engine.proofreadParagraph(input, ctx)
        assertTrue(fix != null)
        assertEquals(
            "Trương Vô Kỵ rất vui vẻ khi xử lý công việc một cách dễ dàng, hòa bình và khỏe mạnh.",
            fix!!.correctedText
        )
    }
}
