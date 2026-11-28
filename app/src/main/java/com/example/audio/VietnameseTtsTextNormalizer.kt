package com.example.audio

import java.text.Normalizer
import java.util.Locale

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

    // Regex phát hiện số phần trăm, số có phân cách hàng nghìn (10.000), số thập phân (3,5 / 3.14), và số nguyên
    private val percentNumberRegex = Regex("""(?<!\d)(\d{1,3}(?:\.\d{3})+|\d+(?:[.,]\d+)?)\s*%""")
    private val thousandGroupedIntRegex = Regex("""(?<!\d)\d{1,3}(?:\.\d{3})+(?!\d)""")
    private val decimalNumberRegex = Regex("""(?<!\d)(\d+)[.,](\d+)(?!\d)""")
    private val plainIntegerRegex = Regex("""(?<!\d)(\d+)(?!\d)""")

    private val VI_DIGITS = arrayOf(
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    )
    private val VI_SCALES = arrayOf("", "nghìn", "triệu", "tỷ")

    /**
     * Chuẩn hóa văn bản (cả `Ref Text` và `Target Text`) trước khi tra từ điển `vi_espeak_ipa.tsv`:
     * 1. Làm sạch & mở rộng viết tắt qua [normalizeForTts].
     * 2. Đổi toàn bộ số (số nguyên, số thập phân, phần trăm, phân cách hàng nghìn) sang chữ tiếng Việt đầy đủ.
     * 3. Chuyển sang chữ thường chuẩn tiếng Việt (`Locale.forLanguageTag("vi")`) và gom khoảng trắng.
     */
    fun normalizeForPhonemizer(rawText: String): String {
        val cleaned = normalizeForTts(rawText)
        if (cleaned.isEmpty()) return ""
        val expandedNumbers = convertNumbersToVietnameseWords(cleaned)
        return whitespaceRegex.replace(
            expandedNumbers.lowercase(Locale.forLanguageTag("vi")),
            " "
        ).trim()
    }

    /**
     * Chuyển đổi các con số trong chuỗi tiếng Việt thành chữ đọc tự nhiên
     * (ví dụ: "2025" -> "hai nghìn không trăm hai mươi lăm", "3,5%" -> "ba phẩy năm phần trăm").
     */
    fun convertNumbersToVietnameseWords(input: String): String {
        if (input.isEmpty() || input.none { it.isDigit() }) return input
        var text = input

        // 1. Phần trăm: 12,5% / 50% / 1.000%
        text = percentNumberRegex.replace(text) { m ->
            val rawNum = m.groupValues[1]
            "${convertSingleNumericToken(rawNum)} phần trăm"
        }

        // 2. Số có dấu chấm phân cách hàng nghìn kiểu Việt Nam: 10.000, 1.500.000
        text = thousandGroupedIntRegex.replace(text) { m ->
            val digitsOnly = m.value.replace(".", "")
            readVietnameseIntegerString(digitsOnly)
        }

        // 3. Số thập phân (3,14 hoặc 3.5)
        text = decimalNumberRegex.replace(text) { m ->
            val intPart = m.groupValues[1]
            val fracPart = m.groupValues[2]
            val intWords = readVietnameseIntegerString(intPart)
            val fracWords = if (fracPart.startsWith("0") || fracPart.length > 3) {
                spellDigitsIndividually(fracPart)
            } else {
                readVietnameseIntegerString(fracPart)
            }
            "$intWords phẩy $fracWords"
        }

        // 4. Số nguyên thông thường
        text = plainIntegerRegex.replace(text) { m ->
            readVietnameseIntegerString(m.groupValues[1])
        }

        return whitespaceRegex.replace(text, " ").trim()
    }

    private fun convertSingleNumericToken(rawNum: String): String {
        if (thousandGroupedIntRegex.matches(rawNum)) {
            return readVietnameseIntegerString(rawNum.replace(".", ""))
        }
        val decMatch = decimalNumberRegex.matchEntire(rawNum)
        if (decMatch != null) {
            val intWords = readVietnameseIntegerString(decMatch.groupValues[1])
            val fracPart = decMatch.groupValues[2]
            val fracWords = if (fracPart.startsWith("0") || fracPart.length > 3) {
                spellDigitsIndividually(fracPart)
            } else {
                readVietnameseIntegerString(fracPart)
            }
            return "$intWords phẩy $fracWords"
        }
        return readVietnameseIntegerString(rawNum)
    }

    private fun readVietnameseIntegerString(digits: String): String {
        if (digits.isEmpty()) return ""
        // Mã có số 0 đứng đầu (ví dụ số điện thoại 09..., mã 007) hoặc số quá dài (>12 chữ số): đọc từng chữ số
        if ((digits.length > 1 && digits[0] == '0') || digits.length > 12) {
            return spellDigitsIndividually(digits)
        }
        val value = digits.toLongOrNull() ?: return spellDigitsIndividually(digits)
        return numberToVietnameseWords(value)
    }

    private fun spellDigitsIndividually(digits: String): String {
        return digits.mapNotNull { ch ->
            val d = ch - '0'
            if (d in 0..9) VI_DIGITS[d] else null
        }.joinToString(" ")
    }

    private fun numberToVietnameseWords(number: Long): String {
        if (number == 0L) return "không"
        if (number < 0L) return "âm ${numberToVietnameseWords(-number)}"

        val groups = ArrayList<Int>(4)
        var rem = number
        while (rem > 0L) {
            groups.add((rem % 1000L).toInt())
            rem /= 1000L
        }

        val parts = ArrayList<String>(groups.size * 2)
        for (i in groups.lastIndex downTo 0) {
            val g = groups[i]
            if (g == 0) continue
            val isHighestGroup = (i == groups.lastIndex)
            val groupText = readThreeDigitGroup(g, readLeadingZeroHundred = !isHighestGroup)
            if (groupText.isNotEmpty()) {
                parts.add(groupText)
                val scale = VI_SCALES.getOrElse(i) { "" }
                if (scale.isNotEmpty()) {
                    parts.add(scale)
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun readThreeDigitGroup(group: Int, readLeadingZeroHundred: Boolean): String {
        val hundreds = group / 100
        val tens = (group % 100) / 10
        val ones = group % 10
        val sb = StringBuilder()

        if (hundreds > 0 || readLeadingZeroHundred) {
            sb.append(VI_DIGITS[hundreds]).append(" trăm")
        }

        when {
            tens > 1 -> {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(VI_DIGITS[tens]).append(" mươi")
                when (ones) {
                    1 -> sb.append(" mốt")
                    4 -> sb.append(" tư")
                    5 -> sb.append(" lăm")
                    in 2..9 -> sb.append(' ').append(VI_DIGITS[ones])
                }
            }
            tens == 1 -> {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append("mười")
                when (ones) {
                    5 -> sb.append(" lăm")
                    in 1..9 -> sb.append(' ').append(VI_DIGITS[ones])
                }
            }
            tens == 0 && ones > 0 -> {
                if (sb.isNotEmpty()) {
                    sb.append(" lẻ ").append(VI_DIGITS[ones])
                } else {
                    sb.append(VI_DIGITS[ones])
                }
            }
        }
        return sb.toString()
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
