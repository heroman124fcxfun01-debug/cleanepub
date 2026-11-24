package com.example.pipeline

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * 100% Offline Frequency-Scored Vietnamese Lexicon (Unigram + Bigram HashMaps, O(1) lookup).
 *
 * 4-Tier Scoring Architecture:
 * - Tier 1 (SCORE_COMMON = +1000): Top ~1,500+ high-frequency real Vietnamese syllables.
 * - Tier 2 (BIGRAM_BONUS = +2500): Compound words & natural 2-syllable collocations from `vi_words.txt` + core prose bigrams.
 * - Tier 3 (SCORE_RARE_VALID = +50): Real but infrequent valid syllables from `vi_syllables.txt` (ghosts filtered out).
 * - Tier 4 (SCORE_INVALID_GHOST = -5000): Unknown tokens, bare consonants, and theoretical/OCR ghost syllables
 *   that do not stand as real words in actual Vietnamese usage (e.g., "cóm", "ột", "ều", "ểm", "ững", "ýt", "ới", "ngà").
 */
object OfflineVietnameseDict {

    private const val ASSET_SYLLABLES = "vi_syllables.txt"
    private const val ASSET_WORDS = "vi_words.txt"

    const val SCORE_COMMON: Int = 1000
    const val BIGRAM_BONUS: Int = 2500
    const val SCORE_RARE_VALID: Int = 50
    const val SCORE_INVALID_GHOST: Int = -5000

    @Volatile
    private var loaded: Boolean = false

    // Unigram frequency score map: normalized lowercase NFC syllable -> score (1000 or 50)
    // Anything absent (or filtered out as a ghost syllable) evaluates to SCORE_INVALID_GHOST (-5000).
    private val syllableScores: HashMap<String, Int> = HashMap(24_000)

    // Raw valid syllable set from assets (kept for count / compatibility metrics)
    private val rawAssetSyllables: HashSet<String> = HashSet(24_000)

    // Bigram/collocation bonus map: "w1 w2" -> BIGRAM_BONUS (+2500)
    private val bigramBonuses: HashMap<String, Int> = HashMap(64_000)

    // Zero-allocation 2-level bigram index: w1 -> set of valid w2
    private val bigramIndex: HashMap<String, HashSet<String>> = HashMap(4_096)

    // Full phrase map from vi_words.txt (supports 2-word and 3-word lookup)
    private val words: HashSet<String> = HashSet(64_000)

    /**
     * Pure consonant letters/clusters that can NEVER stand alone as a Vietnamese word.
     */
    val STRICT_CONSONANT_FRAGMENTS: HashSet<String> = hashSetOf(
        "b", "c", "d", "đ", "f", "g", "h", "j", "k", "l", "m", "n",
        "p", "q", "r", "s", "t", "v", "w", "x", "z",
        "ng", "nh", "ch", "tr", "th", "ph", "kh", "gh", "gi", "qu", "ngh"
    )

    /**
     * Tier-4 Ghost / OCR Artifact Syllables (Score = -5000).
     * These are theoretical onset+rhyme combinations or broken OCR tails that do NOT occur as real
     * standalone words in Vietnamese prose and must NEVER be treated as valid syllables.
     */
    val GHOST_AND_OCR_FRAGMENTS: HashSet<String> = hashSetOf(
        // Bare single letters (real standalone single-letter words like "ý", "ở", "à", "ạ", "ồ", "ừ", "ổ", "y" are excluded)
        "a", "ă", "â", "b", "c", "d", "đ", "e", "ê", "f", "g", "h", "i", "j",
        "k", "l", "m", "n", "o", "ô", "ơ", "p", "q", "r", "s", "t", "u", "ư",
        "v", "w", "x", "z",
        // Bare consonant clusters
        "ng", "nh", "ch", "tr", "th", "ph", "kh", "gh", "gi", "qu", "ngh",
        // Theoretical / OCR ghost syllables explicitly observed in broken EPUB/PDF scans
        "cóm", "ột", "ều", "ểm", "ững", "ýt", "ới", "ngà",
        "nhữ", "đượ", "phẩ", "chấ", "nhiề", "điể", "thờ", "giố", "khôg",
        "bạ", "tầ", "nă", "mấ", "rấ", "hế", "nhấ", "bắ", "đấ", "mắ", "cầ", "nằ",
        "cở", "tở", "đở", "nhở", "chởm", "lởm",
        // Broken vowel-only / rhyme-only OCR tails
        "ời", "ởi", "ỡi", "ợi",
        "ủa", "ùa", "úa", "ũa", "ụa",
        "ưi", "ửi", "ứi", "ừi", "ữi", "ựi",
        "ươi", "ười", "ưới", "ưởi", "ưỡi", "ượi",
        "uôi", "uồi", "uối", "uổi", "uỗi", "uội",
        "ếu", "ểu", "ễu", "ệu",
        "ếm", "ềm", "ễm", "ệm",
        "ừng", "ửng", "ựng",
        "ốt", "ộp", "ộc", "ệc", "ệp", "ệt",
        "iêu", "iều", "iếu", "iểu", "iễu", "iệu",
        "iêm", "iềm", "iếm", "iểm", "iễm", "iệm",
        "iên", "iền", "iến", "iển", "iễn", "iện",
        "iêt", "iết", "iệt",
        "iêng", "iềng", "iếng", "iểng", "iễng", "iệng",
        "uân", "uần", "uấn", "uẩn", "uẫn", "uận", "uật", "uất",
        "uôn", "uồn", "uốn", "uổn", "uỗn", "uộn", "uột", "uốt",
        "ươn", "ườn", "ướn", "ưởn", "ưỡn", "ượn",
        "ường", "ướng", "ưởng", "ưỡng", "ượng", "ướt", "ượt",
        "măn", "mổn", "mồn", "nổn", "hấy", "gấy"
    )

