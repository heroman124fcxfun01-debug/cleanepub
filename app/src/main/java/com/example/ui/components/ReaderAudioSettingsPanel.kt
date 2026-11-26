package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AmbientSoundTrack
import com.example.audio.DetectedCharacter
import com.example.data.LibraryManager
import com.example.data.ReaderPreferences
import com.example.data.ReaderVoiceProfile
import com.example.service.DeviceOfflineVoice
import com.example.ui.theme.IosBlue
import java.util.Locale
import kotlin.math.abs

/**
 * Full-featured iOS Minimalist Audio Settings Sheet for BookReaderScreen:
 * 1. Giọng người dẫn truyện (Narrator selector)
 * 2. Giọng gốc trên thiết bị (Offline) — xếp hạng QUALITY_HIGH + nút Tải thêm giọng chất lượng cao
 * 3. Tùy chỉnh nâng cao (Pitch 0.60–1.40, Speech Rate 0.70–1.80, Sentence Pause 150–800ms + Nghe thử + Khôi phục mặc định)
 * 4. Tất cả 6 giọng mẫu Nam/Nữ (với bánh răng nhỏ chỉnh cao độ từng giọng)
 * 5. Quét nhân vật (Top 7 nhân vật -> phân vai giọng riêng -> OK)
 * 6. Âm thanh nền (Tắt + 3 bản nhạc nền du dương offline)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderAudioSettingsSheetContent(
    readerPrefs: ReaderPreferences,
    availableDeviceVoices: List<DeviceOfflineVoice>,
    isScanningCharacters: Boolean,
    scannedCharacters: List<DetectedCharacter>,
    showCharacterVoicePicker: Boolean,
    savedCharacterAssignments: Map<String, ReaderVoiceProfile>,
    onSelectNarratorVoice: (ReaderVoiceProfile) -> Unit,
    onSelectSystemVoice: (String?) -> Unit,
    onRefreshDeviceVoices: () -> Unit,
    onPreviewSystemVoice: (String?) -> Unit,
    onToggleAdvancedCustom: (Boolean) -> Unit,
    onAdvancedPitchChanged: (Float) -> Unit,
    onAdvancedRateChanged: (Float) -> Unit,
    onSentencePauseChanged: (Int) -> Unit,
    onPreviewAdvanced: (pitch: Float, rate: Float, pauseMs: Int) -> Unit,
    onResetAdvancedDefaults: () -> Unit,
    onPreviewVoice: (ReaderVoiceProfile, Float) -> Unit,
    onSaveVoicePitch: (ReaderVoiceProfile, Float) -> Unit,
    onResetVoicePitch: (ReaderVoiceProfile) -> Unit,
    onScanCharactersClick: () -> Unit,
    onConfirmCharacterAssignments: (Map<String, ReaderVoiceProfile>) -> Unit,
    onSelectAmbientSound: (AmbientSoundTrack) -> Unit,
    onChangeAmbientVolume: (Float) -> Unit,
    onClose: () -> Unit
) {
    // Which voice currently has its small pitch-settings card expanded
    var tuningVoice by remember { mutableStateOf<ReaderVoiceProfile?>(null) }
    var draftPitch by remember { mutableFloatStateOf(1.0f) }

    // Draft character -> voice map while the scanned character picker is open
    val draftCharVoices = remember { mutableStateMapOf<String, ReaderVoiceProfile?>() }
    LaunchedEffect(showCharacterVoicePicker, scannedCharacters, savedCharacterAssignments) {
        if (showCharacterVoicePicker) {
            draftCharVoices.clear()
            scannedCharacters.forEachIndexed { idx, char ->
                // Restore previously saved voice or suggest a varied voice distinct from narrator
                val existing = savedCharacterAssignments[char.name]
                if (existing != null) {
                    draftCharVoices[char.name] = existing
                } else {
                    val allVoices = ReaderVoiceProfile.entries
                    val suggested = allVoices[(idx + 1) % allVoices.size]
                    draftCharVoices[char.name] = suggested
                }
            }
        }
    }

    val cardBg = Color(0xFFF2F2F7)
    val itemBg = Color(0xFFFFFFFF)
    val borderCol = Color(0xFFE5E5EA)
    val primaryInk = Color(0xFF1C1C1E)
    val secondaryInk = Color(0xFF6E6E73)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("reader_audio_settings_sheet")
    ) {
        // Sheet Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(IosBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column {
                    Text(
                        text = "Cài đặt nghe sách",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk
                    )
                    Text(
                        text = "Giọng đọc, phân vai nhân vật & âm thanh nền",
                        fontSize = 10.sp,
                        color = secondaryInk
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(cardBg)
                    .clickable(onClick = onClose)
                    .testTag("audio_settings_close_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Đóng cài đặt nghe",
                    tint = secondaryInk,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = borderCol)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // =================================================================
            // 1. CHỌN GIỌNG NGƯỜI DẪN TRUYỆN (Narrator Voice)
            // =================================================================
            AudioSectionBlock(
                icon = Icons.Rounded.RecordVoiceOver,
                title = "Giọng người dẫn truyện",
                subtitle = "Đọc lời kể chuyện và các đoạn không có nhân vật"
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReaderVoiceProfile.entries.forEach { profile ->
                        val isNarrator = readerPrefs.voiceProfile == profile
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (isNarrator) IosBlue else itemBg,
                            border = BorderStroke(
                                width = 0.6.dp,
                                color = if (isNarrator) IosBlue else borderCol
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onSelectNarratorVoice(profile) }
                                .testTag("narrator_voice_${profile.storageKey}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isNarrator) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = profile.shortLabel,
                                    fontSize = 11.sp,
                                    fontWeight = if (isNarrator) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isNarrator) Color.White else primaryInk,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 1B. GIỌNG GỐC TRÊN THIẾT BỊ (OFFLINE • ƯU TIÊN QUALITY_HIGH)
            // =================================================================
            DeviceOfflineVoicesSection(
                availableDeviceVoices = availableDeviceVoices,
                selectedSystemVoiceName = readerPrefs.selectedSystemVoiceName,
                onSelectSystemVoice = onSelectSystemVoice,
                onRefreshDeviceVoices = onRefreshDeviceVoices,
                onPreviewSystemVoice = onPreviewSystemVoice
            )

            // =================================================================
            // 1C. TÙY CHỈNH NÂNG CAO (PITCH, TỐC ĐỘ, NGẮT NGHỈ GIỮA CÁC CÂU)
            // =================================================================
            AdvancedCustomVoiceSettingsSection(
                readerPrefs = readerPrefs,
                onToggleAdvancedEnabled = onToggleAdvancedCustom,
                onPitchChanged = onAdvancedPitchChanged,
                onSpeechRateChanged = onAdvancedRateChanged,
                onSentencePauseChanged = onSentencePauseChanged,
                onPreviewAdvanced = onPreviewAdvanced,
                onResetDefaults = onResetAdvancedDefaults
            )

            // =================================================================
            // 2. TẤT CẢ GIỌNG (With small gear button beside each voice -> Pitch + Lưu + Reset)
            // =================================================================
            AudioSectionBlock(
                icon = Icons.Rounded.GraphicEq,
                title = "Tất cả giọng",
                subtitle = "Chạm bánh răng nhỏ để tùy chỉnh độ cao bổng từng giọng"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(itemBg)
                        .border(0.5.dp, borderCol, RoundedCornerShape(14.dp))
                ) {
                    ReaderVoiceProfile.entries.forEachIndexed { idx, profile ->
                        val currentPitch = readerPrefs.effectivePitchFor(profile)
                        val isCustomized = readerPrefs.customVoicePitches.containsKey(profile.storageKey) &&
                            abs(currentPitch - profile.pitch) > 0.01f
                        val isNarrator = readerPrefs.voiceProfile == profile
                        val isTuning = tuningVoice == profile

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectNarratorVoice(profile) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = profile.displayName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isNarrator) IosBlue else primaryInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isNarrator) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(IosBlue.copy(alpha = 0.12f))
                                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = "Dẫn truyện",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = IosBlue
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (isCustomized) {
                                            "Cao độ: ${formatPitchLabel(currentPitch)} (Đã tùy chỉnh)"
                                        } else {
                                            "Cao độ mặc định: ${formatPitchLabel(currentPitch)}"
                                        },
                                        fontSize = 10.sp,
                                        color = if (isCustomized) IosBlue else secondaryInk
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // Mini Preview voice button
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(cardBg)
                                            .clickable { onPreviewVoice(profile, currentPitch) }
                                            .testTag("voice_preview_${profile.storageKey}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = "Nghe thử ${profile.shortLabel}",
                                            tint = primaryInk,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // Small Gear Settings icon beside each voice for Pitch Tuning
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(if (isTuning) IosBlue else cardBg)
                                            .clickable {
                                                if (isTuning) {
                                                    tuningVoice = null
                                                } else {
                                                    tuningVoice = profile
                                                    draftPitch = currentPitch
                                                }
                                            }
                                            .testTag("voice_tune_${profile.storageKey}"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = "Chỉnh độ cao bổng ${profile.shortLabel}",
                                            tint = if (isTuning) Color.White else secondaryInk,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }

                            // Expandable Inline Pitch Tuner (Độ cao bổng + Nghe thử + Reset + Lưu)
                            AnimatedVisibility(
                                visible = isTuning,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(cardBg)
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Độ cao bổng (${profile.shortLabel})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = primaryInk
                                        )
                                        Text(
                                            text = "${formatPitchLabel(draftPitch)} • ${describePitchTone(draftPitch)}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = IosBlue
                                        )
                                    }

                                    Slider(
                                        value = draftPitch,
                                        onValueChange = { draftPitch = it },
                                        valueRange = LibraryManager.MIN_VOICE_PITCH..LibraryManager.MAX_VOICE_PITCH,
                                        colors = SliderDefaults.colors(
                                            thumbColor = IosBlue,
                                            activeTrackColor = IosBlue,
                                            inactiveTrackColor = Color(0xFFD1D1D6)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(28.dp)
                                            .testTag("voice_pitch_slider_${profile.storageKey}")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Trầm sâu",
                                            fontSize = 9.sp,
                                            color = secondaryInk
                                        )
                                        Text(
                                            text = "Mặc định (${formatPitchLabel(profile.pitch)})",
                                            fontSize = 9.sp,
                                            color = secondaryInk
                                        )
                                        Text(
                                            text = "Cao bổng",
                                            fontSize = 9.sp,
                                            color = secondaryInk
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Nghe thử nút nhỏ
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = itemBg,
                                            border = BorderStroke(0.5.dp, borderCol),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable { onPreviewVoice(profile, draftPitch) }
                                                .testTag("voice_pitch_preview_${profile.storageKey}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.PlayArrow,
                                                    contentDescription = null,
                                                    tint = primaryInk,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = "Thử",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = primaryInk
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.weight(1f))

                                        // Reset button
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = itemBg,
                                            border = BorderStroke(0.5.dp, borderCol),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable {
                                                    draftPitch = profile.pitch
                                                    onResetVoicePitch(profile)
                                                }
                                                .testTag("voice_pitch_reset_${profile.storageKey}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.RestartAlt,
                                                    contentDescription = null,
                                                    tint = secondaryInk,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = "Reset",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = primaryInk
                                                )
                                            }
                                        }

                                        // Lưu button
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = IosBlue,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable {
                                                    onSaveVoicePitch(profile, draftPitch)
                                                    tuningVoice = null
                                                }
                                                .testTag("voice_pitch_save_${profile.storageKey}")
                                        ) {
                                            Text(
                                                text = "Lưu",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (idx < ReaderVoiceProfile.entries.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = borderCol,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 3. QUÉT NHÂN VẬT TRUYỆN (Top 7 Characters)
            //    Hidden completely until user taps the Scan ("Quét") button!
            // =================================================================
            AudioSectionBlock(
                icon = Icons.Rounded.DocumentScanner,
                title = "Phân vai nhân vật",
                subtitle = if (savedCharacterAssignments.isNotEmpty() && !showCharacterVoicePicker) {
                    "Đã gán giọng cho ${savedCharacterAssignments.size} nhân vật • Bấm Quét để chỉnh"
                } else {
                    "Quét truyện tìm 7 nhân vật xuất hiện nhiều nhất"
                },
                trailingAction = {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = IosBlue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(enabled = !isScanningCharacters, onClick = onScanCharactersClick)
                            .testTag("audio_scan_characters_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isScanningCharacters) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 1.8.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.DocumentScanner,
                                    contentDescription = "Quét nhân vật truyện",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = if (isScanningCharacters) "Đang quét..." else "Quét",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }
                }
            ) {
                // ONLY shown after user explicitly taps "Quét"!
                AnimatedVisibility(
                    visible = showCharacterVoicePicker,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(itemBg)
                            .border(0.5.dp, borderCol, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                            .testTag("character_voice_picker_panel"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (scannedCharacters.isEmpty()) {
                            Text(
                                text = "Không tìm thấy tên nhân vật nổi bật trong nội dung sách này.",
                                fontSize = 11.sp,
                                color = secondaryInk
                            )
                        } else {
                            Text(
                                text = "Top ${scannedCharacters.size} nhân vật nổi bật — Chọn giọng riêng:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = primaryInk
                            )

                            scannedCharacters.forEachIndexed { idx, character ->
                                var expandedMenu by remember(character.name) { mutableStateOf(false) }
                                val assignedVoice = draftCharVoices[character.name]

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
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
                                                .background(IosBlue.copy(alpha = 0.12f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = IosBlue
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = character.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = primaryInk,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${character.mentionCount} lần xuất hiện",
                                                fontSize = 9.sp,
                                                color = secondaryInk
                                            )
                                        }
                                    }

                                    Box {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = cardBg,
                                            border = BorderStroke(0.5.dp, borderCol),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .clickable { expandedMenu = true }
                                                .testTag("character_voice_selector_$idx")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Person,
                                                    contentDescription = null,
                                                    tint = IosBlue,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = assignedVoice?.shortLabel ?: "Dẫn truyện",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = primaryInk,
                                                    maxLines = 1
                                                )
                                                Icon(
                                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                                    contentDescription = "Chọn giọng",
                                                    tint = secondaryInk,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = expandedMenu,
                                            onDismissRequest = { expandedMenu = false },
                                            containerColor = Color.White
                                        ) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = "Mặc định (Người dẫn truyện)",
                                                        fontSize = 12.sp,
                                                        color = if (assignedVoice == null) IosBlue else primaryInk
                                                    )
                                                },
                                                onClick = {
                                                    draftCharVoices[character.name] = null
                                                    expandedMenu = false
                                                }
                                            )
                                            ReaderVoiceProfile.entries.forEach { v ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = v.displayName,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (assignedVoice == v) {
                                                                FontWeight.SemiBold
                                                            } else {
                                                                FontWeight.Normal
                                                            },
                                                            color = if (assignedVoice == v) IosBlue else primaryInk
                                                        )
                                                    },
                                                    onClick = {
                                                        draftCharVoices[character.name] = v
                                                        expandedMenu = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (idx < scannedCharacters.lastIndex) {
                                    HorizontalDivider(thickness = 0.5.dp, color = borderCol)
                                }
                            }
                        }

                        // Confirm "OK" button to apply & close character picker
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = IosBlue,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        val finalMap = LinkedHashMap<String, ReaderVoiceProfile>()
                                        scannedCharacters.forEach { c ->
                                            val chosen = draftCharVoices[c.name]
                                            if (chosen != null) {
                                                finalMap[c.name] = chosen
                                            }
                                        }
                                        onConfirmCharacterAssignments(finalMap)
                                    }
                                    .testTag("character_voice_ok_button")
                            ) {
                                Text(
                                    text = "OK",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 7.dp)
                                )
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 4. ÂM THANH NỀN (Tắt + 3 gentle melodious offline background sounds)
            // =================================================================
            AudioSectionBlock(
                icon = Icons.Rounded.MusicNote,
                title = "Âm thanh nền",
                subtitle = "Nhạc nền nhẹ nhàng du dương khi nghe đọc sách"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(itemBg)
                        .border(0.5.dp, borderCol, RoundedCornerShape(14.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val tracks = AmbientSoundTrack.entries
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        tracks.forEach { track ->
                            val selected = readerPrefs.ambientSound == track
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
                                    .background(if (selected) IosBlue.copy(alpha = 0.10f) else cardBg)
                                    .border(
                                        width = if (selected) 1.dp else 0.5.dp,
                                        color = if (selected) IosBlue else Color.Transparent,
                                        shape = RoundedCornerShape(11.dp)
                                    )
                                    .clickable { onSelectAmbientSound(track) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("ambient_track_${track.storageKey}"),
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
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) IosBlue else Color.White),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = trackIcon,
                                            contentDescription = null,
                                            tint = if (selected) Color.White else secondaryInk,
                                            modifier = Modifier.size(14.dp)
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
                    }

                    AnimatedVisibility(visible = readerPrefs.ambientSound != AmbientSoundTrack.NONE) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Âm lượng nền",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = secondaryInk
                                )
                                Text(
                                    text = "${((readerPrefs.ambientVolume / LibraryManager.MAX_AMBIENT_VOLUME) * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IosBlue
                                )
                            }
                            Slider(
                                value = readerPrefs.ambientVolume,
                                onValueChange = onChangeAmbientVolume,
                                valueRange = LibraryManager.MIN_AMBIENT_VOLUME..LibraryManager.MAX_AMBIENT_VOLUME,
                                colors = SliderDefaults.colors(
                                    thumbColor = IosBlue,
                                    activeTrackColor = IosBlue,
                                    inactiveTrackColor = Color(0xFFD1D1D6)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(26.dp)
                                    .testTag("ambient_volume_slider")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSectionBlock(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = IosBlue,
                    modifier = Modifier.size(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1C1C1E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = Color(0xFF6E6E73),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailingAction != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingAction()
            }
        }
        content()
    }
}

private fun formatPitchLabel(pitch: Float): String {
    return String.format(Locale.US, "%.2f", pitch)
}

private fun describePitchTone(pitch: Float): String {
    return when {
        pitch < 0.78f -> "Trầm sâu"
        pitch < 0.94f -> "Trầm ấm"
        pitch <= 1.10f -> "Tự nhiên"
        pitch <= 1.30f -> "Thanh sáng"
        else -> "Cao bổng"
    }
}
