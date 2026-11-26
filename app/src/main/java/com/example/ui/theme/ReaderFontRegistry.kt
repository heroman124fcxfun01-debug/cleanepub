package com.example.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import com.example.data.CustomImportedFont
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thông tin một font chữ có thể dùng trong trình đọc sách (gồm font tích hợp sẵn và font người dùng nhập).
 */
data class ReaderFontOption(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val isCustom: Boolean = false,
    val filePath: String? = null
)

/**
 * Quản lý toàn bộ font chữ Offline cho BookReaderScreen:
 * - Font tích hợp sẵn hỗ trợ đầy đủ dấu tiếng Việt (`Literata`, `Lora`, `Merriweather`, `Be Vietnam Pro`, ...)
 * - Font `.ttf` / `.otf` do người dùng tự nhập (lưu cục bộ trong `filesDir/book_library/custom_fonts/`)
 */
object ReaderFontRegistry {

    const val DEFAULT_FONT_ID = "literata"

    private val customFontCache = ConcurrentHashMap<String, FontFamily>()

    private val literataFamily: FontFamily by lazy {
        FontFamily(Font(R.font.literata, FontWeight.Normal))
    }

    private val loraFamily: FontFamily by lazy {
        FontFamily(Font(R.font.lora, FontWeight.Normal))
    }

    private val merriweatherFamily: FontFamily by lazy {
        FontFamily(Font(R.font.merriweather, FontWeight.Normal))
    }

    private val beVietnamProFamily: FontFamily by lazy {
        FontFamily(Font(R.font.be_vietnam_pro, FontWeight.Normal))
    }

    val builtInFonts: List<ReaderFontOption> = listOf(
        ReaderFontOption(
            id = "literata",
            displayName = "Literata",
            subtitle = "Chữ sách điện tử chuyên dụng"
        ),
        ReaderFontOption(
            id = "lora",
            displayName = "Lora",
            subtitle = "Chữ tiểu thuyết mềm mại"
        ),
        ReaderFontOption(
            id = "merriweather",
            displayName = "Merriweather",
            subtitle = "Chữ sách in rõ nét"
        ),
        ReaderFontOption(
            id = "be_vietnam_pro",
            displayName = "Be Vietnam Pro",
            subtitle = "Không chân hiện đại cho tiếng Việt"
        ),
        ReaderFontOption(
            id = "system_default",
            displayName = "Mặc định hệ thống",
            subtitle = "Font chuẩn của máy Android"
        ),
        ReaderFontOption(
            id = "serif_classic",
            displayName = "Serif Cổ điển",
            subtitle = "Chữ có chân truyền thống"
        ),
        ReaderFontOption(
            id = "sans_clean",
            displayName = "Sans-Serif Thanh thoát",
            subtitle = "Chữ không chân nhẹ mắt"
        )
    )

    fun allFontOptions(customFonts: List<CustomImportedFont>): List<ReaderFontOption> {
        val customOptions = customFonts.map { cf ->
            ReaderFontOption(
                id = cf.id,
                displayName = cf.displayName,
                subtitle = "Font đã nhập (.ttf/.otf)",
                isCustom = true,
                filePath = cf.filePath
            )
        }
        return customOptions + builtInFonts
    }

    fun findFontDisplayName(fontId: String, customFonts: List<CustomImportedFont>): String {
        return allFontOptions(customFonts).firstOrNull { it.id == fontId }?.displayName
            ?: builtInFonts.first().displayName
    }

    fun resolveFontFamily(fontId: String, customFonts: List<CustomImportedFont>): FontFamily {
        return when (fontId) {
            "literata" -> literataFamily
            "lora" -> loraFamily
            "merriweather" -> merriweatherFamily
            "be_vietnam_pro" -> beVietnamProFamily
            "system_default" -> FontFamily.Default
            "serif_classic" -> FontFamily.Serif
            "sans_clean" -> FontFamily.SansSerif
            else -> {
                val custom = customFonts.firstOrNull { it.id == fontId }
                if (custom != null) {
                    loadCustomFontFamily(custom.filePath) ?: FontFamily.Default
                } else {
                    literataFamily
                }
            }
        }
    }

    fun loadCustomFontFamily(filePath: String): FontFamily? {
        customFontCache[filePath]?.let { return it }
        val file = File(filePath)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val typeface = Typeface.createFromFile(file)
            val family = FontFamily(typeface)
            customFontCache[filePath] = family
            family
        }.getOrNull()
    }

    fun evictCachedFont(filePath: String) {
        customFontCache.remove(filePath)
    }
}
