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
