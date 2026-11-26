package com.example.audio

import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.Locale

/**
 * Northern Vietnamese (Hà Nội) Grapheme-to-IPA Phonemizer & adaptive Tokenizer encoder
 * tailored for VieNeu-TTS v3 Turbo (`tokenizer.json` 419-token phoneme/control vocab)
 * and `sea_g2p.bin` diagnostics.
 */
object VietnameseIpaPhonemizer {

    private enum class ToneFormat {
        CHAO_LETTERS,   // ˧˧, ˨˩, ˧˥, ˧˩˧, ˧ˀ˥ / ˧˥ʔ, ˧˨ʔ
        NUMERIC_DIGITS, // 1..6
        NONE
    }

    /**
     * Reads the first 16 bytes of `sea_g2p.bin` (without loading 48MB into RAM) so diagnostics
     * show its exact binary magic/header signature.
     */
    fun inspectSeaG2pHeader(file: File?): String {
        if (file == null || !file.exists()) return "không tồn tại"
        return runCatching {
            val header = ByteArray(16)
            val read = RandomAccessFile(file, "r").use { raf ->
                raf.read(header)
            }
            if (read <= 0) return "rỗng"
            val hex = (0 until read).joinToString(" ") { String.format(Locale.US, "%02X", header[it]) }
            val ascii = (0 until read).map { idx ->
                val c = header[idx].toInt() and 0xFF
                if (c in 32..126) c.toChar() else '.'
            }.joinToString("")
            "magic='$ascii' hex=[$hex] (${AiSystemDiagnosticReport.formatMb(file.length())})"
        }.getOrElse { "lỗi đọc header: ${it.message}" }
    }

    /**
     * Converts normalized Vietnamese text into a sequence of token IDs matching the exact
     * vocabulary inside `tokenizer.json`.
     */
    fun encodeTextForVieNeuV3(
        cleanText: String,
        tokenizer: ParsedTokenizerData?,
        config: VieNeuConfigData?
    ): Pair<LongArray, String> {
        if (tokenizer == null || tokenizer.tokenToId.isEmpty()) {
            val fallbackIds = LongArray(cleanText.length.coerceAtLeast(1)) { i ->
                ((cleanText[i].code % 192) + 4).toLong()
            }
            return fallbackIds to cleanText
        }

        val vocab = tokenizer.tokenToId
        val usesIpa = detectUsesIpaVocab(vocab)
        val toneFormat = detectToneFormat(vocab)
        val spaceToken = detectSpaceToken(vocab)

        val phonemeString = if (usesIpa) {
            phonemizeVietnameseSentence(cleanText, toneFormat, vocab)
        } else {
            cleanText
        }

        val ids = ArrayList<Long>(phonemeString.length * 2 + 8)

        // Optional BOS / text_bos token
        val bosId = config?.textBosTokenId ?: config?.bosTokenId ?: tokenizer.bosTokenId
        if (bosId != null && bosId >= 0L) {
            ids.add(bosId)
        }

        // Tokenize the phoneme/text string via greedy longest-match against tokenizer.json vocab
        val words = phonemeString.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        for ((wIdx, word) in words.withIndex()) {
            if (wIdx > 0 && spaceToken != null) {
                vocab[spaceToken]?.let { ids.add(it) }
            }
            val prefixed = if (wIdx > 0 && spaceToken == null && vocab.containsKey("▁$word")) {
                "▁$word"
            } else if (wIdx > 0 && spaceToken == null && vocab.containsKey("Ġ$word")) {
                "Ġ$word"
            } else {
                word
            }
            tokenizeGreedy(prefixed, vocab, ids)
        }

        // Optional EOS / text_eos or speech_start token
        val textEos = config?.textEosTokenId ?: tokenizer.speechStartTokenId
        if (textEos != null && textEos >= 0L) {
            ids.add(textEos)
        }

        return LongArray(ids.size) { ids[it] } to phonemeString
    }

    private fun detectUsesIpaVocab(vocab: Map<String, Long>): Boolean {
        val ipaMarkers = listOf("ŋ", "ɲ", "ʈ", "ʂ", "ʐ", "ə", "ɯ", "ɤ", "ɛ", "ɔ", "ʔ", "tʰ", "kʰ", "˧", "˨", "˥")
        var hits = 0
        for (marker in ipaMarkers) {
            if (vocab.keys.any { it.contains(marker) }) hits++
        }
        return hits >= 3
    }

