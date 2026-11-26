package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.pipeline.DiffEntry
import com.example.pipeline.ManualEditorMode
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SaveMode
import com.example.pipeline.TocInspectionResult
import com.example.ui.SaveUiState
import com.example.ui.components.Ios3DCircleIconButton
import com.example.ui.components.Ios3DSurfacePillButton
import com.example.ui.components.IosCard
import com.example.ui.components.IosFloatingDialog
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.IosSaveSuccessDialog
import com.example.ui.components.IosSectionHeader
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.SaveDialog
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoHardShadowColor
import com.example.ui.components.neoInkBorderColor
import com.example.ui.components.neoPaperSurfaceColor
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosOrange
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.NeoEmeraldShadow

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    snapshot: ProcessingSnapshot,
    saveUiState: SaveUiState,
    customSaveFolderLabel: String?,
    onToggleRegenerateToc: (Boolean) -> Unit,
    onToggleDiff: (String) -> Unit,
    onSetAllDiffs: (Boolean) -> Unit,
    onEditMetadataClick: () -> Unit,
    onPickSaveFolderClick: () -> Unit,
    onSaveWithMode: (SaveMode) -> Unit,
    onContinueEdit: (ManualEditorMode) -> Unit,
    onOpenSavedFile: (absolutePath: String?, uriString: String?) -> Unit,
    onFinishToHome: () -> Unit,
    onDismissSaveError: () -> Unit
) {
    BackHandler {
        onFinishToHome()
    }

    var showSaveDialog by remember { mutableStateOf(false) }
    var showContinueEditDialog by remember { mutableStateOf(false) }
    var expandProperNouns by remember { mutableStateOf(false) }

    val acceptedCount = remember(snapshot.diffs) { snapshot.diffs.count { it.accepted } }
    val allSelected = snapshot.diffs.isNotEmpty() && acceptedCount == snapshot.diffs.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar (Swiss Neo-Brutalist 3D Back Stamp + Title + Metadata Pill)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Ios3DCircleIconButton(
                    onClick = onFinishToHome,
                    size = 36.dp,
                    modifier = Modifier.testTag("review_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBackIos,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.review_title),
                        fontSize = 19.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = snapshot.metadata?.title?.ifBlank { snapshot.sourceFile?.displayName.orEmpty() }
                            ?: snapshot.sourceFile?.displayName.orEmpty(),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (snapshot.metadata != null) {
                    NeoBrutalistPressBox(
                        onClick = onEditMetadataClick,
                        isPill = true,
                        shadowOffset = 3.dp,
                        pressDepth = 2.dp,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        borderColor = neoInkBorderColor(),
                        shadowColor = neoHardShadowColor(),
                        modifier = Modifier.testTag("review_edit_metadata_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Sửa Metadata",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Metadata",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Scrollable Content: TOC 3D Pill AT THE VERY TOP -> Summary Stats -> Diffs
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. TOPMOST ITEM: Compact 3D Pill "Làm lại mục lục" Toggle
                item(key = "toc_regenerate_toggle") {
                    RegenerateToc3DPill(
                        tocInspection = snapshot.tocInspection,
                        regenerateToc = snapshot.regenerateToc,
                        onToggle = onToggleRegenerateToc
                    )
                }

                // 2. Summary Stats Card
                item(key = "review_summary") {
                    IosCard(contentPadding = PaddingValues(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SummaryStatItem("Rác & Link", snapshot.stats.junkLinksRemoved, MaterialTheme.colorScheme.tertiary)
                            SummaryStatItem("Trắng & Dấu", snapshot.stats.whitespaceFixes, MaterialTheme.colorScheme.primary)
                            SummaryStatItem("Chính tả", acceptedCount, MaterialTheme.colorScheme.secondary)
                        }

                        if (snapshot.stats.mergedParagraphs > 0 ||
                            snapshot.stats.imagesOptimized > 0 ||
                            snapshot.stats.footnotesRemoved > 0
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (snapshot.stats.footnotesRemoved > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            text = "Đã dọn ${snapshot.stats.footnotesRemoved} chú thích rác",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                if (snapshot.stats.mergedParagraphs > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "Đã nối ${snapshot.stats.mergedParagraphs} đoạn ngắt dòng lỗi",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                if (snapshot.stats.imagesOptimized > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = "Tối ưu ${snapshot.stats.imagesOptimized} ảnh EPUB",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val ctx = snapshot.bookContext
                        if (ctx != null && ctx.properNouns.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            IosHairlineDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandProperNouns = !expandProperNouns }
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${ctx.genre} · ${ctx.properNouns.size} tên riêng được bảo vệ",
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (expandProperNouns) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            if (expandProperNouns) {
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    ctx.properNouns.take(24).forEach { noun ->
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = noun,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Save Error Alert (if saving failed)
                if (saveUiState is SaveUiState.Error) {
                    item(key = "save_error") {
                        val errShape = RoundedCornerShape(14.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 3.dp, bottom = 3.dp)
                                .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
                                .clip(errShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .border(1.5.dp, neoInkBorderColor(), errShape)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = saveUiState.message,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissSaveError) {
                                    Text("Đóng", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // 3. Diffs Section Header
                item(key = "diffs_header") {
                    IosSectionHeader(
                        title = "${stringResource(R.string.review_section_diffs)} (${snapshot.diffs.size})",
                        trailing = if (snapshot.diffs.isNotEmpty()) {
                            {
                                NeoBrutalistPressBox(
                                    onClick = { onSetAllDiffs(!allSelected) },
                                    isPill = true,
                                    shadowOffset = 3.dp,
                                    pressDepth = 2.dp,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    borderColor = neoInkBorderColor(),
                                    shadowColor = neoHardShadowColor()
                                ) {
                                    Text(
                                        text = if (allSelected) {
                                            stringResource(R.string.review_deselect_all)
                                        } else {
                                            stringResource(R.string.review_select_all)
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        } else null
                    )
                }

                if (snapshot.diffs.isEmpty()) {
                    item(key = "no_diffs") {
                        IosCard(contentPadding = PaddingValues(18.dp)) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = stringResource(R.string.review_no_ai_changes_title),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.review_no_ai_changes_desc),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = snapshot.diffs,
                        key = { it.id }
                    ) { diff ->
                        DiffComparisonCard(
                            diff = diff,
                            onToggle = { onToggleDiff(diff.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(10.dp)) }
            }

            // Pinned Bottom Bar: 2 horizontal 3D pill buttons (Lưu | Sửa tiếp)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    IosHairlineDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isSaving = saveUiState is SaveUiState.Saving

                        // Left: Lưu (3D Glossy Blue Pill)
                        IosPrimaryButton(
                            text = if (isSaving) "Đang lưu…" else "Lưu",
                            enabled = !isSaving,
                            height = 46.dp,
                            onClick = { showSaveDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_epub_button"),
                            leadingIcon = {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        )

                        // Right: Sửa tiếp (3D Frosted/Elevated Pill)
                        ContinueEdit3DPillButton(
                            text = "Sửa tiếp",
                            enabled = !isSaving && snapshot.workspace != null,
                            onClick = { showContinueEditDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("continue_edit_button")
                        )
                    }
                }
            }
        }
    }

    // Floating iOS Confirmation Dialog when clicking "Sửa tiếp"
    if (showContinueEditDialog) {
        ContinueEditModeDialog(
            onSelectMode = { mode ->
                showContinueEditDialog = false
                onContinueEdit(mode)
            },
            onDismiss = { showContinueEditDialog = false }
        )
    }

    // Compact Floating Save Dialog with tappable folder picker + 2 clean single-line 3D buttons
    val sourceFile = snapshot.sourceFile
    if (sourceFile != null) {
        val displayFolder = customSaveFolderLabel ?: sourceFile.displayParentFolder
        SaveDialog(
            visible = showSaveDialog,
            displayFolderPath = displayFolder,
            onPickFolderClick = onPickSaveFolderClick,
            onSelectMode = { mode ->
                showSaveDialog = false
                onSaveWithMode(mode)
            },
            onDismiss = { showSaveDialog = false }
        )
    }

    // Floating Save Success Dialog
    if (saveUiState is SaveUiState.Completed) {
        IosSaveSuccessDialog(
            result = saveUiState.result,
            onOpenEpub = {
                onOpenSavedFile(
                    saveUiState.result.savedAbsolutePath,
                    saveUiState.result.savedUriString
                )
            },
            onDone = onFinishToHome
        )
    }
}

@Composable
private fun ContinueEdit3DPillButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inkBorder = neoInkBorderColor()
    val paperBg = neoPaperSurfaceColor()

    NeoBrutalistPressBox(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        isPill = true,
        shadowOffset = 3.dp,
        pressDepth = 2.dp,
        containerColor = if (enabled) paperBg else MaterialTheme.colorScheme.surfaceVariant,
        borderColor = if (enabled) inkBorder else inkBorder.copy(alpha = 0.4f),
        shadowColor = neoHardShadowColor(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.EditNote,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Floating iOS Rounded-Square Confirmation Dialog for "Sửa tiếp":
 * 1. Giữ những gì đã sửa (cleaned paragraphs + original kept in parallel for comparison)
 * 2. Giữ nguyên (original uncleaned paragraphs + original kept in parallel)
 */
@Composable
private fun ContinueEditModeDialog(
    onSelectMode: (ManualEditorMode) -> Unit,
    onDismiss: () -> Unit
) {
    val pillShape = RoundedCornerShape(50)

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Chọn phiên bản để sửa tiếp",
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Bản gốc luôn được lưu song song để bạn bật So sánh 2 nửa màn hình bất cứ lúc nào.",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Option 1: Giữ những gì đã sửa
            NeoBrutalistPressBox(
                onClick = { onSelectMode(ManualEditorMode.KEEP_CLEANED) },
                cornerRadius = 14.dp,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                borderColor = neoInkBorderColor(),
                shadowColor = neoHardShadowColor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("continue_edit_keep_cleaned")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .border(1.2.dp, neoInkBorderColor(), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Giữ những gì đã sửa",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Mở nội dung đã được bộ lọc làm sạch",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Option 2: Giữ nguyên
            NeoBrutalistPressBox(
                onClick = { onSelectMode(ManualEditorMode.KEEP_ORIGINAL) },
                cornerRadius = 14.dp,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                borderColor = neoInkBorderColor(),
                shadowColor = neoHardShadowColor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("continue_edit_keep_original")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(neoPaperSurfaceColor())
                            .border(1.2.dp, neoInkBorderColor(), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Giữ nguyên",
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Mở nội dung gốc ban đầu chưa qua bộ lọc",
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Ios3DSurfacePillButton(
                text = "Hủy",
                height = 40.dp,
                recessed = true,
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("continue_edit_cancel")
            )
        }
    }
}

/**
 * Compact 3D Pill placed at the VERY TOP of ReviewScreen for Smart TOC Regeneration:
 * - Left: "Làm lại mục lục" (13.sp SemiBold) + status ("Hiện có: X mục (Đủ)" / "Chưa có / Thiếu mục lục", 11.sp)
 * - Right: Compact Switch
 */
@Composable
private fun RegenerateToc3DPill(
    tocInspection: TocInspectionResult?,
    regenerateToc: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val inkBorder = neoInkBorderColor()
    val hasSufficient = tocInspection?.hasSufficientToc == true
    val statusText = tocInspection?.statusSummary ?: "Chưa có / Thiếu mục lục"
    val statusColor = if (hasSufficient) IosGreen else IosOrange

    NeoBrutalistPressBox(
        onClick = { onToggle(!regenerateToc) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("review_toc_toggle_pill"),
        cornerRadius = 14.dp,
        shadowOffset = 3.dp,
        pressDepth = 2.dp,
        containerColor = neoPaperSurfaceColor(),
        borderColor = inkBorder,
        shadowColor = if (regenerateToc && !isDark) NeoEmeraldShadow else neoHardShadowColor()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (hasSufficient) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer
                    )
                    .border(1.2.dp, inkBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.FormatListBulleted,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Làm lại mục lục",
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Switch(
                checked = regenerateToc,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .scale(0.80f)
                    .testTag("review_toc_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = IosGreen,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun SummaryStatItem(label: String, value: Int, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontFamily = JetBrainsMonoFamily,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiffComparisonCard(
    diff: DiffEntry,
    onToggle: () -> Unit
) {
    IosCard(contentPadding = PaddingValues(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diff.chapterTitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = diff.reason,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Switch(
                checked = diff.accepted,
                onCheckedChange = { onToggle() },
                modifier = Modifier
                    .scale(0.82f)
                    .testTag("diff_card_toggle"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // BEFORE Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
                .padding(9.dp)
        ) {
            Text(
                text = stringResource(R.string.review_label_before),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = diff.beforeText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }

        // AFTER Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (diff.accepted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(9.dp)
        ) {
            Text(
                text = stringResource(R.string.review_label_after),
                fontSize = 10.sp,
                color = if (diff.accepted) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = diff.afterText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = if (diff.accepted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
