package com.example.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/**
 * Provides a clean, multi-page (3-chapter) Vietnamese sample book with rich dialogue
 * across 7 distinct characters so users can immediately try reading, horizontal page-flip,
 * Offline TTS, 7-character voice scanning, pitch tuning, and ambient background music.
 */
object SampleBookSeeder {

    const val SAMPLE_BOOK_ID = "sample_dem_trang_doi_gio_v1"
    const val SAMPLE_BOOK_TITLE = "Đêm Trăng Đồi Gió"
    const val SAMPLE_BOOK_AUTHOR = "Nguyễn Hoàng Vũ"

    data class SampleChapter(
        val title: String,
        val paragraphs: List<String>
    )

    val SAMPLE_CHAPTERS: List<SampleChapter> = listOf(
        SampleChapter(
            title = "Chương 1: Quán Trà Cuối Dốc",
            paragraphs = listOf(
                "Chiều cuối thu, sương mỏng bắt đầu giăng kín con dốc lát đá dẫn lên Đồi Gió. Gió thổi qua những tán thông già tạo thành âm thanh rì rào như một bản nhạc không lời.",
                "Minh Khang dừng bước trước mái hiên gỗ nâu cũ kỹ, khẽ phủi lớp bụi đường trên vai áo. Từ bên trong quán trà, ánh đèn vàng ấm áp hắt ra ngoài sân đá.",
                "Ông Bảy đang châm thêm nước sôi vào ấm đất nung, thấy khách quen liền ngẩng đầu mỉm cười chào đón.",
                "Ông Bảy trầm giọng nói: \"Minh Khang về sớm hơn mọi hôm đấy. Ngoài đồi gió đang trở lạnh, vào trong uống chén trà nóng cho ấm người đi cháu.\"",
                "Minh Khang cười đáp: \"Cháu nghe Lan Chi nhắn tối nay mọi người tụ họp ở đây nên tranh thủ ghé sớm. Hôm nay quán có loại trà sen mới phải không ạ?\"",
                "Vừa dứt lời, cánh cửa gỗ khẽ kêu cọt kẹt. Lan Chi bước vào, trên tay ôm một tập sổ ghi chép bọc vải lanh màu xanh nhạt.",
                "Lan Chi vui vẻ hỏi: \"Minh Khang đến rồi à? Thảo Vy và Hoàng Nam bảo năm phút nữa sẽ mang đàn cùng giá vẽ lên tới nơi.\"",
                "Ông Bảy rót hai chén trà thơm nghi ngút đặt xuống bàn gỗ, ánh mắt hiền hậu nhìn hai người trẻ đang ríu rít trò chuyện.",
                "Minh Khang nhấp một ngụm trà, chậm rãi kể lại chuyến đi sưu tầm tư liệu cũ ở thị trấn ven biển suốt tuần vừa qua.",
                "Lan Chi chăm chú lắng nghe, thi thoảng lại mở cuốn sổ tay ghi nhanh vài dòng nhận xét cẩn thận."
            )
        ),
        SampleChapter(
            title = "Chương 2: Bức Tranh Và Bản Nhạc",
            paragraphs = listOf(
                "Chưa đầy mười phút sau, tiếng bước chân rộn ràng vang lên ngoài bậc thềm. Thảo Vy đẩy cửa bước vào trước, theo sau là Hoàng Nam mang theo cây đàn gỗ cũ.",
                "Thảo Vy mỉm cười nói: \"Trời chiều nay đẹp quá, tôi vừa kịp phác thảo xong bức tranh ráng chiều trên Đồi Gió trước khi mặt trời khuất hẳn.\"",
                "Hoàng Nam đặt chiếc hộp đàn xuống ghế, hào hứng đáp: \"Tôi cũng vừa viết xong đoạn điệp khúc mới. Chờ Cô Hương và Tuấn Kiệt tới đủ mặt, tôi sẽ đàn cho cả nhóm nghe thử.\"",
                "Minh Khang ghé lại gần xem bức phác thảo của Thảo Vy, khẽ gật đầu khen ngợi đường nét thanh thoát và sắc màu dịu mắt.",
                "Lan Chi khẽ nói: \"Cô Hương vừa nhắn đang mang theo cuốn sách cổ tìm thấy trong kho thư viện thị trấn. Tuấn Kiệt đi đón cô ấy ngay dưới chân dốc.\"",
                "Ngoài hiên, ánh trăng non bắt đầu nhô lên khỏi rặng thông, phủ một lớp ánh sáng bạc êm dịu xuống khu vườn nhỏ.",
                "Ông Bảy chậm rãi đặt thêm chiếc đèn lồng giấy lên bàn, ánh lửa nhỏ rung rinh khiến căn phòng gỗ trở nên ấm cúng lạ thường.",
                "Thảo Vy hỏi: \"Ông Bảy ơi, cuốn sách cổ mà Cô Hương nhắc tới có phải ghi lại câu chuyện về chiếc chuông gió trên đỉnh đồi không ạ?\"",
                "Ông Bảy mỉm cười đáp: \"Đúng rồi cháu ạ. Ngày xưa, mỗi khi trăng tròn tháng mười, tiếng chuông ấy vang vọng khắp thung lũng như lời chúc bình an.\"",
                "Hoàng Nam khẽ gảy vài nốt trầm trên dây đàn, giai điệu nhẹ nhàng hòa cùng tiếng gió đêm khiến ai nấy đều lặng yên lắng nghe."
            )
        ),
        SampleChapter(
            title = "Chương 3: Lời Hẹn Dưới Ánh Trăng",
            paragraphs = listOf(
                "Tiếng cười nói thân quen vang lên ngoài cổng gỗ. Cô Hương bước vào với dáng vẻ điềm đạm, còn Tuấn Kiệt tay xách giỏ bánh nóng thơm lừng mùi quế.",
                "Cô Hương dịu dàng nói: \"Xin lỗi để mọi người chờ lâu nhé. Tuấn Kiệt ghé tiệm bánh đầu phố mua chút đồ ngọt để cả nhóm thưởng thức cùng trà nóng.\"",
                "Tuấn Kiệt cười đáp: \"Bánh vừa ra lò còn nóng hổi đấy! Minh Khang với Hoàng Nam nhớ chừa phần cho Ông Bảy nữa nhé.\"",
                "Cô Hương nhẹ nhàng đặt cuốn sách bìa da cũ kỹ lên giữa bàn gỗ. Mọi người cùng xích lại gần, ánh mắt đầy háo hức.",
                "Minh Khang mở từng trang giấy ngả vàng, khẽ đọc những dòng chữ nắn nót kể về những người nghệ sĩ từng tụ họp trên Đồi Gió cách đây nửa thế kỷ.",
                "Lan Chi thốt lên: \"Thì ra quán trà này của gia đình Ông Bảy từng là nơi khởi nguồn cho biết bao câu chuyện và giai điệu đẹp đến thế!\"",
                "Thảo Vy gật đầu bảo: \"Em sẽ vẽ thêm minh họa cho cuốn kỷ yếu nhỏ của nhóm mình, còn Cô Hương giúp biên tập lại các ghi chép nhé.\"",
                "Tuấn Kiệt hào hứng nói: \"Nếu vậy tôi sẽ ghi âm lại bản nhạc của Hoàng Nam và lời kể của Ông Bảy để lưu giữ làm kỷ niệm chung.\"",
                "Đêm càng về khuya, ánh trăng trên Đồi Gió càng sáng trong vắt. Bên ấm trà thơm, bảy con người cùng nhau trò chuyện say sưa về những dự định phía trước.",
                "Minh Khang nhìn ra khung cửa sổ lộng gió, thầm nghĩ rằng những trang sách đẹp nhất đôi khi không chỉ nằm trên giấy mực, mà còn sống mãi trong những buổi tối bình yên bên bạn bè."
            )
        )
    )

