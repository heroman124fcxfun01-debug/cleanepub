package com.example.audio

import java.util.Locale

data class DetectedCharacter(
    val name: String,
    val mentionCount: Int
)

/**
 * 100% Offline character extraction & dialogue speaker attribution engine.
 * Scans book chapters to identify up to 7 most frequent story characters,
 * and matches spoken dialogue paragraphs to their respective characters.
 */
object StoryCharacterScanner {

    private val speechVerbs = listOf(
        "nói", "hỏi", "đáp", "trả lời", "cười", "khẽ nói", "thì thầm", "quát",
        "hét", "lẩm bẩm", "bảo", "gọi", "thở dài", "nhíu mày", "gật đầu",
        "lắc đầu", "mỉm cười", "lạnh giọng", "trầm giọng", "thầm nghĩ", "kêu",
        "thốt lên", "ngập ngừng", "trấn an", "nhắc", "phản bác", "giải thích",
        "tuyên bố", "ra lệnh", "cười nhạt", "cười lạnh", "nói nhỏ", "nói tiếp",
        "said", "asked", "replied", "whispered", "shouted", "muttered"
    )

    // Matches 2 to 4 capitalized words (supports full Vietnamese diacritics)
    private val multiWordNameRegex = Regex(
        """(?<![\p{L}\p{N}])(\p{Lu}\p{Ll}+(?:[\s\-]+\p{Lu}\p{Ll}+){1,3})(?![\p{L}\p{N}])"""
    )

