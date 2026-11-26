package com.example.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowDropUp
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AmbientSoundTrack
import com.example.audio.ClonedVoiceProfile
import com.example.audio.ImportedVoiceSample
import com.example.audio.OnnxVoiceEngineMode
import com.example.data.CustomAmbientTrack
import com.example.data.LibraryManager
import com.example.data.ReaderPreferences
import com.example.data.ReaderVoiceProfile
import com.example.data.TtsEngineType
import com.example.service.BookAudioPlaybackService
import com.example.service.BookAudioPlaybackState
import com.example.ui.PendingDraftClonedVoice
import com.example.ui.components.AdvancedCustomVoiceSettingsSection
import com.example.ui.components.DeviceOfflineVoicesSection
import com.example.ui.components.Neo3DDropdownMenu
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.StudioVoiceCloneSection
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoHardShadowColor
import com.example.ui.components.neoInkBorderColor
import com.example.ui.components.neoPaperSurfaceColor
import com.example.ui.components.neoSecondaryPaperColor
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosRed
import kotlin.math.roundToInt

/**
 * Tab 3: Studio — Không gian cài đặt âm thanh & giọng đọc riêng biệt chuẩn Swiss Neo-Brutalist 3D:
 * 1. Bộ chọn chế độ độc lập: [Giọng Hệ thống] vs [AI Nhân bản]
 *    - Chọn Hệ thống -> khối AI Nhân bản tối mờ và khóa hoàn toàn.
 *    - Chọn AI Nhân bản -> khối Hệ thống tối mờ và khóa hoàn toàn (tắt 100% tiếng hệ thống).
 * 2. Cài đặt Giọng Hệ thống (Giọng dẫn truyện + Nghe thử bật/tắt, Tùy chỉnh nâng cao, Giọng gốc Offline).
 * 3. Cài đặt & Tạo giọng AI Nhân bản (Ghi âm / Import + Nghe thử mẫu + Tạo / Nghe thử / Lưu + Danh sách giọng AI).
 * 4. Âm thanh nền mặc định & âm lượng.
 */