    /**
     * Generates a sleek, minimalist Apple Books–style cover JPEG (`200x300` px) on disk.
     */
    fun generateSampleCoverImage(destFile: File): String? {
        return runCatching {
            val width = 220
            val height = 320
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)

                // Deep twilight blue-to-warm-slate gradient
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, height.toFloat(),
                        intArrayOf(
                            Color.parseColor("#1B2845"),
                            Color.parseColor("#274060"),
                            Color.parseColor("#3E5C76")
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

                // Soft glowing crescent moon illustration
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(42, 248, 225, 160)
                }
                canvas.drawCircle(width * 0.68f, height * 0.27f, 44f, glowPaint)

                val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F6DF9F")
                }
                canvas.drawCircle(width * 0.68f, height * 0.27f, 24f, moonPaint)

                val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#213452")
                }
                canvas.drawCircle(width * 0.74f, height * 0.24f, 21f, cutoutPaint)

                // Subtle hills silhouette at bottom
                val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(90, 14, 22, 38)
                }
                canvas.drawCircle(width * 0.25f, height * 1.06f, 130f, hillPaint)
                canvas.drawCircle(width * 0.85f, height * 1.08f, 120f, hillPaint)

                // Crisp serif-like book title
                val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 23f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                }
                canvas.drawText("Đêm Trăng", 20f, height * 0.57f, titlePaint)
                canvas.drawText("Đồi Gió", 20f, height * 0.67f, titlePaint)

                // Subtle divider line
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E6C880")
                    strokeWidth = 2f
                }
                canvas.drawLine(20f, height * 0.73f, 82f, height * 0.73f, linePaint)

                // Author label
                val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(210, 235, 240, 248)
                    textSize = 12.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
                canvas.drawText(SAMPLE_BOOK_AUTHOR, 20f, height * 0.82f, authorPaint)

                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(175, 246, 223, 159)
                    textSize = 10.5f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                }
                canvas.drawText("SÁCH MẪU • 3 CHƯƠNG", 20f, 30f, badgePaint)

                destFile.parentFile?.mkdirs()
                FileOutputStream(destFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                destFile.absolutePath
            } finally {
                bmp.recycle()
            }
        }.getOrNull()
    }
}