    /**
     * Real vowel-initial Vietnamese words/syllables.
     * Any vowel-initial token in `vi_syllables.txt` that is NOT in this set (nor in COMMON_SYLLABLES)
     * is automatically filtered as a theoretical zero-onset ghost rhyme (Score = -5000).
     */
    private val REAL_VOWEL_INITIAL_SYLLABLES: HashSet<String> = hashSetOf(
        "à", "á", "ả", "ã", "ạ",
        "ai", "ải", "ái", "ài", "am", "ám", "ảm", "an", "án", "ản",
        "ang", "áng", "anh", "ánh", "ảnh", "ao", "áo", "ảo", "ào", "áp", "át", "ác", "ách",
        "ăn", "ăng", "ắng", "ẳng", "ắt", "ắc",
        "âm", "ấm", "ẩm", "ầm", "ẫm", "ân", "ấn", "ẩn", "ần", "ấp", "ất", "ấc",
        "âu", "ấu", "ầu", "ẩu", "ấy", "ây", "ẩy",
        "è", "é", "ẻ", "ẽ", "ẹ", "em", "ém", "eo", "éo", "ẻo", "ép", "ét", "éc",
        "ê", "ế", "ề", "ể", "ễ", "ệ", "êm", "ếm", "ếch", "ênh",
        "ì", "í", "ỉ", "ĩ", "ị", "im", "ím", "in", "ín", "ít", "ích", "inh", "ình",
        "ò", "ó", "ỏ", "õ", "ọ", "oai", "oái", "oan", "oán", "oang", "oanh", "oe", "oé",
        "oi", "ói", "ỏi", "om", "óm", "ỏm", "on", "ón", "ong", "óng", "òng", "ỏng", "óp", "ót", "óc",
        "ồ", "ố", "ổ", "ỗ", "ộ", "ôi", "ối", "ồi", "ổi", "ôm", "ốm", "ôn", "ốn", "ồn", "ổn",
        "ông", "ống", "ồng", "ổng", "ộng", "ốp", "ốc",
        "ờ", "ớ", "ở", "ỡ", "ợ", "ơi", "ơn", "ớn", "ớt",
        "ù", "ú", "ủ", "ũ", "ụ", "uống", "uốn", "uất", "uẩn", "uể", "uỷ", "ủy", "úy", "uy", "uất", "úng", "ủng", "úc", "út", "um", "ủm",
        "ừ", "ứ", "ử", "ữ", "ự", "ưa", "ưng", "ứng", "ức", "ước", "ướt", "ươm", "ương", "ưỡn",
        "ý", "y", "ỳ", "ỷ", "ỹ", "ỵ", "yên", "yến", "yếm", "yếu", "yêu", "yểm"
    )

    /**
     * Real but rare/ambiguous syllables that MUST stay in Tier 3 (SCORE_RARE_VALID = 50),
     * never promoted to Tier 1 (1000) so OCR splits like "nhi ều", "củ a", "tô i", "ma i", "mộ t"
     * are effortlessly outperformed by "nhiều", "của", "tôi", "mai", "một".
     */
    private val RARE_TIER3_ONLY: HashSet<String> = hashSetOf(
        "nhi", "củ", "tô", "ma", "mộ", "y", "ơi", "ôi", "toà", "choai",
        "nanh", "năn", "nít", "mơn", "nông", "nong", "manh", "tít", "tanh", "bá", "bê", "đê", "nê", "ti", "xi", "tì", "nhì", "tê", "hơ", "cò"
    )

    /**
     * Legacy compatibility set used by external helpers if needed.
     */
    val HIGH_SUSPICION_SINGLES: HashSet<String> = HashSet<String>().apply {
        addAll(GHOST_AND_OCR_FRAGMENTS)
        addAll(listOf("ơi", "ôi", "ấy", "ưa", "y"))
    }

