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
 * Thông tin một font chữ có thể dùng trong trình đọc sách (gồm 5 font chuyên sách tích hợp sẵn và font người dùng nhập).
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
 * - 5 font chữ chuyên đọc sách có chân cao cấp hỗ trợ đầy đủ dấu tiếng Việt:
 *   `Literata`, `Lora`, `Merriweather`, `EB Garamond`, `Spectral`
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

    private val ebGaramondFamily: FontFamily by lazy {
        FontFamily(Font(R.font.eb_garamond, FontWeight.Normal))
    }

    private val spectralFamily: FontFamily by lazy {
        FontFamily(Font(R.font.spectral, FontWeight.Normal))
    }

    val builtInFonts: List<ReaderFontOption> = listOf(
        ReaderFontOption(
            id = "literata",
            displayName = "Literata",
            subtitle = "Chữ sách điện tử chuyên dụng, sắc nét"
        ),
        ReaderFontOption(
            id = "lora",
            displayName = "Lora",
            subtitle = "Chữ tiểu thuyết văn học mềm mại"
        ),
        ReaderFontOption(
            id = "merriweather",
            displayName = "Merriweather",
            subtitle = "Chữ sách in rõ ràng, dễ đọc lâu"
        ),
        ReaderFontOption(
            id = "eb_garamond",
            displayName = "EB Garamond",
            subtitle = "Chữ tiểu thuyết cổ điển thanh lịch"
        ),
        ReaderFontOption(
            id = "spectral",
            displayName = "Spectral",
            subtitle = "Chữ sách hiện đại êm mắt trên màn hình"
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
            "eb_garamond" -> ebGaramondFamily
            "spectral" -> spectralFamily
            else -> {
                val custom = customFonts.firstOrNull { it.id == fontId }
                if (custom != null) {
                    loadCustomFontFamily(custom.filePath) ?: literataFamily
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
