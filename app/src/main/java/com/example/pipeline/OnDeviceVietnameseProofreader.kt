package com.example.pipeline

import java.text.Normalizer

data class OnDeviceCorrection(
    val correctedText: String,
    val reason: String
)

/**
 * 100% on-device, offline Vietnamese NLP engine for:
 * - Step 0: Genre classification & proper noun extraction (to protect character/place names).
 * - Step 4: Vietnamese Unicode NFC normalization, modern tone-mark placement normalization,
 *           OCR typo repair, unambiguous Vietnamese spelling/diacritic correction, and sentence casing.
 */
class OnDeviceVietnameseProofreader {

    private data class PhraseRule(
        val wrong: String,
        val right: String,
        val reason: String
    )

    private val genreKeywords: Map<String, List<String>> = mapOf(
        "Kiếm hiệp / Tiên hiệp" to listOf(
            "nội công", "kiếm khí", "chưởng pháp", "tông môn", "tu luyện", "linh khí",
            "đan điền", "giang hồ", "võ lâm", "sư phụ", "tiểu tử", "lão giả", "công pháp", "đạo hữu"
        ),
        "Tiểu thuyết / Ngôn tình" to listOf(
            "ánh mắt", "mỉm cười", "trái tim", "nước mắt", "nhẹ nhàng", "lặng lẽ",
            "hạnh phúc", "tình yêu", "nắm tay", "ôm chặt", "khuôn mặt", "thì thầm"
        ),
        "Trinh thám / Kinh dị" to listOf(
            "vụ án", "thám tử", "hiện trường", "manh mối", "hung thủ", "cảnh sát",
            "nạn nhân", "điều tra", "bí ẩn", "xác chết", "bóng tối", "ám ảnh"
        ),
        "Lịch sử / Chiến tranh" to listOf(
            "triều đại", "hoàng đế", "tướng quân", "chiến tranh", "quân đội", "kinh thành",
            "lịch sử", "triều đình", "khởi nghĩa", "dân tộc", "chiến trường"
        ),
        "Kinh tế / Kỹ năng" to listOf(
            "tài chính", "đầu tư", "doanh nghiệp", "quản trị", "thị trường", "lợi nhuận",
            "thành công", "tư duy", "chiến lược", "khách hàng", "kỹ năng", "mục tiêu"
        ),
        "Khoa học / Học thuật" to listOf(
            "nghiên cứu", "phương pháp", "dữ liệu", "hệ thống", "thí nghiệm", "kết luận",
            "lý thuyết", "phân tích", "công nghệ", "vũ trụ", "sinh học", "vật lý"
        )
    )

    // Words that frequently appear capitalized at sentence start or headings, never proper nouns by themselves
    private val nonProperNounStarters = setOf(
        "Chương", "Phần", "Hồi", "Mục", "Quyển", "Tập", "Trang", "Lời", "Mở",
        "Nhưng", "Tuy", "Vì", "Nếu", "Khi", "Trong", "Ngoài", "Sau", "Trước",
        "Một", "Hai", "Ba", "Bốn", "Năm", "Những", "Các", "Mọi", "Tất", "Không",
        "Người", "Hắn", "Nàng", "Anh", "Em", "Ông", "Bà", "Cô", "Chú", "Cậu",
        "Bỗng", "Đột", "Thế", "Vậy", "Rồi", "Cuối", "Đầu", "Giữa", "Dưới", "Trên"
    )

    // Multi-word capitalised proper noun candidate (e.g., "Trương Vô Kỵ", "Hà Nội", "Lâm Bình Chi")
    private val properNounRegex = Regex(
        """(?<![\p{L}])([A-ZÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬĐÈÉẺẼẸÊẾỀỂỄỆÌÍỈĨỊÒÓỎÕỌÔỐỒỔỖỘƠỚỜỞỠỢÙÚỦŨỤƯỨỪỬỮỰỲÝỶỸỴ][a-zàáảãạăắằẳẵặâấầẩẫậđèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵ]{1,12}(?:\s+[A-ZÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬĐÈÉẺẼẸÊẾỀỂỄỆÌÍỈĨỊÒÓỎÕỌÔỐỒỔỖỘƠỚỜỞỠỢÙÚỦŨỤƯỨỪỬỮỰỲÝỶỸỴ][a-zàáảãạăắằẳẵặâấầẩẫậđèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợùúủũụưứừửữựỳýỷỹỵ]{1,12}){1,3})(?![\p{L}])"""
    )

