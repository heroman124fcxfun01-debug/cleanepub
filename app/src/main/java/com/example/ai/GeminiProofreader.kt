package com.example.ai

import android.util.Log
import com.example.pipeline.BookContext
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GeminiProofreader"
private const val MODEL_NAME = "gemini-4-carbon"
private const val AI_TIMEOUT_MS = 60_000L

data class IndexedParagraph(
    val index: Int,
    val text: String
)

data class ParagraphCorrection(
    val index: Int,
    val correctedText: String,
    val reason: String
)

class GeminiProofreader {

    private val contextSchema = Schema.obj(
        properties = mapOf(
            "genre" to Schema.string(
                description = "Thể loại chính của cuốn sách bằng tiếng Việt ngắn gọn (ví dụ: Tiểu thuyết kiếm hiệp, Văn học hiện đại, Kinh tế, Lịch sử, Trinh thám)"
            ),
            "properNouns" to Schema.array(
                items = Schema.string(),
                description = "Danh sách tên riêng quan trọng (nhân vật, địa danh, tổ chức, chiêu thức, thuật ngữ đặc thù) cần giữ nguyên, không được sửa nhầm"
            ),
            "summaryNote" to Schema.string(
                description = "Ghi chú ngắn gọn 1 câu về văn phong và bối cảnh để hỗ trợ hiệu đính"
            )
        )
    )

