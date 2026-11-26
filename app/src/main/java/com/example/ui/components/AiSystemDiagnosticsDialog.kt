package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audio.AiDiagnosticTestResult
import com.example.audio.AiSystemDiagnosticReport
import com.example.audio.ModelFileEntry
import com.example.audio.OnnxModelAssetHelper
import com.example.audio.OnnxVoiceEngineMode
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosRed
import com.example.ui.theme.NeoInkLight

/**
 * Hộp thoại "Kiểm tra hệ thống AI" (Swiss Neo-Brutalist 3D Paper & Bookbinder):
 * 1. Liệt kê toàn bộ danh sách file & dung lượng (MB) thực tế trong `assets/models` và `context.filesDir/models`.
 * 2. Hiển thị trạng thái `OrtEnvironment`, ánh xạ model, chữ ký input/output của mọi `OrtSession` đã nạp.
 * 3. Chạy thử trực tiếp MediaCodec, Tạo giọng (`speaker_encoder.onnx`), và Đọc câu mẫu ONNX TTS;
 *    bắt trọn vẹn `e.toString()` và `e.cause?.message` nguyên văn để người dùng đọc & sao chép.
 */
@Composable
fun AiSystemDiagnosticsDialog(
    visible: Boolean,
    report: AiSystemDiagnosticReport?,
    isRunning: Boolean,
    testResult: AiDiagnosticTestResult?,
    lastVerbatimError: String?,
    onRefresh: (forceResync: Boolean) -> Unit,
    onRunMediaCodecTest: (Uri) -> Unit,
    onRunVoiceCreateTest: () -> Unit,
    onRunSentenceReadTest: () -> Unit,
    onStopAudio: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val context = LocalContext.current
    val mediaCodecPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onRunMediaCodecTest(uri)
        }
    }

    LaunchedEffect(visible) {
        if (visible && report == null && !isRunning) {
            onRefresh(false)
        }
    }

    val paperBg = neoPaperSurfaceColor()
    val trayBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val shadowCol = neoHardShadowColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    fun copyFullDiagnosticLog() {
        val fullText = buildString {
            if (report != null) {
                appendLine(report.toPlainTextReport())
            } else {
                appendLine("(Chưa có báo cáo quét hệ thống)")
            }
            if (testResult != null) {
                appendLine()
                appendLine("=== KẾT QUẢ CHẠY THỬ: ${testResult.title} (${testResult.elapsedMs}ms) ===")
                appendLine(testResult.detailLog)
            }
            if (!lastVerbatimError.isNullOrBlank()) {
                appendLine()
                appendLine("=== LỖI GẦN NHẤT TRONG STUDIO ===")
                appendLine(lastVerbatimError)
            }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("VieNeu_AI_Diagnostics", fullText))
        Toast.makeText(context, "Đã sao chép toàn bộ log kiểm tra AI", Toast.LENGTH_SHORT).show()
    }

    Dialog(
        onDismissRequest = {
            onStopAudio()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 580.dp)
                .fillMaxHeight(0.90f)
                .padding(end = 3.dp, bottom = 3.dp)
                .neoHardOffsetShadow(cornerRadius = 18.dp, offsetX = 3.dp, offsetY = 3.dp, shadowColor = shadowCol)
                .clip(RoundedCornerShape(18.dp))
                .background(paperBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(18.dp))
                .testTag("ai_system_diagnostics_dialog")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(IosBlue)
                                .border(1.5.dp, borderCol, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Kiểm tra hệ thống AI",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "assets/models • filesDir/models • MediaCodec • ONNX",
                                fontSize = 10.sp,
                                color = secondaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NeoBrutalistPressBox(
                            onClick = ::copyFullDiagnosticLog,
                            isPill = true,
                            shadowOffset = 2.dp,
                            pressDepth = 1.5.dp,
                            borderWidth = 1.2.dp,
                            containerColor = trayBg,
                            borderColor = borderCol,
                            shadowColor = shadowCol,
                            modifier = Modifier
                                .height(30.dp)
                                .testTag("ai_diag_copy_log_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Sao chép toàn bộ log",
                                    tint = IosBlue,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Chép log",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryInk
                                )
                            }
                        }

                        Ios3DCircleIconButton(
                            onClick = {
                                onStopAudio()
                                onDismiss()
                            },
                            size = 30.dp,
                            modifier = Modifier.testTag("ai_diag_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Đóng kiểm tra hệ thống AI",
                                tint = primaryInk,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp, color = borderCol.copy(alpha = 0.25f))

                // Scrollable Body
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Trạng thái tổng quan & nút Quét lại / Đồng bộ
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(trayBg)
                                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val ready = report?.engineMode == OnnxVoiceEngineMode.ONNX_READY
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (ready) IosGreen else IosRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = when (report?.engineMode) {
                                            OnnxVoiceEngineMode.ONNX_READY -> "ONNX Engine: Sẵn sàng"
                                            OnnxVoiceEngineMode.SAFE_TTS_FALLBACK -> "ONNX Engine: Chưa sẵn sàng (Fallback)"
                                            else -> "ONNX Engine: Đang kiểm tra…"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (ready) IosGreen else IosRed
                                    )
                                }
                                if (isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = IosBlue
                                    )
                                }
                            }

                            Text(
                                text = "OrtEnvironment: ${report?.ortEnvironmentStatus ?: "Đang đọc…"}",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = primaryInk
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NeoBrutalistPressBox(
                                    onClick = { onRefresh(false) },
                                    enabled = !isRunning,
                                    isPill = true,
                                    shadowOffset = 2.dp,
                                    pressDepth = 1.5.dp,
                                    borderWidth = 1.2.dp,
                                    containerColor = paperBg,
                                    borderColor = borderCol,
                                    shadowColor = shadowCol,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .testTag("ai_diag_refresh_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            tint = IosBlue,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "Quét lại",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryInk
                                        )
                                    }
                                }

                                NeoBrutalistPressBox(
                                    onClick = { onRefresh(true) },
                                    enabled = !isRunning,
                                    isPill = true,
                                    shadowOffset = 2.dp,
                                    pressDepth = 1.5.dp,
                                    borderWidth = 1.2.dp,
                                    containerColor = IosBlue,
                                    borderColor = borderCol,
                                    shadowColor = shadowCol,
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .height(32.dp)
                                        .testTag("ai_diag_resync_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Sync,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "Đồng bộ lại models",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Nút chạy thử trực tiếp: MediaCodec / Tạo giọng / Đọc câu mẫu
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(trayBg)
                                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Chạy thử & Bắt lỗi gốc (MediaCodec / OrtSession)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryInk
                            )
                            Text(
                                text = "Câu mẫu đọc thử: \"${OnnxModelAssetHelper.AI_VOICE_TEST_SENTENCE}\"",
                                fontSize = 10.sp,
                                color = secondaryInk
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                NeoBrutalistPressBox(
                                    onClick = { mediaCodecPicker.launch(arrayOf("audio/*")) },
                                    enabled = !isRunning,
                                    isPill = true,
                                    shadowOffset = 2.dp,
                                    pressDepth = 1.5.dp,
                                    borderWidth = 1.2.dp,
                                    containerColor = paperBg,
                                    borderColor = borderCol,
                                    shadowColor = shadowCol,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .testTag("ai_diag_test_mediacodec")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AudioFile,
                                            contentDescription = null,
                                            tint = IosBlue,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "MediaCodec",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryInk,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                NeoBrutalistPressBox(
                                    onClick = onRunVoiceCreateTest,
                                    enabled = !isRunning,
                                    isPill = true,
                                    shadowOffset = 2.dp,
                                    pressDepth = 1.5.dp,
                                    borderWidth = 1.2.dp,
                                    containerColor = IosOrange,
                                    borderColor = borderCol,
                                    shadowColor = shadowCol,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .testTag("ai_diag_test_create_voice")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = NeoInkLight,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "Tạo giọng",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = NeoInkLight,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                NeoBrutalistPressBox(
                                    onClick = onRunSentenceReadTest,
                                    enabled = !isRunning,
                                    isPill = true,
                                    shadowOffset = 2.dp,
                                    pressDepth = 1.5.dp,
                                    borderWidth = 1.2.dp,
                                    containerColor = IosGreen,
                                    borderColor = borderCol,
                                    shadowColor = shadowCol,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .testTag("ai_diag_test_read_sentence")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "Đọc mẫu",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            // Kết quả chạy thử nguyên văn
                            if (testResult != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (testResult.success) IosGreen.copy(alpha = 0.10f)
                                            else IosRed.copy(alpha = 0.10f)
                                        )
                                        .border(
                                            width = 1.2.dp,
                                            color = if (testResult.success) IosGreen else IosRed,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .padding(9.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${if (testResult.success) "✓" else "✗"} ${testResult.title} (${testResult.elapsedMs} ms)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (testResult.success) IosGreen else IosRed
                                    )
                                    Text(
                                        text = testResult.detailLog,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = primaryInk
                                    )
                                }
                            }

                            if (!lastVerbatimError.isNullOrBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(IosRed.copy(alpha = 0.09f))
                                        .border(1.2.dp, IosRed, RoundedCornerShape(10.dp))
                                        .padding(9.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Lỗi gần nhất trong Studio (nguyên văn):",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = IosRed
                                    )
                                    Text(
                                        text = lastVerbatimError,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = primaryInk
                                    )
                                }
                            }
                        }

                        // 3. Danh sách file thực tế trong assets/models
                        DiagnosticFileListCard(
                            title = "1. Thư mục assets/models (trong APK)",
                            subtitle = if (report != null) {
                                "${report.assetsModelsFiles.size} file • Tổng ${AiSystemDiagnosticReport.formatMb(report.totalAssetsBytes)}"
                            } else "Đang quét…",
                            emptyMessage = "TRỐNG — APK hiện tại chưa đóng gói file model nào trong assets/models/. Hãy build APK qua GitHub Actions (build-apk-v2.yml).",
                            files = report?.assetsModelsFiles.orEmpty()
                        )

                        // 4. Danh sách file thực tế trong context.filesDir/models
                        DiagnosticFileListCard(
                            title = "2. Thư mục context.filesDir/models (bộ nhớ máy)",
                            subtitle = if (report != null) {
                                "${report.filesDirModelsFiles.size} file • Tổng ${AiSystemDiagnosticReport.formatMb(report.totalFilesDirBytes)}\n${report.filesDirModelsPath}"
                            } else "Đang quét…",
                            emptyMessage = "TRỐNG — Chưa có file nào được sao chép sang context.filesDir/models/.",
                            files = report?.filesDirModelsFiles.orEmpty()
                        )

                        // 5. Ánh xạ model & OrtSessions đã nạp + Lỗi nạp nguyên văn
                        if (report != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(trayBg)
                                    .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "3. Ánh xạ Model & OrtSession đã nạp",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = primaryInk
                                )
                                report.mappedRoles.forEach { (role, mappedName) ->
                                    Text(
                                        text = "• $role → $mappedName",
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = primaryInk
                                    )
                                }

                                HorizontalDivider(thickness = 0.8.dp, color = borderCol.copy(alpha = 0.2f))

                                Text(
                                    text = "OrtSession đã nạp thành công (${report.loadedSessions.size}):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IosGreen
                                )
                                if (report.loadedSessions.isEmpty()) {
                                    Text(
                                        text = "(Chưa có OrtSession nào được nạp)",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = secondaryInk
                                    )
                                } else {
                                    report.loadedSessions.forEach { (role, sig) ->
                                        Text(
                                            text = "✓ [$role] $sig",
                                            fontSize = 9.5.sp,
                                            lineHeight = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = primaryInk
                                        )
                                    }
                                }

                                if (report.metadataSummary.isNotEmpty()) {
                                    HorizontalDivider(thickness = 0.8.dp, color = borderCol.copy(alpha = 0.2f))
                                    Text(
                                        text = "Metadata (config / heads / tokenizer / voices):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = IosBlue
                                    )
                                    report.metadataSummary.forEach { (key, value) ->
                                        Text(
                                            text = "• $key: $value",
                                            fontSize = 9.5.sp,
                                            lineHeight = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = primaryInk
                                        )
                                    }
                                }

                                if (report.copyErrors.isNotEmpty()) {
                                    HorizontalDivider(thickness = 0.8.dp, color = IosRed.copy(alpha = 0.35f))
                                    Text(
                                        text = "Lỗi sao chép assets → filesDir:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = IosRed
                                    )
                                    report.copyErrors.forEach { err ->
                                        Text(
                                            text = "✗ $err",
                                            fontSize = 9.5.sp,
                                            lineHeight = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = IosRed
                                        )
                                    }
                                }

                                if (report.sessionErrors.isNotEmpty()) {
                                    HorizontalDivider(thickness = 0.8.dp, color = IosRed.copy(alpha = 0.35f))
                                    Text(
                                        text = "Lỗi nạp OrtEnvironment / OrtSession (nguyên văn):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = IosRed
                                    )
                                    report.sessionErrors.forEach { (role, err) ->
                                        Text(
                                            text = "✗ [$role]\n$err",
                                            fontSize = 9.5.sp,
                                            lineHeight = 13.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = IosRed
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticFileListCard(
    title: String,
    subtitle: String,
    emptyMessage: String,
    files: List<ModelFileEntry>
) {
    val trayBg = neoSecondaryPaperColor()
    val paperBg = neoPaperSurfaceColor()
    val borderCol = neoInkBorderColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(trayBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = IosBlue,
                modifier = Modifier.size(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryInk
                )
                Text(
                    text = subtitle,
                    fontSize = 9.5.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = secondaryInk
                )
            }
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosRed.copy(alpha = 0.08f))
                    .border(1.dp, IosRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = emptyMessage,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = IosRed,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(paperBg)
                    .border(1.dp, borderCol.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                files.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = entry.relativePath,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = primaryInk,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = entry.sizeMbLabel,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = IosBlue
                        )
                    }
                }
            }
        }
    }
}
