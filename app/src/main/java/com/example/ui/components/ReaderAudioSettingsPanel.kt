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
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

/**
 * Bảng Cài đặt nghe sách phong cách iOS tối giản trong trình đọc sách:
 * 1. Giọng người dẫn truyện (dạng mũi tên xổ xuống gọn gàng)
 * 2. Giọng gốc trên thiết bị (Offline • ưu tiên QUALITY_HIGH + nút Tải thêm giọng chất lượng cao)
 * 3. Tùy chỉnh nâng cao (Pitch 0.60–1.40, Tốc độ 0.70–1.80, Ngắt nghỉ 150–800ms + Nghe thử + Khôi phục mặc định)
 * 4. Quét nhân vật (Top 7 nhân vật -> chọn giọng -> OK) + Công tắc Bật/Tắt giọng nhân vật ngay bên dưới
 * 5. Âm thanh nền (Tắt + 3 nhạc nền du dương offline + âm lượng)
 */
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
    onToggleCharacterVoicesEnabled: (Boolean) -> Unit,
    onScanCharactersClick: () -> Unit,
    onConfirmCharacterAssignments: (Map<String, ReaderVoiceProfile>) -> Unit,
    onSelectAmbientSound: (AmbientSoundTrack) -> Unit,
    onChangeAmbientVolume: (Float) -> Unit,
    onClose: () -> Unit
) {
    var narratorDropdownExpanded by remember { mutableStateOf(false) }

    // Draft character -> voice map while the scanned character picker is open
    val draftCharVoices = remember { mutableStateMapOf<String, ReaderVoiceProfile?>() }
    LaunchedEffect(showCharacterVoicePicker, scannedCharacters, savedCharacterAssignments) {
        if (showCharacterVoicePicker) {
            draftCharVoices.clear()
            scannedCharacters.forEachIndexed { idx, char ->
                val existing = savedCharacterAssignments[char.name]
                if (existing != null) {
                    draftCharVoices[char.name] = existing
                } else {
                    val allVoices = ReaderVoiceProfile.entries
                    draftCharVoices[char.name] = allVoices[(idx + 1) % allVoices.size]
                }
            }
        }
    }

    val cardBg = neoSecondaryPaperColor()
    val itemBg = neoPaperSurfaceColor()
    val borderCol = neoInkBorderColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

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
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(IosBlue.copy(alpha = 0.14f))
                        .border(1.2.dp, borderCol, CircleShape),
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
                        fontWeight = FontWeight.ExtraBold,
                        color = primaryInk
                    )
                    Text(
                        text = "Giọng dẫn truyện, tinh chỉnh TTS & âm thanh nền",
                        fontSize = 10.sp,
                        color = secondaryInk
                    )
                }
            }

            Ios3DCircleIconButton(
                onClick = onClose,
                size = 32.dp,
                modifier = Modifier.testTag("audio_settings_close_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Đóng cài đặt nghe",
                    tint = primaryInk,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // =================================================================
            // 1. GIỌNG NGƯỜI DẪN TRUYỆN — Dạng mũi tên xổ xuống (Dropdown)
            // =================================================================
            AudioSectionBlock(
                icon = Icons.Rounded.RecordVoiceOver,
                title = "Giọng người dẫn truyện",
                subtitle = "Chọn giọng mặc định để đọc lời kể chuyện"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        NeoBrutalistPressBox(
                            onClick = { narratorDropdownExpanded = true },
                            isPill = false,
                            cornerRadius = 12.dp,
                            shadowOffset = 3.dp,
                            pressDepth = 2.dp,
                            borderWidth = 1.5.dp,
                            containerColor = itemBg,
                            borderColor = borderCol,
                            shadowColor = neoHardShadowColor(),
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("narrator_voice_dropdown")
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
                                    contentDescription = "Chọn giọng dẫn truyện",
                                    tint = IosBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = narratorDropdownExpanded,
                            onDismissRequest = { narratorDropdownExpanded = false },
                            containerColor = itemBg
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
                                        narratorDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Nút nghe thử nhanh giọng dẫn truyện hiện tại
                    NeoBrutalistPressBox(
                        onClick = {
                            val pitch = readerPrefs.resolvePlaybackPitch(readerPrefs.voiceProfile)
                            onPreviewVoice(readerPrefs.voiceProfile, pitch)
                        },
                        isPill = false,
                        cornerRadius = 12.dp,
                        shadowOffset = 3.dp,
                        pressDepth = 2.dp,
                        borderWidth = 1.5.dp,
                        containerColor = itemBg,
                        borderColor = borderCol,
                        shadowColor = neoHardShadowColor(),
                        modifier = Modifier.testTag("narrator_voice_preview_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Nghe thử giọng dẫn truyện",
                                tint = IosBlue,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Thử",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryInk
                            )
                        }
                    }
                }
            }

            // =================================================================
            // 2. TÙY CHỈNH NÂNG CAO (Gọn 1/2 chiều cao • Bật mới sáng • Nghe thử & OK mới lưu)
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
            // 4. PHÂN VAI NHÂN VẬT (Nút Quét + Công tắc Bật/Tắt giọng nhân vật bên dưới)
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
                    NeoBrutalistPressBox(
                        onClick = onScanCharactersClick,
                        enabled = !isScanningCharacters,
                        isPill = true,
                        shadowOffset = 3.dp,
                        pressDepth = 2.dp,
                        borderWidth = 1.5.dp,
                        containerColor = IosBlue,
                        borderColor = borderCol,
                        shadowColor = neoHardShadowColor(),
                        modifier = Modifier.testTag("audio_scan_characters_button")
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
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            ) {
                // Công tắc Bật / Tắt giọng nhân vật ngay dưới mục Quét
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 3.dp, bottom = 3.dp)
                        .neoHardOffsetShadow(cornerRadius = 12.dp, offsetX = 3.dp, offsetY = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(itemBg)
                        .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chế độ giọng nhân vật",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryInk
                        )
                        Text(
                            text = if (readerPrefs.characterVoicesEnabled) {
                                "Đang bật — Tự đổi giọng theo từng nhân vật đã gán"
                            } else {
                                "Đang tắt — Chỉ dùng giọng người dẫn truyện"
                            },
                            fontSize = 10.sp,
                            color = secondaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = readerPrefs.characterVoicesEnabled,
                        onCheckedChange = onToggleCharacterVoicesEnabled,
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
                            .testTag("character_voices_toggle_switch")
                    )
                }

                // Danh sách chọn giọng cho Top 7 nhân vật — CHỈ hiện khi vừa bấm nút Quét
                AnimatedVisibility(
                    visible = showCharacterVoicePicker,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 3.dp, bottom = 3.dp)
                            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(itemBg)
                            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
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
                                        NeoBrutalistPressBox(
                                            onClick = { expandedMenu = true },
                                            isPill = true,
                                            shadowOffset = 2.dp,
                                            pressDepth = 1.5.dp,
                                            borderWidth = 1.5.dp,
                                            containerColor = cardBg,
                                            borderColor = borderCol,
                                            shadowColor = neoHardShadowColor(),
                                            modifier = Modifier.testTag("character_voice_selector_$idx")
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
                                                    fontWeight = FontWeight.Bold,
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
                                            containerColor = itemBg
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            NeoBrutalistPressBox(
                                onClick = {
                                    val finalMap = LinkedHashMap<String, ReaderVoiceProfile>()
                                    scannedCharacters.forEach { c ->
                                        val chosen = draftCharVoices[c.name]
                                        if (chosen != null) {
                                            finalMap[c.name] = chosen
                                        }
                                    }
                                    onConfirmCharacterAssignments(finalMap)
                                },
                                isPill = true,
                                shadowOffset = 3.dp,
                                pressDepth = 2.dp,
                                borderWidth = 1.5.dp,
                                containerColor = IosBlue,
                                borderColor = borderCol,
                                shadowColor = neoHardShadowColor(),
                                modifier = Modifier.testTag("character_voice_ok_button")
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
            // 5. ÂM THANH NỀN (Tắt + 3 nhạc nền du dương offline)
            // =================================================================
            AudioSectionBlock(
                icon = Icons.Rounded.MusicNote,
                title = "Âm thanh nền",
                subtitle = "Nhạc nền nhẹ nhàng du dương khi nghe đọc sách"
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 3.dp, bottom = 3.dp)
                        .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(itemBg)
                        .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
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
                                    .background(if (selected) IosBlue.copy(alpha = 0.12f) else cardBg)
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) IosBlue else borderCol,
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
                                            .background(if (selected) IosBlue else itemBg)
                                            .border(1.dp, borderCol, CircleShape),
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
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
