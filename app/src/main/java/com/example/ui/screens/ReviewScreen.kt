package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.pipeline.DiffEntry
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.SaveMode
import com.example.ui.SaveUiState
import com.example.ui.components.IosCard
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.IosSaveActionSheet
import com.example.ui.components.IosSaveSuccessDialog
import com.example.ui.components.IosSectionHeader
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    snapshot: ProcessingSnapshot,
    saveUiState: SaveUiState,
    onToggleDiff: (String) -> Unit,
    onSetAllDiffs: (Boolean) -> Unit,
    onEditMetadataClick: () -> Unit,
    onSaveWithMode: (SaveMode) -> Unit,
    onOpenSavedFile: (absolutePath: String?, uriString: String?) -> Unit,
    onFinishToHome: () -> Unit,
    onDismissSaveError: () -> Unit
) {
    BackHandler {
        onFinishToHome()
    }

    var showActionSheet by remember { mutableStateOf(false) }
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
            // Top Bar (iOS minimalist + oval Metadata Edit button)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onFinishToHome,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("review_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBackIos,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.review_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = snapshot.metadata?.title?.ifBlank { snapshot.sourceFile?.displayName.orEmpty() }
                            ?: snapshot.sourceFile?.displayName.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (snapshot.metadata != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .testTag("review_edit_metadata_button")
                            .clickable(onClick = onEditMetadataClick)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Sửa Metadata",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Metadata",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Scrollable Diff List & Summary
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Summary Card
                item(key = "review_summary") {
                    IosCard(contentPadding = PaddingValues(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SummaryStatItem("Link rác", snapshot.stats.junkLinksRemoved, MaterialTheme.colorScheme.tertiary)
                            SummaryStatItem("Khoảng trắng", snapshot.stats.whitespaceFixes, MaterialTheme.colorScheme.primary)
                            SummaryStatItem("Chính tả", acceptedCount, MaterialTheme.colorScheme.secondary)
                        }

                        val ctx = snapshot.bookContext
                        if (ctx != null && ctx.properNouns.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            IosHairlineDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandProperNouns = !expandProperNouns }
                                    .padding(top = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${ctx.genre} · ${ctx.properNouns.size} tên riêng được bảo vệ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (expandProperNouns) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
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
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = saveUiState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissSaveError) {
                                    Text("Đóng", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // Diffs Header
                item(key = "diffs_header") {
                    IosSectionHeader(
                        title = "${stringResource(R.string.review_section_diffs)} (${snapshot.diffs.size})",
                        trailing = if (snapshot.diffs.isNotEmpty()) {
                            {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { onSetAllDiffs(!allSelected) }
                                ) {
                                    Text(
                                        text = if (allSelected) {
                                            stringResource(R.string.review_deselect_all)
                                        } else {
                                            stringResource(R.string.review_select_all)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        } else null
                    )
                }

                if (snapshot.diffs.isEmpty()) {
                    item(key = "no_diffs") {
                        IosCard(contentPadding = PaddingValues(22.dp)) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.review_no_ai_changes_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.review_no_ai_changes_desc),
                                    style = MaterialTheme.typography.bodySmall,
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

                item { Spacer(modifier = Modifier.height(12.dp)) }
            }

            // Pinned Bottom Bar with Oval Save CTA
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    IosHairlineDivider()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        val isSaving = saveUiState is SaveUiState.Saving
                        IosPrimaryButton(
                            text = if (isSaving) {
                                stringResource(R.string.review_saving)
                            } else {
                                stringResource(R.string.review_btn_save)
                            },
                            enabled = !isSaving,
                            onClick = { showActionSheet = true },
                            modifier = Modifier.testTag("save_epub_button"),
                            leadingIcon = {
                                if (isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Floating iOS Rounded-Square Save Dialog (Replace Original vs Keep Original _clean.epub)
    val sourceFile = snapshot.sourceFile
    if (sourceFile != null) {
        IosSaveActionSheet(
            visible = showActionSheet,
            sourceFile = sourceFile,
            onSelectMode = { mode ->
                showActionSheet = false
                onSaveWithMode(mode)
            },
            onDismiss = { showActionSheet = false }
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
private fun SummaryStatItem(label: String, value: Int, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = JetBrainsMonoFamily
            ),
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiffComparisonCard(
    diff: DiffEntry,
    onToggle: () -> Unit
) {
    IosCard(contentPadding = PaddingValues(14.dp)) {
        // Card header: Chapter + oval reason pill + accept switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diff.chapterTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = diff.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = diff.accepted,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag("diff_card_toggle"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // BEFORE Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f))
                .padding(10.dp)
        ) {
            Text(
                text = stringResource(R.string.review_label_before),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = diff.beforeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        // AFTER Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (diff.accepted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(10.dp)
        ) {
            Text(
                text = stringResource(R.string.review_label_after),
                style = MaterialTheme.typography.labelSmall,
                color = if (diff.accepted) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = diff.afterText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (diff.accepted) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
