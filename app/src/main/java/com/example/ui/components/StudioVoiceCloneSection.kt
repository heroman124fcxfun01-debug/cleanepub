package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.ClonedVoiceProfile
import com.example.audio.ImportedVoiceSample
import com.example.audio.OnnxVoiceEngineMode
import com.example.audio.VieNeuPresetVoice
import com.example.audio.VoiceSampleRecorder
import com.example.service.BookAudioPlaybackService
import com.example.ui.PendingDraftClonedVoice
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosRed
import com.example.ui.theme.NeoInkLight
import java.util.Locale

/**
 * Khu vực Tạo, Import & Quản lý Giọng AI Nhân bản (100% Offline) trong màn hình Studio:
 * - Tối mờ & khóa hoàn toàn khi người dùng đang chọn chế độ Giọng Hệ thống.
 * - Hỗ trợ cả Ghi âm mẫu (3–8 giây) và Import file âm thanh (.wav/.mp3/.m4a/.aac/.ogg) kèm nút Nghe thử (bật/tắt) ngay bên cạnh mẫu vừa ghi/import.
 * - Hàng 3 nút bên dưới: [Tạo] -> sau khi tạo xong, nút [Nghe thử] bên cạnh sáng lên để kiểm tra (bật/tắt) -> nút [Lưu] bên cạnh để lưu tất cả giọng đọc.
 * - Danh sách các giọng AI nhân bản đã lưu (Chọn, Nghe thử bật/tắt, Xóa).
 */
