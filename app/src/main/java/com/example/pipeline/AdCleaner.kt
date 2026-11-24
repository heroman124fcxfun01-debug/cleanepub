package com.example.pipeline

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class AdCleanResult(
    val removedCount: Int
)

/**
 * 100% Offline Junk Advertisement, Promotional Watermark & Decorative Noise Cleaner for Ebooks.
 * Strips:
 * 1. Promotional / translator / ebook site spam lines ("Tải ebook miễn phí tại...", "Đọc truyện nhanh nhất tại...", etc.)
 * 2. Meaningless repeated decorative character lines ("*****", "=====", "-----", "~~~~~")
 * 3. Hidden DOM elements and banner-only ad <div>/<p> blocks
 */
object AdCleaner {

    private val promoLinePatterns: List<Regex> = listOf(
        Regex("""tải\s+ebook\s+miễn\s+phí\s+tại.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""đọc\s+truyện\s+(?:nhanh\s+nhất|miễn\s+phí|online|full|mới\s+nhất)\s+tại.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""chúc\s+(?:các\s+)?bạn\s+đọc\s+(?:truyện|sách)\s+vui\s+vẻ[!.\s]*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""nguồn\s+sưu\s+tầm.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""bản\s+quyền\s+thuộc\s+về.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""theo\s+dõi\s+(?:fanpage|trang|kênh|facebook|group).*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""ủng\s+hộ\s+(?:nhóm\s+dịch|tác\s+giả|editor|converter|team).*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""mọi\s+chi\s+tiết\s+xin\s+liên\s+hệ.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""truy\s+cập\s+(?:website|web|trang\s+chủ|link).*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""cảm\s+ơn\s+(?:các\s+)?bạn\s+đã\s+(?:tải|đọc|ủng\s+hộ).*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""ebook\s+(?:được\s+)?(?:chia\s+sẻ|tổng\s+hợp|thực\s+hiện|làm)\s+(?:miễn\s+phí|bởi|tại).*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""(?:like|share|đánh\s+giá|vote|bình\s+luận)\s+để\s+ủng\s+hộ.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""(?:nhóm\s+dịch|converter|editor|biên\s+tập)\s*[:：]\s*.*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""được\s+tải\s+(?:về\s+)?từ\s+(?:website|trang|web).*""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""vui\s+lòng\s+không\s+reup.*""", setOf(RegexOption.IGNORE_CASE))
    )

    // Inline spam tail or bracketed spam injected inside an otherwise real paragraph
    private val inlinePromoPatterns: List<Regex> = listOf(
        Regex("""(?:\s*[\[(]?\s*(?:tải\s+ebook\s+miễn\s+phí\s+tại|đọc\s+truyện\s+(?:nhanh\s+nhất|online|miễn\s+phí)\s+tại|nguồn\s+sưu\s+tầm|theo\s+dõi\s+fanpage|ủng\s+hộ\s+nhóm\s+dịch|mọi\s+chi\s+tiết\s+xin\s+liên\s+hệ|bản\s+quyền\s+thuộc\s+về)[^\]\)\n]*[\]\)]?)""", setOf(RegexOption.IGNORE_CASE)),
        Regex("""\s*chúc\s+(?:các\s+)?bạn\s+đọc\s+(?:truyện|sách)\s+vui\s+vẻ[!.\s]*$""", setOf(RegexOption.IGNORE_CASE))
    )

    // Meaningless decorative separator lines: *****, =====, -----, ~~~~~, _____, #####, * * * *
    private val decorativeLineRegex = Regex(
        """^[\s*=\-~_#•·♦◆★☆●○■□▲△❖✦✧+|/\\]{4,}$"""
    )

    // CSS hidden indicators
    private val hiddenStyleRegex = Regex(
        """display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(?:\.0+)?(?:\s*;|\s*$)|font-size\s*:\s*0(?:px|pt|em|rem)?(?:\s*;|\s*$)""",
        setOf(RegexOption.IGNORE_CASE)
    )

    // Ad / banner class, id, or image src/alt keywords
    private val adTokenRegex = Regex(
        """(?i)(?:^|[-_\s/])(ad|ads|advert|advertisement|banner|promo|promotion|sponsor|sponsored|quangcao|quang-cao|qc|watermark|donate|fanpage|footer-ad|header-ad|truyenfull|dtv-ebook|nhasachmienphi|sstruyen|tangthuvien|wattpad-logo)(?:$|[-_\s/.0-9])"""
    )

