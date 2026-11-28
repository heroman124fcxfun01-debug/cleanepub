package com.example.audio

import java.text.Normalizer

/**
 * Bộ tiền xử lý và chuẩn hóa văn bản tiếng Việt chuyên dụng cho Android Offline Text-to-Speech.
 *
 * Mục tiêu:
 * 1. Mở rộng các từ viết tắt phổ biến ("TP.HCM", "BS.", "TS.", "tr.", "ch.", "v.v.", ...) thành chữ đầy đủ.
 * 2. Loại bỏ ký tự rác/ký hiệu đặc biệt gây vấp tiếng (*, #, chú thích [1], [2], ...).
 * 3. Biến dấu gạch ngang đối thoại ("—", " - ") thành dấu phẩy để bộ đọc ngắt nhịp tự nhiên.
 * 4. Tách đoạn văn thành các câu ngắn bằng Regex thông minh, KHÔNG cắt nhầm ở số thập phân (3.14)
 *    hoặc dấu chấm viết tắt (Mr., Dr., ...).
 */
object VietnameseTtsTextNormalizer {

    // Các ký tự ẩn không in được và ký tự điều khiển
    private val invisibleCharsRegex = Regex("""[\u200B-\u200F\uFEFF\u00AD\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFD□■]""")

    // Chú thích trong ngoặc vuông/tròn dính chữ: [1], [2], [14], [a], [*], (*), (**)
    private val bracketCitationRegex = Regex("""\[\s*(?:\d{1,3}|[a-zA-Z]|\*+)\s*]|\(\s*\*+\s*\)""")

    // Ký tự đặc biệt hay bị TTS đọc thành tên ký hiệu hoặc gây ngắt câu bất thường
    private val disruptiveSymbolsRegex = Regex("""[*#~^_|•▪◆◇★☆※†‡§¶]+""")

    // Dấu gạch ngang đối thoại ở đầu đoạn / đầu câu: "— Anh đi đâu?", "– Chào em", "- Không sao đâu"
    private val leadingDialogueDashRegex = Regex("""(?m)^\s*[—–\-]\s+""")

    // Dấu gạch ngang phân tách lời thoại hoặc vế câu ở giữa câu: " — ", " – ", " - "
    private val inlineDialogueDashRegex = Regex("""\s*[—–]\s*|\s+-\s+""")

    // Danh sách cặp Regex -> Chuỗi thay thế cho các từ viết tắt tiếng Việt phổ biến.
    // Thứ tự đặt từ cụm dài -> cụm ngắn để tránh khớp nhầm một phần.
    private val abbreviationReplacements: List<Pair<Regex, String>> = listOf(
        // Địa danh & tổ chức phổ biến
        Regex("""(?i)\bTP\.?\s*HCM\b""") to "Thành phố Hồ Chí Minh",
        Regex("""\bTP\.\s*(?=\p{Lu})""") to "Thành phố ",
        Regex("""\bUBND\b""") to "Ủy ban nhân dân",
        Regex("""\bHĐND\b""") to "Hội đồng nhân dân",
        Regex("""\bNXB\b""") to "Nhà xuất bản",
        Regex("""\bTNHH\b""") to "Trách nhiệm hữu hạn",
        Regex("""\bSTT\b""") to "số thứ tự",

        // Học hàm, học vị, chức danh (có dấu chấm theo sau)
        Regex("""(?i)\bPGS\.?\s*TS\.?\s*""") to "Phó Giáo sư Tiến sĩ ",
        Regex("""(?i)\bGS\.?\s*TS\.?\s*""") to "Giáo sư Tiến sĩ ",
        Regex("""(?i)\bPGS\.\s*""") to "Phó Giáo sư ",
        Regex("""(?i)\bGS\.\s*""") to "Giáo sư ",
        Regex("""(?i)\bThS\.\s*""") to "Thạc sĩ ",
        Regex("""(?i)\bTS\.\s*""") to "Tiến sĩ ",
        Regex("""(?i)\bBS\.\s*""") to "Bác sĩ ",
        Regex("""(?i)\bKS\.\s*""") to "Kỹ sư ",
        Regex("""(?i)\bLS\.\s*""") to "Luật sư ",
        Regex("""(?i)\bKTS\.\s*""") to "Kiến trúc sư ",
        Regex("""(?i)\bĐD\.\s*""") to "Đạo diễn ",
        Regex("""(?i)\bNSND\b""") to "Nghệ sĩ nhân dân",
        Regex("""(?i)\bNSƯT\b""") to "Nghệ sĩ ưu tú",

        // Ký hiệu sách & tham chiếu trang/chương
        Regex("""(?i)\btr\.\s*(?=\d)""") to "trang ",
        Regex("""(?i)\bch\.\s*(?=\d)""") to "chương ",
        Regex("""(?i)\bQ\.\s*(?=\d)""") to "Quận ",
        Regex("""(?i)\bP\.\s*(?=\d)""") to "Phường ",

        // Cụm viết tắt cuối câu / giải thích
        Regex("""(?i)\bv\.\s*v\.(?:\.\.)?""") to "vân vân",
        Regex("""(?i)\bvv\.""") to "vân vân",
        Regex("""(?i)\bVD\s*[:.]""") to "ví dụ:",
        Regex("""(?i)\bđ/c\b""") to "địa chỉ",
        Regex("""(?i)\bSĐT\b""") to "số điện thoại"
    )

