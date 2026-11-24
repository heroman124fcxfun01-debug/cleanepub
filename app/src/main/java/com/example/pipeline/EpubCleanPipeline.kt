package com.example.pipeline

import android.content.Context
import com.example.ai.GeminiProofreader
import com.example.ai.IndexedParagraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import kotlin.coroutines.coroutineContext

private const val MAX_PARAGRAPHS_PER_BATCH = 12
private const val MAX_CHARS_PER_BATCH = 2600

class EpubCleanPipeline(
    private val context: Context,
    private val proofreader: GeminiProofreader = GeminiProofreader()
) {

    suspend fun run(
        source: SourceFileInfo,
        onUpdate: (ProcessingSnapshot) -> Unit
    ): ProcessingSnapshot = withContext(Dispatchers.IO) {
        var stepStates = PipelineStep.entries.associateWith { StepState.PENDING }.toMutableMap()
        var stats = RealtimeStats()
        var bookContext: BookContext? = null
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
                errorMessage = error
            )
            onUpdate(snap)
            return snap
        }

        // ------------------------------------------------------------------
        // BƯỚC 0: Giải nén / Trích xuất PDF & Đọc ngữ cảnh toàn cục
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_0_UNPACK_CONTEXT] = StepState.RUNNING
        emit(PipelineStep.STEP_0_UNPACK_CONTEXT, 0.04f, "Đang nạp tệp nguồn vào vùng xử lý tạm…")

        val sessionDir = EpubWorkspaceManager.createSessionDir(context)
        val rawInputFile = File(
            sessionDir,
            if (source.fileType == InputFileType.EPUB) "input.epub" else "input.pdf"
        )
        FilePathResolver.copyInputToWorkFile(context, source, rawInputFile)
        coroutineContext.ensureActive()

        val workspace: PreparedWorkspace = when (source.fileType) {
            InputFileType.EPUB -> {
                emit(PipelineStep.STEP_0_UNPACK_CONTEXT, 0.08f, "Đang giải nén cấu trúc EPUB…")
                EpubWorkspaceManager.unpackAndStageEpub(
                    sourceEpubFile = rawInputFile,
                    fallbackTitle = source.baseNameWithoutExt,
                    sessionDir = sessionDir
                )
            }
            InputFileType.PDF -> {
                val stageTextDir = File(sessionDir, "stage/OEBPS/Text").apply { mkdirs() }
                val pdfChapters = PdfToXhtmlConverter.convertToXhtmlChapters(
                    context = context,
                    pdfFile = rawInputFile,
                    bookTitle = source.baseNameWithoutExt,
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
                    bookTitle = source.baseNameWithoutExt,
                    sessionDir = sessionDir,
                    chapters = pdfChapters
                )
            }
        }

        // Remove raw input copy once unpacked to keep storage/RAM minimal
        rawInputFile.delete()

        val totalChapters = workspace.chapters.size
        stats = stats.copy(totalChapters = totalChapters, processedChapters = 0)

        emit(
            PipelineStep.STEP_0_UNPACK_CONTEXT,
            0.15f,
            "AI đang quét ngữ cảnh toàn cục & trích xuất Tên riêng ($totalChapters chương)…"
        )
        val contextSample = collectLightweightContextSample(workspace.chapters)
        bookContext = proofreader.analyzeGlobalContext(contextSample)
        stepStates[PipelineStep.STEP_0_UNPACK_CONTEXT] = StepState.COMPLETED
        emit(
            PipelineStep.STEP_0_UNPACK_CONTEXT,
            0.20f,
            "Đã xác định thể loại: ${bookContext.genre} (${bookContext.properNouns.size} tên riêng)"
        )

        // ------------------------------------------------------------------
        // BƯỚC 1: Dọn sạch rác & Liên kết thừa (tuần tự từng chương)
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_1_CLEAN_JUNK] = StepState.RUNNING
        workspace.chapters.forEachIndexed { idx, chapter ->
            coroutineContext.ensureActive()
            val file = File(chapter.filePath)
            val doc = parseXhtml(file)
            val result = TextCleaningEngine.cleanJunkAndLinks(doc)
            saveXhtml(file, chapter.title, doc)

            stats = stats.copy(
                junkLinksRemoved = stats.junkLinksRemoved + result.junkRemovedCount,
                processedChapters = idx + 1
            )
            val frac = 0.20f + ((idx + 1).toFloat() / totalChapters) * 0.14f
            emit(
                PipelineStep.STEP_1_CLEAN_JUNK,
                frac,
                "Đang dọn liên kết rác & chữ ký nguồn (${idx + 1}/$totalChapters chương)…"
            )
        }
        stepStates[PipelineStep.STEP_1_CLEAN_JUNK] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 2: Ép về duy nhất 1 Font chuẩn (Cấm chữ nghiêng)
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_2_UNIFY_FONT] = StepState.RUNNING
        workspace.chapters.forEachIndexed { idx, chapter ->
            coroutineContext.ensureActive()
            val file = File(chapter.filePath)
            val doc = parseXhtml(file)
            TextCleaningEngine.enforceUnifiedTypography(doc)
            saveXhtml(file, chapter.title, doc)

            val frac = 0.34f + ((idx + 1).toFloat() / totalChapters) * 0.12f
            emit(
                PipelineStep.STEP_2_UNIFY_FONT,
                frac,
                "Đang gỡ CSS cũ, bỏ chữ nghiêng & ép 1 font chuẩn (${idx + 1}/$totalChapters)…"
            )
        }
        stepStates[PipelineStep.STEP_2_UNIFY_FONT] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 3: Chuẩn hóa khoảng trắng tuyệt đối
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_3_NORMALIZE_WHITESPACE] = StepState.RUNNING
        workspace.chapters.forEachIndexed { idx, chapter ->
            coroutineContext.ensureActive()
            val file = File(chapter.filePath)
            val doc = parseXhtml(file)
            val result = TextCleaningEngine.normalizeWhitespace(doc)
            saveXhtml(file, chapter.title, doc)

            stats = stats.copy(
                whitespaceFixes = stats.whitespaceFixes + result.whitespaceFixesCount
            )
            val frac = 0.46f + ((idx + 1).toFloat() / totalChapters) * 0.14f
            emit(
                PipelineStep.STEP_3_NORMALIZE_WHITESPACE,
                frac,
                "Đang chuẩn hóa khoảng trắng & dấu câu (${idx + 1}/$totalChapters)…"
            )
        }
        stepStates[PipelineStep.STEP_3_NORMALIZE_WHITESPACE] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 4: Hiệu đính chính tả & dấu tiếng Việt bằng AI + Đóng gói EPUB
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_4_AI_PROOFREAD_PACK] = StepState.RUNNING
        val activeContext = bookContext ?: BookContext("Sách tiếng Việt", emptyList(), "")

        workspace.chapters.forEachIndexed { chapIdx, chapter ->
            coroutineContext.ensureActive()
            val file = File(chapter.filePath)
            val doc = parseXhtml(file)
            val editableElements = TextCleaningEngine.extractEditableParagraphs(doc)

            if (editableElements.isNotEmpty()) {
                val batches = buildParagraphBatches(editableElements.mapIndexed { pIdx, el ->
                    IndexedParagraph(index = pIdx, text = el.text())
                })

                var chapterModified = false
                batches.forEachIndexed { bIdx, batch ->
                    coroutineContext.ensureActive()
                    val chapterProgress = (chapIdx + (bIdx.toFloat() / batches.size.coerceAtLeast(1))) / totalChapters
                    val frac = 0.60f + chapterProgress * 0.34f
                    emit(
                        PipelineStep.STEP_4_AI_PROOFREAD_PACK,
                        frac,
                        "AI đang hiệu đính ${chapter.title} (nhóm ${bIdx + 1}/${batches.size})…"
                    )

                    val corrections = proofreader.proofreadParagraphBatch(batch, activeContext)
                    for (fix in corrections) {
                        val targetEl = editableElements.getOrNull(fix.index) ?: continue
                        val beforeText = targetEl.text()
                        val cleanedAfter = TextCleaningEngine.normalizeParagraphText(fix.correctedText)
                        if (cleanedAfter.isNotBlank() && cleanedAfter != beforeText) {
                            targetEl.text(cleanedAfter)
                            chapterModified = true
                            diffs.add(
                                DiffEntry(
                                    id = "${chapter.fileName}_${fix.index}_${diffs.size}",
                                    chapterFileName = chapter.fileName,
                                    chapterTitle = chapter.title,
                                    paragraphIndex = fix.index,
                                    beforeText = beforeText,
                                    afterText = cleanedAfter,
                                    reason = fix.reason,
                                    accepted = true
                                )
                            )
                            stats = stats.copy(spellingFixes = stats.spellingFixes + 1)
                        }
                    }
                }

                if (chapterModified) {
                    saveXhtml(file, chapter.title, doc)
                }
            }
        }

        emit(
            PipelineStep.STEP_4_AI_PROOFREAD_PACK,
            0.96f,
            "Đang đóng gói tệp EPUB chuẩn (mimetype, META-INF, OEBPS)…"
        )
        EpubWorkspaceManager.packStandardEpub(workspace)
        stepStates[PipelineStep.STEP_4_AI_PROOFREAD_PACK] = StepState.COMPLETED

        emit(
            step = PipelineStep.STEP_4_AI_PROOFREAD_PACK,
            progress = 1.0f,
            detail = "Hoàn tất làm sạch & hiệu đính!",
            phase = ProcessingPhase.COMPLETED,
            workspace = workspace
        )
    }

    /**
     * Re-applies user's accept/reject diff selections on the Review screen and repacks the EPUB cleanly.
     */
    suspend fun rebuildEpubWithSelectedDiffs(
        workspace: PreparedWorkspace,
        diffs: List<DiffEntry>
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
        EpubWorkspaceManager.packStandardEpub(workspace)
    }

    private fun collectLightweightContextSample(chapters: List<ChapterDescriptor>): String {
        if (chapters.isEmpty()) return ""
        val indices = linkedSetOf(0, chapters.size / 2, chapters.lastIndex)
        val sb = StringBuilder(6500)
        for (i in indices) {
            if (sb.length >= 6000) break
            val file = File(chapters[i].filePath)
            if (!file.exists()) continue
            val text = Jsoup.parse(file, "UTF-8").body().text()
            if (text.isNotBlank()) {
                sb.appendLine("--- ${chapters[i].title} ---")
                sb.appendLine(text.take(2000))
            }
        }
        return sb.toString()
    }

    private fun buildParagraphBatches(paragraphs: List<IndexedParagraph>): List<List<IndexedParagraph>> {
        val batches = mutableListOf<List<IndexedParagraph>>()
        var current = mutableListOf<IndexedParagraph>()
        var charCount = 0

        for (p in paragraphs) {
            if (current.isNotEmpty() &&
                (current.size >= MAX_PARAGRAPHS_PER_BATCH || charCount + p.text.length > MAX_CHARS_PER_BATCH)
            ) {
                batches.add(current)
                current = mutableListOf()
                charCount = 0
            }
            current.add(p)
            charCount += p.text.length
        }
        if (current.isNotEmpty()) {
            batches.add(current)
        }
        return batches
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