    private fun detectToneFormat(vocab: Map<String, Long>): ToneFormat {
        val hasChao = vocab.keys.any { it.contains("˧") || it.contains("˨") || it.contains("˥") || it.contains("˩") }
        if (hasChao) return ToneFormat.CHAO_LETTERS
        val hasDigitTones = (1..6).all { d -> vocab.containsKey(d.toString()) || vocab.containsKey("_$d") }
        if (hasDigitTones) return ToneFormat.NUMERIC_DIGITS
        return ToneFormat.NONE
    }

    private fun detectSpaceToken(vocab: Map<String, Long>): String? {
        return when {
            vocab.containsKey(" ") -> " "
            vocab.containsKey("▁") -> "▁"
            vocab.containsKey("Ġ") -> "Ġ"
            vocab.containsKey("|") -> "|"
            vocab.containsKey("<space>") -> "<space>"
            else -> null
        }
    }

    private fun tokenizeGreedy(text: String, vocab: Map<String, Long>, out: MutableList<Long>) {
        // Direct exact match
        vocab[text]?.let {
            out.add(it)
            return
        }
        var cursor = 0
        val len = text.length
        while (cursor < len) {
            var bestId: Long? = null
            var bestLen = 1
            val maxLook = minOf(len, cursor + 12)
            for (end in maxLook downTo cursor + 1) {
                val sub = text.substring(cursor, end)
                val found = vocab[sub]
                if (found != null) {
                    bestId = found
                    bestLen = end - cursor
                    break
                }
            }
            if (bestId != null) {
                out.add(bestId)
                cursor += bestLen
            } else {
                // Skip unknown combining marks gracefully or map lowercase char
                val ch = text[cursor].lowercaseChar().toString()
                vocab[ch]?.let { out.add(it) }
                cursor++
            }
        }
    }

    /**
     * Converts a Vietnamese sentence into space-separated IPA syllables matching Northern Vietnamese
     * phonology (`sea-g2p` / `viphoneme` style) while preserving punctuation tokens present in vocab.
     */
    private fun phonemizeVietnameseSentence(
        sentence: String,
        toneFormat: ToneFormat,
        vocab: Map<String, Long>
    ): String {
        val tokens = Regex("[\\p{L}\\p{M}\\d]+|[.,!?;:…—–\\-()\"']")
            .findAll(sentence)
            .map { it.value }
            .toList()

        val result = ArrayList<String>(tokens.size)
        for (tok in tokens) {
            if (tok.length == 1 && !tok[0].isLetterOrDigit()) {
                if (vocab.containsKey(tok)) {
                    result.add(tok)
                } else if (tok in setOf(";", ":")) {
                    if (vocab.containsKey(",")) result.add(",")
                } else if (tok in setOf("!", "?")) {
                    if (vocab.containsKey(".")) result.add(".")
                }
                continue
            }
            val ipa = phonemizeVietnameseWord(tok.lowercase(Locale("vi", "VN")), toneFormat, vocab)
            if (ipa.isNotEmpty()) {
                result.add(ipa)
            }
        }
        return result.joinToString(" ")
    }