    // Các từ viết tắt tiếng Anh/quốc tế có dấu chấm không nên cắt câu tại dấu chấm đó
    private val nonSentenceEndingAbbrRegex = Regex(
        """(?i)\b(?:Mr|Mrs|Ms|Dr|Prof|St|Jr|Sr|No|Vol|Fig|vs|etc)\."""
    )

    // Số thập phân hoặc số phân tách hàng nghìn (ví dụ: 3.14, 1.5, 10.000)
    private val numericDotRegex = Regex("""(?<=\d)\.(?=\d)""")

    // Chữ cái viết tắt đơn lẻ như A.B. hoặc J.K.
    private val initialDotRegex = Regex("""(?<=\b\p{Lu})\.(?=\s*\p{Lu}\.)""")

    // Dấu chấm câu lặp nhiều lần hoặc dấu phẩy thừa
    private val multiDotsRegex = Regex("""\.{3,}""")
    private val multiPunctRegex = Regex("""([!?]){2,}""")
    private val duplicateCommaRegex = Regex("""(,\s*){2,}""")
    private val leadingCommaRegex = Regex("""^\s*,\s*""")
    private val whitespaceRegex = Regex("""\s+""")

    // Ký tự giữ chỗ tạm thời cho dấu chấm không phải kết thúc câu khi chạy Regex tách câu
    private const val DOT_PLACEHOLDER = '\uE000'

    // Regex tách câu thông minh: tách sau ., !, ?, …, ; (kèm ngoặc đóng nếu có) + khoảng trắng
    private val smartSentenceBoundaryRegex = Regex("""(?<=[.!?…;]["”’»)\]]?)\s+""")

    /**
     * Làm sạch và chuẩn hóa một đoạn văn tiếng Việt để đưa vào Android TTS đọc tự nhiên.
     */
    fun normalizeForTts(rawText: String): String {
        if (rawText.isBlank()) return ""

        // Bước 1: Chuẩn hóa Unicode NFC và dọn ký tự ẩn / khoảng trắng không ngắt
        var text = Normalizer.normalize(rawText, Normalizer.Form.NFC)
            .replace('\u00A0', ' ')
            .replace(invisibleCharsRegex, "")

        // Bước 2: Xóa chú thích trong ngoặc vuông [1], [2], (*) và các ký tự gây vấp (*, #, ...)
        text = text.replace(bracketCitationRegex, "")
        text = text.replace(disruptiveSymbolsRegex, " ")

        // Bước 3: Mở rộng các từ viết tắt tiếng Việt phổ biến sang dạng đầy đủ
        for ((pattern, replacement) in abbreviationReplacements) {
            text = pattern.replace(text, replacement)
        }

        // Bước 4: Xử lý dấu gạch ngang đối thoại ("—", "–", " - ")
        // Đầu đoạn thoại: bỏ gạch đầu dòng để TTS không đọc "gạch ngang"
        text = leadingDialogueDashRegex.replace(text, "")
        // Giữa câu: đổi thành dấu phẩy để bộ đọc ngắt nhịp tự nhiên
        text = inlineDialogueDashRegex.replace(text, ", ")

        // Bước 5: Chuẩn hóa dấu câu dư thừa & khoảng trắng
        text = multiDotsRegex.replace(text, "…")
        text = multiPunctRegex.replace(text, "$1")
        text = duplicateCommaRegex.replace(text, ", ")
        text = leadingCommaRegex.replace(text, "")
        text = text.replace(" ,", ",").replace(" .", ".")
        text = whitespaceRegex.replace(text, " ").trim()

        return text
    }

