package com.example

import com.example.pipeline.AdCleaner
import com.example.pipeline.ChapterDescriptor
import com.example.pipeline.EnglishDictionary
import com.example.pipeline.EnglishWordFixer
import com.example.pipeline.EpubMetadata
import com.example.pipeline.EpubMetadataEditor
import com.example.pipeline.EpubWorkspaceManager
import com.example.pipeline.InputFileType
import com.example.pipeline.OfflineVietnameseDict
import com.example.pipeline.PunctuationFixer
import com.example.pipeline.TextCleaner
import com.example.pipeline.TextCleaningEngine
import com.example.pipeline.TocGenerator
import com.example.pipeline.VietnameseSyllableFixer
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

            val enWordsFile = listOf(
                File("src/main/assets/en_words.txt"),
                File("app/src/main/assets/en_words.txt")
            ).firstOrNull { it.exists() }
                ?: error("Could not locate en_words.txt asset for unit tests")

            OfflineVietnameseDict.loadFromFiles(sylFile, wordsFile)
            EnglishDictionary.loadFromFile(enWordsFile)
            assertTrue("OfflineVietnameseDict should be loaded", OfflineVietnameseDict.isLoaded)
            assertTrue("EnglishDictionary should be loaded", EnglishDictionary.isLoaded)
            assertTrue("Expected > 7000 syllables", OfflineVietnameseDict.syllableCount > 7_000)
            assertTrue("Expected > 40000 words", OfflineVietnameseDict.wordCount > 40_000)
            assertTrue("Expected > 50000 English words", EnglishDictionary.wordCount > 50_000)
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

    @Test
    fun englishWordFixer_fixesSplitAndFuzzyEnglishWordsWhileProtectingVietnamese() {
        val fixer = EnglishWordFixer(EnglishDictionary, OfflineVietnameseDict)

        // Exact requested fuzzy/OCR split case: "i den  fi ni ty" -> "identity"
        val resIdentity = fixer.fixText("i den  fi ni ty")
        assertEquals("identity", resIdentity.cleanedText)
        assertEquals(1, resIdentity.fixCount)

        // Capitalized preservation
        val resCap = fixer.fixText("I den  fi ni ty")
        assertEquals("Identity", resCap.cleanedText)

        // Exact split English words
        assertEquals("computer", fixer.fixText("com pu ter").cleanedText)
        assertEquals("psychology", fixer.fixText("psy cho lo gy").cleanedText)
        assertEquals("internet", fixer.fixText("in ter net").cleanedText)
        assertEquals("identity", fixer.fixText("i den ti ty").cleanedText)

        // Mixed Vietnamese + broken English sentence
        val mixed = fixer.fixText("Khái niệm i den  fi ni ty trong com pu ter rất quan trọng.")
        assertEquals("Khái niệm identity trong computer rất quan trọng.", mixed.cleanedText)
        assertEquals(2, mixed.fixCount)

        // Strict Vietnamese & normal English protection
        val protectedSentences = listOf(
            "Tôi đi học với bạn bè ở Hà Nội.",
            "Của tâm lí học hiện đại là một lĩnh vực thú vị.",
            "It is a good day to be in the room.",
            "ba me cho di cho hom qua"
        )
        for (s in protectedSentences) {
            assertEquals("Should not alter valid sentence: $s", s, fixer.fixText(s).cleanedText)
        }
    }

    @Test
    fun epubMetadataEditor_writesAndReadsMetadataAndCoverImage() {
        val tempRoot = java.nio.file.Files.createTempDirectory("epubclean_meta_test_").toFile()
        try {
            val textDir = File(tempRoot, "stage/OEBPS/Text").apply { mkdirs() }
            val chapFile = File(textDir, "chapter_0001.xhtml")
            chapFile.writeText(
                EpubWorkspaceManager.buildValidXhtmlDocument("Chương 1", "<h1>Chương 1</h1><p>Nội dung thử nghiệm.</p>"),
                Charsets.UTF_8
            )

            // Dummy JPEG header bytes to simulate a picked cover image
            val fakeCoverFile = File(tempRoot, "picked_cover.jpg")
            val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22, 0x33)
            fakeCoverFile.writeBytes(coverBytes)

            val editedMetadata = EpubMetadata(
                title = "Tâm Lý Học Đám Đông",
                creator = "Gustave Le Bon",
                identifier = "urn:isbn:978-604-000-001",
                language = "vi",
                newCoverImagePath = fakeCoverFile.absolutePath
            )

            val workspace = EpubWorkspaceManager.preparePdfWorkspace(
                bookTitle = "Tâm Lý Học Đám Đông",
                sessionDir = tempRoot,
                chapters = listOf(
                    com.example.pipeline.ChapterDescriptor(
                        id = "chap_1",
                        fileName = "chapter_0001.xhtml",
                        title = "Chương 1",
                        filePath = chapFile.absolutePath
                    )
                )
            ).copy(metadata = editedMetadata)

            val epub = EpubWorkspaceManager.packStandardEpub(workspace)
            assertTrue(epub.exists() && epub.length() > 0L)

            // Verify OPF & OEBPS/images/cover.jpg inside packed EPUB
            ZipFile(epub).use { zip ->
                val coverEntry = zip.getEntry("OEBPS/images/cover.jpg")
                assertNotNull("Expected OEBPS/images/cover.jpg inside packed EPUB", coverEntry)

                val opfEntry = zip.getEntry("OEBPS/content.opf")
                assertNotNull(opfEntry)
                val opfText = zip.getInputStream(opfEntry).bufferedReader().readText()
                assertTrue(opfText.contains("<dc:title>Tâm Lý Học Đám Đông</dc:title>"))
                assertTrue(opfText.contains("<dc:creator>Gustave Le Bon</dc:creator>"))
                assertTrue(opfText.contains("<dc:identifier id=\"BookId\">urn:isbn:978-604-000-001</dc:identifier>"))
                assertTrue(opfText.contains("<dc:language>vi</dc:language>"))
                assertTrue(opfText.contains("properties=\"cover-image\""))
                assertTrue(opfText.contains("<meta name=\"cover\" content=\"cover-image\" />"))
            }

            // Round-trip read back using EpubMetadataEditor.readMetadataFromEpubFile
            val readBack = EpubMetadataEditor.readMetadataFromEpubFile(
                epubFile = epub,
                fallback = EpubMetadata("Fallback", "Fallback", "fallback-id", "en")
            )
            assertEquals("Tâm Lý Học Đám Đông", readBack.title)
            assertEquals("Gustave Le Bon", readBack.creator)
            assertEquals("urn:isbn:978-604-000-001", readBack.identifier)
            assertEquals("vi", readBack.language)
            assertNotNull("Cover bytes should be extracted on round-trip", readBack.existingCoverBytes)
            assertTrue(coverBytes.contentEquals(readBack.existingCoverBytes))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun adCleaner_removesPromoSpamDecorativeLinesHiddenAndBannerBlocks() {
        val html = """
            <html><body>
                <h1>Chương 1: Khởi đầu</h1>
                <p>Tải ebook miễn phí tại truyenfull.vn</p>
                <p>Đọc truyện nhanh nhất tại webtruyen.com</p>
                <p>Chúc các bạn đọc truyện vui vẻ!</p>
                <p>Nguồn sưu tầm: Thư viện Ebook</p>
                <p>Bản quyền thuộc về Nhóm Dịch ABC</p>
                <p>Theo dõi fanpage để cập nhật chương mới</p>
                <p>Ủng hộ nhóm dịch qua ví điện tử</p>
                <p>Mọi chi tiết xin liên hệ admin@ebook.vn</p>
                <p>*********</p>
                <p>=========</p>
                <p>---------</p>
                <p>~~~~~~~~~</p>
                <div style="display: none;">Quảng cáo ẩn trong mã nguồn</div>
                <div class="ad-banner"><img src="images/promo_banner.jpg" alt="banner" /></div>
                <p><img src="images/story_illustration.jpg" alt="Minh họa nhân vật" /></p>
                <p>Ánh nắng buổi sớm chiếu qua khung cửa sổ nhỏ. (Đọc truyện nhanh nhất tại sachhay.vn)</p>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val result = AdCleaner.cleanDocument(doc)

        assertTrue("Expected at least 14 junk blocks removed, got ${result.removedCount}", result.removedCount >= 14)
        val bodyText = doc.body().text()
        assertFalse(bodyText.contains("Tải ebook miễn phí", ignoreCase = true))
        assertFalse(bodyText.contains("Đọc truyện nhanh nhất", ignoreCase = true))
        assertFalse(bodyText.contains("Chúc các bạn đọc truyện vui vẻ", ignoreCase = true))
        assertFalse(bodyText.contains("Nguồn sưu tầm", ignoreCase = true))
        assertFalse(bodyText.contains("Bản quyền thuộc về", ignoreCase = true))
        assertFalse(bodyText.contains("Theo dõi fanpage", ignoreCase = true))
        assertFalse(bodyText.contains("Ủng hộ nhóm dịch", ignoreCase = true))
        assertFalse(bodyText.contains("Mọi chi tiết xin liên hệ", ignoreCase = true))
        assertFalse(bodyText.contains("*****"))
        assertFalse(bodyText.contains("====="))
        assertFalse(bodyText.contains("-----"))
        assertFalse(bodyText.contains("~~~~~"))
        assertFalse(bodyText.contains("Quảng cáo ẩn"))

        // Real story text and real story illustration must be preserved
        assertTrue(bodyText.contains("Ánh nắng buổi sớm chiếu qua khung cửa sổ nhỏ."))
        assertEquals(1, doc.select("img[src=images/story_illustration.jpg]").size)
        assertEquals(0, doc.select("img[src=images/promo_banner.jpg]").size)
    }

    @Test
    fun punctuationFixer_normalizesSpacesDuplicatesBracketsAndCapitalizationSafely() {
        // 1. Space before punctuation + missing space after punctuation + auto-capitalize after . ? !
        val res1 = PunctuationFixer.fixParagraph("Xin chào ,thế giới .hôm nay trời đẹp quá !bạn khỏe không ?rất khỏe ;cảm ơn :nhiều .")
        assertEquals("Xin chào, thế giới. Hôm nay trời đẹp quá! Bạn khỏe không? Rất khỏe; cảm ơn: nhiều.", res1.cleanedText)
        assertTrue(res1.fixCount > 0)

        // 2. Brackets & curly quotes inner spaces
        val res2 = PunctuationFixer.fixParagraph("Cô nói “ xin chào ( bạn cũ ) và [ mọi người ] ” rất nhẹ nhàng.")
        assertEquals("Cô nói “xin chào (bạn cũ) và [mọi người]” rất nhẹ nhàng.", res2.cleanedText)

        // 3. Duplicate punctuation collapse while preserving ellipsis "..."
        val res3 = PunctuationFixer.fixParagraph("Thật vậy sao?? Tuyệt vời!! Anh bước đi,, rồi dừng lại.. và... im lặng.")
        assertEquals("Thật vậy sao? Tuyệt vời! Anh bước đi, rồi dừng lại. Và... im lặng.", res3.cleanedText)

        // 4. Protect decimal numbers (3.14) and abbreviations (TP.HCM, PGS.TS, v.v.)
        val res4 = PunctuationFixer.fixParagraph("Tại TP.HCM, PGS.TS An đo được giá trị 3.14 và 2.50 trong thí nghiệm v.v.")
        assertEquals("Tại TP.HCM, PGS.TS An đo được giá trị 3.14 và 2.50 trong thí nghiệm v.v.", res4.cleanedText)
        assertEquals(0, res4.fixCount)
    }

    @Test
    fun tocGenerator_detectsSufficientVsMissingTocAndPreservesOrRegenerates() {
        val tempRoot = java.nio.file.Files.createTempDirectory("epubclean_toc_test_").toFile()
        try {
            // Case 1: PDF -> always missing TOC -> regenerate by default ON
            val pdfInspection = TocGenerator.inspectOriginalToc(
                unpackedDir = null,
                opfFile = null,
                fileType = InputFileType.PDF,
                detectedChapterCount = 3,
                originalFileToStagedName = emptyMap()
            )
            assertFalse(pdfInspection.hasSufficientToc)
            assertTrue(pdfInspection.shouldRegenerateByDefault)
            assertEquals("Chưa có / Thiếu mục lục", pdfInspection.statusSummary)

            // Case 2: EPUB with sufficient toc.ncx (3 entries for 3 chapters) -> default OFF & preserves original titles
            val unpacked = File(tempRoot, "unpacked").apply { mkdirs() }
            val oebps = File(unpacked, "OEBPS").apply { mkdirs() }
            val textSrc = File(oebps, "Text").apply { mkdirs() }
            val src1 = File(textSrc, "c1.xhtml").apply { writeText("<html><body><h1>Chương 1 Mới</h1></body></html>") }
            val src2 = File(textSrc, "c2.xhtml").apply { writeText("<html><body><h1>Chương 2 Mới</h1></body></html>") }
            val src3 = File(textSrc, "c3.xhtml").apply { writeText("<html><body><h1>Chương 3 Mới</h1></body></html>") }

            File(oebps, "toc.ncx").writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="np1" playOrder="1"><navLabel><text>Mở đầu Gốc</text></navLabel><content src="Text/c1.xhtml"/></navPoint>
                    <navPoint id="np2" playOrder="2"><navLabel><text>Giữa truyện Gốc</text></navLabel><content src="Text/c2.xhtml"/></navPoint>
                    <navPoint id="np3" playOrder="3"><navLabel><text>Kết thúc Gốc</text></navLabel><content src="Text/c3.xhtml"/></navPoint>
                  </navMap>
                </ncx>
                """.trimIndent()
            )

            val map = mapOf(
                src1.canonicalPath to "chapter_0001.xhtml",
                src2.canonicalPath to "chapter_0002.xhtml",
                src3.canonicalPath to "chapter_0003.xhtml"
            )

            val epubInspection = TocGenerator.inspectOriginalToc(
                unpackedDir = unpacked,
                opfFile = null,
                fileType = InputFileType.EPUB,
                detectedChapterCount = 3,
                originalFileToStagedName = map
            )
            assertTrue(epubInspection.hasSufficientToc)
            assertFalse(epubInspection.shouldRegenerateByDefault)
            assertEquals(3, epubInspection.existingEntryCount)
            assertEquals("Hiện có: 3 mục (Đủ)", epubInspection.statusSummary)

            // Build staged workspace and verify preserve (regenerateToc=false) vs regenerate (regenerateToc=true)
            val stagedWorkspace = EpubWorkspaceManager.preparePdfWorkspace(
                bookTitle = "Test Book",
                sessionDir = File(tempRoot, "session").apply { mkdirs() },
                chapters = listOf(
                    ChapterDescriptor("c1", "chapter_0001.xhtml", "Chương 1", src1.absolutePath),
                    ChapterDescriptor("c2", "chapter_0002.xhtml", "Chương 2", src2.absolutePath),
                    ChapterDescriptor("c3", "chapter_0003.xhtml", "Chương 3", src3.absolutePath)
                )
            ).copy(tocInspection = epubInspection)

            val preservedEntries = TocGenerator.resolveEntriesForPackaging(stagedWorkspace, regenerateToc = false)
            assertEquals(listOf("Mở đầu Gốc", "Giữa truyện Gốc", "Kết thúc Gốc"), preservedEntries.map { it.title })

            val regeneratedEntries = TocGenerator.resolveEntriesForPackaging(stagedWorkspace, regenerateToc = true)
            assertEquals(listOf("Chương 1 Mới", "Chương 2 Mới", "Chương 3 Mới"), regeneratedEntries.map { it.title })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun vietnameseSyllableFixer_fixesRealBookParagraphWithViterbiDpAndPerformance() {
        // Verify 4-tier Unigram + Bigram frequency scores
        assertEquals(OfflineVietnameseDict.SCORE_COMMON, OfflineVietnameseDict.getSyllableScore("có"))
        assertEquals(OfflineVietnameseDict.SCORE_COMMON, OfflineVietnameseDict.getSyllableScore("một"))
        assertEquals(OfflineVietnameseDict.SCORE_COMMON, OfflineVietnameseDict.getSyllableScore("nhiều"))
        assertEquals(OfflineVietnameseDict.SCORE_COMMON, OfflineVietnameseDict.getSyllableScore("điểm"))
        assertEquals(OfflineVietnameseDict.SCORE_COMMON, OfflineVietnameseDict.getSyllableScore("nữa"))
        assertEquals(OfflineVietnameseDict.SCORE_COMMON, OfflineVietnameseDict.getSyllableScore("những"))

        assertEquals(OfflineVietnameseDict.BIGRAM_BONUS, OfflineVietnameseDict.getBigramBonus("có", "một"))
        assertEquals(OfflineVietnameseDict.BIGRAM_BONUS, OfflineVietnameseDict.getBigramBonus("hơn", "nữa"))
        assertEquals(OfflineVietnameseDict.BIGRAM_BONUS, OfflineVietnameseDict.getBigramBonus("đặc", "điểm"))

        assertEquals(OfflineVietnameseDict.SCORE_INVALID_GHOST, OfflineVietnameseDict.getSyllableScore("cóm"))
        assertEquals(OfflineVietnameseDict.SCORE_INVALID_GHOST, OfflineVietnameseDict.getSyllableScore("ột"))
        assertEquals(OfflineVietnameseDict.SCORE_INVALID_GHOST, OfflineVietnameseDict.getSyllableScore("ều"))
        assertEquals(OfflineVietnameseDict.SCORE_INVALID_GHOST, OfflineVietnameseDict.getSyllableScore("ểm"))
        assertEquals(OfflineVietnameseDict.SCORE_INVALID_GHOST, OfflineVietnameseDict.getSyllableScore("ững"))

        val fixer = VietnameseSyllableFixer(OfflineVietnameseDict)

        // Exact book paragraph requested by user (must match 100%)
        val input = "cóm ột vài khía cạnh của đặc tính có xu hướng không thay đổi theo thời gian – giống như việc xác định ai cao, ai thấp vậy. Nhưng dù cho có nhi ều hơn nữ anh ững phẩm chất và đặc đi ểm cố định"
        val expected = "có một vài khía cạnh của đặc tính có xu hướng không thay đổi theo thời gian – giống như việc xác định ai cao, ai thấp vậy. Nhưng dù cho có nhiều hơn nữa những phẩm chất và đặc điểm cố định"

        val result = fixer.fixText(input)
        assertEquals(expected, result.cleanedText)
        assertEquals(4, result.fixCount)

        // Capitalized leading case preservation ("Cóm ột" -> "Có một")
        assertEquals("Có một vài khía cạnh.", fixer.fix("Cóm ột vài khía cạnh."))

        // Performance check: ~2,300-word chapter (50x paragraphs) with JIT warmup & CI-safe upper bound
        val chapterParagraphs = List(50) { input }
        repeat(20) { fixer.fix(input) }
        val startNs = System.nanoTime()
        for (p in chapterParagraphs) {
            fixer.fix(p)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        assertTrue("Expected full chapter processing < 500ms on CI, took ${elapsedMs}ms", elapsedMs < 500.0)
    }
}