    private val proofreadSchema = Schema.obj(
        properties = mapOf(
            "corrections" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "index" to Schema.integer(description = "Chỉ số [index] của đoạn văn có lỗi"),
                        "corrected" to Schema.string(description = "Toàn bộ nội dung đoạn văn sau khi đã sửa chính tả và dấu tiếng Việt"),
                        "reason" to Schema.string(description = "Mô tả cực ngắn lỗi đã sửa (ví dụ: Sửa dấu hỏi/ngã, Sửa lỗi chính tả OCR, Sửa phụ âm đầu)")
                    )
                ),
                description = "Chỉ liệt kê những đoạn văn thực sự có lỗi chính tả, lỗi dấu tiếng Việt hoặc lỗi OCR. Bỏ qua đoạn đã đúng."
            )
        )
    )

    private val contextModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.2f
                responseMimeType = "application/json"
                responseSchema = contextSchema
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            },
            systemInstruction = content {
                text(
                    "Bạn là chuyên gia biên tập và xử lý ngôn ngữ tự nhiên tiếng Việt cho sách điện tử. " +
                        "Hãy phân tích mẫu văn bản được cung cấp để xác định chính xác thể loại sách " +
                        "và trích xuất danh sách tên riêng (tên nhân vật, địa danh, tổ chức, biệt danh, " +
                        "từ Hán-Việt đặc thù) tối đa 40 mục quan trọng nhất để bảo vệ khi hiệu đính."
                )
            }
        )
    }

    private val proofreadModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.1f
                responseMimeType = "application/json"
                responseSchema = proofreadSchema
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.HIGH
                }
            },
            systemInstruction = content {
                text(
                    "Bạn là biên tập viên hiệu đính chính tả tiếng Việt chuyên nghiệp cho sách điện tử. " +
                        "Quy tắc bắt buộc:\n" +
                        "1. CHỈ sửa lỗi chính tả tiếng Việt rõ ràng, lỗi dấu thanh (hỏi/ngã, sắc/huyền/nặng), lỗi gõ Telex/VNI, lỗi OCR (như l/I, 0/o, rn/m, dính/tách từ sai).\n" +
                        "2. TUYỆT ĐỐI KHÔNG thay đổi văn phong, không diễn đạt lại câu, không đổi từ đồng nghĩa, không thêm hoặc bớt ý.\n" +
                        "3. TUYỆT ĐỐI KHÔNG sửa các Tên riêng nằm trong danh sách ngữ cảnh được cung cấp.\n" +
                        "4. Chỉ trả về những đoạn văn thực sự có sửa đổi trong mảng corrections. Nếu tất cả đều đúng, trả về mảng rỗng."
                )
            }
        )
    }

    suspend fun analyzeGlobalContext(sampleText: String): BookContext {
        if (sampleText.isBlank()) {
            return BookContext(
                genre = "Sách tiếng Việt",
                properNouns = emptyList(),
                summaryNote = "Văn bản tiêu chuẩn"
            )
        }
        val trimmedSample = sampleText.take(6500)
        val prompt = buildString {
            appendLine("Phân tích ngữ cảnh toàn cục cho cuốn sách tiếng Việt từ các đoạn trích sau:")
            appendLine("---")
            appendLine(trimmedSample)
            appendLine("---")
        }

        return try {
            val response = withTimeout(AI_TIMEOUT_MS) {
                contextModel.generateContent(prompt)
            }
            val jsonText = response.text
                ?: throw IllegalStateException("AI không trả về dữ liệu ngữ cảnh.")
            val json = JSONObject(jsonText)
            val genre = json.optString("genre", "Sách tiếng Việt").ifBlank { "Sách tiếng Việt" }
            val note = json.optString("summaryNote", "")
            val nounsArray = json.optJSONArray("properNouns") ?: JSONArray()
            val nouns = buildList {
                for (i in 0 until nounsArray.length()) {
                    val item = nounsArray.optString(i).trim()
                    if (item.isNotEmpty() && !contains(item)) {
                        add(item)
                    }
                }
            }.take(45)

            BookContext(
                genre = genre,
                properNouns = nouns,
                summaryNote = note
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Context analysis timed out", e)
            throw IllegalStateException("Phân tích ngữ cảnh AI bị quá thời gian (60 giây).", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Context analysis failed", e)
            throw e
        }
    }

    suspend fun proofreadParagraphBatch(
        paragraphs: List<IndexedParagraph>,
        bookContext: BookContext
    ): List<ParagraphCorrection> {
        if (paragraphs.isEmpty()) return emptyList()

        val nounsLine = if (bookContext.properNouns.isEmpty()) {
            "(Không có)"
        } else {
            bookContext.properNouns.joinToString(", ")
        }

        val prompt = buildString {
            appendLine("Ngữ cảnh sách:")
            appendLine("- Thể loại: ${bookContext.genre}")
            if (bookContext.summaryNote.isNotBlank()) {
                appendLine("- Lưu ý văn phong: ${bookContext.summaryNote}")
            }
            appendLine("- Danh sách Tên riêng CẤM SỬA NHẦM: $nounsLine")
            appendLine()
            appendLine("Kiểm tra và hiệu đính chính tả tiếng Việt cho các đoạn sau:")
            paragraphs.forEach { item ->
                appendLine("[${item.index}] ${item.text}")
            }
        }

        return try {
            val response = withTimeout(AI_TIMEOUT_MS) {
                proofreadModel.generateContent(prompt)
            }
            val jsonText = response.text ?: return emptyList()
            val root = JSONObject(jsonText)
            val arr = root.optJSONArray("corrections") ?: return emptyList()
            val originalByIndex = paragraphs.associateBy { it.index }

            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val idx = obj.optInt("index", -1)
                    val corrected = obj.optString("corrected", "").trim()
                    val reason = obj.optString("reason", "Hiệu đính chính tả")
                        .ifBlank { "Hiệu đính chính tả" }
                    val original = originalByIndex[idx]?.text ?: continue

                    // Accept only genuine non-empty modifications
                    if (corrected.isNotEmpty() && corrected != original) {
                        add(
                            ParagraphCorrection(
                                index = idx,
                                correctedText = corrected,
                                reason = reason
                            )
                        )
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Proofreading batch timed out", e)
            throw IllegalStateException("Hiệu đính AI bị quá thời gian (60 giây).", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Proofreading batch failed", e)
            throw e
        }
    }
}