    /**
     * Chuẩn hóa văn bản rồi tách thành danh sách các câu ngắn mượt mà cho hàng đợi `QUEUE_ADD`.
     *
     * - Bảo vệ số thập phân (3.14, 10.000) và từ viết tắt có dấu chấm (Mr., Dr., J.K.) để không cắt câu sai.
     * - Nếu một câu quá dài (> [maxSentenceLength] ký tự), tiếp tục tách tại dấu phẩy/hai chấm/khoảng trắng
     *   để Android TTS không bao giờ bị hụt tiếng hoặc vượt giới hạn độ dài utterance.
     */
    fun splitIntoSmartSentences(
        rawText: String,
        maxSentenceLength: Int = 320
    ): List<String> {
        val normalized = normalizeForTts(rawText)
        if (normalized.isEmpty() || normalized.none { it.isLetterOrDigit() }) {
            return emptyList()
        }

        // Bảo vệ các dấu chấm KHÔNG phải dấu kết thúc câu bằng ký tự giữ chỗ tạm
        var protected = numericDotRegex.replace(normalized, DOT_PLACEHOLDER.toString())
        protected = nonSentenceEndingAbbrRegex.replace(protected) { match ->
            match.value.dropLast(1) + DOT_PLACEHOLDER
        }
        protected = initialDotRegex.replace(protected, DOT_PLACEHOLDER.toString())

        // Tách theo ranh giới câu tự nhiên
        val rawSentences = smartSentenceBoundaryRegex
            .split(protected)
            .map { it.replace(DOT_PLACEHOLDER, '.').trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }

        // Kiểm tra độ dài từng câu; chỉ tách nhỏ thêm khi một câu đơn quá dài
        val finalSentences = mutableListOf<String>()
        for (sentence in rawSentences) {
            if (sentence.length <= maxSentenceLength) {
                finalSentences.add(sentence)
            } else {
                splitOverlongSentence(sentence, maxSentenceLength, finalSentences)
            }
        }
        return finalSentences
    }