    // Matches a single capitalized word directly followed by a speech verb (e.g. "Minh nói:", "An hỏi")
    private val singleNameBeforeVerbRegex = Regex(
        """(?<![\p{L}\p{N}])(\p{Lu}\p{Ll}{1,14})\s+(?:khẽ\s+|chậm rãi\s+|lạnh lùng\s+|nhẹ nhàng\s+|vội\s+|liền\s+|bèn\s+|cũng\s+)?(?:${speechVerbs.joinToString("|") { Regex.escape(it) }})(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )

    private val dialogueOpeningMarkers = listOf("\"", "“", "«", "- ", "– ", "— ", "—", "–")

    private val stopWords = setOf(
        // Structural & headings
        "chương", "phần", "quyển", "hồi", "tiết", "mục", "tập", "tác giả", "lời mở đầu",
        "lời nói đầu", "mục lục", "kết thúc", "ngoại truyện", "phụ lục", "chú thích",
        // Common Vietnamese sentence starters & function words
        "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười",
        "nhưng", "tuy", "tuy nhiên", "trong", "ngoài", "khi", "nếu", "bởi", "bởi vì",
        "sau", "sau đó", "trước", "trước đó", "vừa", "đột nhiên", "bỗng", "bỗng nhiên",
        "thấy", "nghe", "nhìn", "không", "chẳng", "chưa", "cũng", "vẫn", "đang", "đã", "sẽ",
        "hắn", "nàng", "chàng", "y", "ta", "ngươi", "mình", "tôi", "cậu", "họ", "chúng",
        "chúng ta", "chúng tôi", "mọi người", "tất cả", "đây", "đó", "kia", "này",
        "thế", "vậy", "thế nhưng", "vì vậy", "cho nên", "sao", "thật", "quá", "rất",
        "hôm nay", "hôm qua", "ngày mai", "lúc này", "lúc đó", "khi đó", "sau này",
        "bây giờ", "hiện tại", "đêm nay", "sáng nay", "chiều nay", "tối nay",
        "bên trong", "bên ngoài", "phía trước", "phía sau", "trên trời", "dưới đất",
        "thế giới", "cuộc đời", "cuộc sống", "thời gian", "không gian", "sự thật",
        "điều này", "chuyện này", "việc này", "lần này", "lần đầu", "cuối cùng",
        "từ từ", "dần dần", "lập tức", "ngay lập tức", "bất ngờ", "dường như",
        "chính là", "quả nhiên", "thậm chí", "mặc dù", "dẫu sao", "nói chung",
        "có lẽ", "có thể", "chắc chắn", "đương nhiên", "tất nhiên", "hiển nhiên",
        "ánh mắt", "đôi mắt", "gương mặt", "khuôn mặt", "bàn tay", "cánh tay",
        "giọng nói", "tiếng cười", "lời nói", "bước chân", "trái tim", "tâm trạng",
        "cảm giác", "suy nghĩ", "ý nghĩ", "ký ức", "giấc mơ", "bóng dáng", "thân hình",
        "mặt trời", "ánh trăng", "bầu trời", "cơn gió", "cơn mưa", "mùa xuân",
        "mùa hạ", "mùa thu", "mùa đông", "buổi sáng", "buổi chiều", "buổi tối",
        // English common non-character capitalized phrases
        "the", "and", "but", "then", "when", "what", "where", "this", "that", "there", "chapter", "part"
    )

    private val leadingNoiseWords = setOf(
        "khi", "nếu", "vì", "do", "nhưng", "mà", "còn", "và", "hay", "hoặc",
        "trong", "ngoài", "trên", "dưới", "giữa", "sau", "trước", "cạnh", "bên",
        "thấy", "nghe", "nhìn", "gặp", "hỏi", "gọi", "bảo", "nói", "cười",
        "theo", "cùng", "với", "cho", "của", "bị", "được", "tại", "về", "đến",
        "từ", "qua", "bỗng", "chợt", "vừa", "đã", "đang", "sẽ", "vẫn", "cũng",
        "không", "chẳng", "chỉ", "rồi", "lại", "như", "tuy", "dù", "mặc",
        "lúc", "ngày", "đêm", "sáng", "chiều", "tối", "hôm", "một", "hai", "ba",
        "những", "các", "mọi", "toàn", "cả", "đây", "đó", "kia", "này"
    )

    private val trailingNoiseWords = setOf(
        "nói", "hỏi", "đáp", "cười", "khóc", "nhìn", "thấy", "nghe", "đi", "đến",
        "về", "lại", "ra", "vào", "lên", "xuống", "rồi", "cũng", "vẫn", "đã", "đang",
        "sẽ", "không", "chẳng", "liền", "bèn", "khẽ", "vội", "chợt", "bỗng", "là", "có"
    )

    /**
     * Scans chapter paragraphs offline and returns up to [maxCharacters] (default 7)
     * most frequently appearing story characters.
     */
    fun scanTopCharacters(
        chaptersParagraphs: List<List<String>>,
        maxCharacters: Int = 7
    ): List<DetectedCharacter> {
        if (chaptersParagraphs.isEmpty() || maxCharacters <= 0) return emptyList()

        // Sample up to 80 chapters evenly if the book is massive so scanning stays snappy on 4GB RAM
        val sampledChapters = if (chaptersParagraphs.size <= 80) {
            chaptersParagraphs
        } else {
            val step = chaptersParagraphs.size.toDouble() / 80.0
            (0 until 80).map { i -> chaptersParagraphs[(i * step).toInt().coerceAtMost(chaptersParagraphs.lastIndex)] }
        }

        // Map normalized lowercase key -> (weighted score, raw mention count, preferred display casing)
        val scoreMap = HashMap<String, Int>()
        val mentionMap = HashMap<String, Int>()
        val displayMap = HashMap<String, String>()

        fun registerCandidate(rawName: String, weight: Int) {
            val cleaned = cleanCandidateName(rawName) ?: return
            val key = cleaned.lowercase(Locale("vi", "VN"))
            if (isStopWord(key)) return
            scoreMap[key] = (scoreMap[key] ?: 0) + weight
            mentionMap[key] = (mentionMap[key] ?: 0) + 1
            // Prefer cleaner capitalized display form
            if (key !in displayMap) {
                displayMap[key] = cleaned
            }
        }

        for (chapter in sampledChapters) {
            for (paragraph in chapter) {
                val trimmed = paragraph.trim()
                if (trimmed.length < 4) continue
                val hasSpeechCue = speechVerbs.any { trimmed.contains(it, ignoreCase = true) } ||
                    trimmed.contains('"') || trimmed.contains('“') || trimmed.contains(':')

                // 1. Multi-word proper names (2..4 capitalized words)
                for (match in multiWordNameRegex.findAll(trimmed)) {
                    val candidate = match.groupValues[1]
                    val startIdx = match.range.first
                    val isSentenceStart = startIdx == 0 ||
                        (startIdx >= 2 && trimmed[startIdx - 2] in charArrayOf('.', '!', '?', '…', ':', '"', '“'))
                    // Boost names appearing mid-sentence or in dialogue/speech-verb paragraphs
                    val weight = when {
                        hasSpeechCue && !isSentenceStart -> 4
                        hasSpeechCue -> 3
                        !isSentenceStart -> 2
                        else -> 1
                    }
                    registerCandidate(candidate, weight)
                }

                // 2. Single-word proper names directly paired with a speech verb (e.g., "An cười đáp:")
                if (hasSpeechCue) {
                    for (match in singleNameBeforeVerbRegex.findAll(trimmed)) {
                        val single = match.groupValues[1]
                        if (single.length >= 2) {
                            registerCandidate(single, 3)
                        }
                    }
                }
            }
        }

        if (scoreMap.isEmpty()) return emptyList()

        // Merge shorter sub-names into full names if a full name already dominates (e.g. "Nguyễn Minh" absorbs "Minh" only if clearly redundant? Keep distinct unless exact suffix of a much stronger full name)
        val sortedKeys = scoreMap.entries
            .filter { (key, score) ->
                val mentions = mentionMap[key] ?: 0
                val wordCount = key.split(' ').size
                // Multi-word names need at least 2 mentions (or 1 strong speech mention in very short test stories)
                if (sampledChapters.sumOf { it.size } <= 25) {
                    mentions >= 1 && score >= 2
                } else {
                    mentions >= 2 && (wordCount >= 2 || score >= 6)
                }
            }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { mentionMap[it.key] ?: 0 }
                    .thenBy { it.key }
            )

        val selected = mutableListOf<DetectedCharacter>()
        val selectedLower = mutableListOf<String>()

        for (entry in sortedKeys) {
            if (selected.size >= maxCharacters) break
            val key = entry.key
            // Avoid picking both "Tiêu Viêm" and a fragmented duplicate like "Viêm" if already represented
            val isDuplicateSubToken = selectedLower.any { existing ->
                val existingParts = existing.split(' ')
                val keyParts = key.split(' ')
                (keyParts.size == 1 && existingParts.size >= 2 && key in existingParts) ||
                    (existingParts.size == 1 && keyParts.size >= 2 && existing in keyParts)
            }
            if (isDuplicateSubToken) continue

            selected.add(
                DetectedCharacter(
                    name = displayMap[key] ?: key,
                    mentionCount = mentionMap[key] ?: 1
                )
            )
            selectedLower.add(key)
        }

        return selected
    }

    /**
     * Determines which scanned character (if any) is speaking in [paragraph].
     * Returns `null` when the paragraph should be read by the Narrator voice.
     */
    fun detectSpeakerForParagraph(
        paragraph: String,
        previousParagraph: String?,
        characterNames: List<String>
    ): String? {
        if (characterNames.isEmpty()) return null
        val trimmed = paragraph.trim()
        if (trimmed.isEmpty()) return null

        val looksLikeDialogue = dialogueOpeningMarkers.any { trimmed.startsWith(it) } ||
            trimmed.contains('"') || trimmed.contains('“') || trimmed.contains('«')

        // Sort longer names first so "Lâm Thanh" matches before "Lâm"
        val orderedNames = characterNames.sortedByDescending { it.length }

        // 1. Direct attribution inside current paragraph: "<Name> ... <speechVerb>" or "<Name>:"
        for (name in orderedNames) {
            val idx = trimmed.indexOf(name, ignoreCase = true)
            if (idx >= 0 && isWholeWordMatch(trimmed, idx, name.length)) {
                val after = trimmed.substring((idx + name.length).coerceAtMost(trimmed.length))
                val nearWindow = after.take(36).lowercase(Locale("vi", "VN"))
                val hasDirectSpeech = nearWindow.startsWith(":") ||
                    speechVerbs.any { verb -> nearWindow.contains(verb) }
                if (hasDirectSpeech) {
                    return name
                }
            }
        }

        // 2. If the paragraph is dialogue and explicitly mentions a character outside or near the quote
        if (looksLikeDialogue) {
            val outsideQuotes = stripQuotedSegments(trimmed)
            if (outsideQuotes.isNotBlank()) {
                for (name in orderedNames) {
                    val idx = outsideQuotes.indexOf(name, ignoreCase = true)
                    if (idx >= 0 && isWholeWordMatch(outsideQuotes, idx, name.length)) {
                        return name
                    }
                }
            }

            // 3. Pure dialogue paragraph ("..." or "— ...") with speaker introduced at the end of previous paragraph
            val prev = previousParagraph?.trim().orEmpty()
            if (prev.isNotEmpty() && (prev.endsWith(":") || speechVerbs.any { prev.contains(it, ignoreCase = true) })) {
                var bestName: String? = null
                var bestPos = -1
                for (name in orderedNames) {
                    val idx = prev.lastIndexOf(name, ignoreCase = true)
                    if (idx > bestPos && isWholeWordMatch(prev, idx, name.length)) {
                        bestPos = idx
                        bestName = name
                    }
                }
                if (bestName != null) return bestName
            }
        }

        return null
    }

    private fun cleanCandidateName(raw: String): String? {
        var words = raw.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase(Locale("vi", "VN")) in leadingNoiseWords) {
            words.removeAt(0)
        }
        while (words.isNotEmpty() && words.last().lowercase(Locale("vi", "VN")) in trailingNoiseWords) {
            words.removeAt(words.lastIndex)
        }
        if (words.isEmpty() || words.size > 4) return null
        val joined = words.joinToString(" ")
        if (joined.length < 2 || joined.length > 32) return null
        if (joined.any { it.isDigit() }) return null
        // Must start with uppercase letter
        if (!joined.first().isUpperCase()) return null
        return joined
    }

    private fun isStopWord(lowerKey: String): Boolean {
        if (lowerKey in stopWords) return true
        val parts = lowerKey.split(' ')
        if (parts.all { it in stopWords || it in leadingNoiseWords }) return true
        return false
    }

    private fun isWholeWordMatch(text: String, startIndex: Int, length: Int): Boolean {
        val beforeOk = startIndex == 0 || !text[startIndex - 1].isLetterOrDigit()
        val endIndex = startIndex + length
        val afterOk = endIndex >= text.length || !text[endIndex].isLetterOrDigit()
        return beforeOk && afterOk
    }

    private fun stripQuotedSegments(text: String): String {
        return text
            .replace(Regex("""“[^”]*”"""), " ")
            .replace(Regex(""""[^"]*""""), " ")
            .replace(Regex("""«[^»]*»"""), " ")
            .trim()
    }
}
