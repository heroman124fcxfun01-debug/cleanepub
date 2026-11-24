package com.example.pipeline

import android.content.Context
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
        onUpdate: (ProcessingSnapshot) -> Unit
    ): ProcessingSnapshot = withContext(Dispatchers.IO) {
        val stepStates = PipelineStep.entries.associateWith { StepState.PENDING }.toMutableMap()
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
        // BƯỚC 0: Giải nén / Trích xuất PDF & Đọc ngữ cảnh toàn cục (100% trên máy)
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

        // Remove raw input copy immediately to keep disk/RAM minimal
        rawInputFile.delete()

        val totalChapters = workspace.chapters.size
        stats = stats.copy(totalChapters = totalChapters, processedChapters = 0)

        emit(
            PipelineStep.STEP_0_UNPACK_CONTEXT,
            0.15f,
            "Đang quét ngữ cảnh & trích xuất Tên riêng trên máy ($totalChapters chương)…"
        )
        val contextSample = collectLightweightContextSample(workspace.chapters)
        bookContext = proofreader.analyzeGlobalContext(contextSample)
        stepStates[PipelineStep.STEP_0_UNPACK_CONTEXT] = StepState.COMPLETED
        emit(
            PipelineStep.STEP_0_UNPACK_CONTEXT,
            0.20f,
            "Thể loại: ${bookContext.genre} (${bookContext.properNouns.size} tên riêng)"
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
            val frac = 0.20f + ((idx + 1).toFloat() / totalChapters) * 0.16f
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

            val frac = 0.36f + ((idx + 1).toFloat() / totalChapters) * 0.14f
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
            val frac = 0.50f + ((idx + 1).toFloat() / totalChapters) * 0.14f
            emit(
                PipelineStep.STEP_3_NORMALIZE_WHITESPACE,
                frac,
                "Đang chuẩn hóa khoảng trắng & dấu câu (${idx + 1}/$totalChapters)…"
            )
        }
        stepStates[PipelineStep.STEP_3_NORMALIZE_WHITESPACE] = StepState.COMPLETED

        // ------------------------------------------------------------------
        // BƯỚC 4: Hiệu đính chính tả & dấu tiếng Việt trên máy + Đóng gói EPUB
        // ------------------------------------------------------------------
        stepStates[PipelineStep.STEP_4_AI_PROOFREAD_PACK] = StepState.RUNNING
        val activeContext = bookContext

        workspace.chapters.forEachIndexed { chapIdx, chapter ->
            coroutineContext.ensureActive()
            val file = File(chapter.filePath)
            val doc = parseXhtml(file)
            val editableElements = TextCleaningEngine.extractEditableParagraphs(doc)

            var chapterModified = false
            editableElements.forEachIndexed { pIdx, el ->
                val beforeText = el.text()
                val fix = proofreader.proofreadParagraph(beforeText, activeContext)
                if (fix != null && fix.correctedText != beforeText) {
                    el.text(fix.correctedText)
                    chapterModified = true
                    stats = stats.copy(spellingFixes = stats.spellingFixes + 1)
                    if (diffs.size < MAX_PREVIEW_DIFFS) {
                        diffs.add(
                            DiffEntry(
                                id = "${chapter.fileName}_${pIdx}_${diffs.size}",
                                chapterFileName = chapter.fileName,
                                chapterTitle = chapter.title,
                                paragraphIndex = pIdx,
                                beforeText = beforeText,
                                afterText = fix.correctedText,
                                reason = fix.reason,
                                accepted = true
                            )
                        )
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
                "Đang hiệu đính chính tả & dấu tiếng Việt (${chapIdx + 1}/$totalChapters chương)…"
            )
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
            detail = "Hoàn tất làm sạch & hiệu đính trên máy!",
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