    private fun phonemizeVietnameseWord(
        rawWord: String,
        toneFormat: ToneFormat,
        vocab: Map<String, Long>
    ): String {
        if (rawWord.isEmpty()) return ""
        val (baseWord, toneIdx) = extractVietnameseTone(rawWord)

        // 1. Parse Onset (Phụ âm đầu)
        var rest = baseWord
        val onset: String
        when {
            rest.startsWith("ngh") -> { onset = "ŋ"; rest = rest.substring(3) }
            rest.startsWith("ng") -> { onset = "ŋ"; rest = rest.substring(2) }
            rest.startsWith("nh") -> { onset = "ɲ"; rest = rest.substring(2) }
            rest.startsWith("ch") -> { onset = "tɕ"; rest = rest.substring(2) }
            rest.startsWith("tr") -> { onset = "tɕ"; rest = rest.substring(2) }
            rest.startsWith("th") -> { onset = "tʰ"; rest = rest.substring(2) }
            rest.startsWith("ph") -> { onset = "f"; rest = rest.substring(2) }
            rest.startsWith("kh") -> { onset = "x"; rest = rest.substring(2) }
            rest.startsWith("gh") -> { onset = "ɣ"; rest = rest.substring(2) }
            rest.startsWith("gi") && rest.length > 2 -> { onset = "z"; rest = rest.substring(2) }
            rest.startsWith("gi") -> { onset = "z"; rest = "i" }
            rest.startsWith("qu") && rest.length > 2 -> { onset = "kw"; rest = rest.substring(2) }
            rest.startsWith("đ") -> { onset = "ɗ"; rest = rest.substring(1) }
            rest.startsWith("b") -> { onset = "ɓ"; rest = rest.substring(1) }
            rest.startsWith("c") || rest.startsWith("k") -> { onset = "k"; rest = rest.substring(1) }
            rest.startsWith("d") || rest.startsWith("r") -> { onset = "z"; rest = rest.substring(1) }
            rest.startsWith("g") -> { onset = "ɣ"; rest = rest.substring(1) }
            rest.startsWith("h") -> { onset = "h"; rest = rest.substring(1) }
            rest.startsWith("l") -> { onset = "l"; rest = rest.substring(1) }
            rest.startsWith("m") -> { onset = "m"; rest = rest.substring(1) }
            rest.startsWith("n") -> { onset = "n"; rest = rest.substring(1) }
            rest.startsWith("p") -> { onset = "p"; rest = rest.substring(1) }
            rest.startsWith("s") || rest.startsWith("x") -> { onset = "s"; rest = rest.substring(1) }
            rest.startsWith("t") -> { onset = "t"; rest = rest.substring(1) }
            rest.startsWith("v") -> { onset = "v"; rest = rest.substring(1) }
            else -> { onset = "" }
        }

        // Normalize onset symbol to match exact vocab inventory (e.g. ɓ vs b, ɗ vs d, tɕ vs c)
        val adaptedOnset = adaptSymbolToVocab(onset, vocab)

        // 2. Parse Coda (Âm cuối)
        var coda = ""
        var isChecked = false
        when {
            rest.endsWith("ng") && rest.length > 2 -> { coda = "ŋ"; rest = rest.dropLast(2) }
            rest.endsWith("nh") && rest.length > 2 -> { coda = "ɲ"; rest = rest.dropLast(2) }
            rest.endsWith("ch") && rest.length > 2 -> { coda = "k"; isChecked = true; rest = rest.dropLast(2) }
            rest.endsWith("m") && rest.length > 1 -> { coda = "m"; rest = rest.dropLast(1) }
            rest.endsWith("n") && rest.length > 1 -> { coda = "n"; rest = rest.dropLast(1) }
            rest.endsWith("p") && rest.length > 1 -> { coda = "p"; isChecked = true; rest = rest.dropLast(1) }
            rest.endsWith("t") && rest.length > 1 -> { coda = "t"; isChecked = true; rest = rest.dropLast(1) }
            rest.endsWith("c") && rest.length > 1 -> { coda = "k"; isChecked = true; rest = rest.dropLast(1) }
            rest.endsWith("i") && rest.length > 1 && rest != "gi" && !rest.endsWith("uoi") -> {
                coda = "j"; rest = rest.dropLast(1)
            }
            rest.endsWith("y") && rest.length > 1 -> {
                coda = "j"; rest = rest.dropLast(1)
            }
            rest.endsWith("u") && rest.length > 1 && !rest.endsWith("ưu") && !rest.endsWith("iu") -> {
                coda = "w"; rest = rest.dropLast(1)
            }
            rest.endsWith("o") && rest.length > 1 && (rest.endsWith("ao") || rest.endsWith("eo")) -> {
                coda = "w"; rest = rest.dropLast(1)
            }
        }

        // 3. Parse Labialized Medial (Âm đệm u/o)
        var medial = ""
        if (rest.length >= 2 && (rest.startsWith("o") || rest.startsWith("u"))) {
            val second = rest[1]
            if ((rest.startsWith("o") && second in setOf('a', 'ă', 'e')) ||
                (rest.startsWith("u") && second in setOf('â', 'ê', 'y', 'ơ', 'a', 'i'))
            ) {
                if (rest !in setOf("ua", "uô", "uo", "ui")) {
                    medial = "w"
                    rest = rest.substring(1)
                }
            }
        }

        // 4. Parse Vowel Nucleus (Âm chính)
        val nucleus = when (rest) {
            "iê", "yê", "ia", "ya" -> "iə"
            "uô", "ua" -> "uə"
            "ươ", "ưa" -> "ɯə"
            "i", "y" -> "i"
            "ê" -> "e"
            "e" -> "ɛ"
            "ư" -> "ɯ"
            "ơ" -> "ɤ"
            "â" -> "ɤ̆"
            "a" -> "a"
            "ă" -> "ă"
            "u" -> "u"
            "ô" -> "o"
            "o" -> "ɔ"
            "ưu" -> "ɯw"
            "iu" -> "iw"
            "uôi" -> "uəj"
            "ươi" -> "ɯəj"
            "ươu" -> "ɯəw"
            "iêu", "yêu" -> "iəw"
            else -> rest.map { ch ->
                when (ch) {
                    'ă' -> "a"
                    'â' -> "ə"
                    'ê' -> "e"
                    'e' -> "ɛ"
                    'ô' -> "o"
                    'o' -> "ɔ"
                    'ơ' -> "ɤ"
                    'ư' -> "ɯ"
                    'y' -> "i"
                    else -> ch.toString()
                }
            }.joinToString("")
        }

        val adaptedNucleus = adaptSymbolToVocab(nucleus, vocab)
        val adaptedCoda = adaptSymbolToVocab(coda, vocab)
        val toneStr = formatToneSymbol(toneIdx, isChecked, toneFormat, vocab)

        return adaptedOnset + medial + adaptedNucleus + adaptedCoda + toneStr
    }