    /**
     * Tier-1 Curated Core Vietnamese Vocabulary (Score = +1000).
     * Combined at load time with high-frequency real syllables extracted from `vi_words.txt`
     * to form ~1,500–2,000 top common Vietnamese syllables.
     */
    private val CURATED_COMMON_SYLLABLES: HashSet<String> = hashSetOf(
        // Function words, pronouns, connectors, particles, numbers
        "có", "một", "là", "của", "và", "những", "nữa", "nhiều", "hơn", "điểm", "đặc", "vài",
        "người", "không", "được", "trong", "với", "cho", "này", "đó", "đây", "kia", "ấy", "nào",
        "như", "nhưng", "dù", "vậy", "vì", "bởi", "nên", "nếu", "khi", "lúc", "sau", "trước",
        "trên", "dưới", "giữa", "ngoài", "từ", "đến", "tới", "về", "ra", "vào", "lên", "xuống",
        "qua", "lại", "đi", "để", "mà", "rằng", "thì", "cũng", "đều", "đã", "đang", "sẽ", "vẫn",
        "chưa", "rồi", "hết", "chỉ", "rất", "quá", "lắm", "nhất", "càng", "vừa", "mới", "ngay",
        "luôn", "thường", "hay", "hoặc", "cùng", "theo", "tại", "bằng", "do", "về", "về", "còn",
        "tôi", "ta", "mình", "bạn", "anh", "chị", "em", "ông", "bà", "cô", "chú", "bác", "họ",
        "chúng", "nó", "ai", "gì", "đâu", "sao", "bao", "nhiêu", "mấy", "mọi", "mỗi", "từng",
        "các", "cả", "tất", "toàn", "riêng", "chung", "khác", "nhau", "tự", "chính", "đúng", "sai",
        "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười", "trăm", "nghìn", "ngàn",
        "triệu", "tỷ", "thứ", "đầu", "cuối", "nửa", "đôi", "chục", "à", "ạ", "nhé", "nhỉ", "chứ",
        "đấy", "thôi", "nha", "ừ", "ồ", "ờ", "vâng", "dạ",
        // Core verbs, adjectives, nouns in books, psychology, literature & daily life
        "khía", "cạnh", "tính", "xu", "hướng", "thay", "đổi", "thời", "gian", "giống", "việc",
        "xác", "định", "cao", "thấp", "phẩm", "chất", "cố", "tâm", "lí", "lý", "học", "chú", "ý",
        "nữ", "nam", "trai", "gái", "con", "cha", "mẹ", "bố", "vợ", "chồng", "nhà", "cửa", "phòng",
        "đường", "phố", "làng", "quê", "nước", "đất", "trời", "biển", "sông", "núi", "rừng", "cây",
        "hoa", "lá", "gió", "mưa", "nắng", "mây", "trăng", "sao", "lửa", "ánh", "sáng", "tối", "đêm",
        "ngày", "mai", "hôm", "tuần", "tháng", "mùa", "xuân", "hạ", "thu", "đông", "sáng", "trưa",
        "chiều", "giờ", "phút", "giây", "bây", "xưa", "nay", "lâu", "mau", "nhanh", "chậm", "sớm",
        "muộn", "trễ", "kịp", "lớn", "nhỏ", "to", "bé", "dài", "ngắn", "rộng", "hẹp", "sâu", "cạn",
        "dày", "mỏng", "nặng", "nhẹ", "mạnh", "yếu", "khỏe", "đau", "ốm", "mệt", "vui", "buồn",
        "giận", "sợ", "lo", "yêu", "ghét", "thương", "nhớ", "quên", "biết", "hiểu", "nghĩ", "suy",
        "tưởng", "ngờ", "tin", "cần", "muốn", "thích", "chọn", "lấy", "đưa", "mang", "cầm", "nắm",
        "giữ", "buông", "bỏ", "đặt", "để", "mở", "đóng", "kéo", "đẩy", "nâng", "hạ", "chạy", "bước",
        "đứng", "ngồi", "nằm", "ngủ", "thức", "dậy", "ăn", "uống", "nói", "kể", "hỏi", "đáp", "trả",
        "lời", "gọi", "nghe", "nhìn", "xem", "thấy", "đọc", "viết", "vẽ", "làm", "tạo", "xây", "dựng",
        "sửa", "chữa", "giúp", "cứu", "bảo", "vệ", "chống", "đánh", "thắng", "thua", "chơi", "hát",
        "cười", "khóc", "thở", "sống", "chết", "sinh", "lớn", "già", "trẻ", "đẹp", "xấu", "tốt",
        "hay", "dở", "giỏi", "kém", "khó", "dễ", "đơn", "giản", "phức", "tạp", "rõ", "ràng", "mờ",
        "thật", "giả", "mới", "cũ", "sạch", "bẩn", "nóng", "lạnh", "ấm", "mát", "khô", "ướt", "ngọt",
        "đắng", "chua", "cay", "mặn", "trắng", "đen", "đỏ", "xanh", "vàng", "tím", "hồng", "nâu",
        "xám", "bạc", "đồng", "sắt", "gỗ", "đá", "cát", "bụi", "khói", "hơi", "tiếng", "âm", "thanh",
        "màu", "sắc", "hình", "dáng", "vẻ", "mặt", "mắt", "mũi", "miệng", "tai", "tóc", "đầu", "cổ",
        "vai", "tay", "chân", "lưng", "ngực", "bụng", "tim", "máu", "xương", "da", "thịt", "hồn",
        "trí", "óc", "não", "lòng", "dạ", "tình", "cảm", "xúc", "nghĩa", "nhân", "đức", "tài", "năng",
        "lực", "sức", "thế", "quyền", "lợi", "hại", "giá", "trị", "tiền", "của", "cải", "hàng", "hóa",
        "công", "nghiệp", "nông", "thương", "dịch", "vụ", "kinh", "tế", "chính", "xã", "hội", "văn",
        "khoa", "kỹ", "thuật", "nghệ", "giáo", "dục", "y", "tế", "pháp", "luật", "quân", "sự", "lịch",
        "sử", "địa", "tự", "nhiên", "môi", "trường", "cuộc", "đời", "kiếp", "số", "phận", "mệnh",
        "duyên", "nghiệp", "phúc", "họa", "may", "rủi", "niềm", "nỗi", "cơn", "trận", "lần", "lượt",
        "chuyến", "bữa", "tiết", "mục", "phần", "chương", "đoạn", "câu", "chữ", "từ", "ngữ", "tiếng",
        "sách", "vở", "báo", "thư", "bài", "trang", "dòng", "tên", "tuổi", "quê", "nghề", "chức",
        "vị", "cấp", "bậc", "hạng", "loại", "kiểu", "dạng", "mẫu", "cách", "lối", "phương", "tiện",
        "dụng", "cụ", "máy", "móc", "xe", "tàu", "thuyền", "máy", "bay", "bàn", "ghế", "giường", "tủ",
        "quần", "áo", "giày", "dép", "mũ", "nón", "túi", "ví", "chìa", "khóa", "đèn", "điện", "mạng",
        "vấn", "đề", "nguyên", "nhân", "kết", "quả", "hậu", "mục", "đích", "tiêu", "kế", "hoạch",
        "chiến", "lược", "biện", "pháp", "giải", "quyết", "xử", "phân", "tích", "tổng", "hợp", "so",
        "sánh", "đánh", "giá", "nhận", "xét", "thức", "biến", "chuyển", "phát", "triển", "tăng",
        "giảm", "mở", "rộng", "thu", "hẹp", "nâng", "cải", "thiện", "hoàn", "thành", "đạt", "thành",
        "bại", "hỏng", "mất", "còn", "thiếu", "đủ", "thừa", "dư", "chứa", "gồm", "bao", "gồm",
        "thuộc", "liên", "quan", "ảnh", "hưởng", "tác", "động", "gây", "dẫn", "tạo", "nên", "ra",
        "chi", "tiết", "nỗ", "hành", "vi", "thói", "quen", "bản", "chất", "trạng", "thái", "điều",
        "kiện", "hoàn", "cảnh", "tình", "huống", "trường", "hợp", "khả", "năng", "cơ", "hội", "thách",
        "nguy", "an", "toàn", "ổn", "bền", "vững", "chắc", "chắn", "tất", "yếu", "ngẫu", "nhiên",
        "trực", "tiếp", "gián", "chủ", "quan", "khách", "tích", "cực", "tiêu", "cơ", "bản", "trọng",
        "yếu", "cần", "thiết", "hợp", "phù", "tương", "đối", "tuyệt", "phổ", "biến", "bình", "thường",
        "biệt", "lạ", "kỳ", "hiếm", "quý", "quan", "trọng", "nghiêm", "nhẹ", "nhàng", "tự", "tin",
        "kiên", "nhẫn", "bình", "tĩnh", "chăm", "chỉ", "siêng", "lười", "thông", "minh", "khôn",
        "ngoan", "ngốc", "dại", "hiền", "lành", "dữ", "ác", "thiện", "trung", "thực", "dối", "trá",
        "tôn", "kính", "trọng", "mến", "yêu", "thích", "ghét", "khinh", "chán", "ngán", "hào", "hứng",
        "phấn", "khích", "thoải", "mái", "dễ", "chịu", "khó", "căng", "thẳng", "áp", "lực", "lo",
        "lắng", "sợ", "hãi", "hoang", "mang", "bối", "rối", "ngạc", "nhiên", "bất", "ngờ", "thất",
        "vọng", "hy", "hạnh", "phúc", "sung", "sướng", "khổ", "đau", "thương", "xót", "tiếc", "nuối",
        "hối", "hận", "xấu", "hổ", "ngại", "ngùng", "tự", "hào", "kiêu", "ngạo", "khiêm", "tốn",
        "gần", "xa", "bên", "cạnh", "xung", "quanh", "khắp", "nơi", "chốn", "vùng", "miền", "khu",
        "vực", "góc", "phía", "hướng", "chiều", "đỉnh", "đáy", "bờ", "mép", "viền", "giữa", "tâm",
        "điểm", "nét", "vết", "dấu", "hiệu", "bóng", "hình", "ảnh", "gương", "mẫu", "chuẩn", "mực",
        "mức", "độ", "tỷ", "lệ", "phần", "trăm", "lượng", "số", "khối", "kích", "thước", "tốc",
        "nhịp", "điệu", "bước", "ngoặt", "giai", "đoạn", "quá", "trình", "chu", "kỳ", "vòng", "lặp",
        "xuất", "hiện", "biến", "tồn", "tại", "diễn", "xảy", "kéo", "duy", "trì", "tiếp", "tục",
        "dừng", "ngừng", "nghỉ", "bắt", "khởi", "chấm", "dứt", "kết", "thúc", "quay", "trở", "về",
        "rời", "khỏi", "tìm", "kiếm", "gặp", "gỡ", "quen", "biết", "trò", "chuyện", "trao", "bàn",
        "luận", "tranh", "cãi", "đồng", "phản", "ủng", "hộ", "hợp", "tác", "chia", "sẻ", "nhường",
        "nhịn", "tha", "thứ", "xin", "lỗi", "cảm", "ơn", "chào", "mừng", "chúc", "tặng", "biếu",
        "mua", "bán", "thuê", "mượn", "vay", "trả", "nợ", "tiết", "kiệm", "chi", "dùng", "sử", "dụng",
        "hưởng", "thụ", "chịu", "đựng", "vượt", "qua", "trải", "nghiệm", "khám", "phá", "nghiên",
        "cứu", "học", "tập", "rèn", "luyện", "thực", "hành", "áp", "dụng", "kiểm", "tra", "chứng",
        "minh", "giải", "thích", "miêu", "tả", "trình", "bày", "thể", "hiện", "biểu", "đạt", "nhấn",
        "mạnh", "khẳng", "phủ", "nghi", "ngờ", "dự", "đoán", "ước", "lượng", "tính", "toán", "đo",
        "lường", "sắp", "xếp", "tổ", "chức", "quản", "điều", "khiển", "hướng", "dẫn", "chỉ", "đạo",
        "lãnh", "đạo", "phục", "vụ", "chăm", "sóc", "nuôi", "dưỡng", "dạy", "dỗ", "khuyên", "bảo",
        "nhắc", "nhở", "cảnh", "báo", "ngăn", "chặn", "cấm", "cho", "phép", "yêu", "cầu", "đề", "nghị",
        "mời", "rủ", "hứa", "hẹn", "thề", "cam", "kết", "đảm", "bảo", "chấp", "nhận", "từ", "chối",
        "tránh", "né", "trốn", "chạy", "đuổi", "theo", "bắt", "giữ", "thả", "cắt", "xé", "đập", "phá",
        "đốt", "cháy", "nấu", "nướng", "rửa", "giặt", "quét", "dọn", "lau", "chùi", "trồng", "tưới",
        "hái", "nhặt", "gặt", "nuôi", "săn", "bắn", "câu", "bơi", "lội", "bay", "nhảy", "leo", "trèo",
        "ngã", "té", "rơi", "đổ", "vỡ", "gãy", "rách", "hư", "hỏng", "lành", "khỏi", "mọc", "nở",
        "tàn", "héo", "chín", "xanh", "sống", "chín", "nguội", "tan", "chảy", "đông", "cứng", "mềm",
        "dẻo", "giòn", "dai", "trơn", "nhám", "sắc", "bén", "cùn", "nhọn", "tù", "thẳng", "cong",
        "tròn", "vuông", "méo", "lệch", "cân", "bằng", "nghiêng", "dốc", "phẳng", "gồ", "ghề",
        "chặt", "lỏng", "kín", "hở", "đầy", "vơi", "rỗng", "trống", "đông", "vắng", "ồn", "ào", "yên",
        "lặng", "tĩnh", "êm", "ái", "dịu", "dàng", "gay", "gắt", "mãnh", "liệt", "dữ", "dội",
        "sâu", "sắc", "nông", "cạn", "tinh", "tế", "thô", "kệch", "lịch", "sự", "văn", "minh",
        "hiện", "đại", "cổ", "điển", "truyền", "thống", "mộc", "mạc", "giản", "dị", "sang", "trọng",
        "giàu", "nghèo", "đắt", "rẻ", "lãi", "lỗ", "lời", "vốn", "thuế", "phí", "lương", "thưởng",
        "chút", "ít", "xíu", "tẹo", "khối", "đống", "bầy", "đàn", "nhóm", "đội", "ban", "bộ", "ngành",
        "hội", "đoàn", "đảng", "tổ", "lớp", "trường", "viện", "khoa", "phòng", "cơ", "quan", "công",
        "ty", "xưởng", "tiệm", "chợ", "siêu", "thị", "bệnh", "nhân", "bác", "sĩ", "thầy", "giáo",
        "sinh", "viên", "học", "trò", "khách", "chủ", "nhân", "lính", "tướng", "vua", "chúa", "thần",
        "tiên", "phật", "ma", "quỷ", "trời", "đất"
    ).apply {
        removeAll(RARE_TIER3_ONLY)
        removeAll(GHOST_AND_OCR_FRAGMENTS)
    }

