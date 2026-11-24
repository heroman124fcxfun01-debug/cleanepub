package com.example.pipeline

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * 100% Offline Vietnamese Dictionary backed by O(1) HashSets loaded from assets:
 * - assets/vi_syllables.txt (~7,000–17,000 valid Vietnamese syllables, accented & unaccented)
 * - assets/vi_words.txt (~40,000+ common Vietnamese compound words & collocations)
 * - HIGH_SUSPICION_SINGLES: standalone letters, consonant clusters, and rare short fragments
 *   commonly produced by PDF/OCR fake-word splitting (e.g., "Củ a" -> "Của").
 */
object OfflineVietnameseDict {

    private const val ASSET_SYLLABLES = "vi_syllables.txt"
    private const val ASSET_WORDS = "vi_words.txt"

    @Volatile
    private var loaded: Boolean = false

    private val syllables: HashSet<String> = HashSet(24_000)
    private val words: HashSet<String> = HashSet(64_000)

    /**
     * Pure consonant clusters/letters that can NEVER stand alone as a Vietnamese word.
     */
    val STRICT_CONSONANT_FRAGMENTS: HashSet<String> = hashSetOf(
        "b", "c", "d", "đ", "f", "g", "h", "j", "k", "l", "m", "n",
        "p", "q", "r", "s", "t", "v", "w", "x", "z",
        "ng", "nh", "ch", "tr", "th", "ph", "kh", "gh", "gi", "qu", "ngh"
    )

    /**
     * Standalone single letters, consonant clusters, and rare rhyme fragments
     * that strongly indicate a broken syllable split when appearing after a stem.
     */
    val HIGH_SUSPICION_SINGLES: HashSet<String> = hashSetOf(
        // Single letters (vowels + consonants)
        "a", "ă", "â", "b", "c", "d", "đ", "e", "ê", "f", "g", "h", "i", "j",
        "k", "l", "m", "n", "o", "ô", "ơ", "p", "q", "r", "s", "t", "u", "ư",
        "v", "w", "x", "y", "z",
        // Consonant digraphs / trigraphs
        "ng", "nh", "ch", "tr", "th", "ph", "kh", "gh", "gi", "qu", "ngh",
        // Rare / high-suspicion standalone vowel rhymes (frequently split off by PDF/OCR)
        "ôi", "ồi", "ối", "ổi", "ỗi", "ội",
        "ơi", "ời", "ới", "ởi", "ỡi", "ợi",
        "ấy", "ầy", "ẩy", "ẫy", "ậy",
        "ủa", "ùa", "úa", "ũa", "ụa",
        "ưa", "ừa", "ứa", "ửa", "ữa", "ựa",
        "ưi", "ửi", "ứi", "ừi", "ữi", "ựi",
        "ươi", "ười", "ưới", "ưởi", "ưỡi", "ượi",
        "uôi", "uồi", "uối", "uổi", "uỗi", "uội",
        "oa", "oà", "oá", "oả", "oã", "oạ",
        "oe", "oè", "oé", "oẻ", "oẽ", "oẹ",
        "uy", "uỳ", "uý", "uỷ", "uỹ", "uỵ",
        "âu", "ầu", "ấu", "ẩu", "ẫu", "ậu",
        "ây", "êu", "eo", "iu", "ia", "ua", "au", "ay", "oi"
    )

    val isLoaded: Boolean
        get() = loaded

    val syllableCount: Int
        get() = syllables.size

    val wordCount: Int
        get() = words.size

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val assetManager = context.applicationContext.assets
            assetManager.open(ASSET_SYLLABLES).use { sylStream ->
                assetManager.open(ASSET_WORDS).use { wordStream ->
                    loadStreamsInternal(sylStream, wordStream)
                }
            }
            loaded = true
        }
    }

    fun loadFromFiles(syllablesFile: File, wordsFile: File) {
        synchronized(this) {
            FileInputStream(syllablesFile).use { sylStream ->
                FileInputStream(wordsFile).use { wordStream ->
                    loadStreamsInternal(sylStream, wordStream)
                }
            }
            loaded = true
        }
    }

    fun loadFromStreams(syllablesStream: InputStream, wordsStream: InputStream) {
        synchronized(this) {
            loadStreamsInternal(syllablesStream, wordsStream)
            loaded = true
        }
    }

    private fun loadStreamsInternal(syllablesStream: InputStream, wordsStream: InputStream) {
        syllables.clear()
        words.clear()

        BufferedReader(InputStreamReader(syllablesStream, Charsets.UTF_8), 32 * 1024).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val clean = normalizeKey(line)
                if (clean.isNotEmpty()) {
                    syllables.add(clean)
                }
                line = reader.readLine()
            }
        }

        BufferedReader(InputStreamReader(wordsStream, Charsets.UTF_8), 32 * 1024).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val clean = normalizeKey(line)
                if (clean.isNotEmpty()) {
                    words.add(clean)
                }
                line = reader.readLine()
            }
        }
    }

    fun isValidSyllable(token: String): Boolean {
        if (token.isEmpty()) return false
        return syllables.contains(normalizeKey(token))
    }

    fun isKnownWord(phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        return words.contains(normalizeKey(phrase))
    }

    fun isKnownBigram(first: String, second: String): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        return words.contains("${normalizeKey(first)} ${normalizeKey(second)}")
    }

    fun isKnownTrigram(first: String, second: String, third: String): Boolean {
        if (first.isEmpty() || second.isEmpty() || third.isEmpty()) return false
        return words.contains("${normalizeKey(first)} ${normalizeKey(second)} ${normalizeKey(third)}")
    }

    fun isHighSuspicionSingle(token: String): Boolean {
        if (token.isEmpty()) return false
        return HIGH_SUSPICION_SINGLES.contains(normalizeKey(token))
    }

    fun isStrictConsonantFragment(token: String): Boolean {
        if (token.isEmpty()) return false
        return STRICT_CONSONANT_FRAGMENTS.contains(normalizeKey(token))
    }

    private fun normalizeKey(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val nfc = if (Normalizer.isNormalized(trimmed, Normalizer.Form.NFC)) {
            trimmed
        } else {
            Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        }
        return nfc.lowercase()
    }
}