    /**
     * Đếm số từ (âm tiết có chữ cái/chữ số) trong một câu tiếng Việt.
     */
    fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        var inWord = false
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
        }
        return count
    }

    /**
     * Gộp các câu quá ngắn (< [minWords] từ) với câu liền kề (tối đa [maxMergedWords] từ / lần sinh)
     * dành riêng cho bộ đọc AI VieNeu-TTS GGUF nhằm giảm mạnh số lần khởi chạy mô hình và tạo ngữ điệu mượt hơn.
     *
     * Trả về danh sách cặp `(mergedSpeakText, firstOriginalSentenceIndex)` để giao diện vẫn tô sáng câu chính xác.
     */
    fun mergeShortSentencesForAiTts(
        sentences: List<String>,
        minWords: Int = 8,
        maxMergedWords: Int = 32
    ): List<Pair<String, Int>> {
        if (sentences.isEmpty()) return emptyList()
        val result = ArrayList<Pair<String, Int>>(sentences.size)
        var i = 0
        while (i < sentences.size) {
            val startIdx = i
            val sb = StringBuilder(sentences[i].trim())
            var words = countWords(sentences[i])
            i++

            // Nếu câu hiện tại ngắn (< minWords) hoặc câu kế tiếp quá ngắn (< minWords)
            // và tổng số từ sau gộp không vượt maxMergedWords thì gộp lại thành một lần sinh GGUF
            while (i < sentences.size) {
                val nextSentence = sentences[i].trim()
                val nextWords = countWords(nextSentence)
                val shouldMerge = (words < minWords || nextWords < minWords) &&
                    (words + nextWords <= maxMergedWords) &&
                    (sb.length + 1 + nextSentence.length <= 220)
                if (!shouldMerge) break
                if (sb.isNotEmpty() && sb.last() !in ".!?…;:") {
                    sb.append('.')
                }
                sb.append(' ').append(nextSentence)
                words += nextWords
                i++
            }
            result.add(sb.toString() to startIdx)
        }
        return result
    }

    /**
     * Chia câu theo dấu câu tự nhiên thành các phân đoạn gọn **80–120 ký tự** dành riêng cho
     * luồng đọc AI VieNeu-TTS độ trễ cực thấp (Producer-Consumer 2 luồng, tối ưu Helio G99):
     * - Câu đầu tiên ([isFirstParagraphOfStream]) ưu tiên ngắn gọn (~55–95 ký tự) để phát tiếng
     *   chỉ sau ~1.2–1.5 giây kể từ lúc bấm Phát.
     * - Các câu tiếp theo giữ trong khoảng 80–120 ký tự (gộp câu quá ngắn `< minChars` nếu tổng
     *   vẫn `<= maxChars`, cắt câu dài `> maxChars` tại `,`, `;`, `:`, hoặc ranh giới từ) giúp
     *   luồng Producer luôn tính xong câu N+1, N+2 trước khi câu N đọc xong.
     *
     * Trả về danh sách cặp `(speakText, firstOriginalSentenceIndex)` để tô sáng câu chính xác.
     */
    fun chunkSentencesForLowLatencyAi(
        sentences: List<String>,
        minChars: Int = 80,
        maxChars: Int = 120,
        firstChunkMaxChars: Int = 95,
        isFirstParagraphOfStream: Boolean = false
    ): List<Pair<String, Int>> {
        if (sentences.isEmpty()) return emptyList()

        // Bước 1: Đảm bảo không có đơn vị nào vượt quá maxChars (cắt gọn theo dấu phẩy/chấm phẩy/khoảng trắng)
        val pieces = ArrayList<Pair<String, Int>>(sentences.size)
        sentences.forEachIndexed { idx, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.none { it.isLetterOrDigit() }) return@forEachIndexed
            val targetLimit = if (isFirstParagraphOfStream && pieces.isEmpty()) firstChunkMaxChars else maxChars
            if (trimmed.length <= targetLimit) {
                pieces.add(trimmed to idx)
            } else {
                val subParts = mutableListOf<String>()
                splitOverlongSentence(trimmed, targetLimit, subParts)
                subParts.forEach { part ->
                    pieces.add(part to idx)
                }
            }
        }
        if (pieces.isEmpty()) return emptyList()

        // Bước 2: Gộp các mẩu ngắn (< minChars) với mẩu kế tiếp miễn tổng <= maxChars
        // (nhưng giữ nguyên mẩu đầu tiên <= firstChunkMaxChars để phát âm tức thì!)
        val result = ArrayList<Pair<String, Int>>(pieces.size)
        var i = 0
        while (i < pieces.size) {
            val (firstText, startIdx) = pieces[i]
            val sb = StringBuilder(firstText)
            val isOpeningChunk = isFirstParagraphOfStream && result.isEmpty()
            val ceiling = if (isOpeningChunk) firstChunkMaxChars else maxChars
            val mergeFloor = if (isOpeningChunk) minOf(55, minChars) else minChars
            i++

            while (i < pieces.size && sb.length < mergeFloor) {
                val nextText = pieces[i].first
                val sep = if (sb.isNotEmpty() && sb[sb.length - 1] !in ".!?…;:,") ". " else " "
                if (sb.length + sep.length + nextText.length > ceiling) break
                sb.append(sep).append(nextText)
                i++
            }
            result.add(sb.toString() to startIdx)
        }
        return result
    }

    private fun splitOverlongSentence(
        sentence: String,
        maxLength: Int,
        out: MutableList<String>
    ) {
        var start = 0
        while (start < sentence.length) {
            val remaining = sentence.length - start
            if (remaining <= maxLength) {
                val tail = sentence.substring(start).trim()
                if (tail.any { it.isLetterOrDigit() }) out.add(tail)
                break
            }

            val window = sentence.substring(start, start + maxLength)
            val clauseBreak = maxOf(
                window.lastIndexOf(", "),
                window.lastIndexOf(": "),
                window.lastIndexOf("; ")
            )
            val splitOffset = when {
                clauseBreak >= maxLength / 3 -> clauseBreak + 1
                else -> {
                    val spaceBreak = window.lastIndexOf(' ')
                    if (spaceBreak > 20) spaceBreak else maxLength
                }
            }
            val part = sentence.substring(start, start + splitOffset).trim()
            if (part.any { it.isLetterOrDigit() }) {
                out.add(part)
            }
            start += splitOffset
            while (start < sentence.length && sentence[start].isWhitespace()) {
                start++
            }
        }
    }
}
