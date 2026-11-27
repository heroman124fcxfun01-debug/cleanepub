package com.example.audio

import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.Locale

/**
 * Northern Vietnamese (Hà Nội) Grapheme-to-IPA Phonemizer & exact HuggingFace GPT-2 `ByteLevel` BPE
 * encoder tailored for VieNeu-TTS v3 Turbo (`tokenizer.json` 419-token ByteLevel BPE vocab:
 * 0..43 special tokens + 44..299 256 UTF-8 byte tokens + 300..418 119 BPE phoneme/symbol merges)
 * and `sea_g2p.bin` diagnostics.
 */
object VietnameseIpaPhonemizer {

    private enum class VocabStyle {
        SEA_G2P_ESPEAK_IPA, // eSpeak-NG / sea-g2p IPA: ˈ, ː, ɗ, t̪, tone digits 2/ɜ(or 5)/4/5(or 3)/6 before coda
        CHAO_IPA,           // Chao tone letters: ˧˧, ˨˩, ˧˥, ˧˩˧, ˧ˀ˥, ˧˨ʔ
        RAW_VIETNAMESE      // Direct UTF-8 Vietnamese orthography
    }

    // -------------------------------------------------------------------------
    // Exact GPT-2 / HuggingFace `ByteLevel` bytes <-> unicode bijection (256 entries)
    // -------------------------------------------------------------------------

    private val BYTE_TO_UNICODE: CharArray = CharArray(256).also { table ->
        val direct = BooleanArray(256)
        for (b in 33..126) direct[b] = true
        for (b in 161..172) direct[b] = true
        for (b in 174..255) direct[b] = true
        var extra = 256
        for (b in 0..255) {
            table[b] = if (direct[b]) b.toChar() else (extra++).toChar()
        }
    }

    private val UNICODE_TO_BYTE: Map<Char, Byte> = HashMap<Char, Byte>(256).also { map ->
        for (b in 0..255) {
            map[BYTE_TO_UNICODE[b]] = b.toByte()
        }
    }