    /**
     * Tier-2 High-Value Curated Bigrams (Bonus = +2500), merged with all 45,000+ entries in `vi_words.txt`.
     * Covers natural book/prose/academic collocations that frequently bridge OCR sliding-space splits.
     */
    private val CURATED_COMMON_BIGRAMS: List<String> = listOf(
        // Exact literary/psychology collocations & high-frequency structural pairs
        "có một", "một vài", "vài khía", "khía cạnh", "cạnh của", "của đặc", "đặc tính",
        "tính có", "có xu", "xu hướng", "hướng không", "không thay", "thay đổi", "đổi theo",
        "theo thời", "thời gian", "giống như", "như việc", "việc xác", "xác định", "định ai",
        "ai cao", "ai thấp", "thấp vậy", "nhưng dù", "dù cho", "cho có", "có nhiều", "nhiều hơn",
        "hơn nữa", "nữa những", "những phẩm", "phẩm chất", "chất và", "và đặc", "đặc điểm",
        "điểm cố", "cố định", "của một", "trong một", "với một", "là một", "như một", "về một",
        "cho một", "từ một", "đến một", "tới một", "bằng một", "thành một", "làm một", "chỉ một",
        "một số", "một ít", "một chút", "một cách", "một lần", "một phần", "một người", "một điều",
        "một việc", "một ngày", "một thời", "một khi", "một mặt", "một bên", "một mình", "một nửa",
        "có thể", "có lẽ", "có vẻ", "có sự", "có những", "có các", "có rất", "có ít", "có phần",
        "có nghĩa", "có ích", "có hại", "có mặt", "có sẵn", "có được", "có phải", "có không",
        "không có", "không thể", "không phải", "không chỉ", "không bao", "không hề", "không cần",
        "không muốn", "không biết", "không hiểu", "không nên", "không được", "không còn", "không sao",
        "nhiều người", "nhiều lần", "nhiều năm", "nhiều ngày", "nhiều thứ", "nhiều điều", "nhiều việc",
        "nhiều cách", "nhiều khi", "nhiều lúc", "rất nhiều", "quá nhiều", "bao nhiêu", "ít nhiều",
        "những người", "những điều", "những việc", "những gì", "những năm", "những ngày", "những lúc",
        "những thứ", "những điểm", "những đặc", "những yếu", "những khía", "những thay", "những cách",
        "chú ý", "ý tới", "ý đến", "ý kiến", "ý nghĩa", "ý thức", "ý muốn", "ý định", "ý tưởng",
        "tâm lí", "tâm lý", "lí học", "lý học", "tâm trạng", "tâm hồn", "tâm trí", "tâm tư",
        "ngày mai", "hôm qua", "hôm nay", "ngày nay", "ngày xưa", "ngày đó", "ngày đầu", "mỗi ngày",
        "tạo ra", "tạo nên", "nỗ lực", "của mỗi", "mỗi người", "điều đó", "điều này", "được tạo",
        "từng chi", "chi tiết", "cảm xúc", "cảm giác", "cảm thấy", "cảm nhận", "cảm ơn",
        "người bạn", "bạn cũ", "quay về", "trở về", "đi học", "đi làm", "đi lại", "đi qua",
        "trời ơi", "ông ấy", "bà ấy", "cô ấy", "anh ấy", "chị ấy", "em ấy", "người ấy", "lúc ấy",
        "khi ấy", "hôm ấy", "ngày ấy", "đêm ấy", "năm ấy", "nơi ấy", "thế ấy", "em à", "anh à",
        "mẹ à", "bố à", "bạn à", "to à", "nhỏ à", "thật à", "vậy à", "sao à", "đúng à",
        "lương y", "đông y", "tây y", "quân y", "thú y", "y học", "y tế", "y sĩ", "từ mẫu",
        "đều khác", "khác nhau", "giống nhau", "cùng nhau", "với nhau", "bên nhau", "gần nhau",
        "đàn ông", "đàn bà", "đàn anh", "đàn em", "phụ nữ", "nữ giới", "nam giới", "anh hùng",
        "con ong", "bàn ăn", "cơm ăn", "nhà ăn", "bạn ấy", "bạn đọc", "cánh đồng",
        "bản thân", "bản chất", "bản tính", "tính cách", "hành vi", "thói quen", "nhận thức",
        "suy nghĩ", "tư duy", "trí tuệ", "khả năng", "năng lực", "kỹ năng", "kinh nghiệm",
        "trải nghiệm", "phát triển", "tiến bộ", "cải thiện", "hoàn thiện", "ổn định", "quyết định",
        "khẳng định", "phủ định", "giả định", "dự định", "nhất định", "vô định", "bất định",
        "yếu tố", "nguyên nhân", "kết quả", "hậu quả", "hiệu quả", "tác động", "ảnh hưởng",
        "mối quan", "quan hệ", "liên quan", "tương quan", "tương tác", "tương đối", "tuyệt đối",
        "quan điểm", "trọng điểm", "ưu điểm", "nhược điểm", "khuyết điểm", "nhấn mạnh", "địa điểm",
        "thời điểm", "thời kỳ", "thời đại", "giai đoạn", "quá trình", "hành trình", "chương trình",
        "vấn đề", "chủ đề", "tiêu đề", "đề tài", "mục tiêu", "mục đích", "phương pháp", "giải pháp",
        "biện pháp", "cách thức", "hình thức", "nội dung", "cấu trúc", "hệ thống", "môi trường",
        "hoàn cảnh", "tình huống", "trường hợp", "điều kiện", "tiêu chuẩn", "nguyên tắc", "quy luật",
        "tự nhiên", "xã hội", "văn hóa", "lịch sử", "khoa học", "công nghệ", "kinh tế", "chính trị",
        "cuộc sống", "cuộc đời", "con người", "thế giới", "tương lai", "quá khứ", "hiện tại",
        "thực tế", "thực sự", "rõ ràng", "chính xác", "đầy đủ", "phù hợp", "cần thiết", "quan trọng",
        "đặc biệt", "phổ biến", "bình thường", "thông thường", "đơn giản", "phức tạp", "tích cực",
        "tiêu cực", "chủ động", "bị động", "tự tin", "tự do", "hạnh phúc", "thành công", "thất bại"
    )

