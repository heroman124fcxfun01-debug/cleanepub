package com.example.pipeline

import android.content.Context
import com.example.data.ErrorMemoryManager
import com.example.data.ParagraphDiffPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import kotlin.coroutines.coroutineContext

private const val MAX_PREVIEW_DIFFS = 400

class EpubCleanPipeline(
    private val context: Context,
    private val proofreader: OnDeviceVietnameseProofreader = OnDeviceVietnameseProofreader()
) {

    suspend fun run(
        source: SourceFileInfo,
        metadata: EpubMetadata? = null,
        options: CleaningOptions = CleaningOptions(),
        onUpdate: (ProcessingSnapshot) -> Unit
    ): ProcessingSnapshot = withContext(Dispatchers.IO) {
        val stepStates = PipelineStep.entries.associateWith { StepState.PENDING }.toMutableMap()
        var stats = RealtimeStats()
        var bookContext: BookContext? = null
        var tocInspection: TocInspectionResult? = null
        var defaultRegenerateToc = false
        val diffs = mutableListOf<DiffEntry>()

        fun emit(
            step: PipelineStep,
            progress: Float,
            detail: String,
            phase: ProcessingPhase = ProcessingPhase.RUNNING,
            workspace: PreparedWorkspace? = null,
            error: String? = null
        ): ProcessingSnapshot {
            val snap = ProcessingSnapshot(
                phase = phase,
                sourceFile = source,
                currentStep = step,
                stepStates = stepStates.toMap(),
                progressFraction = progress.coerceIn(0f, 1f),
                statusDetail = detail,
                stats = stats,
                bookContext = bookContext,
                diffs = diffs.toList(),
                workspace = workspace,
                metadata = metadata ?: workspace?.metadata,
                tocInspection = tocInspection ?: workspace?.tocInspection,
                regenerateToc = defaultRegenerateToc,
                errorMessage = error
            )
            onUpdate(snap)
            return snap
        }

        // ------------------------------------------------------------------
        // BƯỚC 0: Giải nén / Trích xuất PDF, nạp Từ điển Việt + Anh Offline & Đọc ngữ cảnh
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_0_UNPACK_CONTEXT] = StepState.RUNNING
        emit(PipelineStep.STEP_0_UNPACK_CONTEXT, 0.03f, "Đang nạp bộ từ điển Việt – Anh ngoại tuyến…")
        OfflineVietnameseDict.ensureLoaded(context)
        EnglishDictionary.ensureLoaded(context)
        val textCleaner = TextCleaner(OfflineVietnameseDict)
        val englishWordFixer = EnglishWordFixer(EnglishDictionary, OfflineVietnameseDict)

        emit(PipelineStep.STEP_0_UNPACK_CONTEXT, 0.05f, "Đang nạp tệp nguồn vào vùng xử lý tạm…")

        val sessionDir = EpubWorkspaceManager.createSessionDir(context)
        val rawInputFile = File(
            sessionDir,
            if (source.fileType == InputFileType.EPUB) "input.epub" else "input.pdf"
        )
        FilePathResolver.copyInputToWorkFile(context, source, rawInputFile)
        coroutineContext.ensureActive()

        val rawWorkspace: PreparedWorkspace = when (source.fileType) {
            InputFileType.EPUB -> {
                emit(PipelineStep.STEP_0_UNPACK_CONTEXT, 0.08f, "Đang giải nén cấu trúc EPUB…")
                EpubWorkspaceManager.unpackAndStageEpub(
                    sourceEpubFile = rawInputFile,
                    fallbackTitle = metadata?.title?.ifBlank { source.baseNameWithoutExt } ?: source.baseNameWithoutExt,
                    sessionDir = sessionDir
                )
            }
            InputFileType.PDF -> {
                val stageTextDir = File(sessionDir, "stage/OEBPS/Text").apply { mkdirs() }
                val pdfChapters = PdfToXhtmlConverter.convertToXhtmlChapters(
                    context = context,
                    pdfFile = rawInputFile,
                    bookTitle = metadata?.title?.ifBlank { source.baseNameWithoutExt } ?: source.baseNameWithoutExt,
                    outputTextDir = stageTextDir,
                    onPageProgress = { cur, total ->
                        val frac = 0.06f + (cur.toFloat() / total.coerceAtLeast(1)) * 0.08f
                        emit(
                            PipelineStep.STEP_0_UNPACK_CONTEXT,
                            frac,
                            "Đang trích xuất & nối dòng PDF (trang $cur/$total)…"
                        )
                    }
                )
                EpubWorkspaceManager.preparePdfWorkspace(
                    bookTitle = metadata?.title?.ifBlank { source.baseNameWithoutExt } ?: source.baseNameWithoutExt,
                    sessionDir = sessionDir,
                    chapters = pdfChapters
                )
            }
        }

        // Attach edited Metadata & Cover onto workspace and capture smart TOC inspection result
        val workspace = rawWorkspace.copy(
            bookTitle = metadata?.title?.ifBlank { rawWorkspace.bookTitle } ?: rawWorkspace.bookTitle,
            bookAuthor = metadata?.creator?.ifBlank { rawWorkspace.bookAuthor } ?: rawWorkspace.bookAuthor,
            metadata = metadata
        )
        tocInspection = workspace.tocInspection
        defaultRegenerateToc = workspace.tocInspection?.shouldRegenerateByDefault ?: false

        // Remove raw input copy immediately to keep disk/RAM minimal
        rawInputFile.delete()

        val totalChapters = workspace.chapters.size
        stats = stats.copy(totalChapters = totalChapters, processedChapters = 0)

        emit(
            PipelineStep.STEP_0_UNPACK_CONTEXT,
            0.15f,
            "Đang quét ngữ cảnh & trích xuất Tên riêng trên máy ($totalChapters chương)…"
        )
        val contextSample = if (options.optimizeContent) {
            collectLightweightContextSample(workspace.chapters)
        } else {
            ""
        }
        bookContext = proofreader.analyzeGlobalContext(contextSample)
        stepStates[PipelineStep.STEP_0_UNPACK_CONTEXT] = StepState.COMPLETED
        emit(
            PipelineStep.STEP_0_UNPACK_CONTEXT,
            0.20f,
            "Thể loại: ${bookContext.genre} (${bookContext.properNouns.size} tên riêng)"
        )

        // ------------------------------------------------------------------
        // BƯỚC 1: Áp dụng Thư viện Ghi nhớ Lỗi (ưu tiên trước) + Dọn quảng cáo rác & liên kết
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_1_CLEAN_JUNK] = StepState.RUNNING
        val errorMemoryManager = ErrorMemoryManager(context)
        val learnedRules = if (errorMemoryManager.isAutoApplyEnabled()) {
            errorMemoryManager.getRules()
        } else {
            emptyList()
        }

        if (learnedRules.isNotEmpty() || options.cleanLinks) {
            workspace.chapters.forEachIndexed { idx, chapter ->
                coroutineContext.ensureActive()
                val file = File(chapter.filePath)
                val doc = parseXhtml(file)
                var chapterJunkRemoved = 0
                var chapterLearnedVocabFixes = 0

                // Ưu tiên chạy qua Thư viện ghi nhớ lỗi trước (Upgrade 1.3)
                if (learnedRules.isNotEmpty()) {
                    val elements = TextCleaningEngine.extractEditableParagraphs(doc)
                    elements.forEachIndexed { pIdx, el ->
                        val beforeMem = el.text()
                        if (beforeMem.isBlank()) return@forEachIndexed
                        val memRes = ErrorMemoryManager.applyRulesToText(beforeMem, learnedRules)
                        if (memRes.totalFixes > 0) {
                            chapterJunkRemoved += memRes.junkDeletionCount
                            chapterLearnedVocabFixes += memRes.vocabFixCount
                            if (memRes.cleanedText.isBlank()) {
                                el.remove()
                            } else if (memRes.cleanedText != beforeMem) {
                                el.text(memRes.cleanedText)
                                if (diffs.size < MAX_PREVIEW_DIFFS) {
                                    diffs.add(
                                        DiffEntry(
                                            id = "mem_${chapter.fileName}_${pIdx}_${diffs.size}",
                                            chapterFileName = chapter.fileName,
                                            chapterTitle = chapter.title,
                                            paragraphIndex = pIdx,
                                            beforeText = beforeMem,
                                            afterText = memRes.cleanedText,
                                            reason = "Thư viện ghi nhớ lỗi",
                                            accepted = true
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                if (options.cleanLinks) {
                    val adResult = AdCleaner.cleanDocument(doc)
                    val linkResult = TextCleaningEngine.cleanJunkAndLinks(doc)
                    chapterJunkRemoved += adResult.removedCount + linkResult.junkRemovedCount
                }

                saveXhtml(file, chapter.title, doc)

                stats = stats.copy(
                    junkLinksRemoved = stats.junkLinksRemoved + chapterJunkRemoved,
                    spellingFixes = stats.spellingFixes + chapterLearnedVocabFixes,
                    processedChapters = idx + 1
                )
                val frac = 0.20f + ((idx + 1).toFloat() / totalChapters) * 0.16f
                emit(
                    PipelineStep.STEP_1_CLEAN_JUNK,
                    frac,
                    if (learnedRules.isNotEmpty()) {
                        "Đang áp dụng Thư viện ghi nhớ lỗi & dọn rác (${idx + 1}/$totalChapters chương)…"
                    } else {
                        "Đang dọn quảng cáo rác & liên kết thừa (${idx + 1}/$totalChapters chương)…"
                    }
                )
            }
        } else {
            emit(PipelineStep.STEP_1_CLEAN_JUNK, 0.36f, "Bỏ qua dọn link & quảng cáo theo tùy chọn.")
        }
        stepStates[PipelineStep.STEP_1_CLEAN_JUNK] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 2: Ép về duy nhất 1 Font chuẩn (Cấm chữ nghiêng)
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_2_UNIFY_FONT] = StepState.RUNNING
        if (options.fixFonts) {
            workspace.chapters.forEachIndexed { idx, chapter ->
                coroutineContext.ensureActive()
                val file = File(chapter.filePath)
                val doc = parseXhtml(file)
                TextCleaningEngine.enforceUnifiedTypography(doc)
                saveXhtml(file, chapter.title, doc)

                val frac = 0.36f + ((idx + 1).toFloat() / totalChapters) * 0.14f
                emit(
                    PipelineStep.STEP_2_UNIFY_FONT,
                    frac,
                    "Đang gỡ CSS cũ, bỏ chữ nghiêng & ép 1 font chuẩn (${idx + 1}/$totalChapters)…"
                )
            }
        } else {
            emit(PipelineStep.STEP_2_UNIFY_FONT, 0.50f, "Bỏ qua sửa phông theo tùy chọn.")
        }
        stepStates[PipelineStep.STEP_2_UNIFY_FONT] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 3: Chuẩn hóa khoảng trắng & Sửa tách/trượt khoảng trắng bằng Từ điển Offline
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_3_NORMALIZE_WHITESPACE] = StepState.RUNNING
        val activeContext = bookContext
        val diffIndexByKey = HashMap<String, Int>(MAX_PREVIEW_DIFFS)

        if (options.fixWhitespace || options.optimizeContent) {
            workspace.chapters.forEachIndexed { idx, chapter ->
                coroutineContext.ensureActive()
                val file = File(chapter.filePath)
                val doc = parseXhtml(file)

                // Bước 3.0 (Upgrade 1.2): Tự động nối đoạn <p> bị ngắt dòng lỗi giữa câu
                val mergeResult = ParagraphMerger.mergeBrokenParagraphs(doc)
                var chapterWhitespaceFixes = mergeResult.mergedCount

                if (options.fixWhitespace) {
                    // Bước A: Gom khoảng trắng liên tiếp, xóa đoạn rỗng & chuẩn hóa dấu câu
                    val stepAResult = TextCleaningEngine.normalizeWhitespace(doc)
                    chapterWhitespaceFixes += stepAResult.whitespaceFixesCount

                    // Bước B & C: Sửa lỗi trượt khoảng trắng (DP) & ghép âm tiết bị tách giả trên p, h1..h3
                    val editableElements = TextCleaningEngine.extractEditableParagraphs(doc)
                    editableElements.forEachIndexed { pIdx, el ->
                        val beforeWordClean = el.text()
                        val cleanRes = textCleaner.cleanParagraph(beforeWordClean, activeContext.properNouns)
                        if (cleanRes.cleanedText != beforeWordClean && cleanRes.cleanedText.isNotBlank()) {
                            el.text(cleanRes.cleanedText)
                            chapterWhitespaceFixes += cleanRes.totalWordFixes.coerceAtLeast(1)
                            if (diffs.size < MAX_PREVIEW_DIFFS) {
                                val key = "${chapter.fileName}#$pIdx"
                                diffs.add(
                                    DiffEntry(
                                        id = "${chapter.fileName}_${pIdx}_${diffs.size}",
                                        chapterFileName = chapter.fileName,
                                        chapterTitle = chapter.title,
                                        paragraphIndex = pIdx,
                                        beforeText = beforeWordClean,
                                        afterText = cleanRes.cleanedText,
                                        reason = cleanRes.reasons.joinToString(" · ")
                                            .ifBlank { "Sửa lỗi khoảng trắng từ điển" },
                                        accepted = true
                                    )
                                )
                                diffIndexByKey[key] = diffs.lastIndex
                            }
                        }
                    }

                    // Cũng làm sạch các thẻ li, blockquote nếu có
                    doc.body().select("li, blockquote").forEach { el ->
                        if (el.select("img").isEmpty() && el.text().isNotBlank()) {
                            val before = el.text()
                            val cleanRes = textCleaner.cleanParagraph(before, activeContext.properNouns)
                            if (cleanRes.cleanedText != before && cleanRes.cleanedText.isNotBlank()) {
                                el.text(cleanRes.cleanedText)
                                chapterWhitespaceFixes += cleanRes.totalWordFixes.coerceAtLeast(1)
                            }
                        }
                    }
                }

                saveXhtml(file, chapter.title, doc)

                stats = stats.copy(
                    whitespaceFixes = stats.whitespaceFixes + chapterWhitespaceFixes,
                    mergedParagraphs = stats.mergedParagraphs + mergeResult.mergedCount
                )
                val frac = 0.50f + ((idx + 1).toFloat() / totalChapters) * 0.14f
                emit(
                    PipelineStep.STEP_3_NORMALIZE_WHITESPACE,
                    frac,
                    "Đang nối đoạn lỗi & sửa khoảng trắng (${idx + 1}/$totalChapters)…"
                )
            }
        } else {
            emit(PipelineStep.STEP_3_NORMALIZE_WHITESPACE, 0.64f, "Bỏ qua sửa khoảng trắng theo tùy chọn.")
        }
        stepStates[PipelineStep.STEP_3_NORMALIZE_WHITESPACE] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 4: Ghép từ tiếng Anh + Hiệu đính tiếng Việt + Chuẩn hóa dấu câu (PunctuationFixer) + Đóng gói EPUB
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_4_AI_PROOFREAD_PACK] = StepState.RUNNING

        if (options.fixSpelling || options.fixWhitespace || options.optimizeContent) {
            workspace.chapters.forEachIndexed { chapIdx, chapter ->
                coroutineContext.ensureActive()
                val file = File(chapter.filePath)
                val doc = parseXhtml(file)
                val editableElements = TextCleaningEngine.extractEditableParagraphs(doc)

                var chapterModified = false
                editableElements.forEachIndexed { pIdx, el ->
                    val beforeStep4 = el.text()
                    var working = beforeStep4
                    val step4Reasons = linkedSetOf<String>()

                    // 4A. Sửa từ tiếng Anh bị tách khoảng trắng / lỗi OCR (en_words.txt + Levenshtein <= 2)
                    if (options.fixSpelling || options.optimizeContent) {
                        val enRes = englishWordFixer.fixText(working, activeContext.properNouns)
                        if (enRes.fixCount > 0 && enRes.cleanedText != working) {
                            working = enRes.cleanedText
                            step4Reasons.add("Ghép từ tiếng Anh")
                            stats = stats.copy(spellingFixes = stats.spellingFixes + enRes.fixCount)
                        }
                    }

                    // 4B. Hiệu đính chính tả & dấu tiếng Việt
                    if (options.fixSpelling) {
                        val fix = proofreader.proofreadParagraph(working, activeContext)
                        if (fix != null && fix.correctedText != working) {
                            working = fix.correctedText
                            step4Reasons.add(fix.reason)
                            stats = stats.copy(spellingFixes = stats.spellingFixes + 1)
                        }
                    }

                    // 4C. Chuẩn hóa dấu câu Việt – Anh (PunctuationFixer) sau khi sửa khoảng trắng & chính tả
                    if (options.fixWhitespace || options.fixSpelling || options.optimizeContent) {
                        val punctRes = PunctuationFixer.fixParagraph(working)
                        if (punctRes.fixCount > 0 && punctRes.cleanedText != working) {
                            working = punctRes.cleanedText
                            step4Reasons.add("Chuẩn hóa dấu câu")
                            stats = stats.copy(whitespaceFixes = stats.whitespaceFixes + punctRes.fixCount)
                        }
                    }

                    if (working != beforeStep4) {
                        el.text(working)
                        chapterModified = true
                        val combinedReason = step4Reasons.joinToString(" · ")
                            .ifBlank { "Hiệu đính chính tả & dấu câu" }
                        val key = "${chapter.fileName}#$pIdx"
                        val existingIdx = diffIndexByKey[key]
                        if (existingIdx != null) {
                            val existing = diffs[existingIdx]
                            diffs[existingIdx] = existing.copy(
                                afterText = working,
                                reason = "${existing.reason} · $combinedReason"
                            )
                        } else if (diffs.size < MAX_PREVIEW_DIFFS) {
                            diffs.add(
                                DiffEntry(
                                    id = "${chapter.fileName}_${pIdx}_${diffs.size}",
                                    chapterFileName = chapter.fileName,
                                    chapterTitle = chapter.title,
                                    paragraphIndex = pIdx,
                                    beforeText = beforeStep4,
                                    afterText = working,
                                    reason = combinedReason,
                                    accepted = true
                                )
                            )
                            diffIndexByKey[key] = diffs.lastIndex
                        }
                    }
                }

                if (chapterModified) {
                    saveXhtml(file, chapter.title, doc)
                }

                val frac = 0.64f + ((chapIdx + 1).toFloat() / totalChapters) * 0.30f
                emit(
                    PipelineStep.STEP_4_AI_PROOFREAD_PACK,
                    frac,
                    "Đang hiệu đính chính tả & dấu câu (${chapIdx + 1}/$totalChapters chương)…"
                )
            }
        }

        // Bước 4D (Upgrade 1.2): Dọn ảnh lỗi/pixel theo dõi & nén ảnh minh họa dung lượng lớn
        var finalWorkspace = workspace
        if (options.optimizeContent || options.cleanLinks) {
            emit(
                PipelineStep.STEP_4_AI_PROOFREAD_PACK,
                0.95f,
                "Đang dọn ảnh lỗi & nén tối ưu ảnh minh họa…"
            )
            val imgOpt = ImageOptimizer.optimizeWorkspaceImages(workspace)
            finalWorkspace = workspace.copy(imageRelativePaths = imgOpt.updatedImageRelativePaths)
            stats = stats.copy(
                junkLinksRemoved = stats.junkLinksRemoved + imgOpt.brokenOrTinyTagsRemoved,
                imagesOptimized = stats.imagesOptimized + imgOpt.totalOptimizations
            )
        }

        emit(
            PipelineStep.STEP_4_AI_PROOFREAD_PACK,
            0.97f,
            "Đang ghi Metadata, mục lục, ảnh bìa & đóng gói EPUB chuẩn…"
        )
        EpubWorkspaceManager.packStandardEpub(finalWorkspace, regenerateToc = defaultRegenerateToc)
        stepStates[PipelineStep.STEP_4_AI_PROOFREAD_PACK] = StepState.COMPLETED

        emit(
            step = PipelineStep.STEP_4_AI_PROOFREAD_PACK,
            progress = 1.0f,
            detail = "Hoàn tất làm sạch & hiệu đính trên máy!",
            phase = ProcessingPhase.COMPLETED,
            workspace = finalWorkspace
        )
    }

    /**
     * Re-applies user's accept/reject diff selections and TOC regeneration toggle on Review screen,
     * then repacks the EPUB cleanly.
     */
    suspend fun rebuildEpubWithSelectedDiffs(
        workspace: PreparedWorkspace,
        diffs: List<DiffEntry>,
        regenerateToc: Boolean = workspace.tocInspection?.shouldRegenerateByDefault ?: true
    ): File = withContext(Dispatchers.IO) {
        val rejectedByChapter = diffs.filter { !it.accepted }.groupBy { it.chapterFileName }
        if (rejectedByChapter.isNotEmpty()) {
            val chapterByFile = workspace.chapters.associateBy { it.fileName }
            for ((fileName, rejectedList) in rejectedByChapter) {
                val chapter = chapterByFile[fileName] ?: continue
                val file = File(chapter.filePath)
                if (!file.exists()) continue
                val doc = parseXhtml(file)
                val editable = TextCleaningEngine.extractEditableParagraphs(doc)
                for (entry in rejectedList) {
                    val el = editable.getOrNull(entry.paragraphIndex)
                    if (el != null) {
                        el.text(entry.beforeText)
                    }
                }
                saveXhtml(file, chapter.title, doc)
            }
        }
        EpubWorkspaceManager.packStandardEpub(workspace, regenerateToc = regenerateToc)
    }

    /**
     * Loads both `originalParagraphs` (from pre-clean snapshot) and `currentParagraphs`
     * (either cleaned with user's Review diff choices or original) for every chapter
     * so ManualEditorScreen can support editing and 2-half Split-Screen Comparison.
     */
    suspend fun loadManualChapters(
        workspace: PreparedWorkspace,
        diffs: List<DiffEntry>,
        mode: ManualEditorMode
    ): List<ManualChapterContent> = withContext(Dispatchers.IO) {
        val originalDir = workspace.originalTextDirPath?.let { File(it) }
        val rejectedByChapter = diffs.filter { !it.accepted }.groupBy { it.chapterFileName }

        workspace.chapters.mapIndexed { idx, chapter ->
            val stagedFile = File(chapter.filePath)
            val originalFile = originalDir?.let { File(it, chapter.fileName) }
                ?.takeIf { it.exists() }
                ?: stagedFile

            val origParagraphs = if (originalFile.exists()) {
                extractDisplayParagraphs(parseXhtml(originalFile))
            } else {
                emptyList()
            }

            val cleanedParagraphs = if (stagedFile.exists()) {
                val doc = parseXhtml(stagedFile)
                val editable = TextCleaningEngine.extractEditableParagraphs(doc)
                val rejected = rejectedByChapter[chapter.fileName].orEmpty()
                for (entry in rejected) {
                    val el = editable.getOrNull(entry.paragraphIndex)
                    if (el != null) {
                        el.text(entry.beforeText)
                    }
                }
                extractDisplayParagraphs(doc)
            } else {
                origParagraphs
            }

            val safeOrig = if (origParagraphs.isEmpty() && cleanedParagraphs.isNotEmpty()) {
                cleanedParagraphs
            } else {
                origParagraphs
            }

            val chosenCurrent = when (mode) {
                ManualEditorMode.KEEP_CLEANED -> cleanedParagraphs.ifEmpty { safeOrig }
                ManualEditorMode.KEEP_ORIGINAL -> safeOrig.ifEmpty { cleanedParagraphs }
            }

            ManualChapterContent(
                chapterIndex = idx,
                fileName = chapter.fileName,
                title = chapter.title.ifBlank { "Chương ${idx + 1}" },
                filePath = chapter.filePath,
                originalParagraphs = safeOrig,
                currentParagraphs = chosenCurrent
            )
        }
    }

    /**
     * Writes user's manual paragraph edits, dynamic Chapter Splits / Merges, and manual TOC `#anchorId`
     * attributes back into staged XHTML chapters (preserving images and chapter headings), then repacks EPUB.
     */
    suspend fun writeManualEditsAndPackEpub(
        workspace: PreparedWorkspace,
        chapters: List<ManualChapterContent>,
        manualTocEntries: List<ManualTocEntry>,
        regenerateToc: Boolean = workspace.tocInspection?.shouldRegenerateByDefault ?: true,
        editableTocItems: List<EditableTocItem>? = null
    ): File = withContext(Dispatchers.IO) {
        val textDir = File(workspace.stageDirPath, "OEBPS/Text").apply { mkdirs() }
        val activeAnchors: List<ManualTocEntry> = if (editableTocItems != null) {
            editableTocItems.mapNotNull { item ->
                val anchor = item.anchorId ?: return@mapNotNull null
                ManualTocEntry(
                    id = item.id,
                    title = item.title,
                    chapterIndex = item.chapterIndex,
                    chapterFileName = item.chapterFileName,
                    paragraphIndex = item.paragraphIndex,
                    anchorId = anchor
                )
            }
        } else {
            manualTocEntries
        }
        val anchorsByChapter = activeAnchors.groupBy { it.chapterFileName }

        // Remove any merged-away chapter .xhtml files from OEBPS/Text so OPF spine/manifest stays clean
        val activeFileNames = chapters.map { it.fileName }.toSet()
        textDir.listFiles()?.forEach { existingFile ->
            if (existingFile.isFile && existingFile.extension.equals("xhtml", ignoreCase = true)) {
                if (existingFile.name !in activeFileNames) {
                    existingFile.delete()
                }
            }
        }

        val syncedDescriptors = ArrayList<ChapterDescriptor>(chapters.size)

        chapters.forEachIndexed { idx, chapter ->
            val file = chapter.filePath.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: File(textDir, chapter.fileName)
            val chapterAnchors = anchorsByChapter[chapter.fileName]
                .orEmpty()
                .associateBy { it.paragraphIndex }
            val newParagraphs = chapter.currentParagraphs

            val doc: Document = if (file.exists()) {
                parseXhtml(file)
            } else {
                Jsoup.parse("<html><head></head><body></body></html>").apply {
                    outputSettings()
                        .syntax(Document.OutputSettings.Syntax.xml)
                        .charset(Charsets.UTF_8)
                        .prettyPrint(true)
                }
            }

            val editableElements = TextCleaningEngine.extractEditableParagraphs(doc)
            if (file.exists() && editableElements.size == newParagraphs.size) {
                // Fast in-place update: preserves exact tag hierarchy (h1/h2/h3/p) and surrounding images
                editableElements.forEachIndexed { pIdx, el ->
                    val updatedText = newParagraphs[pIdx]
                    el.text(updatedText)
                    val anchor = chapterAnchors[pIdx]
                    if (anchor != null) {
                        el.attr("id", anchor.anchorId)
                    }
                }
            } else {
                // Newly split chapter, merged chapter, or altered paragraph count: preserve existing images
                val preservedImages = doc.body().select("img").map { it.outerHtml() }
                val body = doc.body()
                body.empty()
                newParagraphs.forEachIndexed { pIdx, text ->
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        val tag = if (pIdx == 0 && trimmed.length <= 120) "h2" else "p"
                        val el = body.appendElement(tag).text(trimmed)
                        val anchor = chapterAnchors[pIdx]
                        if (anchor != null) {
                            el.attr("id", anchor.anchorId)
                        }
                    }
                }
                for (imgHtml in preservedImages) {
                    body.append(imgHtml)
                }
            }

            saveXhtml(file, chapter.title, doc)
            syncedDescriptors.add(
                ChapterDescriptor(
                    id = "chap_${idx + 1}",
                    fileName = chapter.fileName,
                    title = chapter.title,
                    filePath = file.absolutePath
                )
            )
        }

        val syncedWorkspace = workspace.copy(chapters = syncedDescriptors)
        val customEntries = editableTocItems?.let { TocGenerator.toTocEntries(it) }
        EpubWorkspaceManager.packStandardEpub(
            workspace = syncedWorkspace,
            regenerateToc = regenerateToc,
            manualTocEntries = activeAnchors,
            customTocEntries = customEntries
        )
    }

    /**
     * Upgrade 1.3: Extracts `(original, final)` paragraph pairs from manual editing chapters
     * (including fully deleted paragraphs for Group 5 junk deletion learning) to persist with History.
     */
    fun extractDiffPairsFromManualChapters(chapters: List<ManualChapterContent>): List<ParagraphDiffPair> {
        val pairs = mutableListOf<ParagraphDiffPair>()
        for (chap in chapters) {
            val orig = chap.originalParagraphs
            val curr = chap.currentParagraphs
            val maxLen = maxOf(orig.size, curr.size)
            for (i in 0 until maxLen) {
                val b = orig.getOrNull(i)?.trim().orEmpty()
                val a = curr.getOrNull(i)?.trim().orEmpty()
                if (b.isNotEmpty() && b != a) {
                    pairs.add(ParagraphDiffPair(beforeText = b, afterText = a))
                }
            }
        }
        return pairs
    }

    /**
     * Upgrade 1.3: Extracts `(original, final)` pairs from accepted ReviewScreen diffs
     * plus any deleted junk lines from the pre-clean original snapshot.
     */
    suspend fun extractDiffPairsFromReview(
        workspace: PreparedWorkspace,
        diffs: List<DiffEntry>
    ): List<ParagraphDiffPair> = withContext(Dispatchers.IO) {
        val pairs = mutableListOf<ParagraphDiffPair>()
        for (d in diffs) {
            if (d.accepted && d.beforeText.isNotBlank() && d.beforeText != d.afterText) {
                pairs.add(ParagraphDiffPair(beforeText = d.beforeText, afterText = d.afterText))
            }
        }

        // Also detect completely removed short junk/URL lines by comparing original_text vs staged chapter
        val originalDir = workspace.originalTextDirPath?.let { File(it) }
        if (originalDir != null && originalDir.exists()) {
            for (chap in workspace.chapters) {
                val origFile = File(originalDir, chap.fileName)
                val stagedFile = File(chap.filePath)
                if (!origFile.exists() || !stagedFile.exists()) continue
                val origParas = extractDisplayParagraphs(parseXhtml(origFile))
                val stagedSet = extractDisplayParagraphs(parseXhtml(stagedFile)).toHashSet()
                for (origLine in origParas) {
                    if (origLine.length in 5..150 && origLine !in stagedSet) {
                        val trimmed = origLine.trim()
                        val looksLikeJunk = trimmed.contains("http", true) ||
                            trimmed.contains("www.", true) ||
                            trimmed.contains(".vn", true) ||
                            trimmed.contains(".com", true) ||
                            trimmed.contains(".net", true) ||
                            trimmed.contains("sưu tầm", true) ||
                            trimmed.contains("truyện", true) ||
                            trimmed.contains("chúc các bạn", true) ||
                            trimmed.contains("fanpage", true)
                        if (looksLikeJunk) {
                            pairs.add(ParagraphDiffPair(beforeText = trimmed, afterText = ""))
                        }
                    }
                }
            }
        }
        pairs
    }

    private fun extractDisplayParagraphs(doc: Document): List<String> {
        val editable = TextCleaningEngine.extractEditableParagraphs(doc)
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        if (editable.isNotEmpty()) return editable

        val fallback = doc.body().text().trim()
        return if (fallback.isNotEmpty()) listOf(fallback) else emptyList()
    }

    private fun collectLightweightContextSample(chapters: List<ChapterDescriptor>): String {
        if (chapters.isEmpty()) return ""
        val step = (chapters.size / 6).coerceAtLeast(1)
        val sb = StringBuilder(24_000)
        var i = 0
        while (i < chapters.size && sb.length < 22_000) {
            val file = File(chapters[i].filePath)
            if (file.exists()) {
                val text = Jsoup.parse(file, "UTF-8").body().text()
                if (text.isNotBlank()) {
                    sb.appendLine(text.take(3500))
                }
            }
            i += step
        }
        return sb.toString()
    }

    private fun parseXhtml(file: File): Document {
        return Jsoup.parse(file, "UTF-8").apply {
            outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(Charsets.UTF_8)
                .prettyPrint(true)
        }
    }

    private fun saveXhtml(file: File, title: String, doc: Document) {
        val xhtml = EpubWorkspaceManager.buildValidXhtmlDocument(title, doc.body().html())
        file.writeText(xhtml, Charsets.UTF_8)
    }
}