@Composable
fun StudioScreen(
    readerPrefs: ReaderPreferences,
    audioPlaybackState: BookAudioPlaybackState,
    clonedVoices: List<ClonedVoiceProfile>,
    voiceCloneEngineMode: OnnxVoiceEngineMode,
    voiceCloneTargetSampleRate: Int,
    isExtractingVoiceClone: Boolean,
    isImportingVoiceFile: Boolean,
    draftClonedVoice: PendingDraftClonedVoice?,
    onSelectTtsEngine: (TtsEngineType) -> Unit,
    onSelectNarratorVoice: (ReaderVoiceProfile) -> Unit,
    onSelectSystemVoice: (String?) -> Unit,
    onToggleAdvancedCustom: (Boolean) -> Unit,
    onAdvancedPitchChanged: (Float) -> Unit,
    onAdvancedRateChanged: (Float) -> Unit,
    onSentencePauseChanged: (Int) -> Unit,
    onResetAdvancedDefaults: () -> Unit,
    onImportVoiceAudio: (Uri, (ImportedVoiceSample) -> Unit) -> Unit,
    onCreateDraftClonedVoice: (name: String, samples: FloatArray, sampleRate: Int) -> Unit,
    onClearDraftClonedVoice: () -> Unit,
    onCommitSaveClonedVoices: (onSaved: () -> Unit) -> Unit,
    onSelectClonedVoice: (String?) -> Unit,
    onDeleteClonedVoice: (String) -> Unit,
    onSelectAmbientSound: (AmbientSoundTrack) -> Unit,
    customAmbientTracks: List<CustomAmbientTrack> = emptyList(),
    isImportingAmbient: Boolean = false,
    onImportCustomAmbient: (Uri) -> Unit = {},
    onSelectCustomAmbient: (String) -> Unit = {},
    onDeleteCustomAmbient: (String) -> Unit = {},
    onChangeAmbientVolume: (Float) -> Unit,
    onToggleAutoPlayAmbient: (Boolean) -> Unit,
    lastAiVerbatimError: String? = null,
    onOpenAiDiagnostics: () -> Unit = {}
) {
    val context = LocalContext.current
    val isAiMode = readerPrefs.ttsEngineType == TtsEngineType.AI_TTS
    val isSystemMode = !isAiMode
    var isPreviewingStudioAmbient by remember { mutableStateOf(false) }

    val studioAmbientImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onImportCustomAmbient(uri)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            BookAudioPlaybackService.setAmbientPreview(context, false)
        }
    }

    // Tự động nạp danh sách giọng Offline trên máy khi mở Studio
    LaunchedEffect(Unit) {
        BookAudioPlaybackService.refreshDeviceVoices(context)
    }

    val systemSectionAlpha by animateFloatAsState(
        targetValue = if (isSystemMode) 1f else 0.36f,
        label = "studio_system_section_alpha"
    )

    val paperBg = neoPaperSurfaceColor()
    val trayBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val shadowCol = neoHardShadowColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("studio_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Studio",
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    letterSpacing = (-0.4).sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag("studio_header_title")
                )
                Text(
                    text = "Cài đặt âm thanh, giọng Hệ thống & nhân bản AI 100% Offline",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = secondaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // =================================================================
            // 1. CHỌN CHẾ ĐỘ GIỌNG ĐỌC (ĐỘC LẬP: HỆ THỐNG <-> AI NHÂN BẢN)
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 3.dp, bottom = 3.dp)
                    .neoHardOffsetShadow(cornerRadius = 15.dp, offsetX = 3.dp, offsetY = 3.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(paperBg)
                    .border(1.5.dp, borderCol, RoundedCornerShape(15.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Chế độ giọng đọc toàn ứng dụng",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryInk,
                        modifier = Modifier.weight(1f)
                    )
                    NeoBrutalistPressBox(
                        onClick = onOpenAiDiagnostics,
                        isPill = true,
                        shadowOffset = 2.dp,
                        pressDepth = 1.5.dp,
                        borderWidth = 1.3.dp,
                        containerColor = trayBg,
                        borderColor = borderCol,
                        shadowColor = shadowCol,
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("studio_open_ai_diagnostics_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
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
                                text = "Kiểm tra hệ thống AI",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryInk,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TtsEngineType.entries.forEach { engine ->
                        val selected = readerPrefs.ttsEngineType == engine
                        val aiTab = engine == TtsEngineType.AI_TTS
                        NeoBrutalistPressBox(
                            onClick = { onSelectTtsEngine(engine) },
                            isPill = false,
                            cornerRadius = 12.dp,
                            shadowOffset = 3.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = if (selected) IosBlue else trayBg,
                            borderColor = borderCol,
                            shadowColor = shadowCol,
                            contentAlignment = Alignment.TopStart,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(if (aiTab) "studio_mode_ai" else "studio_mode_system")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (aiTab) Icons.Rounded.AutoAwesome else Icons.Rounded.RecordVoiceOver,
                                            contentDescription = null,
                                            tint = if (selected) Color.White else primaryInk,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = if (aiTab) "AI Nhân bản" else "Hệ thống",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (selected) Color.White else primaryInk,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Đang bật",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = if (aiTab) {
                                        "Chỉ đọc giọng AI • Tắt toàn bộ tiếng hệ thống"
                                    } else {
                                        "Chỉ đọc giọng TTS gốc trên thiết bị"
                                    },
                                    fontSize = 9.5.sp,
                                    lineHeight = 13.sp,
                                    color = if (selected) Color.White.copy(alpha = 0.88f) else secondaryInk,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 2. KHỐI CÀI ĐẶT GIỌNG HỆ THỐNG (TỐI MỜ & THU GỌN DƯỚI TAM GIÁC KHI BẬT AI NHÂN BẢN)
            // =================================================================
            var systemSectionExpanded by remember { mutableStateOf(isSystemMode) }
            LaunchedEffect(isSystemMode) {
                systemSectionExpanded = isSystemMode
            }
            val showSystemContent = systemSectionExpanded && isSystemMode

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(systemSectionAlpha)
                    .testTag("studio_system_voice_section")
            ) {
                var narratorExpanded by remember { mutableStateOf(false) }
                val isPreviewingStudioSystem = audioPlaybackState.activePreviewTag == "studio_system_narrator"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 3.dp, bottom = 3.dp)
                        .neoHardOffsetShadow(
                            cornerRadius = 15.dp,
                            offsetX = 3.dp,
                            offsetY = 3.dp,
                            shadowColor = if (isSystemMode) shadowCol else Color.Transparent
                        )
                        .clip(RoundedCornerShape(15.dp))
                        .background(paperBg)
                        .border(1.5.dp, borderCol, RoundedCornerShape(15.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = isSystemMode) {
                                systemSectionExpanded = !systemSectionExpanded
                            }
                            .testTag("studio_system_section_header"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isSystemMode) IosBlue else trayBg)
                                .border(1.5.dp, borderCol, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RecordVoiceOver,
                                contentDescription = null,
                                tint = if (isSystemMode) Color.White else secondaryInk,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Cài đặt Giọng Hệ thống",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = primaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (showSystemContent) IosBlue.copy(alpha = 0.14f) else trayBg)
                                        .border(1.dp, borderCol, RoundedCornerShape(6.dp))
                                        .testTag("studio_system_collapse_triangle"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (showSystemContent) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                                        contentDescription = if (showSystemContent) "Thu gọn cài đặt giọng hệ thống" else "Mở rộng cài đặt giọng hệ thống",
                                        tint = if (isSystemMode) IosBlue else secondaryInk,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isSystemMode) {
                                    "Chọn giọng dẫn truyện, tinh chỉnh & giọng gốc offline"
                                } else {
                                    "Đang khóa & thu gọn (đang bật chế độ AI Nhân bản)"
                                },
                                fontSize = 10.sp,
                                color = secondaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showSystemContent,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    NeoBrutalistPressBox(
                                        onClick = { narratorExpanded = true },
                                        enabled = isSystemMode,
                                        isPill = false,
                                        cornerRadius = 12.dp,
                                        shadowOffset = 2.5.dp,
                                        pressDepth = 1.8.dp,
                                        borderWidth = 1.5.dp,
                                        containerColor = trayBg,
                                        borderColor = borderCol,
                                        shadowColor = if (isSystemMode) shadowCol else Color.Transparent,
                                        contentAlignment = Alignment.CenterStart,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("studio_system_narrator_dropdown")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = readerPrefs.voiceProfile.displayName,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryInk,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                                contentDescription = "Chọn giọng hệ thống",
                                                tint = IosBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Neo3DDropdownMenu(
                                        expanded = narratorExpanded && isSystemMode,
                                        onDismissRequest = { narratorExpanded = false },
                                        containerColor = paperBg
                                    ) {
                                        ReaderVoiceProfile.entries.forEach { profile ->
                                            val selected = readerPrefs.voiceProfile == profile
                                            DropdownMenuItem(
                                                text = {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Text(
                                                            text = profile.displayName,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                            color = if (selected) IosBlue else primaryInk
                                                        )
                                                        if (selected) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Check,
                                                                contentDescription = null,
                                                                tint = IosBlue,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    onSelectNarratorVoice(profile)
                                                    narratorExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Nút Nghe thử dạng công tắc (bấm 1 lần phát, bấm lần 2 tắt ngay)
                                NeoBrutalistPressBox(
                                    onClick = {
                                        BookAudioPlaybackService.previewVoice(
                                            context = context,
                                            profile = readerPrefs.voiceProfile,
                                            pitch = readerPrefs.resolvePlaybackPitch(readerPrefs.voiceProfile),
                                            rate = readerPrefs.resolvePlaybackRate(readerPrefs.voiceProfile),
                                            pauseMs = readerPrefs.sentencePauseMs,
                                            previewTag = "studio_system_narrator"
                                        )
                                    },
                                    enabled = isSystemMode,
                                    isPill = false,
                                    cornerRadius = 12.dp,
                                    shadowOffset = 2.5.dp,
                                    pressDepth = 1.8.dp,
                                    borderWidth = 1.5.dp,
                                    containerColor = if (isPreviewingStudioSystem) IosBlue else trayBg,
                                    borderColor = borderCol,
                                    shadowColor = if (isSystemMode) shadowCol else Color.Transparent,
                                    modifier = Modifier.testTag("studio_system_narrator_preview_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPreviewingStudioSystem) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                            contentDescription = if (isPreviewingStudioSystem) "Dừng nghe thử" else "Nghe thử giọng hệ thống",
                                            tint = if (isPreviewingStudioSystem) Color.White else IosBlue,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = if (isPreviewingStudioSystem) "Dừng" else "Nghe thử",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPreviewingStudioSystem) Color.White else primaryInk
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showSystemContent,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tùy chỉnh nâng cao (Pitch, Tốc độ, Ngắt câu + Nghe thử bật/tắt + OK)
                        AdvancedCustomVoiceSettingsSection(
                            readerPrefs = readerPrefs,
                            onToggleAdvancedEnabled = { enabled ->
                                if (enabled && audioPlaybackState.isPlaying) {
                                    BookAudioPlaybackService.pause(context)
                                }
                                onToggleAdvancedCustom(enabled)
                            },
                            onPitchChanged = onAdvancedPitchChanged,
                            onSpeechRateChanged = onAdvancedRateChanged,
                            onSentencePauseChanged = onSentencePauseChanged,
                            onPreviewAdvanced = { pitch, rate, pauseMs ->
                                BookAudioPlaybackService.previewVoice(
                                    context = context,
                                    profile = readerPrefs.voiceProfile,
                                    pitch = pitch,
                                    rate = rate,
                                    pauseMs = pauseMs,
                                    systemVoiceName = readerPrefs.selectedSystemVoiceName,
                                    previewTag = "studio_advanced_preview"
                                )
                            },
                            onResetDefaults = onResetAdvancedDefaults,
                            isPreviewPlaying = audioPlaybackState.activePreviewTag == "studio_advanced_preview",
                            sectionEnabled = isSystemMode
                        )

                        // Danh sách giọng gốc trên thiết bị (Offline)
                        DeviceOfflineVoicesSection(
                            availableDeviceVoices = audioPlaybackState.availableDeviceVoices,
                            selectedSystemVoiceName = readerPrefs.selectedSystemVoiceName,
                            onSelectSystemVoice = onSelectSystemVoice,
                            onRefreshDeviceVoices = { BookAudioPlaybackService.refreshDeviceVoices(context) },
                            onPreviewSystemVoice = { voiceName ->
                                BookAudioPlaybackService.previewVoice(
                                    context = context,
                                    profile = readerPrefs.voiceProfile,
                                    pitch = readerPrefs.resolvePlaybackPitch(readerPrefs.voiceProfile),
                                    rate = readerPrefs.resolvePlaybackRate(readerPrefs.voiceProfile),
                                    pauseMs = readerPrefs.sentencePauseMs,
                                    systemVoiceName = voiceName,
                                    previewTag = "device_voice_${voiceName.orEmpty()}"
                                )
                            },
                            activePreviewTag = audioPlaybackState.activePreviewTag,
                            sectionEnabled = isSystemMode
                        )
                    }
                }
            }

            // =================================================================
            // 3. KHỐI TẠO & QUẢN LÝ GIỌNG AI NHÂN BẢN (TỐI MỜ & KHÓA KHI BẬT HỆ THỐNG)
            // =================================================================
            StudioVoiceCloneSection(
                sectionEnabled = isAiMode,
                clonedVoices = clonedVoices,
                selectedClonedVoiceId = readerPrefs.selectedClonedVoiceId,
                engineMode = voiceCloneEngineMode,
                targetSampleRate = voiceCloneTargetSampleRate,
                isExtractingEmbedding = isExtractingVoiceClone,
                isImportingFile = isImportingVoiceFile,
                draftClonedVoice = draftClonedVoice,
                activeServicePreviewTag = audioPlaybackState.activePreviewTag,
                lastAiVerbatimError = lastAiVerbatimError,
                onOpenAiDiagnostics = onOpenAiDiagnostics,
                onImportAudioUri = onImportVoiceAudio,
                onCreateDraftVoice = onCreateDraftClonedVoice,
                onClearDraftVoice = onClearDraftClonedVoice,
                onSaveAllVoices = onCommitSaveClonedVoices,
                onSelectClonedVoice = onSelectClonedVoice,
                onTogglePreviewSavedVoice = { voice ->
                    BookAudioPlaybackService.previewClonedVoice(
                        context = context,
                        voiceId = voice.id,
                        previewTag = "studio_cloned_${voice.id}"
                    )
                },
                onDeleteClonedVoice = onDeleteClonedVoice
            )

            // =================================================================
            // 4. ÂM THANH NỀN MẶC ĐỊNH
            // =================================================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 3.dp, bottom = 3.dp)
                    .neoHardOffsetShadow(cornerRadius = 15.dp, offsetX = 3.dp, offsetY = 3.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(paperBg)
                    .border(1.5.dp, borderCol, RoundedCornerShape(15.dp))
                    .padding(12.dp)
                    .testTag("studio_ambient_section"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(IosBlue.copy(alpha = 0.14f))
                                .border(1.2.dp, borderCol, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = IosBlue,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Âm thanh nền khi nghe sách",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryInk
                            )
                            Text(
                                text = "Tự động phát cùng giọng đọc sách",
                                fontSize = 10.sp,
                                color = secondaryInk
                            )
                        }
                    }

                    Switch(
                        checked = readerPrefs.autoPlayAmbientWithAudio,
                        onCheckedChange = onToggleAutoPlayAmbient,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = IosBlue,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFD1D1D6),
                            uncheckedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .scale(0.75f)
                            .height(26.dp)
                            .testTag("studio_ambient_autoplay_switch")
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AmbientSoundTrack.entries.forEach { track ->
                        val selected = readerPrefs.selectedCustomAmbientId.isNullOrBlank() &&
                            readerPrefs.ambientSound == track
                        val trackIcon = when (track) {
                            AmbientSoundTrack.NONE -> Icons.Rounded.VolumeOff
                            AmbientSoundTrack.SOFT_RAIN -> Icons.Rounded.WaterDrop
                            AmbientSoundTrack.MELODIC_PAD -> Icons.Rounded.MusicNote
                            AmbientSoundTrack.NIGHT_STREAM -> Icons.Rounded.GraphicEq
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (selected) IosBlue.copy(alpha = 0.12f) else trayBg)
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) IosBlue else borderCol,
                                    shape = RoundedCornerShape(11.dp)
                                )
                                .clickable {
                                    onSelectAmbientSound(track)
                                    if (track == AmbientSoundTrack.NONE && isPreviewingStudioAmbient) {
                                        isPreviewingStudioAmbient = false
                                        BookAudioPlaybackService.setAmbientPreview(context, false)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("studio_ambient_track_${track.storageKey}"),
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
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) IosBlue else paperBg)
                                        .border(1.dp, borderCol, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = trackIcon,
                                        contentDescription = null,
                                        tint = if (selected) Color.White else secondaryInk,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = track.label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) IosBlue else primaryInk
                                    )
                                    Text(
                                        text = track.subtitle,
                                        fontSize = 10.sp,
                                        color = secondaryInk
                                    )
                                }
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Đang chọn",
                                    tint = IosBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Danh sách nhạc nền do người dùng tự import (.mp3 / .m4a / .wav / .ogg)
                    customAmbientTracks.forEach { customTrack ->
                        val selected = readerPrefs.selectedCustomAmbientId == customTrack.id
                        val isThisPreviewing = selected && isPreviewingStudioAmbient
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (selected) IosBlue.copy(alpha = 0.12f) else trayBg)
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) IosBlue else borderCol,
                                    shape = RoundedCornerShape(11.dp)
                                )
                                .clickable { onSelectCustomAmbient(customTrack.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("studio_custom_ambient_${customTrack.id}"),
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
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (selected) IosBlue else paperBg)
                                        .border(1.dp, borderCol, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AudioFile,
                                        contentDescription = null,
                                        tint = if (selected) Color.White else IosBlue,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = customTrack.displayName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) IosBlue else primaryInk,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Nhạc nền đã import • Lặp liên tục offline",
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
                                    onClick = {
                                        if (isThisPreviewing) {
                                            isPreviewingStudioAmbient = false
                                            BookAudioPlaybackService.setAmbientPreview(context, false)
                                        } else {
                                            onSelectCustomAmbient(customTrack.id)
                                            isPreviewingStudioAmbient = true
                                            BookAudioPlaybackService.setAmbientPreview(context, true)
                                        }
                                    },
                                    isCircle = true,
                                    shadowOffset = 2.dp,
                                    pressDepth = 1.5.dp,
                                    borderWidth = 1.2.dp,
                                    containerColor = if (isThisPreviewing) IosBlue else paperBg,
                                    borderColor = borderCol,
                                    shadowColor = shadowCol,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("studio_custom_ambient_preview_${customTrack.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isThisPreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                        contentDescription = if (isThisPreviewing) "Dừng nghe thử nhạc nền" else "Nghe thử nhạc nền",
                                        tint = if (isThisPreviewing) Color.White else primaryInk,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            if (isThisPreviewing) {
                                                isPreviewingStudioAmbient = false
                                                BookAudioPlaybackService.setAmbientPreview(context, false)
                                            }
                                            onDeleteCustomAmbient(customTrack.id)
                                        }
                                        .testTag("studio_custom_ambient_delete_${customTrack.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Xóa nhạc nền ${customTrack.displayName}",
                                        tint = IosRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Nút Import nhạc nền từ máy (3D Neo-Brutalist)
                    NeoBrutalistPressBox(
                        onClick = {
                            if (!isImportingAmbient) {
                                studioAmbientImportLauncher.launch(arrayOf("audio/*"))
                            }
                        },
                        enabled = !isImportingAmbient,
                        isPill = true,
                        shadowOffset = 2.5.dp,
                        pressDepth = 1.8.dp,
                        borderWidth = 1.5.dp,
                        containerColor = trayBg,
                        borderColor = borderCol,
                        shadowColor = shadowCol,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .testTag("studio_import_ambient_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            if (isImportingAmbient) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.8.dp,
                                    color = IosBlue
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.UploadFile,
                                    contentDescription = null,
                                    tint = IosBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = if (isImportingAmbient) "Đang import nhạc nền…" else "+ Import nhạc nền từ máy (.mp3 / .m4a / .wav)",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryInk,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (readerPrefs.isAnyAmbientActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Âm lượng nền",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryInk
                        )
                        Slider(
                            value = readerPrefs.ambientVolume,
                            onValueChange = onChangeAmbientVolume,
                            valueRange = LibraryManager.MIN_AMBIENT_VOLUME..LibraryManager.MAX_AMBIENT_VOLUME,
                            colors = SliderDefaults.colors(
                                thumbColor = IosBlue,
                                activeTrackColor = IosBlue,
                                inactiveTrackColor = trayBg
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .testTag("studio_ambient_volume_slider")
                        )
                        Text(
                            text = "${(readerPrefs.ambientVolume * 100f).roundToInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = IosBlue
                        )
                    }
                }
            }
        }
    }
}