    fun cleanDocument(doc: Document): AdCleanResult {
        var removed = 0
        val body = doc.body()

        // 1. Remove hidden elements (display:none, visibility:hidden, opacity:0, hidden attr)
        val hiddenElements = body.select("[hidden], [style]").toList()
        for (el in hiddenElements) {
            if (el.parent() == null) continue
            val style = el.attr("style")
            if (el.hasAttr("hidden") || (style.isNotBlank() && hiddenStyleRegex.containsMatchIn(style))) {
                el.remove()
                removed++
            }
        }

        // 2. Remove <div> / <p> explicitly marked as ads/banners or containing ONLY an ad banner image
        val blockContainers = body.select("div, p, section, aside, footer").toList()
        for (el in blockContainers) {
            if (el.parent() == null) continue
            if (isAdContainer(el) || isBannerImageOnlyBlock(el)) {
                el.remove()
                removed++
            }
        }

        // 3. Scan text-bearing blocks for promotional spam lines and meaningless decorative separators
        val textBlocks = body.select("p, div, span, li, blockquote, h4, h5, h6").toList()
        for (el in textBlocks) {
            if (el.parent() == null) continue
            // Only process leaf-level containers (avoid removing a whole chapter wrapper <div>)
            if (el.tagName() == "div" && el.select("p, div, h1, h2, h3").isNotEmpty()) continue
            if (el.select("img").isNotEmpty()) continue

            val text = el.text().replace('\u00A0', ' ').trim()
            if (text.isEmpty()) continue

            if (isDecorativeSeparatorLine(text)) {
                el.remove()
                removed++
                continue
            }

            if (isPureSpamParagraph(text)) {
                el.remove()
                removed++
                continue
            }

            // Strip inline spam appended inside a valid story paragraph
            val stripped = stripInlineSpam(text)
            if (stripped != text) {
                if (stripped.isBlank()) {
                    el.remove()
                } else {
                    el.text(stripped)
                }
                removed++
            }
        }

        return AdCleanResult(removedCount = removed)
    }

    /**
     * Pure-text helper for cleaning raw lines/paragraphs (also used by unit tests).
     */
    fun cleanPlainText(rawText: String): Pair<String, Int> {
        var removed = 0
        val cleanedLines = mutableListOf<String>()
        for (line in rawText.lines()) {
            val trimmed = line.replace('\u00A0', ' ').trim()
            if (trimmed.isEmpty()) continue
            if (isDecorativeSeparatorLine(trimmed) || isPureSpamParagraph(trimmed)) {
                removed++
                continue
            }
            val stripped = stripInlineSpam(trimmed)
            if (stripped != trimmed) {
                removed++
            }
            if (stripped.isNotBlank()) {
                cleanedLines.add(stripped)
            }
        }
        return cleanedLines.joinToString("\n") to removed
    }

    internal fun isDecorativeSeparatorLine(text: String): Boolean {
        val compact = text.replace(" ", "")
        if (compact.length < 4) return false
        return decorativeLineRegex.matches(text)
    }

    internal fun isPureSpamParagraph(text: String): Boolean {
        // Real literary paragraphs are typically longer; spam notices are usually <= 240 chars
        if (text.length > 260) return false
        return promoLinePatterns.any { pattern ->
            val match = pattern.find(text) ?: return@any false
            // If matched spam occupies >= 55% of the paragraph, the whole paragraph is junk
            match.value.length >= (text.length * 0.55).toInt()
        }
    }

    private fun stripInlineSpam(text: String): String {
        var current = text
        for (pattern in inlinePromoPatterns) {
            current = pattern.replace(current, "")
        }
        return current.replace(Regex("""\s{2,}"""), " ").trim()
    }

    private fun isAdContainer(el: Element): Boolean {
        // Never remove a container that holds multiple story paragraphs
        if (el.select("p").size >= 2) return false
        val idAndClass = "${el.id()} ${el.className()}".trim()
        if (idAndClass.isBlank()) return false
        if (!adTokenRegex.containsMatchIn(idAndClass)) return false
        // Safety guard: ensure we don't delete long real story text accidentally styled with a weird class
        return el.text().trim().length <= 260
    }

    private fun isBannerImageOnlyBlock(el: Element): Boolean {
        if (el.tagName() !in setOf("div", "p")) return false
        if (el.text().trim().isNotEmpty()) return false
        val imgs = el.select("img")
        if (imgs.isEmpty()) return false
        // Only remove if ALL images inside this text-free block match ad/banner/watermark indicators
        return imgs.all { img ->
            val meta = "${img.attr("src")} ${img.attr("alt")} ${img.className()} ${img.id()}"
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            val looksLikeBannerRatio = (w != null && h != null && h > 0 && w >= h * 4 && h <= 140)
            adTokenRegex.containsMatchIn(meta) || looksLikeBannerRatio
        }
    }
}