@Composable
fun StudioVoiceCloneSection(
    sectionEnabled: Boolean,
    clonedVoices: List<ClonedVoiceProfile>,
    presetAiVoices: List<VieNeuPresetVoice> = emptyList(),
    aiModelStatusBadge: String = "",
    selectedClonedVoiceId: String?,
    engineMode: OnnxVoiceEngineMode,
    targetSampleRate: Int,
    isExtractingEmbedding: Boolean,
    isImportingFile: Boolean,
    draftClonedVoice: PendingDraftClonedVoice?,
    activeServicePreviewTag: String?,
    lastAiVerbatimError: String? = null,
    onOpenAiDiagnostics: () -> Unit = {},
    onImportAudioUri: (Uri, (ImportedVoiceSample) -> Unit) -> Unit,
    onCreateDraftVoice: (name: String, samples: FloatArray, sampleRate: Int, referenceText: String) -> Unit,
    onClearDraftVoice: () -> Unit,
    onSaveAllVoices: (onSaved: () -> Unit) -> Unit,
    onSelectClonedVoice: (String?) -> Unit,
    onTogglePreviewSavedVoice: (ClonedVoiceProfile) -> Unit,
    onTogglePreviewPresetVoice: (VieNeuPresetVoice) -> Unit = {},
    onDeleteClonedVoice: (String) -> Unit
) {
    val context = LocalContext.current
    val recorder = remember(context) { VoiceSampleRecorder(context) }
    val recState by recorder.uiState.collectAsStateWithLifecycle()

    var voiceName by remember { mutableStateOf("") }
    var referenceText by remember { mutableStateOf("") }
    var importedSample by remember { mutableStateOf<ImportedVoiceSample?>(null) }
    var localPreviewTarget by remember { mutableStateOf<String?>(null) }
    var permissionDeniedNote by remember { mutableStateOf<String?>(null) }
    var aiSectionExpanded by remember { mutableStateOf(sectionEnabled) }

    // Khi đang ở chế độ Hệ thống -> tự động thu gọn khối AI dưới tam giác và dừng mọi ghi âm/nghe thử
    LaunchedEffect(sectionEnabled) {
        aiSectionExpanded = sectionEnabled
        if (!sectionEnabled) {
            recorder.stopPreview()
            if (recState.isRecording) recorder.stopRecording()
            localPreviewTarget = null
        }
    }

    // Tự reset cờ localPreviewTarget khi AudioTrack phát xong tự nhiên
    LaunchedEffect(recState.isPlayingPreview) {
        if (!recState.isPlayingPreview) {
            localPreviewTarget = null
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionDeniedNote = null
            importedSample = null
            onClearDraftVoice()
            BookAudioPlaybackService.stopPreview(context)
            recorder.startRecording(targetSampleRate)
        } else {
            permissionDeniedNote = "Cần cấp quyền Micro để ghi âm mẫu giọng 4–7 giây."
        }
    }

    val audioImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && sectionEnabled) {
            recorder.stopPreview()
            if (recState.isRecording) recorder.stopRecording()
            BookAudioPlaybackService.stopPreview(context)
            onImportAudioUri(uri) { decoded ->
                recorder.clearSample()
                importedSample = decoded
                if (voiceName.isBlank()) {
                    voiceName = decoded.displayName.substringBeforeLast('.').take(28)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
        }
    }

    val sectionAlpha by animateFloatAsState(
        targetValue = if (sectionEnabled) 1f else 0.36f,
        label = "studio_ai_section_alpha"
    )
    val paperBg = neoPaperSurfaceColor()
    val trayBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val shadowCol = neoHardShadowColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    // Xác định mẫu âm thanh đầu vào hiện có (ưu tiên file vừa import hoặc bản vừa ghi âm)
    val currentImported = importedSample
    val activeInputSamples: FloatArray? = currentImported?.samples ?: recState.recordedSamples
    val activeInputWavPath: String? = currentImported?.previewWavPath ?: recState.previewWavPath
    val activeInputRate: Int = currentImported?.sampleRate ?: recState.sampleRate
    val activeInputLabel: String? = when {
        currentImported != null -> "Import: ${currentImported.displayName} • ${
            String.format(Locale.US, "%.1fs", currentImported.durationSeconds)
        }"
        recState.hasValidSample -> "Ghi âm mẫu • ${
            String.format(Locale.US, "%.1fs", recState.elapsedSeconds)
        } (${recState.sampleRate / 1000}kHz)"
        else -> null
    }
    val hasValidInput = activeInputSamples != null && activeInputSamples.isNotEmpty() && !recState.isRecording
    val canCreateDraft = hasValidInput

    val isPreviewingRawSample = recState.isPlayingPreview && localPreviewTarget == "raw_sample"
    val isPreviewingDraftVoice = recState.isPlayingPreview && localPreviewTarget == "draft_voice"
    val hasCreatedDraft = draftClonedVoice != null

    fun toggleLocalPreview(
        tag: String,
        samples: FloatArray,
        sampleRate: Int,
        wavPath: String? = null,
        pitchOverride: Float = 1.0f
    ) {
        BookAudioPlaybackService.stopPreview(context)
        if (recState.isPlayingPreview && localPreviewTarget == tag) {
            recorder.stopPreview()
            localPreviewTarget = null
        } else {
            localPreviewTarget = tag
            recorder.playPreview(
                samplesOverride = samples,
                sampleRateOverride = sampleRate,
                wavPathOverride = wavPath,
                pitchOverride = pitchOverride
            )
        }
    }

    fun handleRecordClick() {
        if (!sectionEnabled) return
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
            importedSample = null
            onClearDraftVoice()
            BookAudioPlaybackService.stopPreview(context)
            recorder.startRecording(targetSampleRate)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val showAiContent = aiSectionExpanded && sectionEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(sectionAlpha)
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(
                cornerRadius = 15.dp,
                offsetX = 3.dp,
                offsetY = 3.dp,
                shadowColor = if (sectionEnabled) shadowCol else Color.Transparent
            )
            .clip(RoundedCornerShape(15.dp))
            .background(paperBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(15.dp))
            .padding(12.dp)
            .testTag("studio_ai_clone_section")
    ) {
        // Header khối AI Nhân bản (có nút tam giác thu gọn/mở rộng cạnh tiêu đề)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = sectionEnabled) {
                    aiSectionExpanded = !aiSectionExpanded
                }
                .testTag("studio_ai_section_header"),
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
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (sectionEnabled) IosBlue else trayBg)
                        .border(1.5.dp, borderCol, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = if (sectionEnabled) Color.White else secondaryInk,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Cài đặt & Tạo giọng AI Nhân bản",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = primaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (showAiContent) IosBlue.copy(alpha = 0.14f) else trayBg)
                                .border(1.dp, borderCol, RoundedCornerShape(6.dp))
                                .testTag("studio_ai_collapse_triangle"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (showAiContent) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                                contentDescription = if (showAiContent) "Thu gọn cài đặt giọng AI" else "Mở rộng cài đặt giọng AI",
                                tint = if (sectionEnabled) IosBlue else secondaryInk,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    val resolvedStatusLine = aiModelStatusBadge.ifBlank {
                        when {
                            !sectionEnabled -> "Đang khóa & thu gọn (chọn AI Nhân bản ở trên)"
                            engineMode == OnnxVoiceEngineMode.ONNX_READY -> "Model: OK | Voices: ${presetAiVoices.size}"
                            else -> "Model: Chờ nạp | Voices: ${presetAiVoices.size}"
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val isModelOk = resolvedStatusLine.contains("Model: OK", ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (isModelOk) IosGreen.copy(alpha = 0.16f) else trayBg
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isModelOk) IosGreen else borderCol.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 1.5.dp)
                                .testTag("studio_ai_model_status_badge")
                        ) {
                            Text(
                                text = resolvedStatusLine,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isModelOk) IosGreen else secondaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            NeoBrutalistPressBox(
                onClick = onOpenAiDiagnostics,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 1.5.dp,
                borderWidth = 1.2.dp,
                containerColor = trayBg,
                borderColor = borderCol,
                shadowColor = shadowCol,
                modifier = Modifier
                    .height(28.dp)
                    .testTag("studio_ai_card_diagnostics_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.BugReport,
                        contentDescription = "Kiểm tra hệ thống AI",
                        tint = IosBlue,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Log AI",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryInk,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showAiContent,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
        // 1. Ô đặt tên giọng nhân bản
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(trayBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(11.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp)
        ) {
            if (voiceName.isEmpty()) {
                Text(
                    text = "Đặt tên giọng nhân bản (VD: Giọng kể chuyện…)",
                    fontSize = 12.sp,
                    color = secondaryInk.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = voiceName,
                onValueChange = { if (sectionEnabled && it.length <= 36) voiceName = it },
                enabled = sectionEnabled && !isExtractingEmbedding,
                singleLine = true,
                textStyle = TextStyle(
                    color = primaryInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                cursorBrush = SolidColor(IosBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("studio_clone_name_input")
            )
        }

        // 2. Hàng chọn nguồn mẫu giọng: [Ghi âm mẫu (5-8s)] | [Import giọng nói]
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(trayBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeoBrutalistPressBox(
                    onClick = ::handleRecordClick,
                    enabled = sectionEnabled && !isExtractingEmbedding && !isImportingFile &&
                        (!recState.isRecording || recState.canStopEarly),
                    isPill = true,
                    shadowOffset = 2.5.dp,
                    pressDepth = 1.8.dp,
                    borderWidth = 1.5.dp,
                    containerColor = when {
                        recState.isRecording && recState.canStopEarly -> IosRed
                        recState.isRecording -> IosOrange
                        else -> IosBlue
                    },
                    borderColor = borderCol,
                    shadowColor = if (sectionEnabled) shadowCol else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("studio_clone_record_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (recState.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = if (recState.isRecording && !recState.canStopEarly) NeoInkLight else Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = when {
                                recState.isRecording && !recState.canStopEarly -> {
                                    val remain = ((VoiceSampleRecorder.MIN_DURATION_MS - recState.elapsedMs + 999L) / 1000L)
                                        .coerceAtLeast(1L)
                                    "Đang ghi (${remain}s)"
                                }
                                recState.isRecording -> "Dừng (${String.format(Locale.US, "%.1fs", recState.elapsedSeconds)})"
                                else -> "Ghi âm (3–12s)"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (recState.isRecording && !recState.canStopEarly) NeoInkLight else Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                NeoBrutalistPressBox(
                    onClick = {
                        if (sectionEnabled) {
                            audioImportLauncher.launch(arrayOf("audio/*"))
                        }
                    },
                    enabled = sectionEnabled && !recState.isRecording && !isExtractingEmbedding && !isImportingFile,
                    isPill = true,
                    shadowOffset = 2.5.dp,
                    pressDepth = 1.8.dp,
                    borderWidth = 1.5.dp,
                    containerColor = paperBg,
                    borderColor = borderCol,
                    shadowColor = if (sectionEnabled) shadowCol else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("studio_clone_import_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        if (isImportingFile) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = IosBlue
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.UploadFile,
                                contentDescription = null,
                                tint = IosBlue,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Text(
                            text = if (isImportingFile) "Đang đọc…" else "Import giọng",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryInk,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Sóng âm trực tiếp khi đang ghi âm
            if (recState.isRecording) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val weights = remember { listOf(0.45f, 0.72f, 0.95f, 0.65f, 1.0f, 0.85f, 0.55f, 0.9f) }
                    weights.forEachIndexed { i, factor ->
                        val barFrac by animateFloatAsState(
                            targetValue = (recState.amplitudeLevel * factor + 0.12f).coerceIn(0.14f, 1.0f),
                            label = "studio_rec_bar_$i"
                        )
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .height((20.dp * barFrac).coerceAtLeast(4.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(IosRed)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = String.format(Locale.US, "%.1fs / 12.0s", recState.elapsedSeconds),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = IosRed
                    )
                }
            }

            // Hàng hiển thị mẫu giọng vừa Import / Ghi âm + nút [Nghe thử] ngay bên cạnh (bấm 1 lần nghe, lần 2 tắt)
            if (activeInputLabel != null && activeInputSamples != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(paperBg)
                        .border(1.2.dp, borderCol, RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (importedSample != null) Icons.Rounded.AudioFile else Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = IosBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = activeInputLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    NeoBrutalistPressBox(
                        onClick = {
                            toggleLocalPreview(
                                tag = "raw_sample",
                                samples = activeInputSamples,
                                sampleRate = activeInputRate,
                                wavPath = activeInputWavPath
                            )
                        },
                        enabled = sectionEnabled && !isExtractingEmbedding,
                        isPill = true,
                        shadowOffset = 2.dp,
                        pressDepth = 1.5.dp,
                        borderWidth = 1.2.dp,
                        containerColor = if (isPreviewingRawSample) IosBlue else trayBg,
                        borderColor = borderCol,
                        shadowColor = if (sectionEnabled) shadowCol else Color.Transparent,
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("studio_sample_preview_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = if (isPreviewingRawSample) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPreviewingRawSample) "Dừng nghe thử mẫu" else "Nghe thử mẫu vừa nhập",
                                tint = if (isPreviewingRawSample) Color.White else IosBlue,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (isPreviewingRawSample) "Dừng" else "Nghe thử",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isPreviewingRawSample) Color.White else primaryInk,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // 2.5. Lời thoại mẫu (ref_text) khớp với đoạn ghi âm 3–8 giây để đồng bộ cùng ref_codes từ neucodec_encoder.onnx
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Lời thoại mẫu (ref_text — khuyên nhập)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    NeoBrutalistPressBox(
                        onClick = {
                            if (sectionEnabled && !isExtractingEmbedding) {
                                referenceText = com.example.audio.OnnxModelAssetHelper.SUGGESTED_RECORDING_SCRIPT
                            }
                        },
                        enabled = sectionEnabled && !isExtractingEmbedding,
                        isPill = true,
                        shadowOffset = 1.5.dp,
                        pressDepth = 1.dp,
                        borderWidth = 1.dp,
                        containerColor = paperBg,
                        borderColor = borderCol,
                        shadowColor = if (sectionEnabled) shadowCol else Color.Transparent,
                        modifier = Modifier
                            .height(24.dp)
                            .testTag("studio_clone_fill_script_button")
                    ) {
                        Text(
                            text = "Điền câu mẫu để đọc",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = IosBlue,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(paperBg)
                        .border(
                            width = 1.3.dp,
                            color = borderCol,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (referenceText.isEmpty()) {
                        Text(
                            text = "Nhập đúng câu nói trong đoạn ghi âm 4–7 giây (hoặc bấm \"Điền câu mẫu để đọc\" rồi thu âm) để NeuCodec Encoder ONNX trích xuất & nhân bản giọng thật chuẩn nhất.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = secondaryInk.copy(alpha = 0.72f)
                        )
                    }
                    BasicTextField(
                        value = referenceText,
                        onValueChange = { if (sectionEnabled && it.length <= 500) referenceText = it },
                        enabled = sectionEnabled && !isExtractingEmbedding,
                        minLines = 2,
                        maxLines = 4,
                        textStyle = TextStyle(
                            color = primaryInk,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(IosBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("studio_clone_ref_text_input")
                    )
                }
            }

            // 3. Hàng 3 nút theo đúng quy trình: [ Tạo ] -> [ Nghe thử (sáng lên sau khi tạo) ] -> [ Lưu ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Nút 1: TẠO
                NeoBrutalistPressBox(
                    onClick = {
                        val samples = activeInputSamples ?: return@NeoBrutalistPressBox
                        recorder.stopPreview()
                        if (activeServicePreviewTag != null) {
                            BookAudioPlaybackService.stopPreview(context)
                        }
                        val name = voiceName.trim().ifBlank {
                            "Giọng AI #${clonedVoices.size + 1}"
                        }
                        onCreateDraftVoice(name, samples, activeInputRate, referenceText.trim())
                    },
                    enabled = sectionEnabled && canCreateDraft && !isExtractingEmbedding,
                    isPill = true,
                    shadowOffset = 2.5.dp,
                    pressDepth = 1.8.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (canCreateDraft && sectionEnabled) IosBlue else paperBg,
                    borderColor = borderCol,
                    shadowColor = if (canCreateDraft && sectionEnabled) shadowCol else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(35.dp)
                        .alpha(if (canCreateDraft) 1f else 0.45f)
                        .testTag("studio_clone_create_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        if (isExtractingEmbedding && !hasCreatedDraft) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.8.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Tạo giọng nhân bản",
                                tint = if (canCreateDraft && sectionEnabled) Color.White else secondaryInk,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = if (isExtractingEmbedding && !hasCreatedDraft) "Đang tạo…" else "Tạo",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (canCreateDraft && sectionEnabled) Color.White else secondaryInk,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Nút 2: NGHE THỬ (chỉ sáng lên sau khi bấm Tạo xong!)
                val draftCanPreview = sectionEnabled && hasCreatedDraft && !isExtractingEmbedding
                NeoBrutalistPressBox(
                    onClick = {
                        val draft = draftClonedVoice ?: return@NeoBrutalistPressBox
                        toggleLocalPreview(
                            tag = "draft_voice",
                            samples = draft.samples,
                            sampleRate = draft.sampleRate,
                            wavPath = draft.previewWavPath,
                            pitchOverride = 1.0f
                        )
                    },
                    enabled = draftCanPreview,
                    isPill = true,
                    shadowOffset = 2.5.dp,
                    pressDepth = 1.8.dp,
                    borderWidth = 1.5.dp,
                    containerColor = when {
                        isPreviewingDraftVoice -> IosBlue
                        draftCanPreview -> IosOrange
                        else -> paperBg
                    },
                    borderColor = borderCol,
                    shadowColor = if (draftCanPreview) shadowCol else Color.Transparent,
                    modifier = Modifier
                        .weight(1.15f)
                        .height(35.dp)
                        .alpha(if (hasCreatedDraft) 1f else 0.36f)
                        .testTag("studio_clone_test_draft_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isPreviewingDraftVoice) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = "Nghe thử giọng vừa tạo",
                            tint = when {
                                isPreviewingDraftVoice -> Color.White
                                draftCanPreview -> NeoInkLight
                                else -> secondaryInk
                            },
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isPreviewingDraftVoice) "Dừng" else "Nghe thử",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = when {
                                isPreviewingDraftVoice -> Color.White
                                draftCanPreview -> NeoInkLight
                                else -> secondaryInk
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // Nút 3: LƯU (bấm Lưu mới lưu lại tất cả giọng đọc)
                val canSave = sectionEnabled && !isExtractingEmbedding && (hasCreatedDraft || clonedVoices.isNotEmpty())
                NeoBrutalistPressBox(
                    onClick = {
                        recorder.stopPreview()
                        BookAudioPlaybackService.stopPreview(context)
                        onSaveAllVoices {
                            voiceName = ""
                            referenceText = ""
                            importedSample = null
                            recorder.clearSample()
                        }
                    },
                    enabled = canSave,
                    isPill = true,
                    shadowOffset = 2.5.dp,
                    pressDepth = 1.8.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (hasCreatedDraft && sectionEnabled) IosGreen else paperBg,
                    borderColor = borderCol,
                    shadowColor = if (canSave) shadowCol else Color.Transparent,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(35.dp)
                        .alpha(if (canSave) 1f else 0.42f)
                        .testTag("studio_clone_save_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = "Lưu tất cả giọng đọc",
                            tint = if (hasCreatedDraft && sectionEnabled) Color.White else primaryInk,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Lưu",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (hasCreatedDraft && sectionEnabled) Color.White else primaryInk,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // Thông báo trạng thái bản nháp vừa tạo (chờ bấm Lưu)
            AnimatedVisibility(visible = draftClonedVoice != null) {
                draftClonedVoice?.let { draft ->
                    val tokenCount = draft.referenceCodes?.size ?: 0
                    val modeBadge = if (draft.synthesizedPreview) {
                        "NeuCodec Encoder ONNX • GGUF 24kHz"
                    } else {
                        "NeuCodec Encoder ONNX"
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "✓ Đã tạo \"${draft.name} (${tokenCount}c)\" • $modeBadge — Bấm Nghe thử để kiểm tra, rồi bấm Lưu.",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IosGreen
                        )
                        if (draft.acousticSummary.isNotBlank()) {
                            Text(
                                text = draft.acousticSummary,
                                fontSize = 9.5.sp,
                                color = secondaryInk
                            )
                        }
                    }
                }
            }

            val noteText = permissionDeniedNote ?: recState.errorMessage
            AnimatedVisibility(visible = !noteText.isNullOrBlank()) {
                SelectionContainer {
                    Text(
                        text = noteText.orEmpty(),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = IosRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Hiển thị nguyên văn lỗi MediaCodec / OrtEnvironment / OrtSession (không bị cắt ngắn)
            AnimatedVisibility(visible = !lastAiVerbatimError.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(IosRed.copy(alpha = 0.09f))
                        .border(1.2.dp, IosRed, RoundedCornerShape(10.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Chi tiết lỗi VieNeu GGUF / NeuCodec (nguyên văn):",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = IosRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        NeoBrutalistPressBox(
                            onClick = onOpenAiDiagnostics,
                            isPill = true,
                            shadowOffset = 1.5.dp,
                            pressDepth = 1.dp,
                            borderWidth = 1.dp,
                            containerColor = paperBg,
                            borderColor = IosRed,
                            shadowColor = shadowCol,
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BugReport,
                                    contentDescription = null,
                                    tint = IosRed,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "Kiểm tra AI",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = IosRed,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = lastAiVerbatimError.orEmpty(),
                            fontSize = 9.5.sp,
                            lineHeight = 13.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = primaryInk
                        )
                    }
                }
            }

            var dismissedErrorText by remember { mutableStateOf<String?>(null) }
            val activeErrorDialog = lastAiVerbatimError?.takeIf { it.isNotBlank() && it != dismissedErrorText }
            if (activeErrorDialog != null) {
                AlertDialog(
                    onDismissRequest = { dismissedErrorText = activeErrorDialog },
                    title = {
                        Text(
                            text = "Chi tiết lỗi nhân bản giọng AI",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = IosRed
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            SelectionContainer {
                                Text(
                                    text = activeErrorDialog,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = primaryInk
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                dismissedErrorText = activeErrorDialog
                                onOpenAiDiagnostics()
                            }
                        ) {
                            Text("Kiểm tra AI", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { dismissedErrorText = activeErrorDialog }) {
                            Text("Đóng")
                        }
                    }
                )
            }
        }

        // 4. Danh sách giọng mẫu chính thức VieNeu-TTS từ voices.json (chọn dùng ngay / nghe thử bật-tắt)
        if (presetAiVoices.isNotEmpty()) {
            HorizontalDivider(thickness = 0.8.dp, color = borderCol.copy(alpha = 0.2f))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Giọng mẫu VieNeu-TTS sẵn có (${presetAiVoices.size})",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk
                )

                presetAiVoices.forEachIndexed { idx, preset ->
                    val isSelected = selectedClonedVoiceId == preset.storageId ||
                        (selectedClonedVoiceId == null && clonedVoices.isEmpty() && idx == 0)
                    val presetPreviewTag = "studio_preset_${preset.id}"
                    val isThisPreviewing = activeServicePreviewTag == presetPreviewTag

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
                            .clickable(enabled = sectionEnabled) { onSelectClonedVoice(preset.storageId) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("studio_preset_voice_item_${preset.id}"),
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
                                    imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = if (isSelected) Color.White else IosBlue,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.displayLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) IosBlue else primaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${preset.referenceCodes.size} mã NeuCodec • voices.json${
                                        if (isSelected) " • Đang chọn" else ""
                                    }",
                                    fontSize = 9.5.sp,
                                    color = secondaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        NeoBrutalistPressBox(
                            onClick = {
                                recorder.stopPreview()
                                onTogglePreviewPresetVoice(preset)
                            },
                            enabled = sectionEnabled,
                            isCircle = true,
                            shadowOffset = 2.dp,
                            pressDepth = 1.5.dp,
                            borderWidth = 1.2.dp,
                            containerColor = if (isThisPreviewing) IosBlue else paperBg,
                            borderColor = borderCol,
                            shadowColor = if (sectionEnabled) shadowCol else Color.Transparent,
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("studio_preset_voice_preview_${preset.id}")
                        ) {
                            Icon(
                                imageVector = if (isThisPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = if (isThisPreviewing) "Dừng nghe thử ${preset.id}" else "Nghe thử ${preset.id}",
                                tint = if (isThisPreviewing) Color.White else primaryInk,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // 5. Danh sách giọng AI Nhân bản đã lưu (chọn dùng / nghe thử bật-tắt / xóa)
        HorizontalDivider(thickness = 0.8.dp, color = borderCol.copy(alpha = 0.2f))

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Danh sách giọng AI nhân bản của bạn (${clonedVoices.size})",
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = primaryInk
            )

            if (clonedVoices.isEmpty()) {
                Text(
                    text = "Chưa có giọng nhân bản riêng. Bạn có thể chọn ngay giọng mẫu VieNeu-TTS ở trên, hoặc Ghi âm/Import rồi bấm Tạo -> Nghe thử -> Lưu.",
                    fontSize = 10.5.sp,
                    color = secondaryInk
                )
            } else {
                clonedVoices.forEach { voice ->
                    val isSelected = selectedClonedVoiceId == voice.id
                    val voicePreviewTag = "studio_cloned_${voice.id}"
                    val isThisPreviewing = activeServicePreviewTag == voicePreviewTag
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
                            .clickable(enabled = sectionEnabled) { onSelectClonedVoice(voice.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag("studio_cloned_voice_item_${voice.id}"),
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
                                    text = voice.displayNameWithCodeCount,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) IosBlue else primaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val tokenLabel = if (voice.referenceCodeCount > 0) {
                                    "${voice.referenceCodeCount}c NeuCodec"
                                } else {
                                    "${voice.embeddingDim}d"
                                }
                                Text(
                                    text = "${String.format(Locale.US, "%.1fs", voice.durationSeconds)} • $tokenLabel${
                                        if (isSelected) " • Đang chọn" else ""
                                    }",
                                    fontSize = 9.5.sp,
                                    color = secondaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (voice.referenceText.isNotBlank()) {
                                    Text(
                                        text = "Lời mẫu: \"${voice.referenceText}\"",
                                        fontSize = 9.sp,
                                        color = secondaryInk.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Nút nghe thử dạng công tắc (bấm 1 lần phát, bấm lần 2 tắt)
                            NeoBrutalistPressBox(
                                onClick = {
                                    recorder.stopPreview()
                                    onTogglePreviewSavedVoice(voice)
                                },
                                enabled = sectionEnabled,
                                isCircle = true,
                                shadowOffset = 2.dp,
                                pressDepth = 1.5.dp,
                                borderWidth = 1.2.dp,
                                containerColor = if (isThisPreviewing) IosBlue else paperBg,
                                borderColor = borderCol,
                                shadowColor = if (sectionEnabled) shadowCol else Color.Transparent,
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("studio_saved_clone_preview_${voice.id}")
                            ) {
                                Icon(
                                    imageVector = if (isThisPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isThisPreviewing) "Dừng nghe thử ${voice.name}" else "Nghe thử ${voice.name}",
                                    tint = if (isThisPreviewing) Color.White else primaryInk,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = sectionEnabled) { onDeleteClonedVoice(voice.id) }
                                    .testTag("studio_saved_clone_delete_${voice.id}"),
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
}
