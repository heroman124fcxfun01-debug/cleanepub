package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.ClonedVoiceProfile
import com.example.audio.OnnxVoiceEngineMode
import com.example.audio.VoiceSampleRecorder
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosRed
import com.example.ui.theme.NeoInkLight
import java.util.Locale

/**
 * Hộp thoại gọn chuẩn Swiss Neo-Brutalist 3D cho tính năng "Giọng đọc nhân bản AI (100% Offline)":
 * - Ô đặt tên giọng nhân bản
 * - Nút "Ghi âm mẫu (5–8 giây)" xin quyền RECORD_AUDIO, bộ đếm giây (tự dừng ở 8s, cho phép dừng sớm từ giây thứ 3),
 *   chuyển đổi về Mono 16kHz/24kHz Float32Array
 * - Nút "Nghe thử" phát trực tiếp mẫu vừa ghi qua AudioTrack
 * - Nút "Lưu giọng" trích xuất speaker embedding trên luồng Worker ngầm & lưu vĩnh viễn
 * - Danh sách các giọng nhân bản đã lưu kèm nút Chọn dùng & Xóa giọng
 */
@Composable
fun VoiceCloneDialog(
    clonedVoices: List<ClonedVoiceProfile>,
    activeClonedVoiceId: String?,
    engineMode: OnnxVoiceEngineMode,
    targetSampleRate: Int,
    isExtractingEmbedding: Boolean,
    onSaveClonedVoice: (name: String, samples: FloatArray, sampleRate: Int, onDone: () -> Unit) -> Unit,
    onSelectClonedVoice: (String?) -> Unit,
    onDeleteClonedVoice: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val recorder = remember { VoiceSampleRecorder() }
    val recState by recorder.uiState.collectAsStateWithLifecycle()

    var voiceName by remember { mutableStateOf("") }
    var permissionDeniedNote by remember { mutableStateOf<String?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedNote = null
            recorder.startRecording(targetSampleRate)
        } else {
            permissionDeniedNote = "Cần cấp quyền Micro để ghi âm mẫu giọng 3–8 giây hoàn toàn ngoại tuyến."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
        }
    }

    val paperBg = neoPaperSurfaceColor()
    val trayBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val shadowCol = neoHardShadowColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    fun handleRecordClick() {
        if (recState.isRecording) {
            if (recState.canStopEarly) {
                recorder.stopRecording()
            }
            return
        }
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            permissionDeniedNote = null
            recorder.startRecording(targetSampleRate)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isExtractingEmbedding) {
                recorder.stopPreview()
                if (recState.isRecording) recorder.stopRecording()
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(end = 4.dp, bottom = 4.dp)
                .neoHardOffsetShadow(cornerRadius = 18.dp, offsetX = 4.dp, offsetY = 4.dp, shadowColor = shadowCol)
                .clip(RoundedCornerShape(18.dp))
                .background(paperBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(18.dp))
                .padding(14.dp)
                .testTag("voice_clone_dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Header
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(IosBlue.copy(alpha = 0.14f))
                            .border(1.5.dp, borderCol, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = IosBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Giọng đọc nhân bản AI",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = primaryInk
                        )
                        Text(
                            text = when (engineMode) {
                                OnnxVoiceEngineMode.ONNX_READY -> "ONNX Offline sẵn sàng • Mono ${targetSampleRate / 1000}kHz"
                                else -> "100% Offline • Tự dự phòng giọng thiết bị nếu chưa nạp model"
                            },
                            fontSize = 10.sp,
                            color = secondaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Ios3DCircleIconButton(
                    onClick = {
                        if (!isExtractingEmbedding) onDismiss()
                    },
                    size = 30.dp,
                    modifier = Modifier.testTag("voice_clone_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng",
                        tint = primaryInk,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = borderCol.copy(alpha = 0.22f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 2. Ô đặt tên giọng
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Tên giọng nhân bản",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(trayBg)
                            .border(1.5.dp, borderCol, RoundedCornerShape(11.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (voiceName.isEmpty()) {
                            Text(
                                text = "VD: Giọng của tôi, Kể chuyện đêm…",
                                fontSize = 12.5.sp,
                                color = secondaryInk.copy(alpha = 0.7f)
                            )
                        }
                        BasicTextField(
                            value = voiceName,
                            onValueChange = { if (it.length <= 36) voiceName = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = primaryInk,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            cursorBrush = SolidColor(IosBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("voice_clone_name_input")
                        )
                    }
                }

                // 3. Khu vực Ghi âm mẫu (3–8 giây) + bộ đếm giây + thanh sóng âm
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(trayBg)
                        .border(1.5.dp, borderCol, RoundedCornerShape(13.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = if (recState.isRecording) IosRed else IosBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = when {
                                    recState.isRecording && !recState.canStopEarly -> "Đang ghi… (tối thiểu 3 giây)"
                                    recState.isRecording -> "Đang ghi… (bấm Dừng hoặc tự dừng ở 8s)"
                                    recState.hasValidSample -> "Đã thu xong mẫu Mono ${recState.sampleRate / 1000}kHz"
                                    else -> "Đọc tự nhiên 1–2 câu trong 5–8 giây"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (recState.isRecording) IosRed else primaryInk
                            )
                        }

                        Text(
                            text = String.format(Locale.US, "%.1fs / 8.0s", recState.elapsedSeconds),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (recState.isRecording) IosRed else IosBlue,
                            modifier = Modifier.testTag("voice_clone_timer_text")
                        )
                    }

                    // Live waveform meter while recording
                    if (recState.isRecording) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val weights = remember { listOf(0.45f, 0.72f, 0.95f, 0.65f, 1.0f, 0.85f, 0.55f, 0.9f, 0.75f, 0.5f) }
                            weights.forEachIndexed { i, factor ->
                                val barFrac by animateFloatAsState(
                                    targetValue = (recState.amplitudeLevel * factor + (if (i % 2 == 0) 0.14f else 0.08f))
                                        .coerceIn(0.14f, 1.0f),
                                    label = "rec_bar_$i"
                                )
                                Box(
                                    modifier = Modifier
                                        .width(6.dp)
                                        .height((24.dp * barFrac).coerceAtLeast(4.dp))
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(IosRed)
                                )
                            }
                        }
                    }

                    // Record / Stop button
                    NeoBrutalistPressBox(
                        onClick = ::handleRecordClick,
                        enabled = !isExtractingEmbedding && (!recState.isRecording || recState.canStopEarly),
                        isPill = true,
                        shadowOffset = 3.dp,
                        pressDepth = 2.dp,
                        borderWidth = 1.5.dp,
                        containerColor = when {
                            recState.isRecording && recState.canStopEarly -> IosRed
                            recState.isRecording -> IosOrange
                            else -> IosBlue
                        },
                        borderColor = borderCol,
                        shadowColor = shadowCol,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .testTag("voice_clone_record_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (recState.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = null,
                                tint = if (recState.isRecording && !recState.canStopEarly) NeoInkLight else Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = when {
                                    recState.isRecording && !recState.canStopEarly -> {
                                        val remain = ((VoiceSampleRecorder.MIN_DURATION_MS - recState.elapsedMs + 999L) / 1000L)
                                            .coerceAtLeast(1L)
                                        "Đang thu âm… (chờ ${remain}s)"
                                    }
                                    recState.isRecording -> "Dừng ghi âm (${String.format(Locale.US, "%.1fs", recState.elapsedSeconds)})"
                                    recState.hasValidSample -> "Ghi âm lại (5–8 giây)"
                                    else -> "Ghi âm mẫu (5–8 giây)"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (recState.isRecording && !recState.canStopEarly) NeoInkLight else Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Nghe thử + Lưu giọng (active once >= 3s sample captured)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NeoBrutalistPressBox(
                            onClick = { recorder.togglePreviewRecordedSample() },
                            enabled = recState.hasValidSample && !isExtractingEmbedding,
                            isPill = true,
                            shadowOffset = 2.5.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = paperBg,
                            borderColor = borderCol,
                            shadowColor = shadowCol,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .testTag("voice_clone_preview_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (recState.isPlayingPreview) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    contentDescription = "Nghe thử mẫu giọng",
                                    tint = if (recState.hasValidSample) IosBlue else secondaryInk,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (recState.isPlayingPreview) "Dừng nghe" else "Nghe thử",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (recState.hasValidSample) primaryInk else secondaryInk,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        NeoBrutalistPressBox(
                            onClick = {
                                val samples = recState.recordedSamples ?: return@NeoBrutalistPressBox
                                recorder.stopPreview()
                                val finalName = voiceName.trim().ifBlank {
                                    "Giọng nhân bản #${clonedVoices.size + 1}"
                                }
                                onSaveClonedVoice(finalName, samples, recState.sampleRate) {
                                    voiceName = ""
                                    recorder.clearSample()
                                }
                            },
                            enabled = recState.hasValidSample && !isExtractingEmbedding,
                            isPill = true,
                            shadowOffset = 2.5.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = if (recState.hasValidSample) IosGreen else paperBg,
                            borderColor = borderCol,
                            shadowColor = shadowCol,
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .testTag("voice_clone_save_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                if (isExtractingEmbedding) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Đang trích xuất…",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Lưu giọng nhân bản",
                                        tint = if (recState.hasValidSample) Color.White else secondaryInk,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "Lưu giọng",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (recState.hasValidSample) Color.White else secondaryInk,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    val noteText = permissionDeniedNote ?: recState.errorMessage
                    AnimatedVisibility(visible = !noteText.isNullOrBlank()) {
                        Text(
                            text = noteText.orEmpty(),
                            fontSize = 10.5.sp,
                            color = IosRed,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // 4. Danh sách giọng nhân bản đã lưu (chọn dùng / xóa giọng)
                if (clonedVoices.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Giọng nhân bản đã lưu (${clonedVoices.size})",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryInk
                            )
                            if (activeClonedVoiceId != null) {
                                Text(
                                    text = "Dùng giọng hệ thống",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IosBlue,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSelectClonedVoice(null) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("voice_clone_switch_system_button")
                                )
                            }
                        }

                        clonedVoices.forEach { voice ->
                            val isSelected = activeClonedVoiceId == voice.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (isSelected) IosBlue.copy(alpha = 0.13f) else trayBg)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) IosBlue else borderCol,
                                        shape = RoundedCornerShape(11.dp)
                                    )
                                    .clickable {
                                        onSelectClonedVoice(if (isSelected) null else voice.id)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("cloned_voice_item_${voice.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) IosBlue else paperBg)
                                            .border(1.dp, borderCol, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = if (isSelected) Color.White else IosBlue,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = voice.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) IosBlue else primaryInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = buildString {
                                                append(String.format(Locale.US, "%.1fs", voice.durationSeconds))
                                                append(" • ")
                                                append("${voice.embeddingDim}d ")
                                                append(if (voice.usedOnnxEncoder) "ONNX" else "Vector")
                                                if (isSelected) append(" • Đang chọn")
                                            },
                                            fontSize = 9.5.sp,
                                            color = secondaryInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .clickable { onDeleteClonedVoice(voice.id) }
                                        .testTag("delete_cloned_voice_${voice.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Xóa giọng ${voice.name}",
                                        tint = IosRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