    fun encodeUtf8ToByteLevel(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = CharArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = BYTE_TO_UNICODE[bytes[i].toInt() and 0xFF]
        }
        return String(out)
    }

    fun decodeByteLevelToUtf8(token: String): String {
        val out = ByteArray(token.length)
        var n = 0
        for (ch in token) {
            val b = UNICODE_TO_BYTE[ch]
            if (b != null) {
                out[n++] = b
            } else {
                val utf = ch.toString().toByteArray(Charsets.UTF_8)
                return token
            }
        }
        return String(out, 0, n, Charsets.UTF_8)
    }

    /**
     * Decodes BPE merge tokens (`id >= 300` or multi-char non-special tokens) from GPT-2 ByteLevel
     * back to readable UTF-8 for diagnostics and runtime phoneme-alphabet adaptation.
     */
    fun describeDecodedBpeMerges(tokenizer: ParsedTokenizerData?, limit: Int = 120): String {
        if (tokenizer == null || tokenizer.idToToken.isEmpty()) return "(trống)"
        val merged = tokenizer.idToToken.entries
            .filter { (id, tok) ->
                id >= 300L && !tok.startsWith("<|")
            }
            .sortedBy { it.key }
            .take(limit)
        if (merged.isEmpty()) return "(không có merge >= 300)"
        return merged.joinToString(" ") { (id, tok) ->
            val decoded = if (tokenizer.isByteLevelBpe) decodeByteLevelToUtf8(tok) else tok
            val safe = decoded.replace("\n", "\\n").replace("\r", "\\r").replace(" ", "␠")
            "$id:$safe"
        }
    }

    /**
     * Reads the first 64 bytes of `sea_g2p.bin` (0 KB Java heap) so diagnostics show its header layout.
     */
    fun inspectSeaG2pHeader(file: File?): String {
        if (file == null || !file.exists()) return "không tồn tại"
        return runCatching {
            val header = ByteArray(64)
            val read = RandomAccessFile(file, "r").use { raf ->
                raf.read(header)
            }
            if (read <= 0) return "rỗng"
            val hex = (0 until minOf(read, 32)).joinToString(" ") { String.format(Locale.US, "%02X", header[it]) }
            val ascii = (0 until minOf(read, 16)).map { idx ->
                val c = header[idx].toInt() and 0xFF
                if (c in 32..126) c.toChar() else '.'
            }.joinToString("")
            "magic='$ascii' hex32=[$hex] (${AiSystemDiagnosticReport.formatMb(file.length())})"
        }.getOrElse { "lỗi đọc header: ${it.message}" }
    }

    /**
     * Converts normalized Vietnamese text into a sequence of exact token IDs matching `tokenizer.json`.
     * Supports both GPT-2 `ByteLevel` BPE (`VieNeu-TTS-v3-Turbo`) and standard direct vocab tokenizers.
     */
    fun encodeTextForVieNeuV3(
        cleanText: String,
        tokenizer: ParsedTokenizerData?,
        config: VieNeuConfigData?,
        seaG2pDict: SeaG2pBinaryDict? = null
    ): Pair<LongArray, String> {
        if (tokenizer == null || tokenizer.tokenToId.isEmpty()) {
            val fallbackIds = LongArray(cleanText.length.coerceAtLeast(1)) { i ->
                ((cleanText[i].code % 192) + 4).toLong()
            }
            return fallbackIds to cleanText
        }

        val vocab = tokenizer.tokenToId
        val decodedMergedStrings = extractDecodedMergedStrings(tokenizer)
        val style = detectVocabStyle(tokenizer, decodedMergedStrings)

        val surfaceOrPhonemes = when (style) {
            VocabStyle.SEA_G2P_ESPEAK_IPA -> phonemizeSeaG2pEspeak(cleanText, decodedMergedStrings, seaG2pDict)
            VocabStyle.CHAO_IPA -> phonemizeChaoIpa(cleanText, vocab)
            VocabStyle.RAW_VIETNAMESE -> cleanText
        }

        val ids = ArrayList<Long>(surfaceOrPhonemes.length * 2 + 8)

        // Optional BOS / text_bos token (skipped when config == null because VieNeuV3OnnxRunner adds exact wrappers)
        if (config != null) {
            val bosId = config.textBosTokenId ?: config.bosTokenId ?: tokenizer.bosTokenId
            if (bosId != null && bosId >= 0L) {
                ids.add(bosId)
            }
        }

        if (tokenizer.isByteLevelBpe) {
            encodeByteLevelBpe(
                text = surfaceOrPhonemes,
                tokenizer = tokenizer,
                out = ids
            )
        } else {
            val spaceToken = detectSpaceToken(vocab)
            val words = surfaceOrPhonemes.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            for ((wIdx, word) in words.withIndex()) {
                if (wIdx > 0 && spaceToken != null) {
                    vocab[spaceToken]?.let { ids.add(it) }
                }
                tokenizeGreedy(word, vocab, ids)
            }
        }

        if (config != null) {
            val textEos = config.textEosTokenId ?: tokenizer.speechStartTokenId
            if (textEos != null && textEos >= 0L) {
                ids.add(textEos)
            }
        }

        return LongArray(ids.size) { ids[it] } to surfaceOrPhonemes
    }

    // -------------------------------------------------------------------------
    // Exact HuggingFace ByteLevel BPE Encoder
    // -------------------------------------------------------------------------

    private fun encodeByteLevelBpe(
        text: String,
        tokenizer: ParsedTokenizerData,
        out: MutableList<Long>
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val mergeRanks = HashMap<Pair<String, String>, Int>(tokenizer.merges.size * 2 + 16)
        for ((idx, pair) in tokenizer.merges.withIndex()) {
            mergeRanks[pair] = idx
        }

        val vocab = tokenizer.tokenToId
        val unkId = vocab["<|unk|>"] ?: vocab["<unk>"] ?: 43L

        // Standard GPT-2 / ByteLevel pre-tokenization splits around spaces (keeping leading space attached to words)
        // or encodes the whole string cleanly if there are no spaces.
        val inputWithPrefix = if (tokenizer.addPrefixSpace && !trimmed.startsWith(" ")) {
            " $trimmed"
        } else {
            trimmed
        }

        val chunks = splitWithTokenizerPattern(inputWithPrefix, tokenizer.splitPattern, tokenizer.splitBehavior)
        for (chunk in chunks) {
            val byteEncoded = encodeUtf8ToByteLevel(chunk)
            if (byteEncoded.isEmpty()) continue

            // Apply BPE merges in exact rank order
            val symbols = ArrayList<String>(byteEncoded.length)
            for (ch in byteEncoded) {
                symbols.add(ch.toString())
            }

            if (mergeRanks.isNotEmpty() && symbols.size > 1) {
                while (symbols.size > 1) {
                    var bestRank = Int.MAX_VALUE
                    var bestPos = -1
                    for (i in 0 until symbols.size - 1) {
                        val rank = mergeRanks[symbols[i] to symbols[i + 1]]
                        if (rank != null && rank < bestRank) {
                            bestRank = rank
                            bestPos = i
                        }
                    }
                    if (bestPos < 0) break
                    val merged = symbols[bestPos] + symbols[bestPos + 1]
                    symbols[bestPos] = merged
                    symbols.removeAt(bestPos + 1)
                }
            } else if (mergeRanks.isEmpty() && symbols.size > 1) {
                // Fallback greedy longest-match in ByteLevel space when merges array is empty
                tokenizeGreedy(byteEncoded, vocab, out)
                continue
            }

            for (sym in symbols) {
                val id = vocab[sym]
                if (id != null) {
                    out.add(id)
                } else {
                    // Fallback per-byte inside symbol
                    for (c in sym) {
                        out.add(vocab[c.toString()] ?: unkId)
                    }
                }
            }
        }
    }

    private fun splitWithTokenizerPattern(
        text: String,
        splitPattern: String?,
        splitBehavior: String
    ): List<String> {
        if (!splitPattern.isNullOrEmpty() && splitBehavior.equals("Isolated", ignoreCase = true)) {
            val regexPieces = runCatching {
                val re = Regex(splitPattern)
                val out = ArrayList<String>()
                var cursor = 0
                for (m in re.findAll(text)) {
                    if (m.range.first > cursor) {
                        out.add(text.substring(cursor, m.range.first))
                    }
                    if (m.value.isNotEmpty()) {
                        out.add(m.value)
                    }
                    cursor = m.range.last + 1
                }
                if (cursor < text.length) {
                    out.add(text.substring(cursor))
                }
                out
            }.getOrNull()
            if (!regexPieces.isNullOrEmpty()) return regexPieces
        }
        return splitByteLevelChunks(text)
    }

    /**
     * Splits text into ByteLevel pre-tokenizer chunks preserving spaces as leading word prefixes
     * (`" a b, c."` -> `[" a", " b", ",", " c", "."]`), matching HuggingFace ByteLevel pre-tokenizer.
     */
    private fun splitByteLevelChunks(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == ' ') {
                if (sb.isNotEmpty()) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                sb.append(ch)
                i++
            } else if (ch in setOf('.', ',', '!', '?', ';', ':', '…', '(', ')', '"', '\n')) {
                if (sb.isNotEmpty() && sb.toString() != " ") {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                sb.append(ch)
                out.add(sb.toString())
                sb.setLength(0)
                i++
            } else {
                sb.append(ch)
                i++
            }
        }
        if (sb.isNotEmpty()) {
            out.add(sb.toString())
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Runtime Vocab Style Detection (via decoded ByteLevel BPE merges!)
    // -------------------------------------------------------------------------

    private fun extractDecodedMergedStrings(tokenizer: ParsedTokenizerData): Set<String> {
        val decoded = HashSet<String>(256)
        for ((id, tok) in tokenizer.idToToken) {
            if (tok.startsWith("<|")) continue
            if (tokenizer.isByteLevelBpe) {
                if (id >= 300L || tok.length > 1) {
                    decoded.add(decodeByteLevelToUtf8(tok))
                }
            } else {
                decoded.add(tok)
            }
        }
        return decoded
    }

    private fun detectVocabStyle(
        tokenizer: ParsedTokenizerData,
        decodedMerges: Set<String>
    ): VocabStyle {
        val allMergedText = decodedMerges.joinToString(" ")

        // 1. Check for Chao tone letters
        val hasChao = allMergedText.any { it in setOf('˧', '˨', '˥', '˩') }
        if (hasChao) return VocabStyle.CHAO_IPA

        // 2. Check for eSpeak-NG / sea-g2p IPA symbols inside decoded ByteLevel merges
        val ipaMarkers = listOf("ˈ", "ˌ", "ː", "ŋ", "ɲ", "ɗ", "ə", "ɛ", "ɔ", "ɯ", "ɤ", "ɣ", "ʃ", "t̪", "tʰ", "ɜ", "ɪ", "ʊ", "θ")
        var ipaHits = 0
        for (m in ipaMarkers) {
            if (allMergedText.contains(m)) ipaHits++
        }
        if (ipaHits >= 2) return VocabStyle.SEA_G2P_ESPEAK_IPA

        // 3. Check for raw Vietnamese accented characters in decoded merges
        val viMarkers = listOf("á", "à", "ả", "ã", "ạ", "ắ", "ằ", "ấ", "ầ", "ế", "ề", "ố", "ồ", "ớ", "ờ", "ứ", "ừ", "đ", "ư", "ơ")
        var viHits = 0
        for (m in viMarkers) {
            if (allMergedText.contains(m)) viHits++
        }
        if (viHits >= 3) return VocabStyle.RAW_VIETNAMESE

        // Default for VieNeu-TTS v3 Turbo ByteLevel BPE is sea-g2p / eSpeak-NG IPA
        return if (tokenizer.isByteLevelBpe) VocabStyle.SEA_G2P_ESPEAK_IPA else VocabStyle.RAW_VIETNAMESE
    }

    // -------------------------------------------------------------------------
    // Exact `sea-g2p` / eSpeak-NG Northern Vietnamese IPA Phonemizer
    // Reference examples from `pnnbao97/sea-g2p`:
    //   "Giá" -> "zˈaːɜ", "là" -> "lˈaː2", "bốn" -> "bˈoɜn", "nghìn" -> "ŋˈi2n",
    //   "hai" -> "hˈaːj", "trăm" -> "tʃˈam", "phẩy" -> "fˈəɪ4", "năm" -> "nˈam",
    //   "điểm" -> "ɗˈiɛ4m", "một" -> "mˈo6t̪", "hôm" -> "hˈom", "nay" -> "nˈaj"
    // -------------------------------------------------------------------------

    private fun phonemizeSeaG2pEspeak(
        sentence: String,
        decodedMerges: Set<String>,
        seaG2pDict: SeaG2pBinaryDict? = null
    ): String {
        val mergedBlob = decodedMerges.joinToString(" ")
        val useStressMark = mergedBlob.contains("ˈ") || decodedMerges.isEmpty()
        // NOTE: Combining dental diacritic U+032A (`̪`, token 330) appears standalone in tokenizer[300..418]!
        val useDentalT = mergedBlob.contains("\u032A") || mergedBlob.contains("t\u032A")
        val useLengthColon = mergedBlob.contains("ː") || decodedMerges.isEmpty()
        val useImplosiveD = mergedBlob.contains("ɗ") || decodedMerges.isEmpty()
        val useImplosiveB = mergedBlob.contains("ɓ")
        val hasAspirationH = mergedBlob.contains("ʰ")
        // NOTE: `tokenizer[300..418]` contains `ᵻ` (333) and NO `ɯ`! Use `ᵻ` when `ɯ` is absent.
        val uBarSymbol = when {
            mergedBlob.contains("ɯ") -> "ɯ"
            mergedBlob.contains("ᵻ") -> "ᵻ"
            else -> "ᵻ"
        }
        val useChAffricate = when {
            mergedBlob.contains("tɕ") || mergedBlob.contains("ɕ") -> "tɕ"
            mergedBlob.contains("ʃ") -> "tʃ"
            else -> "tʃ"
        }
        // In eSpeak-NG Vietnamese (`sea-g2p`):
        // tone 1 (ngang) = "" (or 1)
        // tone 2 (huyền) = "2"
        // tone 3 (sắc)   = "ɜ" (311:ɜ in tokenizer[300..418] — eSpeak IPA rendering of `ph_vietnam` sắc!)
        // tone 4 (hỏi)   = "4"
        // tone 5 (ngã)   = "5"
        // tone 6 (nặng)  = "6"
        val sacSymbol = when {
            mergedBlob.contains("ɜ") -> "ɜ"
            mergedBlob.contains("5") -> "5"
            else -> "ɜ"
        }
        val ngaSymbol = when {
            sacSymbol == "ɜ" -> "5"
            else -> "3"
        }

        val tokens = Regex("[\\p{L}\\p{M}\\d]+|[.,!?;:…—–\\-]")
            .findAll(sentence)
            .map { it.value }
            .toList()

        val result = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.length == 1 && !tok[0].isLetterOrDigit()) {
                val mappedPunct = when (tok) {
                    ",", ".", "!", "?", ";", ":" -> tok
                    "…" -> "."
                    "—", "–", "-" -> ","
                    else -> ""
                }
                if (mappedPunct.isNotEmpty() && result.isNotEmpty()) {
                    result[result.lastIndex] = result.last() + mappedPunct
                }
                i++
                continue
            }

            val lower = tok.lowercase(Locale("vi", "VN"))
            // 1. Prefer exact mmap lookup in `sea_g2p.bin` (check 2-word Vietnamese compound first, then 1-word)
            if (seaG2pDict != null && seaG2pDict.isValidatedForVietnamese) {
                if (i + 1 < tokens.size && tokens[i + 1].all { it.isLetterOrDigit() }) {
                    val bigram = "$lower ${tokens[i + 1].lowercase(Locale("vi", "VN"))}"
                    val biIpa = seaG2pDict.lookupVietnamese(bigram)
                    if (!biIpa.isNullOrBlank()) {
                        result.add(biIpa.trim())
                        i += 2
                        continue
                    }
                }
                val uniIpa = seaG2pDict.lookupVietnamese(lower)
                if (!uniIpa.isNullOrBlank()) {
                    result.add(uniIpa.trim())
                    i++
                    continue
                }
            }

            // 2. Fallback to exact eSpeak-NG / sea-g2p Northern Vietnamese rule phonemizer
            val ipa = phonemizeSingleVietnameseWordEspeak(
                rawWord = lower,
                useStress = useStressMark,
                useDentalT = useDentalT,
                useLengthColon = useLengthColon,
                useImplosiveD = useImplosiveD,
                useImplosiveB = useImplosiveB,
                hasAspirationH = hasAspirationH,
                uBarSymbol = uBarSymbol,
                chAffricate = useChAffricate,
                sacSymbol = sacSymbol,
                ngaSymbol = ngaSymbol
            )
            if (ipa.isNotEmpty()) {
                result.add(ipa)
            }
            i++
        }
        return result.joinToString(" ")
    }

    private fun phonemizeSingleVietnameseWordEspeak(
        rawWord: String,
        useStress: Boolean,
        useDentalT: Boolean,
        useLengthColon: Boolean,
        useImplosiveD: Boolean,
        useImplosiveB: Boolean,
        hasAspirationH: Boolean,
        uBarSymbol: String,
        chAffricate: String,
        sacSymbol: String,
        ngaSymbol: String
    ): String {
        if (rawWord.isEmpty()) return ""
        val (baseWord, toneIdx) = extractVietnameseTone(rawWord)

        // 1. Parse Onset (Phụ âm đầu)
        var rest = baseWord
        val tSym = if (useDentalT) "t\u032A" else "t"
        val thSym = if (hasAspirationH) "tʰ" else "${tSym}h"
        val onset: String
        when {
            rest.startsWith("ngh") -> { onset = "ŋ"; rest = rest.substring(3) }
            rest.startsWith("ng") -> { onset = "ŋ"; rest = rest.substring(2) }
            rest.startsWith("nh") -> { onset = "ɲ"; rest = rest.substring(2) }
            rest.startsWith("ch") -> { onset = chAffricate; rest = rest.substring(2) }
            rest.startsWith("tr") -> { onset = chAffricate; rest = rest.substring(2) }
            rest.startsWith("th") -> { onset = thSym; rest = rest.substring(2) }
            rest.startsWith("ph") -> { onset = "f"; rest = rest.substring(2) }
            rest.startsWith("kh") -> { onset = "x"; rest = rest.substring(2) }
            rest.startsWith("gh") -> { onset = "ɣ"; rest = rest.substring(2) }
            rest.startsWith("gi") && rest.length > 2 -> { onset = "z"; rest = rest.substring(2) }
            rest.startsWith("gi") -> { onset = "z"; rest = "i" }
            rest.startsWith("qu") && rest.length > 2 -> { onset = "kw"; rest = rest.substring(2) }
            rest.startsWith("đ") -> { onset = if (useImplosiveD) "ɗ" else "d"; rest = rest.substring(1) }
            rest.startsWith("b") -> { onset = if (useImplosiveB) "ɓ" else "b"; rest = rest.substring(1) }
            rest.startsWith("c") || rest.startsWith("k") -> { onset = "k"; rest = rest.substring(1) }
            rest.startsWith("d") || rest.startsWith("r") -> { onset = "z"; rest = rest.substring(1) }
            rest.startsWith("g") -> { onset = "ɣ"; rest = rest.substring(1) }
            rest.startsWith("h") -> { onset = "h"; rest = rest.substring(1) }
            rest.startsWith("l") -> { onset = "l"; rest = rest.substring(1) }
            rest.startsWith("m") -> { onset = "m"; rest = rest.substring(1) }
            rest.startsWith("n") -> { onset = "n"; rest = rest.substring(1) }
            rest.startsWith("p") -> { onset = "p"; rest = rest.substring(1) }
            rest.startsWith("s") || rest.startsWith("x") -> { onset = "s"; rest = rest.substring(1) }
            rest.startsWith("t") -> { onset = tSym; rest = rest.substring(1) }
            rest.startsWith("v") -> { onset = "v"; rest = rest.substring(1) }
            else -> { onset = "" }
        }

        // 2. Parse Labialized Medial (Âm đệm u/o: xuân -> swˈən, ngoài -> ŋwˈaː2j, hoa -> hwˈaː)
        var medial = ""
        if (rest.length >= 2 && (rest.startsWith("o") || rest.startsWith("u"))) {
            val second = rest[1]
            if ((rest.startsWith("o") && second in setOf('a', 'ă', 'e')) ||
                (rest.startsWith("u") && second in setOf('â', 'ê', 'y', 'ơ', 'a', 'i'))
            ) {
                if (rest !in setOf("ua", "uô", "uo", "ui", "uôi", "uôn", "uông", "uôt", "uôc", "uôm")) {
                    medial = "w"
                    rest = rest.substring(1)
                }
            }
        }

        // 3. Parse Coda (Âm cuối)
        var coda = ""
        when {
            rest.endsWith("ng") && rest.length > 2 -> { coda = "ŋ"; rest = rest.dropLast(2) }
            rest.endsWith("nh") && rest.length > 2 -> { coda = "ɲ"; rest = rest.dropLast(2) }
            rest.endsWith("ch") && rest.length > 2 -> { coda = "k"; rest = rest.dropLast(2) }
            rest.endsWith("m") && rest.length > 1 -> { coda = "m"; rest = rest.dropLast(1) }
            rest.endsWith("n") && rest.length > 1 -> { coda = "n"; rest = rest.dropLast(1) }
            rest.endsWith("p") && rest.length > 1 -> { coda = "p"; rest = rest.dropLast(1) }
            rest.endsWith("t") && rest.length > 1 -> { coda = tSym; rest = rest.dropLast(1) }
            rest.endsWith("c") && rest.length > 1 -> { coda = "k"; rest = rest.dropLast(1) }
            rest.endsWith("i") && rest.length > 1 && rest !in setOf("gi", "uôi", "ươi") -> {
                coda = "j"; rest = rest.dropLast(1)
            }
            rest.endsWith("y") && rest.length > 1 -> {
                coda = "j"; rest = rest.dropLast(1)
            }
            rest.endsWith("u") && rest.length > 1 && rest !in setOf("ưu", "iu", "ươu", "iêu", "yêu") -> {
                coda = "w"; rest = rest.dropLast(1)
            }
            rest.endsWith("o") && rest.length > 1 && (rest.endsWith("ao") || rest.endsWith("eo")) -> {
                coda = "w"; rest = rest.dropLast(1)
            }
        }

        // 4. Parse Vowel Nucleus (Âm chính) matching eSpeak-NG / sea-g2p Northern Vietnamese & tokenizer[300..418]
        val longMark = if (useLengthColon) "ː" else ""
        val nucleus = when (rest) {
            "iê", "yê", "ia", "ya" -> "iɛ"
            "uô", "ua" -> "uə"
            "ươ", "ưa" -> "${uBarSymbol}ə"
            "i", "y" -> "i"
            "ê" -> "e"
            "e" -> "ɛ"
            "ư" -> uBarSymbol
            "ơ" -> "ə$longMark"
            "â" -> "ə"
            "a" -> "a$longMark"
            "ă" -> "a"
            "u" -> "u"
            "ô" -> "o"
            "o" -> "ɔ"
            "ưu" -> { coda = "w"; uBarSymbol }
            "iu" -> { coda = "w"; "i" }
            "uôi" -> { coda = "j"; "uə" }
            "ươi" -> { coda = "j"; "${uBarSymbol}ə" }
            "ươu" -> { coda = "w"; "${uBarSymbol}ə" }
            "iêu", "yêu" -> { coda = "w"; "iɛ" }
            else -> rest.map { ch ->
                when (ch) {
                    'ă' -> "a"
                    'â' -> "ə"
                    'ê' -> "e"
                    'e' -> "ɛ"
                    'ô' -> "o"
                    'o' -> "ɔ"
                    'ơ' -> "ə$longMark"
                    'ư' -> uBarSymbol
                    'y' -> "i"
                    else -> ch.toString()
                }
            }.joinToString("")
        }

        // 5. Tone marker placed BETWEEN vowel nucleus and coda (exact sea-g2p / eSpeak-NG convention!)
        // Tone indices: 1=ngang, 2=huyền, 3=sắc, 4=hỏi, 5=ngã, 6=nặng
        val toneStr = when (toneIdx) {
            1 -> ""
            2 -> "2"
            3 -> sacSymbol
            4 -> "4"
            5 -> ngaSymbol
            6 -> "6"
            else -> ""
        }

        val stress = if (useStress) "ˈ" else ""
        return onset + medial + stress + nucleus + toneStr + coda
    }

    // -------------------------------------------------------------------------
    // Fallback Chao-Tone IPA Phonemizer (for non-eSpeak Chao-tone models)
    // -------------------------------------------------------------------------

    private fun phonemizeChaoIpa(sentence: String, vocab: Map<String, Long>): String {
        val tokens = Regex("[\\p{L}\\p{M}\\d]+|[.,!?;:]")
            .findAll(sentence)
            .map { it.value }
            .toList()
        val result = ArrayList<String>(tokens.size)
        for (tok in tokens) {
            if (tok.length == 1 && !tok[0].isLetterOrDigit()) {
                result.add(tok)
                continue
            }
            val (baseWord, toneIdx) = extractVietnameseTone(tok.lowercase(Locale("vi", "VN")))
            val chaoTone = when (toneIdx) {
                1 -> "˧˧"
                2 -> "˨˩"
                3 -> "˧˥"
                4 -> "˧˩˧"
                5 -> "˧ˀ˥"
                6 -> "˧˨ʔ"
                else -> "˧˧"
            }
            val core = phonemizeSingleVietnameseWordEspeak(
                rawWord = baseWord,
                useStress = false,
                useDentalT = false,
                useLengthColon = false,
                useImplosiveD = true,
                useImplosiveB = true,
                hasAspirationH = true,
                uBarSymbol = "ɯ",
                chAffricate = "tɕ",
                sacSymbol = "",
                ngaSymbol = ""
            )
            result.add(core + chaoTone)
        }
        return result.joinToString(" ")
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
                val ch = text[cursor].lowercaseChar().toString()
                vocab[ch]?.let { out.add(it) }
                cursor++
            }
        }
    }

    /**
     * Strips Vietnamese tone diacritics while preserving vowel quality diacritics (ă, â, ê, ô, ơ, ư, đ).
     * Returns `(tonelessWord, toneIdx 1..6)`:
     * 1=ngang, 2=huyền, 3=sắc, 4=hỏi, 5=ngã, 6=nặng.
     */
    private fun extractVietnameseTone(word: String): Pair<String, Int> {
        var tone = 1
        val sb = StringBuilder(word.length)
        for (ch in word) {
            val mapped = TONE_DECOMPOSE[ch]
            if (mapped != null) {
                sb.append(mapped.first)
                if (mapped.second != 1) tone = mapped.second
            } else {
                val nfd = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                if (nfd.length > 1) {
                    for (c in nfd) {
                        when (c) {
                            '\u0300' -> tone = 2
                            '\u0301' -> tone = 3
                            '\u0309' -> tone = 4
                            '\u0303' -> tone = 5
                            '\u0323' -> tone = 6
                        }
                    }
                    val stripped = Normalizer.normalize(
                        nfd.filter { it !in setOf('\u0300', '\u0301', '\u0309', '\u0303', '\u0323') },
                        Normalizer.Form.NFC
                    )
                    sb.append(if (stripped.isNotEmpty()) stripped else ch.toString())
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString() to tone
    }

    private val TONE_DECOMPOSE: Map<Char, Pair<Char, Int>> = hashMapOf(
        'à' to ('a' to 2), 'á' to ('a' to 3), 'ả' to ('a' to 4), 'ã' to ('a' to 5), 'ạ' to ('a' to 6),
        'ằ' to ('ă' to 2), 'ắ' to ('ă' to 3), 'ẳ' to ('ă' to 4), 'ẵ' to ('ă' to 5), 'ặ' to ('ă' to 6),
        'ầ' to ('â' to 2), 'ấ' to ('â' to 3), 'ẩ' to ('â' to 4), 'ẫ' to ('â' to 5), 'ậ' to ('â' to 6),
        'è' to ('e' to 2), 'é' to ('e' to 3), 'ẻ' to ('e' to 4), 'ẽ' to ('e' to 5), 'ẹ' to ('e' to 6),
        'ề' to ('ê' to 2), 'ế' to ('ê' to 3), 'ể' to ('ê' to 4), 'ễ' to ('ê' to 5), 'ệ' to ('ê' to 6),
        'ì' to ('i' to 2), 'í' to ('i' to 3), 'ỉ' to ('i' to 4), 'ĩ' to ('i' to 5), 'ị' to ('i' to 6),
        'ò' to ('o' to 2), 'ó' to ('o' to 3), 'ỏ' to ('o' to 4), 'õ' to ('o' to 5), 'ọ' to ('o' to 6),
        'ồ' to ('ô' to 2), 'ố' to ('ô' to 3), 'ổ' to ('ô' to 4), 'ỗ' to ('ô' to 5), 'ộ' to ('ô' to 6),
        'ờ' to ('ơ' to 2), 'ớ' to ('ơ' to 3), 'ở' to ('ơ' to 4), 'ỡ' to ('ơ' to 5), 'ợ' to ('ơ' to 6),
        'ù' to ('u' to 2), 'ú' to ('u' to 3), 'ủ' to ('u' to 4), 'ũ' to ('u' to 5), 'ụ' to ('u' to 6),
        'ừ' to ('ư' to 2), 'ứ' to ('ư' to 3), 'ử' to ('ư' to 4), 'ữ' to ('ư' to 5), 'ự' to ('ư' to 6),
        'ỳ' to ('y' to 2), 'ý' to ('y' to 3), 'ỷ' to ('y' to 4), 'ỹ' to ('y' to 5), 'ỵ' to ('y' to 6)
    )
}
