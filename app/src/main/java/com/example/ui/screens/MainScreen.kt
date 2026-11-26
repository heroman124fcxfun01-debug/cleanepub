package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
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
import com.example.ui.BatchCleanUiState
import com.example.ui.components.Ios3DCircleIconButton
import com.example.ui.components.Ios3DSurfacePillButton
import com.example.ui.components.IosFloatingDialog
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.Neo3DDropdownMenu
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.NeoFolderCard
import com.example.ui.components.NeoPaperStackContainer
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoHardShadowColor
import com.example.ui.components.neoInkBorderColor
import com.example.ui.components.neoPaperSurfaceColor
import com.example.ui.theme.IosAmberPillInk
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosOrangeSoft
import com.example.ui.theme.IosRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.NeoEmeraldShadow
import com.example.ui.theme.NeoInkLight
import com.example.ui.theme.NeoShadowDark

@Composable
fun MainScreen(
    recentFiles: List<CleanedFileHistory>,
    isOpeningEditor: Boolean,
    hasAllFilesAccess: Boolean,
    cleaningOptions: CleaningOptions,
    isLoadingMetadata: Boolean,
    batchCleanState: BatchCleanUiState = BatchCleanUiState.Idle,
    errorBanner: String?,
    onSelectFileClick: () -> Unit,
    onBatchCleanClick: () -> Unit = {},
    onCancelBatchClean: () -> Unit = {},
    onDismissBatchClean: () -> Unit = {},
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
    var isHistorySearchOpen by remember { mutableStateOf(false) }
    var historySearchQuery by remember { mutableStateOf("") }

    val filteredRecentFiles = remember(recentFiles, historySearchQuery) {
        val q = historySearchQuery.trim()
        if (q.isEmpty()) recentFiles
        else recentFiles.filter {
            it.fileName.contains(q, ignoreCase = true) ||
                it.sourceType.contains(q, ignoreCase = true)
        }
    }

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
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp)
        ) {
            // 1. Swiss Neo-Brutalist Header: Crisp 22.sp Bold "Cleanbook" + Subtitle | Amber 3D Inked "Cấp quyền" + 3D Inked Settings Circle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cleanbook",
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        letterSpacing = (-0.4).sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("clean_epub_header_title")
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = "Làm sạch & hiệu đính sách Offline",
                        fontSize = 11.5.sp,
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
                                .padding(end = 2.5.dp, bottom = 2.5.dp)
                                .size(36.dp)
                                .neoHardOffsetShadow(
                                    isCircle = true,
                                    offsetX = 2.5.dp,
                                    offsetY = 2.5.dp,
                                    shadowColor = if (isDark) NeoShadowDark else NeoEmeraldShadow
                                )
                                .clip(CircleShape)
                                .background(IosGreen)
                                .border(1.5.dp, neoInkBorderColor(), CircleShape)
                                .testTag("storage_granted_icon"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = stringResource(R.string.storage_permission_granted),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        NeoBrutalistPressBox(
                            onClick = onRequestStoragePermission,
                            isPill = true,
                            shadowOffset = 2.5.dp,
                            pressDepth = 1.8.dp,
                            containerColor = IosOrange,
                            borderColor = neoInkBorderColor(),
                            shadowColor = neoHardShadowColor(),
                            modifier = Modifier
                                .height(36.dp)
                                .testTag("grant_storage_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = IosAmberPillInk,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = stringResource(R.string.storage_permission_action),
                                    fontSize = 11.5.sp,
                                    lineHeight = 14.sp,
                                    color = IosAmberPillInk,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Ios3DCircleIconButton(
                        onClick = onOpenSettingsClick,
                        size = 36.dp,
                        modifier = Modifier.testTag("open_settings_button")
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
                Spacer(modifier = Modifier.height(10.dp))
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

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Bookbinder Archival Folder Card (clean integrated top-left folder tab + 1.5dp ink outline + 4dp solid 3D shadow)
            val isBatchBusy = batchCleanState is BatchCleanUiState.Running
            NeoFolderCard(
                tabWidth = 72.dp,
                tabHeight = 10.dp,
                cornerRadius = 14.dp,
                shadowOffset = 4.dp,
                contentPadding = PaddingValues(12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top: Cobalt Blue #1D5CD6 3D Inked Pill "+ Chọn file EPUB / PDF"
                    IosPrimaryButton(
                        text = if (isLoadingMetadata) "Đang đọc thông tin sách…" else "Chọn file EPUB / PDF",
                        enabled = !isLoadingMetadata && !isBatchBusy,
                        height = 44.dp,
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
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )

                    // Bottom: Tactile 3D Paper Pill "Quét hàng loạt sách tự động [Nhiều file]"
                    Ios3DSurfacePillButton(
                        text = "Quét hàng loạt sách tự động",
                        enabled = !isLoadingMetadata && !isBatchBusy,
                        height = 40.dp,
                        recessed = true,
                        onClick = onBatchCleanClick,
                        modifier = Modifier.testTag("batch_auto_clean_button"),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.LibraryAdd,
                                contentDescription = null,
                                tint = if (isDark) MaterialTheme.colorScheme.primary else IosBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        trailingContent = {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(IosOrange)
                                    .border(1.dp, neoInkBorderColor(), RoundedCornerShape(50))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Nhiều file",
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = NeoInkLight,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Swiss Neo-Brutalist 3D Paper Feature Toggle Cards (strictly 1-line labels)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(7.dp)
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

            Spacer(modifier = Modifier.height(12.dp))

            // 4. "Tất cả các file" + Circular 3D Inked Search Stamp + Layered Paper Stack Empty State
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Tất cả các file",
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (isOpeningEditor) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.8.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (recentFiles.isNotEmpty()) {
                            Text(
                                text = "Xóa tất cả",
                                fontSize = 11.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) MaterialTheme.colorScheme.primary else IosBlue,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(onClick = onClearAllHistory)
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    .testTag("clear_all_history_button")
                            )
                        }

                        Ios3DCircleIconButton(
                            onClick = {
                                isHistorySearchOpen = !isHistorySearchOpen
                                if (!isHistorySearchOpen) historySearchQuery = ""
                            },
                            size = 34.dp,
                            highlighted = isHistorySearchOpen,
                            highlightBg = IosOrange,
                            modifier = Modifier.testTag("cleanbook_search_history_button")
                        ) {
                            Icon(
                                imageVector = if (isHistorySearchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = "Tìm file đã xử lý",
                                tint = if (isHistorySearchOpen) NeoInkLight else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isHistorySearchOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, end = 3.dp, bottom = 3.dp)
                            .height(38.dp)
                            .neoHardOffsetShadow(cornerRadius = 999.dp, offsetX = 3.dp, offsetY = 3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(neoPaperSurfaceColor())
                            .border(1.5.dp, neoInkBorderColor(), RoundedCornerShape(50))
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (historySearchQuery.isEmpty()) {
                                    Text(
                                        text = "Tìm theo tên file EPUB / PDF…",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = historySearchQuery,
                                    onValueChange = { historySearchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cleanbook_search_history_input")
                                )
                            }
                            if (historySearchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { historySearchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Xóa từ khóa",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (filteredRecentFiles.isEmpty()) {
                    // Bookbinder Stacked Paper Sheets Empty State (peeking lower sheet + crisp ink borders)
                    NeoPaperStackContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 8.dp),
                        cornerRadius = 14.dp,
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (historySearchQuery.isNotBlank()) {
                                    "Không tìm thấy file phù hợp"
                                } else {
                                    "Chưa có file nào được xử lý"
                                },
                                fontSize = 12.5.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("recent_files_list"),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = filteredRecentFiles,
                            key = { it.id }
                        ) { item ->
                            HistoryFileCard(
                                item = item,
                                isBusy = isOpeningEditor,
                                modifier = Modifier.animateItem(),
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

    // Batch Auto-Clean Progress & Summary Dialog (auto-saves without asking "Sửa tiếp?")
    CleanbookBatchProgressDialog(
        state = batchCleanState,
        onCancel = onCancelBatchClean,
        onDismiss = onDismissBatchClean
    )

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
 * Swiss Neo-Brutalist 3D Paper Feature Toggle Card:
 * - Crisp `1.5.dp` solid ink border + `3.dp, 3.dp` solid hard offset shadow
 * - Physically translates `(2.dp, 2.dp)` into the shadow on press
 * - Strictly 1-line compact label (`11.5.sp` Bold) with zero wrapping or clipping
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
    val ink = neoInkBorderColor()
    val shadowColor = when {
        isDark -> NeoShadowDark
        enabled -> NeoEmeraldShadow
        else -> ink
    }

    NeoBrutalistPressBox(
        onClick = onToggle,
        isPill = false,
        cornerRadius = 12.dp,
        shadowOffset = if (enabled) 3.dp else 2.dp,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = neoPaperSurfaceColor(),
        borderColor = ink,
        shadowColor = shadowColor,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.62f)
            .testTag(testTagName)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) accentContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(1.dp, ink.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Text(
                text = title,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
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

            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (enabled) IosGreen else MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.2.dp, ink, CircleShape),
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
    modifier: Modifier = Modifier,
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

    val cardShape = RoundedCornerShape(14.dp)
    val ink = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()

    NeoBrutalistPressBox(
        onClick = onClick,
        enabled = !isBusy,
        isPill = false,
        cornerRadius = 14.dp,
        shadowOffset = 3.dp,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = neoPaperSurfaceColor(),
        borderColor = ink,
        shadowColor = hardShadow,
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .testTag("history_file_card_${item.id}")
    ) {
        Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
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

                Neo3DDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = neoPaperSurfaceColor()
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

/**
 * Cleanbook Batch Auto-Clean Progress & Completion Dialog:
 * Automatically cleans & saves all picked EPUB/PDF books sequentially WITHOUT asking "Sửa tiếp?".
 * Users can tap any book in "Tất cả các file" later to manually edit it anytime.
 */
@Composable
private fun CleanbookBatchProgressDialog(
    state: BatchCleanUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val accentBlue = if (isDark) MaterialTheme.colorScheme.primary else IosBlue

    when (state) {
        is BatchCleanUiState.Idle -> Unit

        is BatchCleanUiState.Running -> {
            val overallProgress = ((state.currentIndex.toFloat() + state.currentBookProgress) /
                state.totalCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val overallPercent = (overallProgress * 100f).toInt().coerceIn(0, 100)

            IosFloatingDialog(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("cleanbook_batch_spinner"),
                        strokeWidth = 3.2.dp,
                        color = accentBlue
                    )

                    Text(
                        text = "Đang sửa hàng loạt (${state.currentIndex + 1}/${state.totalCount})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = state.currentBookName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = accentBlue,
                        trackColor = accentBlue.copy(alpha = 0.16f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.statusDetail,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$overallPercent%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentBlue
                        )
                    }

                    if (state.completedCount > 0) {
                        Text(
                            text = "Đã lưu ${state.completedCount} cuốn · ${state.totalFixesSoFar} lỗi đã sửa",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = IosGreen
                        )
                    }

                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("cleanbook_batch_cancel_button")
                    ) {
                        Text(
                            text = "Dừng quét hàng loạt",
                            fontSize = 12.sp,
                            color = IosRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        is BatchCleanUiState.Completed -> {
            IosFloatingDialog(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isDark) IosGreen.copy(alpha = 0.18f) else IosGreenSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = IosGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = if (state.wasCancelled) {
                            "Đã dừng quét hàng loạt"
                        } else {
                            "Đã sửa tự động hàng loạt xong!"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    val statSummary = buildString {
                        append("Hoàn thành ${state.successCount}/${state.totalCount} cuốn sách")
                        if (state.totalFixes > 0) {
                            append(" · Đã tự động sửa ${state.totalFixes} lỗi")
                        }
                        if (state.failedCount > 0) {
                            append(" · ${state.failedCount} tệp lỗi")
                        }
                    }

                    Text(
                        text = statSummary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Tất cả sách đã được tự động lưu vào mục \"Tất cả các file\". Bạn có thể bấm vào từng cuốn bất cứ lúc nào để Sửa tiếp thủ công.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    IosPrimaryButton(
                        text = "Hoàn tất",
                        height = 42.dp,
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cleanbook_batch_done_button")
                    )
                }
            }
        }
    }
}
