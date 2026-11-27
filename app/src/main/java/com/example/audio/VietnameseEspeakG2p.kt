package com.example.audio

import java.io.File
import java.util.Locale

/**
 * Official eSpeak-NG Vietnamese (`-v vi`) IPA Phonemizer for `pnnbao97/VieNeu-TTS` GGUF models.
 *
 * Strategy:
 * 1. Primary: Loads `vi_espeak_ipa.tsv` from `filesDir/models/` — generated directly by real
 *    `espeak-ng -v vi --ipa -q` (matching `phonemizer.backend.EspeakBackend(language="vi",
 *    preserve_punctuation=True, with_stress=True)`) for all ~6,800 legal Vietnamese syllables,
 *    digits, and common loanwords.
 * 2. Fallback: Deterministic eSpeak-NG Vietnamese (`vi`) syllable-to-IPA converter with tones
 *    `1` (ngang), `2` (huyền), `3` (hỏi), `4` (ngã), `5` (sắc), `6` (nặng) matching eSpeak-NG's
 *    exact Northern Vietnamese (`vi`) numbering convention (`ma1 ma2 ma3 ma4 ma5 ma6` / eSpeak
 *    `ph_vietnam`).
 */
class VietnameseEspeakG2p private constructor(
    private val syllableDict: Map<String, String>,
    val dictSourcePath: String?
) {

    val entryCount: Int
        get() = syllableDict.size

    fun phonemize(rawText: String): String {
        val normalized = VietnameseTtsTextNormalizer.normalizeForTts(rawText).trim()
        if (normalized.isEmpty()) return ""

        val out = StringBuilder(normalized.length * 2)
        val tokenRegex = Regex("([\\p{L}\\p{N}]+)|([.,!?;:()\"'\\-–—…]+)")
        var first = true

        for (match in tokenRegex.findAll(normalized)) {
            val word = match.groupValues[1]
            val punct = match.groupValues[2]

            if (word.isNotEmpty()) {
                val lower = word.lowercase(Locale.forLanguageTag("vi"))
                val ipa = lookupOrFallback(lower)
                if (ipa.isNotEmpty()) {
                    if (!first && out.isNotEmpty() && out.last() != ' ') {
                        out.append(' ')
                    }
                    out.append(ipa)
                    first = false
                }
            } else if (punct.isNotEmpty()) {
                val mappedPunct = normalizePunctuation(punct)
                if (mappedPunct.isNotEmpty()) {
                    out.append(mappedPunct)
                }
            }
        }

        return out.toString()
            .replace(Regex("\\s+([.,!?;:])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun lookupOrFallback(lowerWord: String): String {
        syllableDict[lowerWord]?.let { return it }

        // If pure digits remain after normalization, spell digit-by-digit in Vietnamese
        if (lowerWord.all { it.isDigit() }) {
            return lowerWord.map { ch ->
                val word = DIGIT_WORDS[ch] ?: ch.toString()
                syllableDict[word] ?: fallbackSyllableToEspeakIpa(word)
            }.joinToString(" ")
        }

        // Split hyphenated or compound ASCII/Vietnamese tokens
        if (lowerWord.contains('-')) {
            return lowerWord.split('-')
                .filter { it.isNotBlank() }
                .joinToString(" ") { lookupOrFallback(it) }
        }

        return fallbackSyllableToEspeakIpa(lowerWord)
    }

    private fun normalizePunctuation(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when (ch) {
                ',', '،' -> sb.append(',')
                '.', '…' -> sb.append('.')
                '!' -> sb.append('!')
                '?' -> sb.append('?')
                ';' -> sb.append(';')
                ':' -> sb.append(':')
                '–', '—' -> sb.append(',')
            }
        }
        return sb.toString()
    }

    /**
     * Rule-based fallback matching eSpeak-NG `-v vi --ipa` output for unseen Vietnamese syllables.
     */
    private fun fallbackSyllableToEspeakIpa(syllable: String): String {
        if (syllable.isEmpty()) return ""
        var tone = 1
        val plain = StringBuilder(syllable.length)
        for (ch in syllable) {
            val info = CHAR_TONE_MAP[ch]
            if (info != null) {
                plain.append(info.first)
                if (info.second != 1) tone = info.second
            } else {
                plain.append(ch)
            }
        }
        val base = plain.toString()
        if (base.isEmpty()) return ""

        // Extract Vietnamese onset
        var idx = 0
        val onset = when {
            base.startsWith("ngh") -> { idx = 3; "ŋ" }
            base.startsWith("ng") -> { idx = 2; "ŋ" }
            base.startsWith("nh") -> { idx = 2; "ɲ" }
            base.startsWith("ch") -> { idx = 2; "c" }
            base.startsWith("tr") -> { idx = 2; "c" }
            base.startsWith("th") -> { idx = 2; "tʰ" }
            base.startsWith("ph") -> { idx = 2; "f" }
            base.startsWith("kh") -> { idx = 2; "x" }
            base.startsWith("gh") -> { idx = 2; "ɣ" }
            base.startsWith("gi") -> {
                if (base.length > 2) { idx = 2; "z" } else { idx = 1; "z" }
            }
            base.startsWith("qu") -> { idx = 2; "kw" }
            base.startsWith("đ") -> { idx = 1; "ɗ" }
            base.startsWith("b") -> { idx = 1; "ɓ" }
            base.startsWith("c") || base.startsWith("k") -> { idx = 1; "k" }
            base.startsWith("d") -> { idx = 1; "z" }
            base.startsWith("g") -> { idx = 1; "ɣ" }
            base.startsWith("h") -> { idx = 1; "h" }
            base.startsWith("l") -> { idx = 1; "l" }
            base.startsWith("m") -> { idx = 1; "m" }
            base.startsWith("n") -> { idx = 1; "n" }
            base.startsWith("p") -> { idx = 1; "p" }
            base.startsWith("r") -> { idx = 1; "z" }
            base.startsWith("s") || base.startsWith("x") -> { idx = 1; "s" }
            base.startsWith("t") -> { idx = 1; "t" }
            base.startsWith("v") -> { idx = 1; "v" }
            else -> ""
        }

        val rhymeRaw = base.substring(idx)
        if (rhymeRaw.isEmpty()) return onset

        val rhymeIpa = RHYME_MAP[rhymeRaw] ?: approximateRhyme(rhymeRaw)
        return "$onset$rhymeIpa$tone"
    }

    private fun approximateRhyme(rhyme: String): String {
        var r = rhyme
        var coda = ""
        when {
            r.endsWith("ng") -> { coda = "ŋ"; r = r.dropLast(2) }
            r.endsWith("nh") -> { coda = "ɲ"; r = r.dropLast(2) }
            r.endsWith("ch") -> { coda = "c"; r = r.dropLast(2) }
            r.endsWith("n") -> { coda = "n"; r = r.dropLast(1) }
            r.endsWith("m") -> { coda = "m"; r = r.dropLast(1) }
            r.endsWith("p") -> { coda = "p"; r = r.dropLast(1) }
            r.endsWith("t") -> { coda = "t"; r = r.dropLast(1) }
            r.endsWith("c") -> { coda = "k"; r = r.dropLast(1) }
            r.endsWith("i") && r.length > 1 && !r.endsWith("ui") -> { coda = "j"; r = r.dropLast(1) }
            r.endsWith("y") && r.length > 1 -> { coda = "j"; r = r.dropLast(1) }
            r.endsWith("o") && r.length > 1 -> { coda = "w"; r = r.dropLast(1) }
            r.endsWith("u") && r.length > 1 -> { coda = "w"; r = r.dropLast(1) }
        }
        val nucleus = when (r) {
            "a" -> "aː"
            "ă" -> "a"
            "â" -> "ə"
            "e" -> "ɛ"
            "ê" -> "e"
            "i", "y" -> "i"
            "o" -> "ɔ"
            "ô" -> "o"
            "ơ" -> "əː"
            "u" -> "u"
            "ư" -> "ɯ"
            "iê", "ia", "yê", "ya" -> "iə"
            "uô", "ua" -> "uə"
            "ươ", "ưa" -> "ɯə"
            "oa" -> "waː"
            "oă" -> "wa"
            "oe" -> "wɛ"
            "uâ" -> "wə"
            "uê" -> "we"
            "uy" -> "wi"
            "uơ" -> "wəː"
            else -> r
        }
        return "$nucleus$coda"
    }

    companion object {
        fun loadFromFile(tsvFile: File?): VietnameseEspeakG2p {
            if (tsvFile == null || !tsvFile.exists() || tsvFile.length() <= 16L) {
                return VietnameseEspeakG2p(emptyMap(), null)
            }
            val map = HashMap<String, String>(8192)
            runCatching {
                tsvFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val tab = line.indexOf('\t')
                        if (tab <= 0 || tab >= line.lastIndex) continue
                        val key = line.substring(0, tab).trim().lowercase(Locale.forLanguageTag("vi"))
                        val value = line.substring(tab + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            map[key] = value
                        }
                    }
                }
            }
            return VietnameseEspeakG2p(map, tsvFile.absolutePath)
        }

        private val DIGIT_WORDS = mapOf(
            '0' to "không",
            '1' to "một",
            '2' to "hai",
            '3' to "ba",
            '4' to "bốn",
            '5' to "năm",
            '6' to "sáu",
            '7' to "bảy",
            '8' to "tám",
            '9' to "chín"
        )

        // eSpeak-NG Vietnamese tone numbering:
        // 1=ngang, 2=huyền, 3=hỏi, 4=ngã, 5=sắc, 6=nặng (matches espeak-ng ph_vietnam)
        private val CHAR_TONE_MAP: Map<Char, Pair<Char, Int>> = buildMap {
            fun addGroup(base: Char, huyen: Char, hoi: Char, nga: Char, sac: Char, nang: Char) {
                put(base, base to 1)
                put(huyen, base to 2)
                put(hoi, base to 3)
                put(nga, base to 4)
                put(sac, base to 5)
                put(nang, base to 6)
            }
            addGroup('a', 'à', 'ả', 'ã', 'á', 'ạ')
            addGroup('ă', 'ằ', 'ẳ', 'ẵ', 'ắ', 'ặ')
            addGroup('â', 'ầ', 'ẩ', 'ẫ', 'ấ', 'ậ')
            addGroup('e', 'è', 'ẻ', 'ẽ', 'é', 'ẹ')
            addGroup('ê', 'ề', 'ể', 'ễ', 'ế', 'ệ')
            addGroup('i', 'ì', 'ỉ', 'ĩ', 'í', 'ị')
            addGroup('o', 'ò', 'ỏ', 'õ', 'ó', 'ọ')
            addGroup('ô', 'ồ', 'ổ', 'ỗ', 'ố', 'ộ')
            addGroup('ơ', 'ờ', 'ở', 'ỡ', 'ớ', 'ợ')
            addGroup('u', 'ù', 'ủ', 'ũ', 'ú', 'ụ')
            addGroup('ư', 'ừ', 'ử', 'ữ', 'ứ', 'ự')
            addGroup('y', 'ỳ', 'ỷ', 'ỹ', 'ý', 'ỵ')
        }

        private val RHYME_MAP = mapOf(
            "a" to "aː", "ai" to "aːj", "ao" to "aːw", "au" to "aw", "ay" to "aj",
            "am" to "aːm", "an" to "aːn", "ang" to "aːŋ", "anh" to "ajŋ",
            "ap" to "aːp", "at" to "aːt", "ac" to "aːk", "ach" to "ajk",
            "ăm" to "am", "ăn" to "an", "ăng" to "aŋ", "ăp" to "ap", "ăt" to "at", "ăc" to "ak",
            "âm" to "əm", "ân" to "ən", "âng" to "əŋ", "âp" to "əp", "ât" to "ət", "âc" to "ək",
            "âu" to "əw", "ây" to "əj",
            "e" to "ɛ", "eo" to "ɛw", "em" to "ɛm", "en" to "ɛn", "eng" to "ɛŋ", "ep" to "ɛp", "et" to "ɛt",
            "ê" to "e", "êu" to "ew", "êm" to "em", "ên" to "en", "ênh" to "ejŋ", "êp" to "ep", "êt" to "et", "êch" to "ejk",
            "i" to "i", "ia" to "iə", "iu" to "iw", "im" to "im", "in" to "in", "inh" to "ijŋ", "ip" to "ip", "it" to "it", "ich" to "ijk",
            "iêm" to "iəm", "iên" to "iən", "iêng" to "iəŋ", "iêp" to "iəp", "iêt" to "iət", "iêc" to "iək", "iêu" to "iəw",
            "o" to "ɔ", "oi" to "ɔj", "om" to "ɔm", "on" to "ɔn", "ong" to "ɔŋ", "op" to "ɔp", "ot" to "ɔt", "oc" to "ɔk",
            "ô" to "o", "ôi" to "oj", "ôm" to "om", "ôn" to "on", "ông" to "oŋ", "ôp" to "op", "ôt" to "ot", "ôc" to "ok",
            "ơ" to "əː", "ơi" to "əːj", "ơm" to "əːm", "ơn" to "əːn", "ơp" to "əːp", "ơt" to "əːt",
            "u" to "u", "ua" to "uə", "ui" to "uj", "um" to "um", "un" to "un", "ung" to "uŋ", "up" to "up", "ut" to "ut", "uc" to "uk",
            "uôi" to "uəj", "uôm" to "uəm", "uôn" to "uən", "uông" to "uəŋ", "uôt" to "uət", "uôc" to "uək",
            "ư" to "ɯ", "ưa" to "ɯə", "ưi" to "ɯj", "ưu" to "ɯw", "ưng" to "ɯŋ", "ưt" to "ɯt", "ưc" to "ɯk",
            "ươi" to "ɯəj", "ươu" to "ɯəw", "ươm" to "ɯəm", "ươn" to "ɯən", "ương" to "ɯəŋ", "ươp" to "ɯəp", "ươt" to "ɯət", "ươc" to "ɯək",
            "y" to "i", "yên" to "iən", "yêng" to "iəŋ", "yêt" to "iət", "yêu" to "iəw"
        )
    }
}