    // Modern Vietnamese tone-mark placement rules (chuẩn hóa dấu thanh trên nguyên âm đôi)
    private val tonePlacementRules: List<Pair<Regex, String>> = listOf(
        "hoà" to "hòa", "hoá" to "hóa", "hoả" to "hỏa", "hoã" to "hõa", "hoạ" to "họa",
        "toà" to "tòa", "toá" to "tóa", "toả" to "tỏa", "toạ" to "tọa",
        "khoẻ" to "khỏe", "khoè" to "khòe", "khoé" to "khóe",
        "loè" to "lòe", "loé" to "lóe", "loà" to "lòa", "loá" to "lóa", "loã" to "lõa", "loạ" to "lọa",
        "xoà" to "xòa", "xoá" to "xóa", "xoả" to "xỏa",
        "nhoà" to "nhòa", "nhoè" to "nhòe", "choà" to "chòa", "choá" to "chóa", "choè" to "chòe",
        "doạ" to "dọa", "toé" to "tóe", "toè" to "tòe",
        "thuỷ" to "thủy", "thuý" to "thúy", "thuỳ" to "thùy", "thuỵ" to "thụy",
        "tuỳ" to "tùy", "tuý" to "túy", "tuỵ" to "tụy",
        "huỷ" to "hủy", "huý" to "húy", "huỳ" to "hùy",
        "nguỵ" to "ngụy", "luỹ" to "lũy", "luỵ" to "lụy", "chuỳ" to "chùy", "truỵ" to "trụy",
        "quỳ" to "quỳ", "quỵ" to "quỵ"
    ).filter { it.first != it.second }.map { (oldForm, newForm) ->
        Regex("""(?iu)(?<![\p{L}])${Regex.escape(oldForm)}(?![\p{L}])""") to newForm
    }

    // Common OCR confusion rules in Vietnamese PDFs/EPUBs (I/l confusion at word start, 0/o inside words)
    private val ocrRules: List<Pair<Regex, String>> = listOf(
        Regex("""(?<![\p{L}])I(à|ại|úc|ên|òng|ời|àm|ớn|ấy|ần|ẽ|ạnh|ưng|uôn|iền|ặng|ỗi|ối|ưng)(?![\p{L}])""") to "l$1",
        Regex("""(?iu)(?<![\p{L}])kh0ng(?![\p{L}])""") to "không",
        Regex("""(?iu)(?<![\p{L}])ngư0i(?![\p{L}])""") to "người",
        Regex("""(?iu)(?<![\p{L}])nhưngx(?![\p{L}])""") to "những",
        Regex("""(?iu)(?<![\p{L}])cungx(?![\p{L}])""") to "cũng",
        Regex("""(?iu)(?<![\p{L}])vanx(?![\p{L}])""") to "vẫn",
        Regex("""(?iu)(?<![\p{L}])đax(?![\p{L}])""") to "đã",
        Regex("""(?iu)(?<![\p{L}])sẽ(?![\p{L}])""") to "sẽ"
    )