    private fun adaptSymbolToVocab(sym: String, vocab: Map<String, Long>): String {
        if (sym.isEmpty() || vocab.containsKey(sym)) return sym
        return when (sym) {
            "ɓ" -> if (vocab.containsKey("b")) "b" else sym
            "ɗ" -> if (vocab.containsKey("d")) "d" else sym
            "tɕ" -> when {
                vocab.containsKey("c") -> "c"
                vocab.containsKey("tʃ") -> "tʃ"
                else -> sym
            }
            "ɤ̆" -> when {
                vocab.containsKey("ə") -> "ə"
                vocab.containsKey("ɤ") -> "ɤ"
                else -> "a"
            }
            "ă" -> if (vocab.containsKey("a")) "a" else sym
            "ɤ" -> if (vocab.containsKey("ə")) "ə" else sym
            "ɣ" -> if (vocab.containsKey("g")) "g" else sym
            "ɯə" -> if (vocab.containsKey("ɯə")) "ɯə" else "ɯə"
            else -> sym
        }
    }

    /**
     * Tone indices:
     * 1 = ngang (level), 2 = huyền (falling), 3 = sắc (rising),
     * 4 = hỏi (dipping), 5 = ngã (creaky rising), 6 = nặng (glottalized low)
     */
    private fun formatToneSymbol(
        toneIdx: Int,
        isChecked: Boolean,
        format: ToneFormat,
        vocab: Map<String, Long>
    ): String {
        val effTone = when {
            isChecked && toneIdx == 3 -> 3
            isChecked && toneIdx == 6 -> 6
            else -> toneIdx
        }
        return when (format) {
            ToneFormat.NONE -> ""
            ToneFormat.NUMERIC_DIGITS -> {
                val underscore = "_$effTone"
                if (vocab.containsKey(underscore)) underscore else effTone.toString()
            }
            ToneFormat.CHAO_LETTERS -> {
                val candidates = when (effTone) {
                    1 -> listOf("˧˧", "˧", "33")
                    2 -> listOf("˨˩", "˨", "21")
                    3 -> if (isChecked) listOf("˧˥", "˦˥", "˥", "35") else listOf("˧˥", "˨˦", "35")
                    4 -> listOf("˧˩˧", "˧˩", "˨˩˨", "313")
                    5 -> listOf("˧ˀ˥", "˧˥ʔ", "˧˨˥", "˧˥", "35")
                    6 -> listOf("˧˨ʔ", "˨˩ʔ", "˧˩ʔ", "˨˩", "32")
                    else -> listOf("˧˧")
                }
                candidates.firstOrNull { cand ->
                    vocab.containsKey(cand) || cand.all { vocab.containsKey(it.toString()) }
                } ?: candidates.first()
            }
        }
    }

