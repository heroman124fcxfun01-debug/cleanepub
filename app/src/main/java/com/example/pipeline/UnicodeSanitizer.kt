package com.example.pipeline

import org.jsoup.nodes.Document
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.text.Normalizer

data class UnicodeSanitizeResult(
    val cleanedText: String,
    val sanitizedCount: Int
)

data class DocumentUnicodeSanitizeResult(
    val modifiedNodes: Int,
    val sanitizedChars: Int
)

/**
 * 100% Offline Unicode NFC Normalizer & Dead/Invisible Character Cleaner (Upgrade 1.4):
 * - Runs at the very start of processing to unify Vietnamese encoding via `Normalizer.Form.NFC`
 *   (ensuring 100% accurate search matching and smooth TTS pronunciation).
 * - Strips invisible zero-width chars (`\u200B`, `\u200C`, `\u200D`, `\u2060`, `\uFEFF`),
 *   soft hyphens (`\u00AD`), stray control chars, Private-Use OCR artifacts, and font-error
 *   replacement boxes (`\uFFFD`, `□`, `■`).
 */
object UnicodeSanitizer {

    private val exoticSpaces = setOf(
        '\u00A0', // NBSP
        '\u1680',
        '\u2000', '\u2001', '\u2002', '\u2003', '\u2004',
        '\u2005', '\u2006', '\u2007', '\u2008', '\u2009', '\u200A',
        '\u202F', '\u205F', '\u3000'
    )

    private val invisibleOrDeadChars = setOf(
        '\u200B', // Zero-width space
        '\u200C', // Zero-width non-joiner
        '\u200D', // Zero-width joiner
        '\u200E', // LTR mark
        '\u200F', // RTL mark
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', // Bidi controls
        '\u2060', // Word joiner
        '\u180E', // Mongolian vowel separator
        '\uFEFF', // BOM / Zero-width NBSP
        '\u00AD', // Soft hyphen
        '\uFFFD', // Unicode replacement character
        '\u25A1', // □ White square (font-error box)
        '\u25A0', // ■ Black square (font-error box)
        '\u25AA', // ▪ Small black square
        '\u25AB'  // ▫ Small white square
    )

    fun isDeadOrInvisibleChar(ch: Char): Boolean {
        if (ch in invisibleOrDeadChars) return true
        val code = ch.code
        // C0 controls (excluding \t=0x09, \n=0x0A, \r=0x0D) and DEL / C1 controls
        if (code <= 0x08 || code == 0x0B || code == 0x0C || (code in 0x0E..0x1F) || (code in 0x7F..0x9F)) {
            return true
        }
        // Broken PDF/OCR Private Use Area glyphs (e.g. \uE000..\uF8FF)
        if (code in 0xE000..0xF8FF) {
            return true
        }
        return false
    }

    /**
     * Strips illegal XML 1.0 control characters while preserving tabs and newlines.
     */
    fun stripIllegalXmlChars(raw: String): String {
        if (raw.isEmpty()) return raw
        var needsClean = false
        for (ch in raw) {
            val c = ch.code
            if (c <= 0x08 || c == 0x0B || c == 0x0C || (c in 0x0E..0x1F) || c == 0xFFFE || c == 0xFFFF) {
                needsClean = true
                break
            }
        }
        if (!needsClean) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = ch.code
            if (c <= 0x08 || c == 0x0B || c == 0x0C || (c in 0x0E..0x1F) || c == 0xFFFE || c == 0xFFFF) {
                continue
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Sanitizes a single string: removes invisible/dead OCR characters, normalizes exotic spaces
     * to standard ASCII space, and converts the entire string to Unicode NFC form.
     */
    fun sanitizeText(raw: String): UnicodeSanitizeResult {
        if (raw.isEmpty()) return UnicodeSanitizeResult(raw, 0)

        var removedOrReplaced = 0
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                isDeadOrInvisibleChar(ch) -> {
                    removedOrReplaced++
                }
                ch in exoticSpaces -> {
                    sb.append(' ')
                    removedOrReplaced++
                }
                else -> sb.append(ch)
            }
        }

        val stripped = sb.toString()
        val nfc = if (Normalizer.isNormalized(stripped, Normalizer.Form.NFC)) {
            stripped
        } else {
            Normalizer.normalize(stripped, Normalizer.Form.NFC)
        }

        val nfcChangeCount = if (nfc != stripped) 1 else 0
        return UnicodeSanitizeResult(
            cleanedText = nfc,
            sanitizedCount = removedOrReplaced + nfcChangeCount
        )
    }

    /**
     * Sanitizes every text node inside `doc.body()` in-place without disturbing HTML tags or images.
     */
    fun sanitizeDocument(doc: Document): DocumentUnicodeSanitizeResult {
        val body = doc.body() ?: return DocumentUnicodeSanitizeResult(0, 0)
        var modifiedNodes = 0
        var sanitizedChars = 0

        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    if (node is TextNode) {
                        val whole = node.wholeText
                        if (whole.isEmpty()) return
                        val result = sanitizeText(whole)
                        if (result.sanitizedCount > 0 || result.cleanedText != whole) {
                            node.text(result.cleanedText)
                            modifiedNodes++
                            sanitizedChars += result.sanitizedCount.coerceAtLeast(1)
                        }
                    }
                }

                override fun tail(node: Node, depth: Int) = Unit
            },
            body
        )

        return DocumentUnicodeSanitizeResult(
            modifiedNodes = modifiedNodes,
            sanitizedChars = sanitizedChars
        )
    }
}