    // High-confidence unambiguous Vietnamese spelling & hỏi/ngã phrase dictionary
    private val phraseCorrections: List<PhraseRule> = listOf(
        // Lỗi chính tả phụ âm / từ ghép phổ biến
        PhraseRule("sử lý", "xử lý", "Sửa lỗi s/x"),
        PhraseRule("xử dụng", "sử dụng", "Sửa lỗi s/x"),
        PhraseRule("suất sắc", "xuất sắc", "Sửa lỗi s/x"),
        PhraseRule("xuất ăn", "suất ăn", "Sửa lỗi s/x"),
        PhraseRule("chính sác", "chính xác", "Sửa lỗi s/x"),
        PhraseRule("sác xuất", "xác suất", "Sửa lỗi s/x"),
        PhraseRule("xác xuất", "xác suất", "Sửa lỗi s/x"),
        PhraseRule("sác suất", "xác suất", "Sửa lỗi s/x"),
        PhraseRule("bổ xung", "bổ sung", "Sửa lỗi s/x"),
        PhraseRule("xúc tích", "súc tích", "Sửa lỗi s/x"),
        PhraseRule("cọ sát", "cọ xát", "Sửa lỗi s/x"),
        PhraseRule("lãng mạng", "lãng mạn", "Sửa chính tả"),
        PhraseRule("chuẩn đoán", "chẩn đoán", "Sửa chính tả"),
        PhraseRule("vô hình chung", "vô hình trung", "Sửa lỗi ch/tr"),
        PhraseRule("tựu chung", "tựu trung", "Sửa lỗi ch/tr"),
        PhraseRule("chăn trở", "trăn trở", "Sửa lỗi ch/tr"),
        PhraseRule("chủ chương", "chủ trương", "Sửa lỗi ch/tr"),
        PhraseRule("bắt trước", "bắt chước", "Sửa lỗi ch/tr"),
        PhraseRule("chỉnh chu", "chỉn chu", "Sửa chính tả"),
        PhraseRule("sát nhập", "sáp nhập", "Sửa chính tả"),
        PhraseRule("đọc giả", "độc giả", "Sửa chính tả"),
        PhraseRule("thăm quan", "tham quan", "Sửa chính tả"),
        PhraseRule("khắc khe", "khắt khe", "Sửa chính tả"),
        PhraseRule("giấu diếm", "giấu giếm", "Sửa lỗi d/gi"),
        PhraseRule("dư giả", "dư dả", "Sửa lỗi d/gi"),
        PhraseRule("giã man", "dã man", "Sửa lỗi d/gi"),
        PhraseRule("mẫu thuẫn", "mâu thuẫn", "Sửa lỗi dấu thanh"),
        PhraseRule("mâu thuẩn", "mâu thuẫn", "Sửa dấu hỏi/ngã"),

        // Lỗi dấu hỏi / ngã cực phổ biến trong ebook tiếng Việt
        PhraseRule("nghĩ ngơi", "nghỉ ngơi", "Sửa dấu hỏi/ngã"),
        PhraseRule("suy nghỉ", "suy nghĩ", "Sửa dấu hỏi/ngã"),
        PhraseRule("cảm nghỉ", "cảm nghĩ", "Sửa dấu hỏi/ngã"),
        PhraseRule("ngã rẻ", "ngã rẽ", "Sửa dấu hỏi/ngã"),
        PhraseRule("bảo táp", "bão táp", "Sửa dấu hỏi/ngã"),
        PhraseRule("mãi mê", "mải mê", "Sửa dấu hỏi/ngã"),
        PhraseRule("mải mải", "mãi mãi", "Sửa dấu hỏi/ngã"),
        PhraseRule("kỉ lưỡng", "kỹ lưỡng", "Sửa dấu hỏi/ngã"),
        PhraseRule("kỹ lưởng", "kỹ lưỡng", "Sửa dấu hỏi/ngã"),
        PhraseRule("rỏ ràng", "rõ ràng", "Sửa dấu hỏi/ngã"),
        PhraseRule("dể dàng", "dễ dàng", "Sửa dấu hỏi/ngã"),
        PhraseRule("hổ trợ", "hỗ trợ", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhẩn nại", "nhẫn nại", "Sửa dấu hỏi/ngã"),
        PhraseRule("kiên nhẩn", "kiên nhẫn", "Sửa dấu hỏi/ngã"),
        PhraseRule("vửng chắc", "vững chắc", "Sửa dấu hỏi/ngã"),
        PhraseRule("đứng vửng", "đứng vững", "Sửa dấu hỏi/ngã"),
        PhraseRule("giữ vửng", "giữ vững", "Sửa dấu hỏi/ngã"),
        PhraseRule("giử vững", "giữ vững", "Sửa dấu hỏi/ngã"),
        PhraseRule("ngở ngàng", "ngỡ ngàng", "Sửa dấu hỏi/ngã"),
        PhraseRule("lổi lầm", "lỗi lầm", "Sửa dấu hỏi/ngã"),
        PhraseRule("xin lổi", "xin lỗi", "Sửa dấu hỏi/ngã"),
        PhraseRule("nổi nhớ", "nỗi nhớ", "Sửa dấu hỏi/ngã"),
        PhraseRule("nổi buồn", "nỗi buồn", "Sửa dấu hỏi/ngã"),
        PhraseRule("nổi đau", "nỗi đau", "Sửa dấu hỏi/ngã"),
        PhraseRule("nổi sợ", "nỗi sợ", "Sửa dấu hỏi/ngã"),
        PhraseRule("nổi lo", "nỗi lo", "Sửa dấu hỏi/ngã"),
        PhraseRule("sẳn sàng", "sẵn sàng", "Sửa dấu hỏi/ngã"),
        PhraseRule("bản lỉnh", "bản lĩnh", "Sửa dấu hỏi/ngã"),
        PhraseRule("dủng cảm", "dũng cảm", "Sửa dấu hỏi/ngã"),
        PhraseRule("cẫn thận", "cẩn thận", "Sửa dấu hỏi/ngã"),
        PhraseRule("cẫn trọng", "cẩn trọng", "Sửa dấu hỏi/ngã"),
        PhraseRule("hiễu biết", "hiểu biết", "Sửa dấu hỏi/ngã"),
        PhraseRule("kỹ niệm", "kỷ niệm", "Sửa dấu hỏi/ngã"),
        PhraseRule("lịch sữ", "lịch sử", "Sửa dấu hỏi/ngã"),
        PhraseRule("bình tỉnh", "bình tĩnh", "Sửa dấu hỏi/ngã"),
        PhraseRule("tĩnh táo", "tỉnh táo", "Sửa dấu hỏi/ngã"),
        PhraseRule("lảnh đạo", "lãnh đạo", "Sửa dấu hỏi/ngã"),
        PhraseRule("củ kỹ", "cũ kỹ", "Sửa dấu hỏi/ngã"),
        PhraseRule("củ kỷ", "cũ kỹ", "Sửa dấu hỏi/ngã"),
        PhraseRule("chính tã", "chính tả", "Sửa dấu hỏi/ngã"),
        PhraseRule("diển biến", "diễn biến", "Sửa dấu hỏi/ngã"),
        PhraseRule("diển xuất", "diễn xuất", "Sửa dấu hỏi/ngã"),
        PhraseRule("biểu diển", "biểu diễn", "Sửa dấu hỏi/ngã"),
        PhraseRule("biễu diễn", "biểu diễn", "Sửa dấu hỏi/ngã"),
        PhraseRule("nguy hiễm", "nguy hiểm", "Sửa dấu hỏi/ngã"),
        PhraseRule("thẵng thắn", "thẳng thắn", "Sửa dấu hỏi/ngã"),
        PhraseRule("căng thẵng", "căng thẳng", "Sửa dấu hỏi/ngã"),
        PhraseRule("vẩn còn", "vẫn còn", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhửng người", "những người", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhửng điều", "những điều", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhửng gì", "những gì", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhửng năm", "những năm", "Sửa dấu hỏi/ngã"),
        PhraseRule("củng như", "cũng như", "Sửa dấu hỏi/ngã"),
        PhraseRule("củng vậy", "cũng vậy", "Sửa dấu hỏi/ngã"),
        PhraseRule("củng được", "cũng được", "Sửa dấu hỏi/ngã"),
        PhraseRule("đả từng", "đã từng", "Sửa dấu hỏi/ngã"),
        PhraseRule("đả lâu", "đã lâu", "Sửa dấu hỏi/ngã"),
        PhraseRule("đả biết", "đã biết", "Sửa dấu hỏi/ngã"),
        PhraseRule("đả nói", "đã nói", "Sửa dấu hỏi/ngã"),
        PhraseRule("đả đến", "đã đến", "Sửa dấu hỏi/ngã"),
        PhraseRule("đả xong", "đã xong", "Sửa dấu hỏi/ngã"),
        PhraseRule("sẻ không", "sẽ không", "Sửa dấu hỏi/ngã"),
        PhraseRule("chẵng phải", "chẳng phải", "Sửa dấu hỏi/ngã"),
        PhraseRule("bổng nhiên", "bỗng nhiên", "Sửa dấu hỏi/ngã"),
        PhraseRule("bổng dưng", "bỗng dưng", "Sửa dấu hỏi/ngã"),
        PhraseRule("ngẩu nhiên", "ngẫu nhiên", "Sửa dấu hỏi/ngã"),
        PhraseRule("lặng lẻ", "lặng lẽ", "Sửa dấu hỏi/ngã"),
        PhraseRule("mạnh mẻ", "mạnh mẽ", "Sửa dấu hỏi/ngã"),
        PhraseRule("vui vẽ", "vui vẻ", "Sửa dấu hỏi/ngã"),
        PhraseRule("sạch sẻ", "sạch sẽ", "Sửa dấu hỏi/ngã"),
        PhraseRule("chặt chẻ", "chặt chẽ", "Sửa dấu hỏi/ngã"),
        PhraseRule("khẻ khàng", "khẽ khàng", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhẹ nhỏm", "nhẹ nhõm", "Sửa dấu hỏi/ngã"),
        PhraseRule("hõi thăm", "hỏi thăm", "Sửa dấu hỏi/ngã"),
        PhraseRule("suy diển", "suy diễn", "Sửa dấu hỏi/ngã"),
        PhraseRule("tưỡng chừng", "tưởng chừng", "Sửa dấu hỏi/ngã"),
        PhraseRule("giãi quyết", "giải quyết", "Sửa dấu hỏi/ngã"),
        PhraseRule("bão vệ", "bảo vệ", "Sửa dấu hỏi/ngã"),
        PhraseRule("phãn ứng", "phản ứng", "Sửa dấu hỏi/ngã"),
        PhraseRule("ãnh hưởng", "ảnh hưởng", "Sửa dấu hỏi/ngã"),
        PhraseRule("hình ãnh", "hình ảnh", "Sửa dấu hỏi/ngã"),
        PhraseRule("biễu hiện", "biểu hiện", "Sửa dấu hỏi/ngã"),
        PhraseRule("tiêu chuẫn", "tiêu chuẩn", "Sửa dấu hỏi/ngã"),
        PhraseRule("chuẫn bị", "chuẩn bị", "Sửa dấu hỏi/ngã"),
        PhraseRule("kiễm tra", "kiểm tra", "Sửa dấu hỏi/ngã"),
        PhraseRule("phát triễn", "phát triển", "Sửa dấu hỏi/ngã"),
        PhraseRule("chuyễn động", "chuyển động", "Sửa dấu hỏi/ngã"),
        PhraseRule("sãn phẩm", "sản phẩm", "Sửa dấu hỏi/ngã"),
        PhraseRule("sãn xuất", "sản xuất", "Sửa dấu hỏi/ngã"),
        PhraseRule("kết quã", "kết quả", "Sửa dấu hỏi/ngã"),
        PhraseRule("hiệu quã", "hiệu quả", "Sửa dấu hỏi/ngã"),
        PhraseRule("quã thật", "quả thật", "Sửa dấu hỏi/ngã"),
        PhraseRule("tất cã", "tất cả", "Sửa dấu hỏi/ngã"),
        PhraseRule("trãi qua", "trải qua", "Sửa dấu hỏi/ngã"),
        PhraseRule("trãi nghiệm", "trải nghiệm", "Sửa dấu hỏi/ngã"),
        PhraseRule("cãm thấy", "cảm thấy", "Sửa dấu hỏi/ngã"),
        PhraseRule("cãm ơn", "cảm ơn", "Sửa dấu hỏi/ngã"),
        PhraseRule("cãm giác", "cảm giác", "Sửa dấu hỏi/ngã"),
        PhraseRule("đãm bảo", "đảm bảo", "Sửa dấu hỏi/ngã"),
        PhraseRule("quãn lý", "quản lý", "Sửa dấu hỏi/ngã"),
        PhraseRule("bãn thân", "bản thân", "Sửa dấu hỏi/ngã"),
        PhraseRule("khẫn trương", "khẩn trương", "Sửa dấu hỏi/ngã"),
        PhraseRule("khẫn cấp", "khẩn cấp", "Sửa dấu hỏi/ngã"),
        PhraseRule("ngẩm nghĩ", "ngẫm nghĩ", "Sửa dấu hỏi/ngã"),
        PhraseRule("lẩn lộn", "lẫn lộn", "Sửa dấu hỏi/ngã"),
        PhraseRule("giửa chừng", "giữa chừng", "Sửa dấu hỏi/ngã"),
        PhraseRule("nữa đêm", "nửa đêm", "Sửa dấu hỏi/ngã"),
        PhraseRule("một nữa", "một nửa", "Sửa dấu hỏi/ngã"),
        PhraseRule("lần nửa", "lần nữa", "Sửa dấu hỏi/ngã"),
        PhraseRule("hơn nửa", "hơn nữa", "Sửa dấu hỏi/ngã"),
        PhraseRule("dẩn dắt", "dẫn dắt", "Sửa dấu hỏi/ngã"),
        PhraseRule("hướng dẩn", "hướng dẫn", "Sửa dấu hỏi/ngã"),
        PhraseRule("hấp dẩn", "hấp dẫn", "Sửa dấu hỏi/ngã"),
        PhraseRule("chỉ dẩn", "chỉ dẫn", "Sửa dấu hỏi/ngã"),
        PhraseRule("kỷ năng", "kỹ năng", "Sửa dấu hỏi/ngã"),
        PhraseRule("kỷ thuật", "kỹ thuật", "Sửa dấu hỏi/ngã"),
        PhraseRule("mỷ thuật", "mỹ thuật", "Sửa dấu hỏi/ngã"),
        PhraseRule("thẩm mỷ", "thẩm mỹ", "Sửa dấu hỏi/ngã"),
        PhraseRule("thẫm mỹ", "thẩm mỹ", "Sửa dấu hỏi/ngã"),
        PhraseRule("nghỉa vụ", "nghĩa vụ", "Sửa dấu hỏi/ngã"),
        PhraseRule("ý nghỉa", "ý nghĩa", "Sửa dấu hỏi/ngã"),
        PhraseRule("dử liệu", "dữ liệu", "Sửa dấu hỏi/ngã"),
        PhraseRule("giử gìn", "giữ gìn", "Sửa dấu hỏi/ngã"),
        PhraseRule("chửa bệnh", "chữa bệnh", "Sửa dấu hỏi/ngã"),
        PhraseRule("sữa chữa", "sửa chữa", "Sửa dấu hỏi/ngã"),
        PhraseRule("sửa chửa", "sửa chữa", "Sửa dấu hỏi/ngã"),
        PhraseRule("sữa chửa", "sửa chữa", "Sửa dấu hỏi/ngã"),
        PhraseRule("sữa đổi", "sửa đổi", "Sửa dấu hỏi/ngã"),
        PhraseRule("sữa sai", "sửa sai", "Sửa dấu hỏi/ngã"),
        PhraseRule("chỉnh sữa", "chỉnh sửa", "Sửa dấu hỏi/ngã"),
        PhraseRule("bửa ăn", "bữa ăn", "Sửa dấu hỏi/ngã"),
        PhraseRule("bửa cơm", "bữa cơm", "Sửa dấu hỏi/ngã"),
        PhraseRule("bửa tối", "bữa tối", "Sửa dấu hỏi/ngã"),
        PhraseRule("bửa sáng", "bữa sáng", "Sửa dấu hỏi/ngã"),
        PhraseRule("bửa trưa", "bữa trưa", "Sửa dấu hỏi/ngã"),
        PhraseRule("cữa sổ", "cửa sổ", "Sửa dấu hỏi/ngã"),
        PhraseRule("cửa sỗ", "cửa sổ", "Sửa dấu hỏi/ngã"),
        PhraseRule("cữa hàng", "cửa hàng", "Sửa dấu hỏi/ngã"),
        PhraseRule("mở cữa", "mở cửa", "Sửa dấu hỏi/ngã"),
        PhraseRule("đóng cữa", "đóng cửa", "Sửa dấu hỏi/ngã"),
        PhraseRule("trể nải", "trễ nải", "Sửa dấu hỏi/ngã"),
        PhraseRule("lể phép", "lễ phép", "Sửa dấu hỏi/ngã"),
        PhraseRule("lể hội", "lễ hội", "Sửa dấu hỏi/ngã"),
        PhraseRule("nghi lể", "nghi lễ", "Sửa dấu hỏi/ngã"),
        PhraseRule("rể cây", "rễ cây", "Sửa dấu hỏi/ngã"),
        PhraseRule("chú rễ", "chú rể", "Sửa dấu hỏi/ngã"),
        PhraseRule("lịch lảm", "lịch lãm", "Sửa dấu hỏi/ngã"),
        PhraseRule("triển lảm", "triển lãm", "Sửa dấu hỏi/ngã"),
        PhraseRule("triễn lãm", "triển lãm", "Sửa dấu hỏi/ngã"),
        PhraseRule("giã mạo", "giả mạo", "Sửa dấu hỏi/ngã"),
        PhraseRule("không phãi", "không phải", "Sửa dấu hỏi/ngã"),
        PhraseRule("bỡi vì", "bởi vì", "Sửa dấu hỏi/ngã"),
        PhraseRule("mổi người", "mỗi người", "Sửa dấu hỏi/ngã"),
        PhraseRule("mổi ngày", "mỗi ngày", "Sửa dấu hỏi/ngã"),
        PhraseRule("mổi lần", "mỗi lần", "Sửa dấu hỏi/ngã"),
        PhraseRule("mổi khi", "mỗi khi", "Sửa dấu hỏi/ngã"),
        PhraseRule("chứng tõ", "chứng tỏ", "Sửa dấu hỏi/ngã"),
        PhraseRule("tõ ra", "tỏ ra", "Sửa dấu hỏi/ngã"),
        PhraseRule("bõ qua", "bỏ qua", "Sửa dấu hỏi/ngã"),
        PhraseRule("từ bõ", "từ bỏ", "Sửa dấu hỏi/ngã"),
        PhraseRule("nhõ bé", "nhỏ bé", "Sửa dấu hỏi/ngã"),
        PhraseRule("trẻ nhõ", "trẻ nhỏ", "Sửa dấu hỏi/ngã"),
        PhraseRule("cỗ đại", "cổ đại", "Sửa dấu hỏi/ngã"),
        PhraseRule("cỗ điển", "cổ điển", "Sửa dấu hỏi/ngã"),
        PhraseRule("cổ điễn", "cổ điển", "Sửa dấu hỏi/ngã"),
        PhraseRule("khỗ sở", "khổ sở", "Sửa dấu hỏi/ngã"),
        PhraseRule("khổ sỡ", "khổ sở", "Sửa dấu hỏi/ngã"),
        PhraseRule("đau khỗ", "đau khổ", "Sửa dấu hỏi/ngã"),
        PhraseRule("tỗ chức", "tổ chức", "Sửa dấu hỏi/ngã"),
        PhraseRule("tỗ quốc", "tổ quốc", "Sửa dấu hỏi/ngã"),
        PhraseRule("nổ lực", "nỗ lực", "Sửa dấu hỏi/ngã"),
        PhraseRule("bùng nỗ", "bùng nổ", "Sửa dấu hỏi/ngã"),
        PhraseRule("chổ ngồi", "chỗ ngồi", "Sửa dấu hỏi/ngã"),
        PhraseRule("chổ đứng", "chỗ đứng", "Sửa dấu hỏi/ngã"),
        PhraseRule("chổ ở", "chỗ ở", "Sửa dấu hỏi/ngã"),
        PhraseRule("tại chổ", "tại chỗ", "Sửa dấu hỏi/ngã"),
        PhraseRule("gổ quý", "gỗ quý", "Sửa dấu hỏi/ngã"),
        PhraseRule("bàn gổ", "bàn gỗ", "Sửa dấu hỏi/ngã"),
        PhraseRule("ghế gổ", "ghế gỗ", "Sửa dấu hỏi/ngã"),
        PhraseRule("hổn loạn", "hỗn loạn", "Sửa dấu hỏi/ngã"),
        PhraseRule("hổn hợp", "hỗn hợp", "Sửa dấu hỏi/ngã"),
        PhraseRule("cư xữ", "cư xử", "Sửa dấu hỏi/ngã"),
        PhraseRule("đối xữ", "đối xử", "Sửa dấu hỏi/ngã"),
        PhraseRule("phân xữ", "phân xử", "Sửa dấu hỏi/ngã"),
        PhraseRule("tữ tế", "tử tế", "Sửa dấu hỏi/ngã"),
        PhraseRule("tữ vong", "tử vong", "Sửa dấu hỏi/ngã"),
        PhraseRule("thữ thách", "thử thách", "Sửa dấu hỏi/ngã"),
        PhraseRule("thữ nghiệm", "thử nghiệm", "Sửa dấu hỏi/ngã"),
        PhraseRule("chử viết", "chữ viết", "Sửa dấu hỏi/ngã"),
        PhraseRule("chử ký", "chữ ký", "Sửa dấu hỏi/ngã"),
        PhraseRule("chử nghĩa", "chữ nghĩa", "Sửa dấu hỏi/ngã"),
        PhraseRule("ngôn ngử", "ngôn ngữ", "Sửa dấu hỏi/ngã"),
        PhraseRule("từ ngử", "từ ngữ", "Sửa dấu hỏi/ngã"),
        PhraseRule("lử hành", "lữ hành", "Sửa dấu hỏi/ngã"),
        PhraseRule("lử khách", "lữ khách", "Sửa dấu hỏi/ngã"),
        PhraseRule("vủ trụ", "vũ trụ", "Sửa dấu hỏi/ngã"),
        PhraseRule("vủ khí", "vũ khí", "Sửa dấu hỏi/ngã"),
        PhraseRule("vủ điệu", "vũ điệu", "Sửa dấu hỏi/ngã"),
        PhraseRule("phụ nử", "phụ nữ", "Sửa dấu hỏi/ngã"),
        PhraseRule("thiếu nử", "thiếu nữ", "Sửa dấu hỏi/ngã"),
        PhraseRule("nử hoàng", "nữ hoàng", "Sửa dấu hỏi/ngã"),
        PhraseRule("nử sinh", "nữ sinh", "Sửa dấu hỏi/ngã"),
        PhraseRule("ngũ yên", "ngủ yên", "Sửa dấu hỏi/ngã"),
        PhraseRule("giấc ngũ", "giấc ngủ", "Sửa dấu hỏi/ngã"),
        PhraseRule("chũ động", "chủ động", "Sửa dấu hỏi/ngã"),
        PhraseRule("chũ yếu", "chủ yếu", "Sửa dấu hỏi/ngã"),
        PhraseRule("chũ nhân", "chủ nhân", "Sửa dấu hỏi/ngã")
    )

    private val compiledPhraseRules: List<Triple<Regex, String, String>> by lazy {
        phraseCorrections.map { rule ->
            val regex = Regex(
                """(?iu)(?<![\p{L}])${Regex.escape(rule.wrong)}(?![\p{L}])"""
            )
            Triple(regex, rule.right, rule.reason)
        }
    }

    private val duplicateCommaRegex = Regex(""",{2,}""")
    private val duplicateSemicolonRegex = Regex(""";{2,}""")
    private val doubleDotRegex = Regex("""(?<!\.)\.\.(?!\.)""")

    /**
     * Step 0: Offline global context & proper-noun scan (zero network calls).
     */
    fun analyzeGlobalContext(sampleText: String): BookContext {
        if (sampleText.isBlank()) {
            return BookContext(
                genre = "Sách tiếng Việt",
                properNouns = emptyList(),
                summaryNote = "Xử lý ngoại tuyến hoàn toàn trên thiết bị"
            )
        }

        val lowerSample = Normalizer.normalize(sampleText, Normalizer.Form.NFC).lowercase()
        var bestGenre = "Văn học / Sách tiếng Việt"
        var bestScore = 0
        for ((genre, keywords) in genreKeywords) {
            var score = 0
            for (kw in keywords) {
                var idx = lowerSample.indexOf(kw)
                while (idx >= 0) {
                    score++
                    idx = lowerSample.indexOf(kw, idx + kw.length)
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestGenre = genre
            }
        }

        // Extract frequent capitalized multi-word names (characters, places, sects)
        val counts = mutableMapOf<String, Int>()
        val normalized = Normalizer.normalize(sampleText, Normalizer.Form.NFC)
        for (match in properNounRegex.findAll(normalized)) {
            val candidate = match.groupValues[1].trim()
            val firstWord = candidate.substringBefore(' ')
            if (firstWord in nonProperNounStarters) continue
            if (candidate.length < 4 || candidate.length > 36) continue
            counts[candidate] = (counts[candidate] ?: 0) + 1
        }

        val properNouns = counts.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(40)

        return BookContext(
            genre = bestGenre,
            properNouns = properNouns,
            summaryNote = "Xử lý trực tiếp trên máy (ngoại tuyến)"
        )
    }

    /**
     * Step 4: Offline Vietnamese spelling, diacritic, OCR & punctuation proofreading for a single paragraph.
     * Returns null if no edits are needed.
     */
    fun proofreadParagraph(
        rawParagraph: String,
        bookContext: BookContext
    ): OnDeviceCorrection? {
        if (rawParagraph.isBlank()) return null

        val reasons = linkedSetOf<String>()
        var text = rawParagraph

        // 1. Unicode NFC normalization (fixes broken decomposed Vietnamese diacritics from PDFs/EPUBs)
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        if (nfc != text) {
            text = nfc
            reasons.add("Chuẩn hóa mã dấu Unicode")
        }

        // Protect proper nouns using temporary sentinel placeholders so rules never touch them
        val protectedMap = mutableListOf<Pair<String, String>>()
        var working = text
        bookContext.properNouns.sortedByDescending { it.length }.forEachIndexed { idx, noun ->
            if (noun.isNotBlank() && working.contains(noun)) {
                val token = "\uE000PN${idx}\uE001"
                working = working.replace(noun, token)
                protectedMap.add(token to noun)
            }
        }

        // 2. OCR typo repair
        for ((regex, replacement) in ocrRules) {
            val next = regex.replace(working, replacement)
            if (next != working) {
                working = next
                reasons.add("Sửa lỗi OCR")
            }
        }

        // 3. Modern Vietnamese tone placement normalization (hoà -> hòa, thuỷ -> thủy, khoẻ -> khỏe...)
        for ((regex, canonical) in tonePlacementRules) {
            val next = regex.replace(working) { match ->
                preserveCasing(match.value, canonical)
            }
            if (next != working) {
                working = next
                reasons.add("Chuẩn hóa vị trí dấu thanh")
            }
        }

        // 4. High-confidence Vietnamese spelling & hỏi/ngã dictionary
        for ((regex, canonical, reason) in compiledPhraseRules) {
            val next = regex.replace(working) { match ->
                preserveCasing(match.value, canonical)
            }
            if (next != working) {
                working = next
                reasons.add(reason)
            }
        }

        // 5. Stray duplicate punctuation repair
        val punctFixed = working
            .replace(duplicateCommaRegex, ",")
            .replace(duplicateSemicolonRegex, ";")
            .replace(doubleDotRegex, "...")
        if (punctFixed != working) {
            working = punctFixed
            reasons.add("Chuẩn hóa dấu câu")
        }

        // Restore protected proper nouns verbatim
        for ((token, noun) in protectedMap) {
            working = working.replace(token, noun)
        }

        working = TextCleaningEngine.normalizeParagraphText(working)
        if (working == rawParagraph || working.isBlank()) return null

        return OnDeviceCorrection(
            correctedText = working,
            reason = reasons.take(2).joinToString(" · ").ifBlank { "Hiệu đính chính tả" }
        )
    }

    private fun preserveCasing(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement
        return when {
            original == original.uppercase() -> replacement.uppercase()
            original.first().isUpperCase() -> {
                replacement.replaceFirstChar { it.uppercaseChar() }
            }
            else -> replacement
        }
    }
}
