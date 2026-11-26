package com.example.ui.components

import android.content.Intent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SpeakerPhone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LibraryManager
import com.example.data.ReaderPreferences
import com.example.service.DeviceOfflineVoice
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hai mục nâng cấp chuyên sâu cho Android Offline TTS trong bảng Cài đặt nghe sách:
 * 1. Danh sách giọng gốc tiếng Việt Offline trên máy (xếp hạng ưu tiên QUALITY_HIGH lên đầu)
 *    + nút "Tải thêm giọng chất lượng cao" gọi `TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA`.
 * 2. Chế độ "Tùy chỉnh nâng cao" gồm 3 thanh trượt:
 *    - Độ cao giọng (Pitch): 0.60f .. 1.40f (bước 0.05f)
 *    - Tốc độ đọc (Speech Rate): 0.70f .. 1.80f (bước 0.05f)
 *    - Thời gian ngắt nghỉ giữa các câu (Sentence Pause): 150ms .. 800ms (`playSilentUtterance`)
 *    kèm nút "Nghe thử" trực tiếp và nút "Khôi phục mặc định".
 */
@Composable
fun DeviceOfflineVoicesSection(
    availableDeviceVoices: List<DeviceOfflineVoice>,
    selectedSystemVoiceName: String?,
    onSelectSystemVoice: (String?) -> Unit,
    onRefreshDeviceVoices: () -> Unit,
    onPreviewSystemVoice: (String?) -> Unit,
    activePreviewTag: String? = null,
    sectionEnabled: Boolean = true
) {
    val context = LocalContext.current
    val cardBg = neoPaperSurfaceColor()
    val itemBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant
    val highQualityGreen = Color(0xFF16A34A)

    val highQualityCount = availableDeviceVoices.count { it.isHighQuality }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp)
            .testTag("device_offline_voices_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Tiêu đề mục + nút làm mới danh sách giọng trên máy
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(IosBlue.copy(alpha = 0.14f))
                        .border(1.2.dp, borderCol, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SpeakerPhone,
                        contentDescription = null,
                        tint = IosBlue,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Column {
                    Text(
                        text = "Giọng gốc trên thiết bị (Offline)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (availableDeviceVoices.isEmpty()) {
                            "Đang dùng giọng tiếng Việt mặc định của hệ thống"
                        } else {
                            "Tìm thấy ${availableDeviceVoices.size} giọng Việt offline ($highQualityCount chất lượng cao)"
                        },
                        fontSize = 10.sp,
                        color = secondaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Ios3DCircleIconButton(
                onClick = { if (sectionEnabled) onRefreshDeviceVoices() },
                size = 30.dp,
                modifier = Modifier.testTag("refresh_device_voices_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Quét lại giọng trên thiết bị",
                    tint = IosBlue,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Danh sách tùy chọn giọng gốc
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(itemBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
        ) {
            // Tùy chọn mặc định: Tự động chọn giọng chất lượng cao nhất theo hồ sơ Nam/Nữ
            val isAutoSelected = selectedSystemVoiceName.isNullOrBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = sectionEnabled) { onSelectSystemVoice(null) }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("device_voice_auto"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = if (isAutoSelected) IosBlue else secondaryInk,
                        modifier = Modifier.size(15.dp)
                    )
                    Column {
                        Text(
                            text = "Tự động (Ưu tiên chất lượng cao)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isAutoSelected) IosBlue else primaryInk
                        )
                        Text(
                            text = "Tự chọn giọng Offline cao nhất theo hồ sơ Nam / Nữ",
                            fontSize = 10.sp,
                            color = secondaryInk
                        )
                    }
                }
                if (isAutoSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Đang chọn",
                        tint = IosBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            availableDeviceVoices.forEachIndexed { idx, voice ->
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = borderCol,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                val isSelected = selectedSystemVoiceName == voice.voiceName
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = sectionEnabled) { onSelectSystemVoice(voice.voiceName) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("device_voice_item_$idx"),
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
                                text = voice.displayLabel,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) IosBlue else primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (voice.isHighQuality) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(highQualityGreen.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = voice.qualityLabel,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = highQualityGreen
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${voice.voiceName} • Offline",
                            fontSize = 9.sp,
                            color = secondaryInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isThisVoicePreviewing = activePreviewTag == "device_voice_${voice.voiceName}"
                        NeoBrutalistPressBox(
                            onClick = { onPreviewSystemVoice(voice.voiceName) },
                            enabled = sectionEnabled,
                            isCircle = true,
                            shadowOffset = 2.dp,
                            pressDepth = 1.5.dp,
                            borderWidth = 1.2.dp,
                            containerColor = if (isThisVoicePreviewing) IosBlue else cardBg,
                            borderColor = borderCol,
                            shadowColor = neoHardShadowColor(),
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("device_voice_preview_$idx")
                        ) {
                            Icon(
                                imageVector = if (isThisVoicePreviewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = if (isThisVoicePreviewing) "Dừng nghe thử" else "Nghe thử ${voice.displayLabel}",
                                tint = if (isThisVoicePreviewing) Color.White else primaryInk,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Đã chọn",
                                tint = IosBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Nút gọi Intent hệ thống ACTION_INSTALL_TTS_DATA để tải thêm gói giọng tiếng Việt chất lượng cao
        NeoBrutalistPressBox(
            onClick = {
                val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching {
                    context.startActivity(installIntent)
                    true
                }.getOrDefault(false)
                if (!launched) {
                    val fallbackIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(fallbackIntent) }.onFailure {
                        Toast.makeText(
                            context,
                            "Vui lòng mở Cài đặt Android → Chuyển văn bản thành giọng nói để tải thêm giọng tiếng Việt.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            enabled = sectionEnabled,
            isPill = true,
            shadowOffset = 3.dp,
            pressDepth = 2.dp,
            borderWidth = 1.5.dp,
            containerColor = cardBg,
            borderColor = borderCol,
            shadowColor = neoHardShadowColor(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("install_tts_data_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = IosBlue,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "Tải thêm giọng chất lượng cao",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = IosBlue
                )
            }
        }
    }
}

/**
 * Bảng "Tùy chỉnh nâng cao" thu gọn 1/2 chiều cao:
 * - Mặc định tối mờ và khóa; khi bật công tắc mới sáng lên cho phép kéo chỉnh (đồng thời tắt đọc sách đang phát).
 * - Kéo thanh trượt & bấm "Nghe thử" chỉ xem trước trên giá trị tạm, chỉ khi bấm "OK" mới lưu và áp dụng thay đổi.
 */
@Composable
fun AdvancedCustomVoiceSettingsSection(
    readerPrefs: ReaderPreferences,
    onToggleAdvancedEnabled: (Boolean) -> Unit,
    onPitchChanged: (Float) -> Unit,
    onSpeechRateChanged: (Float) -> Unit,
    onSentencePauseChanged: (Int) -> Unit,
    onPreviewAdvanced: (pitch: Float, rate: Float, pauseMs: Int) -> Unit,
    onResetDefaults: () -> Unit,
    isPreviewPlaying: Boolean = false,
    sectionEnabled: Boolean = true
) {
    val context = LocalContext.current
    val cardBg = neoPaperSurfaceColor()
    val itemBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant

    val isEnabled = sectionEnabled && readerPrefs.advancedCustomEnabled
    val controlsAlpha by animateFloatAsState(
        targetValue = if (isEnabled) 1f else 0.40f,
        label = "advanced_tts_enabled_alpha"
    )

    // Giá trị tạm khi kéo thanh trượt — chỉ lưu khi người dùng bấm nút OK!
    var draftPitch by remember(readerPrefs.customPitch) { mutableFloatStateOf(readerPrefs.customPitch) }
    var draftRate by remember(readerPrefs.customSpeechRate) { mutableFloatStateOf(readerPrefs.customSpeechRate) }
    var draftPauseMs by remember(readerPrefs.sentencePauseMs) { mutableIntStateOf(readerPrefs.sentencePauseMs) }

    val hasPendingChanges = draftPitch != readerPrefs.customPitch ||
        draftRate != readerPrefs.customSpeechRate ||
        draftPauseMs != readerPrefs.sentencePauseMs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("advanced_tts_settings_section"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Header gọn 1 dòng + Công tắc bật/tắt chế độ Tùy chỉnh nâng cao
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
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (isEnabled) IosBlue else IosBlue.copy(alpha = 0.14f))
                        .border(1.2.dp, borderCol, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (isEnabled) Color.White else IosBlue,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = "Tùy chỉnh nâng cao",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = primaryInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = readerPrefs.advancedCustomEnabled,
                enabled = sectionEnabled,
                onCheckedChange = onToggleAdvancedEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = IosBlue,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D1D6),
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .scale(0.70f)
                    .height(22.dp)
                    .testTag("advanced_tts_mode_switch")
            )
        }

        // Khu vực 3 thanh trượt thu gọn 1/2 chiều cao — chỉ sáng và chỉnh được khi bật công tắc
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(controlsAlpha)
                .clip(RoundedCornerShape(11.dp))
                .background(itemBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(11.dp))
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. Độ cao giọng (Pitch): 0.60f -> 1.40f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Độ cao",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk,
                    maxLines = 1,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = draftPitch,
                    enabled = isEnabled,
                    onValueChange = { raw ->
                        draftPitch = LibraryManager.snapFloatStep(
                            value = raw,
                            step = LibraryManager.STEP_ADVANCED_PITCH,
                            min = LibraryManager.MIN_ADVANCED_PITCH,
                            max = LibraryManager.MAX_ADVANCED_PITCH
                        )
                    },
                    valueRange = LibraryManager.MIN_ADVANCED_PITCH..LibraryManager.MAX_ADVANCED_PITCH,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = IosBlue,
                        activeTrackColor = IosBlue,
                        inactiveTrackColor = Color(0xFFD1D1D6),
                        disabledThumbColor = secondaryInk.copy(alpha = 0.5f),
                        disabledActiveTrackColor = secondaryInk.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .testTag("advanced_pitch_slider")
                )
                Text(
                    text = String.format(Locale.US, "%.2fx", draftPitch),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isEnabled) IosBlue else secondaryInk,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(42.dp)
                )
            }

            // 2. Tốc độ đọc (Speech Rate): 0.70f -> 1.80f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Tốc độ",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk,
                    maxLines = 1,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = draftRate,
                    enabled = isEnabled,
                    onValueChange = { raw ->
                        draftRate = LibraryManager.snapFloatStep(
                            value = raw,
                            step = LibraryManager.STEP_ADVANCED_RATE,
                            min = LibraryManager.MIN_ADVANCED_RATE,
                            max = LibraryManager.MAX_ADVANCED_RATE
                        )
                    },
                    valueRange = LibraryManager.MIN_ADVANCED_RATE..LibraryManager.MAX_ADVANCED_RATE,
                    steps = 21,
                    colors = SliderDefaults.colors(
                        thumbColor = IosBlue,
                        activeTrackColor = IosBlue,
                        inactiveTrackColor = Color(0xFFD1D1D6),
                        disabledThumbColor = secondaryInk.copy(alpha = 0.5f),
                        disabledActiveTrackColor = secondaryInk.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .testTag("advanced_rate_slider")
                )
                Text(
                    text = String.format(Locale.US, "%.2fx", draftRate),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isEnabled) IosBlue else secondaryInk,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(42.dp)
                )
            }

            // 3. Ngắt nghỉ giữa các câu: 150ms -> 800ms
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Ngắt câu",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk,
                    maxLines = 1,
                    modifier = Modifier.width(56.dp)
                )
                Slider(
                    value = draftPauseMs.toFloat(),
                    enabled = isEnabled,
                    onValueChange = { raw ->
                        val step = LibraryManager.STEP_SENTENCE_PAUSE_MS
                        draftPauseMs = ((raw / step).roundToInt() * step)
                            .coerceIn(LibraryManager.MIN_SENTENCE_PAUSE_MS, LibraryManager.MAX_SENTENCE_PAUSE_MS)
                    },
                    valueRange = LibraryManager.MIN_SENTENCE_PAUSE_MS.toFloat()..LibraryManager.MAX_SENTENCE_PAUSE_MS.toFloat(),
                    steps = 12,
                    colors = SliderDefaults.colors(
                        thumbColor = IosBlue,
                        activeTrackColor = IosBlue,
                        inactiveTrackColor = Color(0xFFD1D1D6),
                        disabledThumbColor = secondaryInk.copy(alpha = 0.5f),
                        disabledActiveTrackColor = secondaryInk.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(20.dp)
                        .testTag("advanced_pause_slider")
                )
                Text(
                    text = "${draftPauseMs}ms",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isEnabled) IosBlue else secondaryInk,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(42.dp)
                )
            }

            // Hàng nút bấm gọn: Nghe thử (chỉ nghe tạm) | Mặc định | OK (xác nhận lưu)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeoBrutalistPressBox(
                    onClick = { onPreviewAdvanced(draftPitch, draftRate, draftPauseMs) },
                    enabled = isEnabled,
                    isPill = true,
                    shadowOffset = 2.dp,
                    pressDepth = 1.5.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (isPreviewPlaying) IosBlue else cardBg,
                    borderColor = borderCol,
                    shadowColor = if (isEnabled) hardShadow else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("advanced_tts_preview_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (isPreviewPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPreviewPlaying) "Dừng nghe thử" else "Nghe thử",
                            tint = if (isPreviewPlaying) Color.White else IosBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(3.dp))
                        Text(
                            text = if (isPreviewPlaying) "Dừng thử" else "Nghe thử",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPreviewPlaying) Color.White else primaryInk,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                NeoBrutalistPressBox(
                    onClick = {
                        draftPitch = LibraryManager.DEFAULT_ADVANCED_PITCH
                        draftRate = LibraryManager.DEFAULT_ADVANCED_RATE
                        draftPauseMs = LibraryManager.DEFAULT_SENTENCE_PAUSE_MS
                        onResetDefaults()
                    },
                    enabled = isEnabled,
                    isPill = true,
                    shadowOffset = 2.dp,
                    pressDepth = 1.5.dp,
                    borderWidth = 1.5.dp,
                    containerColor = cardBg,
                    borderColor = borderCol,
                    shadowColor = if (isEnabled) hardShadow else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .testTag("advanced_tts_reset_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            tint = primaryInk,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.size(3.dp))
                        Text(
                            text = "Mặc định",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryInk,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                NeoBrutalistPressBox(
                    onClick = {
                        onPitchChanged(draftPitch)
                        onSpeechRateChanged(draftRate)
                        onSentencePauseChanged(draftPauseMs)
                        Toast.makeText(
                            context,
                            "Đã áp dụng tùy chỉnh giọng đọc",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    enabled = isEnabled,
                    isPill = true,
                    shadowOffset = 2.dp,
                    pressDepth = 1.5.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (hasPendingChanges) IosGreen else IosBlue,
                    borderColor = borderCol,
                    shadowColor = if (isEnabled) hardShadow else Color.Transparent,
                    modifier = Modifier
                        .weight(0.85f)
                        .height(30.dp)
                        .testTag("advanced_tts_ok_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(3.dp))
                        Text(
                            text = "OK",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
