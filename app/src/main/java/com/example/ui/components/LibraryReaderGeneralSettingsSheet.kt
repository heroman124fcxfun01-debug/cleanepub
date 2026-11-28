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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AmbientSoundTrack
import com.example.data.CustomAmbientTrack
import com.example.data.CustomImportedFont
import com.example.data.HorizontalPageEffect
import com.example.data.LibraryManager
import com.example.data.ReaderPreferences
import com.example.data.ReaderScrollDirection
import com.example.data.ReaderVoiceProfile
import com.example.data.TtsEngineType
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosDarkBlue
import com.example.ui.theme.IosRed
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.ReaderFontRegistry
import java.util.Locale
import kotlin.math.roundToInt

private enum class GeneralSettingsPillTab {
    READING_MODE,
    AUDIO_LISTENING
}

/**
 * Cài đặt chung cho trình đọc sách mở từ icon bánh răng bên cạnh dấu `+` trong Thư viện sách.
 * Gồm 2 viên thuốc cạnh nhau:
 * - Viên thuốc 1 (`Chế độ đọc`): Cửa sổ cuộn chọn Font mặc định + Import font bên cạnh,
 *   lướt dọc/ngang, 3 chế độ lướt ngang (Lật sách / Chạm lướt mờ / Không hiệu ứng), cỡ chữ, lề trang & giãn dòng mặc định.
 * - Viên thuốc 2 (`Âm thanh nghe`): Chọn TTS Hệ thống hoặc TTS AI (chờ tích hợp),
 *   giọng dẫn truyện mặc định (xổ xuống), âm thanh nền mặc định, công tắc tự bật âm thanh nền khi phát đọc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryReaderGeneralSettingsSheet(
    readerPrefs: ReaderPreferences,
    customFonts: List<CustomImportedFont>,
    onSelectFont: (String) -> Unit,
    onImportFontClick: () -> Unit,
    onDeleteCustomFont: (String) -> Unit,
    onSelectScrollDirection: (ReaderScrollDirection) -> Unit,
    onSelectHorizontalPageEffect: (HorizontalPageEffect) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onAdjustPageMargin: (Float) -> Unit,
    onAdjustLineHeight: (Float) -> Unit,
    onSelectTtsEngine: (TtsEngineType) -> Unit,
    onSelectNarratorVoice: (ReaderVoiceProfile) -> Unit,
    clonedVoices: List<com.example.audio.ClonedVoiceProfile> = emptyList(),
    presetAiVoices: List<com.example.audio.VieNeuPresetVoice> = emptyList(),
    onSelectClonedVoice: (String?) -> Unit = {},
    customAmbientTracks: List<CustomAmbientTrack> = emptyList(),
    onImportCustomAmbientClick: () -> Unit = {},
    onSelectCustomAmbient: (String) -> Unit = {},
    onDeleteCustomAmbient: (String) -> Unit = {},
    onSelectAmbientSound: (AmbientSoundTrack) -> Unit,
    onToggleAutoPlayAmbient: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val sheetBg = MaterialTheme.colorScheme.background
    val cardBg = neoPaperSurfaceColor()
    val itemBg = neoSecondaryPaperColor()
    val borderCol = neoInkBorderColor()
    val primaryInk = MaterialTheme.colorScheme.onSurface
    val secondaryInk = MaterialTheme.colorScheme.onSurfaceVariant
    val accentBlue = if (isDark) IosDarkBlue else IosBlue

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember { mutableStateOf(GeneralSettingsPillTab.READING_MODE) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = sheetBg,
        contentColor = primaryInk
    ) {
        val currentDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = currentDensity.density,
                fontScale = currentDensity.fontScale.coerceAtMost(1.0f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("library_reader_general_settings_sheet")
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
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
                                .background(accentBlue.copy(alpha = 0.14f))
                                .border(1.2.dp, borderCol, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = accentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Cài đặt chung đọc sách",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryInk
                            )
                            Text(
                                text = "Áp dụng mặc định cho toàn bộ Thư viện sách",
                                fontSize = 10.sp,
                                color = secondaryInk
                            )
                        }
                    }

                    Ios3DCircleIconButton(
                        onClick = onDismiss,
                        size = 32.dp,
                        modifier = Modifier.testTag("library_general_settings_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Đóng cài đặt chung",
                            tint = primaryInk,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 2 VIÊN THUỐC CẠNH NHAU: [ Chế độ đọc ]   [ Âm thanh nghe ]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GeneralSettingsTabPill(
                        label = "Chế độ đọc",
                        icon = Icons.Rounded.AutoStories,
                        selected = activeTab == GeneralSettingsPillTab.READING_MODE,
                        accentBlue = accentBlue,
                        unselectedBg = cardBg,
                        unselectedInk = primaryInk,
                        borderCol = borderCol,
                        testTag = "general_settings_tab_reading",
                        modifier = Modifier.weight(1f),
                        onClick = { activeTab = GeneralSettingsPillTab.READING_MODE }
                    )
                    GeneralSettingsTabPill(
                        label = "Âm thanh nghe",
                        icon = Icons.Rounded.Headphones,
                        selected = activeTab == GeneralSettingsPillTab.AUDIO_LISTENING,
                        accentBlue = accentBlue,
                        unselectedBg = cardBg,
                        unselectedInk = primaryInk,
                        borderCol = borderCol,
                        testTag = "general_settings_tab_audio",
                        modifier = Modifier.weight(1f),
                        onClick = { activeTab = GeneralSettingsPillTab.AUDIO_LISTENING }
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 490.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (activeTab == GeneralSettingsPillTab.READING_MODE) {
                        ReadingModeDefaultsTab(
                            readerPrefs = readerPrefs,
                            customFonts = customFonts,
                            cardBg = cardBg,
                            itemBg = itemBg,
                            borderCol = borderCol,
                            primaryInk = primaryInk,
                            secondaryInk = secondaryInk,
                            accentBlue = accentBlue,
                            onSelectFont = onSelectFont,
                            onImportFontClick = onImportFontClick,
                            onDeleteCustomFont = onDeleteCustomFont,
                            onSelectScrollDirection = onSelectScrollDirection,
                            onSelectHorizontalPageEffect = onSelectHorizontalPageEffect,
                            onSetFontSize = onSetFontSize,
                            onAdjustFontSize = onAdjustFontSize,
                            onAdjustPageMargin = onAdjustPageMargin,
                            onAdjustLineHeight = onAdjustLineHeight
                        )
                    } else {
                        AudioListeningDefaultsTab(
                            readerPrefs = readerPrefs,
                            clonedVoices = clonedVoices,
                            presetAiVoices = presetAiVoices,
                            cardBg = cardBg,
                            itemBg = itemBg,
                            borderCol = borderCol,
                            primaryInk = primaryInk,
                            secondaryInk = secondaryInk,
                            accentBlue = accentBlue,
                            onSelectTtsEngine = onSelectTtsEngine,
                            onSelectNarratorVoice = onSelectNarratorVoice,
                            onSelectClonedVoice = onSelectClonedVoice,
                            customAmbientTracks = customAmbientTracks,
                            onImportCustomAmbientClick = onImportCustomAmbientClick,
                            onSelectCustomAmbient = onSelectCustomAmbient,
                            onDeleteCustomAmbient = onDeleteCustomAmbient,
                            onSelectAmbientSound = onSelectAmbientSound,
                            onToggleAutoPlayAmbient = onToggleAutoPlayAmbient
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsTabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accentBlue: Color,
    unselectedBg: Color,
    unselectedInk: Color,
    borderCol: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    NeoBrutalistPressBox(
        onClick = onClick,
        isPill = true,
        shadowOffset = 3.dp,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = if (selected) accentBlue else unselectedBg,
        borderColor = borderCol,
        shadowColor = neoHardShadowColor(),
        modifier = modifier
            .height(41.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else unselectedInk,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else unselectedInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =============================================================================
// PHẦN 1: CÀI ĐẶT CHẾ ĐỘ ĐỌC MẶC ĐỊNH
// =============================================================================

@Composable
private fun ReadingModeDefaultsTab(
    readerPrefs: ReaderPreferences,
    customFonts: List<CustomImportedFont>,
    cardBg: Color,
    itemBg: Color,
    borderCol: Color,
    primaryInk: Color,
    secondaryInk: Color,
    accentBlue: Color,
    onSelectFont: (String) -> Unit,
    onImportFontClick: () -> Unit,
    onDeleteCustomFont: (String) -> Unit,
    onSelectScrollDirection: (ReaderScrollDirection) -> Unit,
    onSelectHorizontalPageEffect: (HorizontalPageEffect) -> Unit,
    onSetFontSize: (Float) -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onAdjustPageMargin: (Float) -> Unit,
    onAdjustLineHeight: (Float) -> Unit
) {
    val allFonts = remember(customFonts) { ReaderFontRegistry.allFontOptions(customFonts) }

    // 1. Cửa sổ cuộn chọn Font mặc định + Nút Import font bên cạnh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp),
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
                    imageVector = Icons.Rounded.FontDownload,
                    contentDescription = null,
                    tint = accentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Column {
                    Text(
                        text = "Font chữ mặc định",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk
                    )
                    Text(
                        text = "Lăn xuống để chọn hoặc nhập font .ttf/.otf",
                        fontSize = 10.sp,
                        color = secondaryInk
                    )
                }
            }

            // Nút Import Font ngay bên cạnh
            NeoBrutalistPressBox(
                onClick = onImportFontClick,
                isPill = true,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                borderWidth = 1.5.dp,
                containerColor = accentBlue,
                borderColor = borderCol,
                shadowColor = neoHardShadowColor(),
                modifier = Modifier.testTag("general_settings_import_font_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = "Nhập font chữ",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Import font",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        // Cửa sổ danh sách font dạng lăn xuống (scrollable box)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(164.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(itemBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                .testTag("general_settings_font_scroll_window")
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(allFonts, key = { _, f -> f.id }) { idx, fontOpt ->
                    val isSelected = readerPrefs.fontId == fontOpt.id
                    val previewFamily = remember(fontOpt.id, customFonts) {
                        ReaderFontRegistry.resolveFontFamily(fontOpt.id, customFonts)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) accentBlue.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable { onSelectFont(fontOpt.id) }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .testTag("general_font_option_${fontOpt.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fontOpt.displayName,
                                fontSize = 13.sp,
                                fontFamily = previewFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) accentBlue else primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${fontOpt.subtitle} • Tiếng Việt mẫu",
                                fontSize = 10.sp,
                                fontFamily = previewFamily,
                                color = secondaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (fontOpt.isCustom) {
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .clickable { onDeleteCustomFont(fontOpt.id) }
                                        .testTag("delete_custom_font_${fontOpt.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Xóa font đã nhập",
                                        tint = IosRed,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Font mặc định",
                                    tint = accentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (idx < allFonts.lastIndex) {
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

    // 2. Cách lướt mặc định (Dọc / Ngang) + 3 chế độ hiệu ứng khi Lật ngang
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(
            text = "Cách lướt trang mặc định",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = primaryInk
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReaderScrollDirection.entries.forEach { dir ->
                val selected = readerPrefs.scrollDirection == dir
                NeoBrutalistPressBox(
                    onClick = { onSelectScrollDirection(dir) },
                    isPill = true,
                    shadowOffset = 3.dp,
                    pressDepth = 2.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (selected) accentBlue else itemBg,
                    borderColor = borderCol,
                    shadowColor = neoHardShadowColor(),
                    modifier = Modifier
                        .weight(1f)
                        .height(37.dp)
                        .testTag("general_scroll_dir_${dir.storageKey}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (dir == ReaderScrollDirection.VERTICAL) {
                                Icons.Rounded.SwapVert
                            } else {
                                Icons.Rounded.SwapHoriz
                            },
                            contentDescription = null,
                            tint = if (selected) Color.White else primaryInk,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = dir.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else primaryInk
                        )
                    }
                }
            }
        }

        // 3 chế độ hiệu ứng khi chọn Lướt ngang: Lật sách | Chạm lướt mờ | Không hiệu ứng
        AnimatedVisibility(
            visible = readerPrefs.scrollDirection == ReaderScrollDirection.HORIZONTAL,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Hiệu ứng lướt ngang mặc định",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = secondaryInk
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(itemBg)
                        .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                ) {
                    HorizontalPageEffect.entries.forEachIndexed { idx, effect ->
                        val selected = readerPrefs.horizontalPageEffect == effect
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectHorizontalPageEffect(effect) }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                                .testTag("horizontal_effect_${effect.storageKey}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = effect.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) accentBlue else primaryInk
                                )
                                Text(
                                    text = effect.subtitle,
                                    fontSize = 10.sp,
                                    color = secondaryInk
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Đang chọn",
                                    tint = accentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (idx < HorizontalPageEffect.entries.lastIndex) {
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
    }

    // 3. Cỡ chữ mặc định, Lề trang mặc định & Giãn dòng mặc định
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.TextFields,
                contentDescription = null,
                tint = accentBlue,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = "Cỡ chữ, lề trang & giãn dòng mặc định",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = primaryInk
            )
        }

        // Cỡ chữ mặc định (13..48sp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(itemBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Cỡ chữ mặc định", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryInk)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactCircleStepBtn(
                        label = "−",
                        bg = cardBg,
                        ink = primaryInk,
                        onClick = { onAdjustFontSize(-1f) }
                    )
                    Text(
                        text = "${readerPrefs.fontSizeSp.roundToInt()} sp",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentBlue
                    )
                    CompactCircleStepBtn(
                        label = "+",
                        bg = cardBg,
                        ink = primaryInk,
                        onClick = { onAdjustFontSize(1f) }
                    )
                }
            }
            Slider(
                value = readerPrefs.fontSizeSp,
                onValueChange = onSetFontSize,
                valueRange = LibraryManager.MIN_FONT_SIZE_SP..LibraryManager.MAX_FONT_SIZE_SP,
                colors = SliderDefaults.colors(
                    thumbColor = accentBlue,
                    activeTrackColor = accentBlue,
                    inactiveTrackColor = borderCol
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .testTag("general_default_font_size_slider")
            )

            HorizontalDivider(thickness = 0.5.dp, color = borderCol)

            // Lề trang mặc định
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Lề trang mặc định", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryInk)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactCircleStepBtn(
                        label = "−",
                        bg = cardBg,
                        ink = primaryInk,
                        onClick = { onAdjustPageMargin(-4f) }
                    )
                    Text(
                        text = "${readerPrefs.pageMarginDp.roundToInt()} dp",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentBlue
                    )
                    CompactCircleStepBtn(
                        label = "+",
                        bg = cardBg,
                        ink = primaryInk,
                        onClick = { onAdjustPageMargin(4f) }
                    )
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = borderCol)

            // Giãn dòng mặc định
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Giãn dòng mặc định", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryInk)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactCircleStepBtn(
                        label = "−",
                        bg = cardBg,
                        ink = primaryInk,
                        onClick = { onAdjustLineHeight(-0.1f) }
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", readerPrefs.lineHeightMultiplier),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentBlue
                    )
                    CompactCircleStepBtn(
                        label = "+",
                        bg = cardBg,
                        ink = primaryInk,
                        onClick = { onAdjustLineHeight(0.1f) }
                    )
                }
            }
        }
    }
}

// =============================================================================
// PHẦN 2: CÀI ĐẶT ÂM THANH NGHE MẶC ĐỊNH
// =============================================================================

@Composable
private fun AudioListeningDefaultsTab(
    readerPrefs: ReaderPreferences,
    clonedVoices: List<com.example.audio.ClonedVoiceProfile>,
    presetAiVoices: List<com.example.audio.VieNeuPresetVoice>,
    cardBg: Color,
    itemBg: Color,
    borderCol: Color,
    primaryInk: Color,
    secondaryInk: Color,
    accentBlue: Color,
    onSelectTtsEngine: (TtsEngineType) -> Unit,
    onSelectNarratorVoice: (ReaderVoiceProfile) -> Unit,
    onSelectClonedVoice: (String?) -> Unit,
    customAmbientTracks: List<CustomAmbientTrack>,
    onImportCustomAmbientClick: () -> Unit,
    onSelectCustomAmbient: (String) -> Unit,
    onDeleteCustomAmbient: (String) -> Unit,
    onSelectAmbientSound: (AmbientSoundTrack) -> Unit,
    onToggleAutoPlayAmbient: (Boolean) -> Unit
) {
    var narratorExpanded by remember { mutableStateOf(false) }
    val isAiMode = readerPrefs.ttsEngineType == TtsEngineType.AI_TTS
    val activeCloned = remember(readerPrefs.selectedClonedVoiceId, clonedVoices) {
        val selectedId = readerPrefs.selectedClonedVoiceId
        if (selectedId != null && !selectedId.startsWith(com.example.audio.OnnxModelAssetHelper.PRESET_VOICE_ID_PREFIX)) {
            clonedVoices.firstOrNull { it.id == selectedId }
        } else {
            null
        }
    }
    val activePreset = remember(readerPrefs.selectedClonedVoiceId, presetAiVoices, activeCloned, clonedVoices) {
        val selectedId = readerPrefs.selectedClonedVoiceId
        val presetId = com.example.audio.OnnxModelAssetHelper.extractPresetId(selectedId)
        when {
            presetId != null -> presetAiVoices.firstOrNull { it.id == presetId }
            activeCloned != null -> null
            selectedId.isNullOrBlank() && clonedVoices.isNotEmpty() -> null
            else -> presetAiVoices.firstOrNull()
        }
    }
    val effectiveCloned = activeCloned ?: if (activePreset == null) clonedVoices.firstOrNull() else null
    val activeAiLabel = when {
        effectiveCloned != null -> "✦ ${effectiveCloned.displayNameWithCodeCount}"
        activePreset != null -> "★ ${activePreset.displayLabel}"
        else -> "Chưa có giọng AI (kiểm tra voices.json / Studio)"
    }

    // 1. Chọn bộ máy giọng đọc: TTS Hệ thống (hiện tại) hoặc TTS AI (sắp tích hợp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Nguồn giọng đọc mặc định",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = primaryInk
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TtsEngineType.entries.forEach { engine ->
                val selected = readerPrefs.ttsEngineType == engine
                val isAi = engine == TtsEngineType.AI_TTS
                NeoBrutalistPressBox(
                    onClick = { onSelectTtsEngine(engine) },
                    isPill = false,
                    cornerRadius = 12.dp,
                    shadowOffset = 3.dp,
                    pressDepth = 2.dp,
                    borderWidth = 1.5.dp,
                    containerColor = if (selected) accentBlue.copy(alpha = 0.14f) else itemBg,
                    borderColor = if (selected) accentBlue else borderCol,
                    shadowColor = neoHardShadowColor(),
                    contentAlignment = Alignment.TopStart,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("general_tts_engine_${engine.storageKey}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAi) Icons.Rounded.AutoAwesome else Icons.Rounded.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = if (selected) accentBlue else secondaryInk,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = engine.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) accentBlue else primaryInk
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = accentBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Text(
                            text = engine.subtitle,
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            color = secondaryInk,
                            maxLines = 2
                        )
                    }
                }
            }
        }
    }

    // 2. Giọng người dẫn truyện mặc định (Dropdown mũi tên xổ xuống)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (isAiMode) {
                "Giọng dẫn truyện mặc định (Chỉ hiển thị giọng AI Nhân bản)"
            } else {
                "Giọng dẫn truyện mặc định (Chỉ hiển thị giọng Hệ thống)"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = primaryInk
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            NeoBrutalistPressBox(
                onClick = { narratorExpanded = true },
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
                    .testTag("general_default_narrator_dropdown")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isAiMode) {
                            activeAiLabel
                        } else {
                            readerPrefs.voiceProfile.displayName
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Chọn giọng dẫn truyện mặc định",
                        tint = accentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Neo3DDropdownMenu(
                expanded = narratorExpanded,
                onDismissRequest = { narratorExpanded = false },
                containerColor = cardBg
            ) {
                if (isAiMode) {
                    if (clonedVoices.isEmpty() && presetAiVoices.isEmpty()) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Chưa có giọng AI — Vào tab Studio để tạo & lưu",
                                    fontSize = 11.5.sp,
                                    color = secondaryInk
                                )
                            },
                            onClick = { narratorExpanded = false }
                        )
                    } else {
                        clonedVoices.forEach { cloned ->
                            val selected = effectiveCloned?.id == cloned.id
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "✦ ${cloned.displayNameWithCodeCount}",
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) accentBlue else primaryInk
                                        )
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = accentBlue,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onSelectClonedVoice(cloned.id)
                                    narratorExpanded = false
                                }
                            )
                        }
                        presetAiVoices.forEach { preset ->
                            val selected = activePreset?.id == preset.id
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "★ ${preset.displayLabel}",
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) accentBlue else primaryInk
                                        )
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = accentBlue,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onSelectClonedVoice(preset.storageId)
                                    narratorExpanded = false
                                }
                            )
                        }
                    }
                } else {
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
                                        color = if (selected) accentBlue else primaryInk
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = accentBlue,
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
        }
    }

    // 3. Âm thanh nền mặc định + Công tắc tự bật/tắt âm thanh nền khi phát đọc
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 3.dp, bottom = 3.dp)
            .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg)
            .border(1.5.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bật âm thanh nền khi phát đọc",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryInk
                )
                Text(
                    text = if (readerPrefs.autoPlayAmbientWithAudio) {
                        "Tự động mở nhạc nền mặc định khi nghe sách"
                    } else {
                        "Tắt mặc định — Chỉ đọc giọng nói"
                    },
                    fontSize = 10.sp,
                    color = secondaryInk
                )
            }
            Switch(
                checked = readerPrefs.autoPlayAmbientWithAudio,
                onCheckedChange = onToggleAutoPlayAmbient,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accentBlue,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD1D1D6),
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .scale(0.75f)
                    .height(26.dp)
                    .testTag("general_auto_play_ambient_switch")
            )
        }

        HorizontalDivider(thickness = 0.8.dp, color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Âm thanh nền mặc định",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = secondaryInk
            )
            NeoBrutalistPressBox(
                onClick = onImportCustomAmbientClick,
                isPill = true,
                shadowOffset = 2.dp,
                pressDepth = 1.5.dp,
                borderWidth = 1.2.dp,
                containerColor = itemBg,
                borderColor = borderCol,
                shadowColor = neoHardShadowColor(),
                modifier = Modifier
                    .height(28.dp)
                    .testTag("general_import_ambient_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = null,
                        tint = accentBlue,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "+ Import nhạc nền",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryInk,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(itemBg)
                .border(1.5.dp, borderCol, RoundedCornerShape(12.dp))
        ) {
            AmbientSoundTrack.entries.forEachIndexed { idx, track ->
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
                        .clickable { onSelectAmbientSound(track) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("general_ambient_${track.storageKey}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = trackIcon,
                            contentDescription = null,
                            tint = if (selected) accentBlue else secondaryInk,
                            modifier = Modifier.size(15.dp)
                        )
                        Column {
                            Text(
                                text = track.label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) accentBlue else primaryInk
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
                            tint = accentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (idx < AmbientSoundTrack.entries.lastIndex || customAmbientTracks.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = borderCol,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            customAmbientTracks.forEachIndexed { idx, customTrack ->
                val selected = readerPrefs.selectedCustomAmbientId == customTrack.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectCustomAmbient(customTrack.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("general_custom_ambient_${customTrack.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AudioFile,
                            contentDescription = null,
                            tint = if (selected) accentBlue else secondaryInk,
                            modifier = Modifier.size(15.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = customTrack.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) accentBlue else primaryInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Nhạc nền đã import",
                                fontSize = 10.sp,
                                color = secondaryInk
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Đang chọn",
                                tint = accentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { onDeleteCustomAmbient(customTrack.id) }
                                .testTag("general_custom_ambient_delete_${customTrack.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Xóa nhạc nền ${customTrack.displayName}",
                                tint = IosRed,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
                if (idx < customAmbientTracks.lastIndex) {
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

@Composable
private fun CompactCircleStepBtn(
    label: String,
    bg: Color,
    ink: Color,
    onClick: () -> Unit
) {
    NeoBrutalistPressBox(
        onClick = onClick,
        isCircle = true,
        shadowOffset = 2.dp,
        pressDepth = 1.5.dp,
        borderWidth = 1.5.dp,
        containerColor = bg,
        borderColor = neoInkBorderColor(),
        shadowColor = neoHardShadowColor(),
        modifier = Modifier.size(30.dp)
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ink
        )
    }
}