    /**
     * Strips Vietnamese tone diacritics while preserving vowel quality diacritics (ă, â, ê, ô, ơ, ư, đ).
     * Returns `(tonelessWord, toneIdx 1..6)`.
     */
    private fun extractVietnameseTone(word: String): Pair<String, Int> {
        var tone = 1 // ngang default
        val sb = StringBuilder(word.length)
        for (ch in word) {
            val mapped = TONE_DECOMPOSE[ch]
            if (mapped != null) {
                sb.append(mapped.first)
                if (mapped.second != 1) tone = mapped.second
            } else {
                // Handle NFD combining tone accents if input wasn't NFC
                val nfd = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                if (nfd.length > 1) {
                    var base = ch
                    for (c in nfd) {
                        when (c) {
                            '\u0300' -> tone = 2 // huyền
                            '\u0301' -> tone = 3 // sắc
                            '\u0309' -> tone = 4 // hỏi
                            '\u0303' -> tone = 5 // ngã
                            '\u0323' -> tone = 6 // nặng
                        }
                    }
                    val stripped = Normalizer.normalize(
                        nfd.filter { it !in setOf('\u0300', '\u0301', '\u0309', '\u0303', '\u0323') },
                        Normalizer.Form.NFC
                    )
                    sb.append(if (stripped.isNotEmpty()) stripped else base.toString())
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString() to tone
    }

    private val TONE_DECOMPOSE: Map<Char, Pair<Char, Int>> = hashMapOf(
        // a
        'à' to ('a' to 2), 'á' to ('a' to 3), 'ả' to ('a' to 4), 'ã' to ('a' to 5), 'ạ' to ('a' to 6),
        // ă
        'ằ' to ('ă' to 2), 'ắ' to ('ă' to 3), 'ẳ' to ('ă' to 4), 'ẵ' to ('ă' to 5), 'ặ' to ('ă' to 6),
        // â
        'ầ' to ('â' to 2), 'ấ' to ('â' to 3), 'ẩ' to ('â' to 4), 'ẫ' to ('â' to 5), 'ậ' to ('â' to 6),
        // e
        'è' to ('e' to 2), 'é' to ('e' to 3), 'ẻ' to ('e' to 4), 'ẽ' to ('e' to 5), 'ẹ' to ('e' to 6),
        // ê
        'ề' to ('ê' to 2), 'ế' to ('ê' to 3), 'ể' to ('ê' to 4), 'ễ' to ('ê' to 5), 'ệ' to ('ê' to 6),
        // i
        'ì' to ('i' to 2), 'í' to ('i' to 3), 'ỉ' to ('i' to 4), 'ĩ' to ('i' to 5), 'ị' to ('i' to 6),
        // o
        'ò' to ('o' to 2), 'ó' to ('o' to 3), 'ỏ' to ('o' to 4), 'õ' to ('o' to 5), 'ọ' to ('o' to 6),
        // ô
        'ồ' to ('ô' to 2), 'ố' to ('ô' to 3), 'ổ' to ('ô' to 4), 'ỗ' to ('ô' to 5), 'ộ' to ('ô' to 6),
        // ơ
        'ờ' to ('ơ' to 2), 'ớ' to ('ơ' to 3), 'ở' to ('ơ' to 4), 'ỡ' to ('ơ' to 5), 'ợ' to ('ơ' to 6),
        // u
        'ù' to ('u' to 2), 'ú' to ('u' to 3), 'ủ' to ('u' to 4), 'ũ' to ('u' to 5), 'ụ' to ('u' to 6),
        // ư
        'ừ' to ('ư' to 2), 'ứ' to ('ư' to 3), 'ử' to ('ư' to 4), 'ữ' to ('ư' to 5), 'ự' to ('ư' to 6),
        // y
        'ỳ' to ('y' to 2), 'ý' to ('y' to 3), 'ỷ' to ('y' to 4), 'ỹ' to ('y' to 5), 'ỵ' to ('y' to 6)
    )
}
