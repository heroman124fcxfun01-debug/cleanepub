package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.CleanedFileHistory
import com.example.data.FileRecordStatus
import com.example.data.HistoryManager
import com.example.pipeline.CleaningOptions
import com.example.ui.components.IosFloatingDialog
import com.example.ui.components.IosPrimaryButton
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosOrangeSoft
import com.example.ui.theme.IosPillBorderBottomLight
import com.example.ui.theme.IosPillBottomDark
import com.example.ui.theme.IosPillBottomLight
import com.example.ui.theme.IosPillTopDark
import com.example.ui.theme.IosPillTopLight
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalIsDarkTheme

@Composable
fun MainScreen(
    recentFiles: List<CleanedFileHistory>,
    isOpeningEditor: Boolean,
    hasAllFilesAccess: Boolean,
    cleaningOptions: CleaningOptions,
    isLoadingMetadata: Boolean,
    errorBanner: String?,
    onSelectFileClick: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onUpdateCleaningOptions: (CleaningOptions) -> Unit,
    onContinueEditHistoryFile: (CleanedFileHistory) -> Unit,
    onOpenHistoryFile: (CleanedFileHistory) -> Unit,
    onReprocessHistoryFile: (CleanedFileHistory) -> Unit,
    onLearnErrorsFromHistory: (CleanedFileHistory) -> Unit,
    onDeleteHistoryFile: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    onDismissError: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    var selectedHistoryItem by remember { mutableStateOf<CleanedFileHistory?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            // 1. Compact Header: EpubCleanAI + Subtitle + Compact Green Checkmark + Settings Gear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Làm sạch & hiệu đính sách Offline",
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasAllFilesAccess) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .shadow(
                                    elevation = 4.dp,
                                    shape = CircleShape,
                                    ambientColor = Color.Black.copy(alpha = 0.06f),
                                    spotColor = Color.Black.copy(alpha = 0.12f)
                                )
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .testTag("storage_granted_icon"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = stringResource(R.string.storage_permission_granted),
                                tint = IosGreen,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .testTag("grant_storage_button")
                                .clickable(onClick = onRequestStoragePermission)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.storage_permission_action),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .shadow(
                                elevation = 5.dp,
                                shape = CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.08f),
                                spotColor = Color.Black.copy(alpha = 0.14f)
                            )
                            .clip(CircleShape)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = if (isDark) {
                                        listOf(IosPillTopDark, IosPillBottomDark)
                                    } else {
                                        listOf(IosPillTopLight, IosPillBottomLight)
                                    }
                                )
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colors = if (isDark) {
                                        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
                                    } else {
                                        listOf(Color.White.copy(alpha = 0.95f), IosPillBorderBottomLight)
                                    }
                                ),
                                shape = CircleShape
                            )
                            .clickable(onClick = onOpenSettingsClick)
                            .testTag("open_settings_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Cài đặt",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!errorBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorBanner,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = onDismissError,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Đóng",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 3D Glossy Blue Pill CTA Button: "+ Chọn file EPUB / PDF"
            IosPrimaryButton(
                text = if (isLoadingMetadata) "Đang đọc thông tin sách…" else "Chọn file EPUB / PDF",
                enabled = !isLoadingMetadata,
                height = 48.dp,
                onClick = onSelectFileClick,
                modifier = Modifier.testTag("select_file_button"),
                leadingIcon = {
                    if (isLoadingMetadata) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Compact 2-Column 3D Pill Feature Grid (3 rows, 8.dp spacing, 42.dp pill height)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Dọn link | Sửa phông
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactFeature3DPill(
                        title = "Dọn link",
                        icon = Icons.Rounded.LinkOff,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        accentContainer = MaterialTheme.colorScheme.tertiaryContainer,
                        enabled = cleaningOptions.cleanLinks,
                        testTagName = "feature_clean_links",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(cleanLinks = !cleaningOptions.cleanLinks)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CompactFeature3DPill(
                        title = "Sửa phông",
                        icon = Icons.Rounded.FormatColorText,
                        accentColor = MaterialTheme.colorScheme.primary,
                        accentContainer = MaterialTheme.colorScheme.primaryContainer,
                        enabled = cleaningOptions.fixFonts,
                        testTagName = "feature_fix_fonts",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(fixFonts = !cleaningOptions.fixFonts)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 2: Sửa chính tả | Sửa khoảng trắng
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactFeature3DPill(
                        title = "Sửa chính tả",
                        icon = Icons.Rounded.Spellcheck,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                        enabled = cleaningOptions.fixSpelling,
                        testTagName = "feature_fix_spelling",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(fixSpelling = !cleaningOptions.fixSpelling)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    CompactFeature3DPill(
                        title = "Sửa khoảng trắng",
                        icon = Icons.Rounded.SpaceBar,
                        accentColor = MaterialTheme.colorScheme.primary,
                        accentContainer = MaterialTheme.colorScheme.primaryContainer,
                        enabled = cleaningOptions.fixWhitespace,
                        testTagName = "feature_fix_whitespace",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(fixWhitespace = !cleaningOptions.fixWhitespace)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Row 3: Tối ưu lại nội dung (full width)
                CompactFeature3DPill(
                    title = "Tối ưu lại nội dung",
                    icon = Icons.Rounded.AutoFixHigh,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                    enabled = cleaningOptions.optimizeContent,
                    testTagName = "feature_optimize_content",
                    onToggle = {
                        onUpdateCleaningOptions(
                            cleaningOptions.copy(optimizeContent = !cleaningOptions.optimizeContent)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. "Tất cả các file" (Unified Draft + Saved books) occupying remaining height
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Tất cả các file",
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        if (isOpeningEditor) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (recentFiles.isNotEmpty()) {
                        Text(
                            text = "Xóa tất cả",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) MaterialTheme.colorScheme.primary else IosBlue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onClearAllHistory)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .testTag("clear_all_history_button")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (recentFiles.isEmpty()) {
                    // Subtle empty state centered inside remaining area
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.55f else 0.72f),
                            border = BorderStroke(
                                width = 0.8.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "Chưa có file nào được xử lý",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("recent_files_list"),
                        contentPadding = PaddingValues(bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = recentFiles,
                            key = { it.id }
                        ) { item ->
                            HistoryFileCard(
                                item = item,
                                isBusy = isOpeningEditor,
                                onClick = { selectedHistoryItem = item },
                                onContinueEditClick = { onContinueEditHistoryFile(item) },
                                onLearnErrorsClick = { onLearnErrorsFromHistory(item) },
                                onOpenClick = { onOpenHistoryFile(item) },
                                onReprocessClick = { onReprocessHistoryFile(item) },
                                onDeleteClick = { onDeleteHistoryFile(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Quick Action Floating Dialog when tapping a file card in "Tất cả các file"
    val activeItem = selectedHistoryItem
    if (activeItem != null) {
        HistoryQuickActionDialog(
            item = activeItem,
            onContinueEditClick = {
                selectedHistoryItem = null
                onContinueEditHistoryFile(activeItem)
            },
            onOpenClick = {
                selectedHistoryItem = null
                onOpenHistoryFile(activeItem)
            },
            onLearnErrorsClick = {
                selectedHistoryItem = null
                onLearnErrorsFromHistory(activeItem)
            },
            onReprocessClick = {
                selectedHistoryItem = null
                onReprocessHistoryFile(activeItem)
            },
            onDeleteClick = {
                selectedHistoryItem = null
                onDeleteHistoryFile(activeItem.id)
            },
            onDismiss = { selectedHistoryItem = null }
        )
    }
}

/**
 * Compact 3D iPhone Pill for the 2-column feature grid:
 * - Height `42.dp`, `RoundedCornerShape(50)`, single horizontal line (no subtitle)
 * - `[22.dp circle icon]` + `[12.sp SemiBold title, 1 line]` + `[16.dp green checkmark]`
 * - Drop shadow `5.dp`, vertical gradient surface, `1.dp` bevel border
 */
@Composable
private fun CompactFeature3DPill(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    accentContainer: Color,
    enabled: Boolean,
    testTagName: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val pillShape = RoundedCornerShape(50)

    val surfaceBrush = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(IosPillTopDark, IosPillBottomDark)
        } else {
            listOf(IosPillTopLight, IosPillBottomLight)
        }
    )

    val bevelBorderBrush = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(
                Color.White.copy(alpha = if (enabled) 0.22f else 0.12f),
                if (enabled) accentColor.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.04f)
            )
        } else {
            listOf(
                Color.White.copy(alpha = 0.96f),
                if (enabled) accentColor.copy(alpha = 0.24f) else IosPillBorderBottomLight
            )
        }
    )

    Box(
        modifier = modifier
            .height(42.dp)
            .alpha(if (enabled) 1f else 0.62f)
            .shadow(
                elevation = if (enabled) 5.dp else 2.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.14f)
            )
            .clip(pillShape)
            .background(brush = surfaceBrush)
            .border(
                width = 1.dp,
                brush = bevelBorderBrush,
                shape = pillShape
            )
            .testTag(testTagName)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left circular icon (22.dp)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) accentContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Single-line feature title (12.sp SemiBold)
            Text(
                text = title,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(5.dp))

            // Right green checkmark or muted gray circle (16.dp)
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) IosGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Đang bật",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * Unified file card in "Tất cả các file" (`RoundedCornerShape(16.dp)`, `elevation = 3.dp`, `padding = 10.dp`):
 * - Left: EPUB / PDF 10.sp Bold format badge
 * - Center: file name (`13.sp` SemiBold, 1 line) + status badge (`Bản nháp` orange / `Đã lưu` green) + subtitle
 * - Right: 3-dot (`⋮`) menu with unified `Sửa tiếp` as the primary option for both Draft and Saved items.
 */
@Composable
private fun HistoryFileCard(
    item: CleanedFileHistory,
    isBusy: Boolean,
    onClick: () -> Unit,
    onContinueEditClick: () -> Unit,
    onLearnErrorsClick: () -> Unit,
    onOpenClick: () -> Unit,
    onReprocessClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isDark = LocalIsDarkTheme.current
    val isPdf = item.sourceType.equals("PDF", ignoreCase = true)
    val isDraft = item.status == FileRecordStatus.DRAFT

    val formatBadgeBg = when {
        isPdf && isDark -> MaterialTheme.colorScheme.tertiaryContainer
        isPdf -> IosOrangeSoft
        isDark -> MaterialTheme.colorScheme.secondaryContainer
        else -> IosGreenSoft
    }
    val formatBadgeFg = when {
        isPdf && isDark -> MaterialTheme.colorScheme.tertiary
        isPdf -> IosOrange
        isDark -> MaterialTheme.colorScheme.secondary
        else -> Color(0xFF1A7F37)
    }

    val statusBadgeBg = when {
        isDraft && isDark -> MaterialTheme.colorScheme.tertiaryContainer
        isDraft -> IosOrangeSoft
        isDark -> MaterialTheme.colorScheme.secondaryContainer
        else -> IosGreenSoft
    }
    val statusBadgeFg = when {
        isDraft && isDark -> MaterialTheme.colorScheme.tertiary
        isDraft -> IosOrange
        isDark -> MaterialTheme.colorScheme.secondary
        else -> Color(0xFF1A7F37)
    }

    val timeLabel = remember(item.timestampMillis) {
        HistoryManager.formatRelativeTime(item.timestampMillis)
    }
    val subtitleText = if (isDraft) {
        "Chương ${item.lastChapterIndex + 1}/${item.totalChapters.coerceAtLeast(1)} • $timeLabel"
    } else {
        "Đã sửa ${item.totalFixes} lỗi • $timeLabel"
    }

    val cardShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.22f else 0.06f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.30f else 0.10f)
            )
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.8.dp,
                color = if (isDraft) {
                    statusBadgeFg.copy(alpha = if (isDark) 0.35f else 0.28f)
                } else if (isDark) {
                    Color.White.copy(alpha = 0.08f)
                } else {
                    Color.White.copy(alpha = 0.9f)
                },
                shape = cardShape
            )
            .clickable(enabled = !isBusy, onClick = onClick)
            .padding(10.dp)
            .testTag("history_file_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Format badge (EPUB / PDF)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(formatBadgeBg)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPdf) "PDF" else "EPUB",
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = formatBadgeFg,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.fileName,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Crisp status pill badge: "Bản nháp" (orange) or "Đã lưu" (green)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(statusBadgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("history_status_badge_${item.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDraft) "Bản nháp" else "Đã lưu",
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusBadgeFg,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitleText,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(2.dp))

            // 3-dot overflow menu with unified "Sửa tiếp" for BOTH Draft and Saved files
            Box {
                IconButton(
                    onClick = { if (!isBusy) menuExpanded = true },
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("history_item_menu_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Tùy chọn tệp",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Sửa tiếp",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onContinueEditClick()
                        },
                        modifier = Modifier.testTag("history_continue_edit_${item.id}")
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Ghi nhớ lỗi (Lưu vào thư viện)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onLearnErrorsClick()
                        },
                        modifier = Modifier.testTag("history_learn_errors_${item.id}")
                    )
                    if (!isDraft) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Mở đọc sách",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onOpenClick()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Xử lý lại file này",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onReprocessClick()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Xóa khỏi danh sách",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(17.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteClick()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Floating iOS quick-action dialog opened when tapping a file in "Tất cả các file":
 * offers unified "Sửa tiếp" for both Draft and Saved items, plus Read/Learn/Reprocess/Delete.
 */
@Composable
private fun HistoryQuickActionDialog(
    item: CleanedFileHistory,
    onContinueEditClick: () -> Unit,
    onOpenClick: () -> Unit,
    onLearnErrorsClick: () -> Unit,
    onReprocessClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val isDraft = item.status == FileRecordStatus.DRAFT
    val timeLabel = remember(item.timestampMillis) {
        HistoryManager.formatRelativeTime(item.timestampMillis)
    }
    val statusBadgeBg = when {
        isDraft && isDark -> MaterialTheme.colorScheme.tertiaryContainer
        isDraft -> IosOrangeSoft
        isDark -> MaterialTheme.colorScheme.secondaryContainer
        else -> IosGreenSoft
    }
    val statusBadgeFg = when {
        isDraft && isDark -> MaterialTheme.colorScheme.tertiary
        isDraft -> IosOrange
        isDark -> MaterialTheme.colorScheme.secondary
        else -> Color(0xFF1A7F37)
    }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isDraft) "Bản nháp đang sửa" else "Chi tiết file đã lưu",
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(statusBadgeBg)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDraft) "Bản nháp" else "Đã lưu",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusBadgeFg,
                            maxLines = 1
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = item.fileName,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = if (isDraft) {
                    "Đang ở Chương ${item.lastChapterIndex + 1}/${item.totalChapters.coerceAtLeast(1)} • $timeLabel"
                } else {
                    "Đã sửa ${item.totalFixes} lỗi • Chương ${item.lastChapterIndex + 1}/${item.totalChapters.coerceAtLeast(1)} • $timeLabel"
                },
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isDraft && item.filePath.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Đường dẫn lưu",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = item.filePath,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontFamily = JetBrainsMonoFamily,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Primary unified action: "Sửa tiếp"
            IosPrimaryButton(
                text = "Sửa tiếp",
                height = 44.dp,
                onClick = onContinueEditClick,
                modifier = Modifier.testTag("history_dialog_continue_edit_button"),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )

            if (!isDraft) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("history_dialog_open_book_button"),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Mở đọc sách",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onLearnErrorsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .testTag("history_dialog_learn_errors_button"),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ghi nhớ lỗi (Lưu vào thư viện)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isDraft) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onReprocessClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Xử lý lại file này",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            TextButton(
                onClick = onDeleteClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Xóa khỏi danh sách",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
