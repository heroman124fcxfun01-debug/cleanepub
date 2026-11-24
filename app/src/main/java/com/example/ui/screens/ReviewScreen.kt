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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
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
import com.example.ui.theme.IosBackground
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosBlueSoft
import com.example.ui.theme.IosCardSurface
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosRed
import com.example.ui.theme.IosRedSoft
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    snapshot: ProcessingSnapshot,
    saveUiState: SaveUiState,
    onToggleDiff: (String) -> Unit,
    onSetAllDiffs: (Boolean) -> Unit,
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
            .background(IosBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar (iOS minimalist)
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
                        tint = IosBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.review_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = IosLabelPrimary
                    )
                    Text(
                        text = snapshot.sourceFile?.displayName.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = IosLabelSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                            SummaryStatItem("Link rác", snapshot.stats.junkLinksRemoved, IosOrange)
                            SummaryStatItem("Khoảng trắng", snapshot.stats.whitespaceFixes, IosBlue)
                            SummaryStatItem("Chính tả AI", acceptedCount, IosGreen)
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
                                    color = IosLabelSecondary,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = if (expandProperNouns) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = IosLabelSecondary,
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
                                            shape = RoundedCornerShape(8.dp),
                                            color = IosBackground
                                        ) {
                                            Text(
                                                text = noun,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = IosLabelPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                            shape = RoundedCornerShape(14.dp),
                            color = IosRedSoft,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = saveUiState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosRed,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissSaveError) {
                                    Text("Đóng", color = IosRed)
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
                                Text(
                                    text = if (allSelected) {
                                        stringResource(R.string.review_deselect_all)
                                    } else {
                                        stringResource(R.string.review_select_all)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = IosBlue,
                                    modifier = Modifier
                                        .clickable { onSetAllDiffs(!allSelected) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
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
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(IosGreenSoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = IosGreen,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.review_no_ai_changes_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = IosLabelPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.review_no_ai_changes_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosLabelSecondary
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

            // Pinned Bottom Bar with Save CTA
            Surface(
                color = IosCardSurface,
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

    // iOS Action Sheet (Replace Original vs Keep Original _clean.epub)
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

    // Save Success Dialog
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
            color = IosLabelSecondary
        )
    }
}

@Composable
private fun DiffComparisonCard(
    diff: DiffEntry,
    onToggle: () -> Unit
) {
    IosCard(contentPadding = PaddingValues(14.dp)) {
        // Card header: Chapter + reason pill + accept switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = diff.chapterTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = IosLabelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = IosBlueSoft
                ) {
                    Text(
                        text = diff.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = IosBlue,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
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
                    checkedTrackColor = IosGreen,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = IosLabelSecondary.copy(alpha = 0.25f),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // BEFORE Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(IosRedSoft.copy(alpha = 0.72f))
                .padding(10.dp)
        ) {
            Text(
                text = stringResource(R.string.review_label_before),
                style = MaterialTheme.typography.labelSmall,
                color = IosRed,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = diff.beforeText,
                style = MaterialTheme.typography.bodyMedium,
                color = IosLabelPrimary
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
                tint = IosLabelSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        // AFTER Block
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (diff.accepted) IosGreenSoft.copy(alpha = 0.8f)
                    else IosBackground
                )
                .padding(10.dp)
        ) {
            Text(
                text = stringResource(R.string.review_label_after),
                style = MaterialTheme.typography.labelSmall,
                color = if (diff.accepted) Color(0xFF1A7F37) else IosLabelSecondary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = diff.afterText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (diff.accepted) IosLabelPrimary else IosLabelSecondary
            )
        }
    }
}
