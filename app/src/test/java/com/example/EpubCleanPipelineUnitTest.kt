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

    @Test
    fun tocGenerator_mergesManualBookmarksAndPacksWithOriginalSnapshot() {
        val tempRoot = java.nio.file.Files.createTempDirectory("epubclean_manual_toc_test_").toFile()
        try {
            val textDir = File(tempRoot, "stage/OEBPS/Text").apply { mkdirs() }
            val c1 = File(textDir, "chapter_0001.xhtml").apply {
                writeText(
                    EpubWorkspaceManager.buildValidXhtmlDocument(
                        "Chương 1",
                        "<h1>Chương 1</h1><p>Đoạn mở đầu.</p><p>Mục nhỏ đặc biệt cần đánh dấu.</p>"
                    ),
                    Charsets.UTF_8
                )
            }
            val c2 = File(textDir, "chapter_0002.xhtml").apply {
                writeText(
                    EpubWorkspaceManager.buildValidXhtmlDocument(
                        "Chương 2",
                        "<h1>Chương 2</h1><p>Nội dung chương hai.</p>"
                    ),
                    Charsets.UTF_8
                )
            }

            val workspace = EpubWorkspaceManager.preparePdfWorkspace(
                bookTitle = "Sách Thử Nghiệm 1.1",
                sessionDir = tempRoot,
                chapters = listOf(
                    ChapterDescriptor("chap_1", "chapter_0001.xhtml", "Chương 1", c1.absolutePath),
                    ChapterDescriptor("chap_2", "chapter_0002.xhtml", "Chương 2", c2.absolutePath)
                )
            )

            // Verify pre-clean original_text snapshot was created for Split-Screen Comparison
            val originalDirPath = workspace.originalTextDirPath
            assertNotNull("Expected originalTextDirPath to be populated", originalDirPath)
            assertTrue(File(originalDirPath!!, "chapter_0001.xhtml").exists())
            assertTrue(File(originalDirPath, "chapter_0002.xhtml").exists())

            val manualBookmarks = listOf(
                com.example.pipeline.ManualTocEntry(
                    id = "bm_1",
                    title = "Mục nhỏ đặc biệt",
                    chapterIndex = 0,
                    chapterFileName = "chapter_0001.xhtml",
                    paragraphIndex = 2,
                    anchorId = "manual_toc_1"
                )
            )

            val resolved = TocGenerator.resolveEntriesForPackaging(
                workspace = workspace,
                regenerateToc = true,
                manualEntries = manualBookmarks
            )
            assertEquals(
                listOf("Chương 1", "Mục nhỏ đặc biệt", "Chương 2"),
                resolved.map { it.title }
            )
            assertEquals(
                listOf("Text/chapter_0001.xhtml", "Text/chapter_0001.xhtml#manual_toc_1", "Text/chapter_0002.xhtml"),
                resolved.map { it.href }
            )

            val epub = EpubWorkspaceManager.packStandardEpub(
                workspace = workspace,
                regenerateToc = true,
                manualTocEntries = manualBookmarks
            )
            ZipFile(epub).use { zip ->
                val nav = zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).bufferedReader().readText()
                val ncx = zip.getInputStream(zip.getEntry("OEBPS/toc.ncx")).bufferedReader().readText()
                assertTrue(nav.contains("href=\"Text/chapter_0001.xhtml#manual_toc_1\""))
                assertTrue(nav.contains("Mục nhỏ đặc biệt"))
                assertTrue(ncx.contains("src=\"Text/chapter_0001.xhtml#manual_toc_1\""))
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun textDiffHelper_highlightsExactChangedWordsFast() {
        val original = "Củ  a  tâm lí học có nhi ều điểm đặc biệt."
        val current = "Của tâm lí học có nhiều điểm đặc biệt."

        val diff = com.example.pipeline.TextDiffHelper.computeWordDiffRanges(original, current)
        assertTrue(diff.hasDifferences)

        val origMarked = diff.originalChangedSpans.map { original.substring(it.start, it.end) }
        val currMarked = diff.currentChangedSpans.map { current.substring(it.start, it.end) }

        assertEquals(listOf("Củ  a", "nhi ều"), origMarked)
        assertEquals(listOf("Của", "nhiều"), currMarked)

        // Identical text produces zero diff spans
        val identical = com.example.pipeline.TextDiffHelper.computeWordDiffRanges(current, current)
        assertFalse(identical.hasDifferences)
        assertTrue(identical.originalChangedSpans.isEmpty())
        assertTrue(identical.currentChangedSpans.isEmpty())
    }

    @Test
    fun tocGenerator_renamesAndDeletesEntriesSyncedToEpub() {
        val tempRoot = java.nio.file.Files.createTempDirectory("epubclean_toc_edit_test_").toFile()
        try {
            val textDir = File(tempRoot, "stage/OEBPS/Text").apply { mkdirs() }
            val c1 = File(textDir, "chapter_0001.xhtml").apply {
                writeText(EpubWorkspaceManager.buildValidXhtmlDocument("Chương 1", "<h1>Chương 1</h1><p>A</p>"))
            }
            val c2 = File(textDir, "chapter_0002.xhtml").apply {
                writeText(EpubWorkspaceManager.buildValidXhtmlDocument("Chương 2", "<h1>Chương 2</h1><p>B</p>"))
            }
            val c3 = File(textDir, "chapter_0003.xhtml").apply {
                writeText(EpubWorkspaceManager.buildValidXhtmlDocument("Chương 3", "<h1>Chương 3</h1><p>C</p>"))
            }

            val workspace = EpubWorkspaceManager.preparePdfWorkspace(
                bookTitle = "Test TOC Edit",
                sessionDir = tempRoot,
                chapters = listOf(
                    ChapterDescriptor("chap_1", "chapter_0001.xhtml", "Chương 1", c1.absolutePath),
                    ChapterDescriptor("chap_2", "chapter_0002.xhtml", "Chương 2", c2.absolutePath),
                    ChapterDescriptor("chap_3", "chapter_0003.xhtml", "Chương 3", c3.absolutePath)
                )
            )

            var editable = TocGenerator.buildEditableTocItems(workspace, regenerateToc = true)
            assertEquals(3, editable.size)

            // Rename chapter 1 and delete chapter 2
            editable = TocGenerator.renameEntry(editable, editable[0].id, "Chương 1: Khởi Đầu Mới")
            editable = TocGenerator.deleteEntry(editable, editable[1].id)

            val finalEntries = TocGenerator.toTocEntries(editable)
            assertEquals(listOf("Chương 1: Khởi Đầu Mới", "Chương 3"), finalEntries.map { it.title })

            val epub = EpubWorkspaceManager.packStandardEpub(
                workspace = workspace,
                regenerateToc = true,
                customTocEntries = finalEntries
            )
            ZipFile(epub).use { zip ->
                val nav = zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).bufferedReader().readText()
                val ncx = zip.getInputStream(zip.getEntry("OEBPS/toc.ncx")).bufferedReader().readText()
                assertTrue(nav.contains("Chương 1: Khởi Đầu Mới"))
                assertFalse(nav.contains("Chương 2"))
                assertTrue(nav.contains("Chương 3"))
                assertTrue(ncx.contains("Chương 1: Khởi Đầu Mới"))
                assertFalse(ncx.contains("Chương 2"))
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun paragraphMerger_mergesBrokenMidSentenceLinesAndProtectsHeadingsAndDialogue() {
        val html = """
            <html><body>
                <h2>Chương 1</h2>
                <p>hôm nay trời rất đẹp</p>
                <p>Anh bước ra khỏi căn nhà nhỏ bên sườn đồi</p>
                <p>và nhìn về phía chân trời xa xăm</p>
                <p>, nơi những đám mây đang trôi lặng lẽ.</p>
                <p>Cô ấy hỏi:</p>
                <p>– Anh đang nghĩ gì vậy?</p>
                <p>– Tôi đang nghĩ về quá khứ</p>
                <p>của chúng ta năm xưa.</p>
                <p>Đoạn văn đã kết thúc trọn vẹn.</p>
                <p>Đoạn tiếp theo bắt đầu câu mới.</p>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val result = com.example.pipeline.ParagraphMerger.mergeBrokenParagraphs(doc)

        // Expected merges:
        // 1. "Anh bước..." + "và nhìn..." + ", nơi..." (2 merges)
        // 2. "– Tôi đang nghĩ về quá khứ" + "của chúng ta năm xưa." (1 merge)
        // Total = 3 merges; h2 must NOT merge with "hôm nay..."; "Cô ấy hỏi:" must NOT merge with "– Anh..."
        assertEquals(3, result.mergedCount)
        assertEquals("Chương 1", doc.selectFirst("h2")?.text())
        val paragraphs = doc.select("p").map { it.text() }
        assertEquals(
            listOf(
                "hôm nay trời rất đẹp",
                "Anh bước ra khỏi căn nhà nhỏ bên sườn đồi và nhìn về phía chân trời xa xăm, nơi những đám mây đang trôi lặng lẽ.",
                "Cô ấy hỏi:",
                "– Anh đang nghĩ gì vậy?",
                "– Tôi đang nghĩ về quá khứ của chúng ta năm xưa.",
                "Đoạn văn đã kết thúc trọn vẹn.",
                "Đoạn tiếp theo bắt đầu câu mới."
            ),
            paragraphs
        )
    }

    @Test
    fun customReplaceManager_batchAppliesMultipleRulesAcrossChapters() {
        val rules = listOf(
            com.example.data.CustomReplaceRule(id = "r1", findText = "Iàm", replaceText = "làm"),
            com.example.data.CustomReplaceRule(id = "r2", findText = "rn", replaceText = "m"),
            com.example.data.CustomReplaceRule(id = "r3", findText = "Lý Tiêu", replaceText = "Lý Tiêu Dao")
        )
        val chapters = listOf(
            com.example.pipeline.ManualChapterContent(
                chapterIndex = 0,
                fileName = "chapter_0001.xhtml",
                title = "Chương 1",
                filePath = "",
                originalParagraphs = listOf("Lý Tiêu đang Iàm việc trong rnoột căn phòng."),
                currentParagraphs = listOf("Lý Tiêu đang Iàm việc trong rnột căn phòng.")
            ),
            com.example.pipeline.ManualChapterContent(
                chapterIndex = 1,
                fileName = "chapter_0002.xhtml",
                title = "Chương 2",
                filePath = "",
                originalParagraphs = listOf("Không nên Iàm thế nữa."),
                currentParagraphs = listOf("Không nên Iàm thế nữa.")
            )
        )

        val (updated, count) = com.example.data.CustomReplaceManager.applyRulesToChapters(chapters, rules)
        assertEquals(4, count)
        assertEquals(
            "Lý Tiêu Dao đang làm việc trong một căn phòng.",
            updated[0].currentParagraphs[0]
        )
        assertEquals(
            "Không nên làm thế nữa.",
            updated[1].currentParagraphs[0]
        )
    }

    @Test
    fun imageOptimizer_removesBrokenImageTagsAndComputesSafeDownsampling() {
        // 1. Verify RAM-safe downsampling & aspect ratio scaling math
        assertEquals(1, com.example.pipeline.ImageOptimizer.calculateInSampleSize(1200, 800, 1200))
        assertEquals(2, com.example.pipeline.ImageOptimizer.calculateInSampleSize(2400, 1600, 1200))
        assertEquals(4, com.example.pipeline.ImageOptimizer.calculateInSampleSize(4800, 3200, 1200))
        assertEquals(1200 to 900, com.example.pipeline.ImageOptimizer.computeScaledDimensions(2400, 1800, 1200))
        assertEquals(800 to 600, com.example.pipeline.ImageOptimizer.computeScaledDimensions(800, 600, 1200))

        // 2. Verify broken <img> tags & 0-byte tracker files are stripped cleanly from XHTML
        val tempRoot = java.nio.file.Files.createTempDirectory("epubclean_img_opt_").toFile()
        try {
            val textDir = File(tempRoot, "stage/OEBPS/Text").apply { mkdirs() }
            val imagesDir = File(tempRoot, "stage/OEBPS/Images").apply { mkdirs() }

            // Create 1 valid non-empty SVG and 1 corrupted 0-byte tracker image
            File(imagesDir, "valid_art.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>")
            File(imagesDir, "empty_tracker.jpg").writeBytes(ByteArray(0))

            val c1 = File(textDir, "chapter_0001.xhtml").apply {
                writeText(
                    EpubWorkspaceManager.buildValidXhtmlDocument(
                        "Chương 1",
                        """
                        <h1>Chương 1</h1>
                        <p>Ảnh hợp lệ: <img src="../Images/valid_art.svg" alt="ok" /></p>
                        <p>Ảnh hỏng: <img src="../Images/missing_file.jpg" alt="broken" /></p>
                        <p>Tracker rỗng: <img src="../Images/empty_tracker.jpg" alt="tracker" /></p>
                        """.trimIndent()
                    ),
                    Charsets.UTF_8
                )
            }

            val workspace = EpubWorkspaceManager.preparePdfWorkspace(
                bookTitle = "Test Image Opt",
                sessionDir = tempRoot,
                chapters = listOf(
                    ChapterDescriptor("chap_1", "chapter_0001.xhtml", "Chương 1", c1.absolutePath)
                )
            ).copy(imageRelativePaths = listOf("Images/valid_art.svg", "Images/empty_tracker.jpg"))

            val opt = com.example.pipeline.ImageOptimizer.optimizeWorkspaceImages(workspace)
            assertTrue("Expected broken/empty images stripped", opt.brokenOrTinyTagsRemoved >= 3)
            assertEquals(listOf("Images/valid_art.svg"), opt.updatedImageRelativePaths)
            assertFalse("Empty tracker file should be deleted", File(imagesDir, "empty_tracker.jpg").exists())

            val updatedDoc = Jsoup.parse(c1, "UTF-8")
            assertEquals(1, updatedDoc.select("img").size)
            assertEquals("../Images/valid_art.svg", updatedDoc.selectFirst("img")?.attr("src"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun errorMemoryManager_extractsAll5CategoriesFiltersUnsafeAndAppliesCaseInsensitive() {
        val pairs = listOf(
            // Group 1: Whitespace split / slide (1-4 words & multi-fragment English)
            com.example.data.ParagraphDiffPair(
                beforeText = "Anh ấy có cóm ột khái niệm i den fi ni ty rất rõ.",
                afterText = "Anh ấy có có một khái niệm identity rất rõ."
            ),
            // Group 2: OCR character misrecognition
            com.example.data.ParagraphDiffPair(
                beforeText = "Chúng ta cần Iàm rnột việc để ta ra kết quả.",
                afterText = "Chúng ta cần làm một việc để tạo ra kết quả."
            ),
            // Group 3: Diacritics / tone typing errors
            com.example.data.ParagraphDiffPair(
                beforeText = "Cô ấy muồn ăn nựa quạ chuối chín.",
                afterText = "Cô ấy muốn ăn nửa quả chuối chín."
            ),
            // Group 4: Proper nouns / terminology
            com.example.data.ParagraphDiffPair(
                beforeText = "Nhân vật Lý Tiêu bước vào đại điện.",
                afterText = "Nhân vật Lý Tiêu Dao bước vào đại điện."
            ),
            // Group 5: Junk / URL / website / signature full deletion (5..150 chars)
            com.example.data.ParagraphDiffPair(
                beforeText = "Nguồn sưu tầm độc quyền tại WebTruyenVip.Com - Chúc vui vẻ!",
                afterText = ""
            ),
            // Safety filter: Long sentence rewrite (> 4 words) MUST be rejected
            com.example.data.ParagraphDiffPair(
                beforeText = "Một hai ba bốn năm sáu bảy tám chín mười",
                afterText = "Câu văn đã được viết lại hoàn toàn khác hẳn"
            ),
            // Safety filter: Single character edit MUST be rejected
            com.example.data.ParagraphDiffPair(
                beforeText = "a",
                afterText = "b"
            )
        )

        val rules = com.example.data.ErrorMemoryManager.extractRulesFromPairs(pairs)
        val map = rules.associateBy { it.wrongText }

        assertEquals("có một", map["cóm ột"]?.correctText)
        assertEquals(com.example.data.ErrorMemoryCategory.WHITESPACE_SPLIT, map["cóm ột"]?.category)

        assertEquals("identity", map["i den fi ni ty"]?.correctText)
        assertEquals(com.example.data.ErrorMemoryCategory.WHITESPACE_SPLIT, map["i den fi ni ty"]?.category)
        assertEquals("làm", map["Iàm"]?.correctText)
        assertEquals("một", map["rnột"]?.correctText)
        assertEquals("tạo", map["ta"]?.correctText)
        assertEquals("muốn", map["muồn"]?.correctText)
        assertEquals("nửa", map["nựa"]?.correctText)
        assertEquals("quả", map["quạ"]?.correctText)
        assertEquals("Lý Tiêu Dao", map["Lý Tiêu"]?.correctText)
        assertEquals(com.example.data.ErrorMemoryCategory.PROPER_NOUN_TERM, map["Lý Tiêu"]?.category)

        val junkRule = rules.firstOrNull { it.category == com.example.data.ErrorMemoryCategory.JUNK_DELETION }
        assertNotNull("Expected Group 5 junk deletion rule", junkRule)
        assertEquals("", junkRule!!.correctText)

        // Ensure long rewrite & single-char edit were rejected
        assertFalse(rules.any { it.wrongText.contains("Một hai ba bốn năm") })
        assertFalse(rules.any { it.wrongText == "a" })

        // Test prioritized application (case-insensitive junk removal + vocab correction)
        val rawParagraph = "nguồn sưu tầm độc quyền tại webtruyenvip.com - chúc vui vẻ! Lý Tiêu muồn Iàm rnột quạ chuối."
        val applied = com.example.data.ErrorMemoryManager.applyRulesToText(rawParagraph, rules)
        assertEquals("Lý Tiêu Dao muốn làm một quả chuối.", applied.cleanedText)
        assertTrue(applied.junkDeletionCount >= 1)
        assertTrue(applied.vocabFixCount >= 5)

        // Test non-destructive merge + JSON round-trip
        val json = com.example.data.ErrorMemoryManager.serializeRulesToJson(rules)
        val parsed = com.example.data.ErrorMemoryManager.parseRulesFromJson(json)
        assertEquals(rules.size, parsed.size)

        val extraRule = com.example.data.ErrorMemoryRule(
            wrongText = "đặc biêt",
            correctText = "đặc biệt",
            category = com.example.data.ErrorMemoryCategory.TONE_TYPO
        )
        val merged = com.example.data.ErrorMemoryManager.mergeUniqueRules(rules, parsed + extraRule)
        assertEquals(1, merged.addedRules.size)
        assertEquals(rules.size + 1, merged.allRules.size)
    }

    @Test
    fun tocGenerator_updatesTocAccuratelyAfterChapterSplitAndMerge() {
        val initialToc = listOf(
            com.example.pipeline.EditableTocItem(
                id = "c1",
                title = "Chương 1",
                href = "Text/chapter_0001.xhtml",
                chapterIndex = 0,
                chapterFileName = "chapter_0001.xhtml",
                paragraphIndex = 0,
                anchorId = null,
                isManualBookmark = false
            ),
            com.example.pipeline.EditableTocItem(
                id = "bm1",
                title = "Đánh dấu giữa chương 1",
                href = "Text/chapter_0001.xhtml#manual_toc_1",
                chapterIndex = 0,
                chapterFileName = "chapter_0001.xhtml",
                paragraphIndex = 6,
                anchorId = "manual_toc_1",
                isManualBookmark = true
            ),
            com.example.pipeline.EditableTocItem(
                id = "c2",
                title = "Chương 2",
                href = "Text/chapter_0002.xhtml",
                chapterIndex = 1,
                chapterFileName = "chapter_0002.xhtml",
                paragraphIndex = 0,
                anchorId = null,
                isManualBookmark = false
            )
        )

        // 1. Split Chapter 1 at paragraph index 4 -> new chapter inserted at index 1, bm1 moves to index 1 para 2, c2 shifts to index 2
        val afterSplit = TocGenerator.updateTocAfterChapterSplit(
            entries = initialToc,
            splitChapterIndex = 0,
            newChapterFileName = "chapter_split_2.xhtml",
            newChapterTitle = "Chương 1B",
            splitParagraphIndex = 4
        )
        assertEquals(listOf("Chương 1", "Chương 1B", "Đánh dấu giữa chương 1", "Chương 2"), afterSplit.map { it.title })
        val movedBm = afterSplit.first { it.id == "bm1" }
        assertEquals(1, movedBm.chapterIndex)
        assertEquals(2, movedBm.paragraphIndex)
        assertEquals("chapter_split_2.xhtml", movedBm.chapterFileName)
        assertEquals(2, afterSplit.first { it.id == "c2" }.chapterIndex)

        // 2. Merge Chapter 1 (index 0, 4 paragraphs) into Chương 1B (index 1) -> Chương 1 entry removed, Chương 1B at index 0, bm1 shifted to para 6, c2 back to index 1
        val afterMerge = TocGenerator.updateTocAfterChapterMerge(
            entries = afterSplit,
            sourceChapterIndex = 0,
            targetChapterIndex = 1,
            targetChapterFileName = "chapter_split_2.xhtml",
            sourceParagraphCount = 4
        )
        assertEquals(listOf("Chương 1B", "Đánh dấu giữa chương 1", "Chương 2"), afterMerge.map { it.title })
        assertEquals(0, afterMerge.first { it.title == "Chương 1B" }.chapterIndex)
        val mergedBm = afterMerge.first { it.id == "bm1" }
        assertEquals(0, mergedBm.chapterIndex)
        assertEquals(6, mergedBm.paragraphIndex)
        assertEquals(1, afterMerge.first { it.id == "c2" }.chapterIndex)
    }
}