    val isLoaded: Boolean
        get() = loaded

    val syllableCount: Int
        get() = rawAssetSyllables.size

    val scoredSyllableCount: Int
        get() = syllableScores.size

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
        syllableScores.clear()
        rawAssetSyllables.clear()
        bigramBonuses.clear()
        bigramIndex.clear()
        words.clear()

        // 1. Load raw asset syllables -> assign Tier 3 (+50) ONLY if NOT a ghost/OCR fragment
        BufferedReader(InputStreamReader(syllablesStream, Charsets.UTF_8), 32 * 1024).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val clean = normalizeKey(line)
                if (clean.isNotEmpty()) {
                    rawAssetSyllables.add(clean)
                    if (!isGhostSyllable(clean)) {
                        syllableScores[clean] = SCORE_RARE_VALID
                    }
                }
                line = reader.readLine()
            }
        }

        // 2. Load vi_words.txt -> populate Tier 2 Bigram Bonus (+2500) & count real syllable frequency
        val wordSyllableFreq = HashMap<String, Int>(4096)
        BufferedReader(InputStreamReader(wordsStream, Charsets.UTF_8), 32 * 1024).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val clean = normalizeKey(line)
                if (clean.isNotEmpty()) {
                    words.add(clean)
                    val spaceIdx = clean.indexOf(' ')
                    if (spaceIdx > 0) {
                        val secondSpaceIdx = clean.indexOf(' ', spaceIdx + 1)
                        if (secondSpaceIdx < 0) {
                            // Exact 2-syllable compound / collocation
                            bigramBonuses[clean] = BIGRAM_BONUS
                            val w1 = clean.substring(0, spaceIdx)
                            val w2 = clean.substring(spaceIdx + 1)
                            bigramIndex.getOrPut(w1) { HashSet(8) }.add(w2)
                        }
                        val parts = clean.split(' ')
                        for (part in parts) {
                            if (part.isNotEmpty()) {
                                wordSyllableFreq[part] = (wordSyllableFreq[part] ?: 0) + 1
                            }
                        }
                    } else {
                        wordSyllableFreq[clean] = (wordSyllableFreq[clean] ?: 0) + 1
                    }
                }
                line = reader.readLine()
            }
        }

        // 3. Promote real frequent syllables from vi_words.txt into Tier 1 (+1000)
        for ((syl, freq) in wordSyllableFreq) {
            if (isGhostSyllable(syl)) continue
            if (syl in RARE_TIER3_ONLY) {
                syllableScores[syl] = SCORE_RARE_VALID
            } else if (freq >= 1) {
                syllableScores[syl] = SCORE_COMMON
            }
        }

        // 4. Overlay curated Tier 1 common syllables (+1000)
        for (common in CURATED_COMMON_SYLLABLES) {
            val clean = normalizeKey(common)
            if (clean.isNotEmpty() && !isGhostSyllable(clean) && clean !in RARE_TIER3_ONLY) {
                syllableScores[clean] = SCORE_COMMON
            }
        }

        // 5. Enforce Tier 3 rare overrides and Tier 4 ghost removals
        for (rare in RARE_TIER3_ONLY) {
            val clean = normalizeKey(rare)
            if (clean.isNotEmpty() && clean !in GHOST_AND_OCR_FRAGMENTS) {
                syllableScores[clean] = SCORE_RARE_VALID
            }
        }
        for (ghost in GHOST_AND_OCR_FRAGMENTS) {
            syllableScores.remove(normalizeKey(ghost))
        }

        // 6. Overlay curated Tier 2 high-value prose bigrams (+2500)
        for (bg in CURATED_COMMON_BIGRAMS) {
            val clean = normalizeKey(bg)
            if (clean.isNotEmpty()) {
                words.add(clean)
                bigramBonuses[clean] = BIGRAM_BONUS
                val spaceIdx = clean.indexOf(' ')
                if (spaceIdx > 0 && clean.indexOf(' ', spaceIdx + 1) < 0) {
                    val w1 = clean.substring(0, spaceIdx)
                    val w2 = clean.substring(spaceIdx + 1)
                    bigramIndex.getOrPut(w1) { HashSet(8) }.add(w2)
                }
            }
        }
    }

    /**
     * Checks whether a lowercase NFC syllable is a theoretical/OCR ghost fragment.
     * Filters explicit ghost tokens + theoretical vowel-initial rhymes that aren't real Vietnamese words.
     */
    private fun isGhostSyllable(lowerNfc: String): Boolean {
        if (lowerNfc.isEmpty()) return true
        if (lowerNfc in GHOST_AND_OCR_FRAGMENTS) return true
        if (lowerNfc in CURATED_COMMON_SYLLABLES || lowerNfc in REAL_VOWEL_INITIAL_SYLLABLES) {
            return false
        }
        // Any vowel-initial syllable NOT in real Vietnamese vowel-initial words is a ghost rhyme tail
        // (e.g. "ột", "ều", "ểm", "ững", "ới", "iền", "uổi", "ường", "ếch" is in REAL set, etc.)
        if (isVietnameseVowel(lowerNfc[0])) {
            return true
        }
        return false
    }

    private fun isVietnameseVowel(c: Char): Boolean {
        return when (c) {
            'a', 'à', 'á', 'ả', 'ã', 'ạ',
            'ă', 'ằ', 'ắ', 'ẳ', 'ẵ', 'ặ',
            'â', 'ầ', 'ấ', 'ẩ', 'ẫ', 'ậ',
            'e', 'è', 'é', 'ẻ', 'ẽ', 'ẹ',
            'ê', 'ề', 'ế', 'ể', 'ễ', 'ệ',
            'i', 'ì', 'í', 'ỉ', 'ĩ', 'ị',
            'o', 'ò', 'ó', 'ỏ', 'õ', 'ọ',
            'ô', 'ồ', 'ố', 'ổ', 'ỗ', 'ộ',
            'ơ', 'ờ', 'ớ', 'ở', 'ỡ', 'ợ',
            'u', 'ù', 'ú', 'ủ', 'ũ', 'ụ',
            'ư', 'ừ', 'ứ', 'ử', 'ữ', 'ự',
            'y', 'ỳ', 'ý', 'ỷ', 'ỹ', 'ỵ' -> true
            else -> false
        }
    }

    /**
     * O(1) Unigram Frequency Score:
     * - +1000 (Common)
     * - +50   (Rare valid)
     * - -5000 (Ghost / OCR artifact / invalid)
     */
    fun getSyllableScore(token: String): Int {
        if (token.isEmpty()) return SCORE_INVALID_GHOST
        return getSyllableScoreNormalized(normalizeKey(token))
    }

    /**
     * Zero-allocation hot-path lookup when caller already normalized lowercase NFC.
     */
    fun getSyllableScoreNormalized(lowerNfc: String): Int {
        return syllableScores[lowerNfc] ?: SCORE_INVALID_GHOST
    }

    /**
     * O(1) Bigram Bonus (+2500 if pair forms a known compound/collocation, else 0).
     */
    fun getBigramBonus(first: String, second: String): Int {
        if (first.isEmpty() || second.isEmpty()) return 0
        return getBigramBonusNormalized(normalizeKey(first), normalizeKey(second))
    }

    fun getBigramBonusNormalized(firstLower: String, secondLower: String): Int {
        if (firstLower.isEmpty() || secondLower.isEmpty()) return 0
        return if (bigramIndex[firstLower]?.contains(secondLower) == true) BIGRAM_BONUS else 0
    }

    fun isRealSyllableNormalized(lowerNfc: String): Boolean {
        return syllableScores.containsKey(lowerNfc)
    }

    fun isValidSyllable(token: String): Boolean {
        if (token.isEmpty()) return false
        return syllableScores.containsKey(normalizeKey(token))
    }

    fun isKnownWord(phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        return words.contains(normalizeKey(phrase))
    }

    fun isKnownBigram(first: String, second: String): Boolean {
        if (first.isEmpty() || second.isEmpty()) return false
        return bigramIndex[normalizeKey(first)]?.contains(normalizeKey(second)) == true
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

    internal fun normalizeKey(raw: String): String {
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
