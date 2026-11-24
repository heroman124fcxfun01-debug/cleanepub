package com.example.pipeline

enum class InputFileType(val label: String) {
    EPUB("EPUB"),
    PDF("PDF")
}

enum class SaveMode(val code: String) {
    REPLACE_ORIGINAL("REPLACE"),
    KEEP_ORIGINAL("KEEP_COPY")
}

enum class PipelineStep(val index: Int) {
    STEP_0_UNPACK_CONTEXT(0),
    STEP_1_CLEAN_JUNK(1),
    STEP_2_UNIFY_FONT(2),
    STEP_3_NORMALIZE_WHITESPACE(3),
    STEP_4_AI_PROOFREAD_PACK(4)
}

enum class StepState {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}

data class SourceFileInfo(
    val displayName: String,
    val sourceUriString: String,
    val resolvedFilePath: String?,
    val resolvedParentDir: String?,
    val fileType: InputFileType,
    val fileSizeBytes: Long
) {
    val baseNameWithoutExt: String
        get() {
            val dotIndex = displayName.lastIndexOf('.')
            return if (dotIndex > 0) displayName.substring(0, dotIndex) else displayName
        }

    val cleanCopyFileName: String
        get() = "${baseNameWithoutExt}_clean.epub"

    val displayParentFolder: String
        get() {
            val dir = resolvedParentDir ?: return "Thư mục gốc của tệp"
            return dir.removePrefix("/storage/emulated/0")
                .ifBlank { "/Bộ nhớ trong" }
        }
}

data class BookContext(
    val genre: String = "",
    val properNouns: List<String> = emptyList(),
    val summaryNote: String = ""
)

data class RealtimeStats(
    val junkLinksRemoved: Int = 0,
    val whitespaceFixes: Int = 0,
    val spellingFixes: Int = 0,
    val totalChapters: Int = 0,
    val processedChapters: Int = 0
)

data class DiffEntry(
    val id: String,
    val chapterFileName: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val beforeText: String,
    val afterText: String,
    val reason: String,
    val accepted: Boolean = true
)

data class ChapterDescriptor(
    val id: String,
    val fileName: String,
    val title: String,
    val filePath: String
)

data class CleaningOptions(
    val cleanLinks: Boolean = true,
    val fixFonts: Boolean = true,
    val fixSpelling: Boolean = true,
    val fixWhitespace: Boolean = true,
    val optimizeContent: Boolean = true
)

data class PreparedWorkspace(
    val sessionDirPath: String,
    val stageDirPath: String,
    val tempOutputEpubPath: String,
    val bookTitle: String,
    val bookAuthor: String,
    val chapters: List<ChapterDescriptor>,
    val imageRelativePaths: List<String>,
    val metadata: EpubMetadata? = null
)

sealed interface SaveResult {
    data class Success(
        val outputFileName: String,
        val outputDisplayPath: String,
        val savedAbsolutePath: String?,
        val savedUriString: String?,
        val mode: SaveMode
    ) : SaveResult

    data class Failure(val message: String) : SaveResult
}

enum class ProcessingPhase {
    IDLE,
    RUNNING,
    COMPLETED,
    ERROR,
    CANCELLED
}

data class ProcessingSnapshot(
    val phase: ProcessingPhase = ProcessingPhase.IDLE,
    val sourceFile: SourceFileInfo? = null,
    val currentStep: PipelineStep = PipelineStep.STEP_0_UNPACK_CONTEXT,
    val stepStates: Map<PipelineStep, StepState> = PipelineStep.entries.associateWith { StepState.PENDING },
    val progressFraction: Float = 0f,
    val statusDetail: String = "",
    val stats: RealtimeStats = RealtimeStats(),
    val bookContext: BookContext? = null,
    val diffs: List<DiffEntry> = emptyList(),
    val workspace: PreparedWorkspace? = null,
    val metadata: EpubMetadata? = null,
    val errorMessage: String? = null
)
