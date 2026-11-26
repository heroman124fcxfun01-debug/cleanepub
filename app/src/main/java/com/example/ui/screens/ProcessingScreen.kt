package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.pipeline.PipelineStep
import com.example.pipeline.ProcessingPhase
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.StepState
import com.example.ui.components.Ios3DSurfacePillButton
import com.example.ui.components.IosCard
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.IosSectionHeader
import com.example.ui.components.NeoBrutalistPressBox
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoHardShadowColor
import com.example.ui.components.neoInkBorderColor
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun ProcessingScreen(
    snapshot: ProcessingSnapshot,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    onBackHomeClick: () -> Unit
) {
    BackHandler {
        if (snapshot.phase != ProcessingPhase.RUNNING) {
            onBackHomeClick()
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = snapshot.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 280),
        label = "pipeline_progress"
    )
    val percentInt = (animatedProgress * 100).toInt().coerceIn(0, 100)

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
                .navigationBarsPadding()
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                item(key = "proc_header") {
                    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.processing_title),
                                fontSize = 21.sp,
                                lineHeight = 25.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (snapshot.phase == ProcessingPhase.RUNNING) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    border = BorderStroke(1.5.dp, neoInkBorderColor())
                                ) {
                                    Text(
                                        text = stringResource(R.string.processing_background_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = snapshot.sourceFile?.displayName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Overall Progress Card
                item(key = "progress_card") {
                    IosCard(contentPadding = PaddingValues(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "$percentInt%",
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontFamily = JetBrainsMonoFamily
                                ),
                                color = if (snapshot.phase == ProcessingPhase.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            if (snapshot.stats.totalChapters > 0) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.2.dp, neoInkBorderColor())
                                ) {
                                    Text(
                                        text = "${snapshot.stats.totalChapters} chương",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50)),
                            color = if (snapshot.phase == ProcessingPhase.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.outline,
                            strokeCap = StrokeCap.Round
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = snapshot.statusDetail.ifBlank { "Đang chuẩn bị…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Global Context Pill once Step 0 detects genre + proper nouns
                        val ctx = snapshot.bookContext
                        if (ctx != null && ctx.genre.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.2.dp, neoInkBorderColor()),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${ctx.genre} · Bảo vệ ${ctx.properNouns.size} tên riêng",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                // Real-time 3 Error Counters
                item(key = "live_metrics") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LiveMetricCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.LinkOff,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                            count = snapshot.stats.junkLinksRemoved,
                            label = stringResource(R.string.processing_metric_junk)
                        )
                        LiveMetricCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.SpaceBar,
                            iconTint = MaterialTheme.colorScheme.primary,
                            iconBg = MaterialTheme.colorScheme.primaryContainer,
                            count = snapshot.stats.whitespaceFixes,
                            label = stringResource(R.string.processing_metric_whitespace)
                        )
                        LiveMetricCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Spellcheck,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconBg = MaterialTheme.colorScheme.secondaryContainer,
                            count = snapshot.stats.spellingFixes,
                            label = stringResource(R.string.processing_metric_spelling)
                        )
                    }
                }

                // Error Banner (if failed)
                if (snapshot.phase == ProcessingPhase.ERROR && !snapshot.errorMessage.isNullOrBlank()) {
                    item(key = "error_card") {
                        val errShape = RoundedCornerShape(14.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 3.dp, bottom = 3.dp)
                                .neoHardOffsetShadow(
                                    cornerRadius = 14.dp,
                                    offsetX = 3.dp,
                                    offsetY = 3.dp,
                                    shadowColor = neoHardShadowColor()
                                )
                                .clip(errShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .border(1.5.dp, neoInkBorderColor(), errShape)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Xử lý gặp sự cố",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = snapshot.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // 5-Step Pipeline Checklist
                item(key = "steps_header") {
                    IosSectionHeader(title = stringResource(R.string.processing_section_pipeline))
                }

                item(key = "steps_card") {
                    IosCard(contentPadding = PaddingValues(0.dp)) {
                        val steps = listOf(
                            PipelineStep.STEP_0_UNPACK_CONTEXT to stringResource(R.string.processing_step_0),
                            PipelineStep.STEP_1_CLEAN_JUNK to stringResource(R.string.processing_step_1),
                            PipelineStep.STEP_2_UNIFY_FONT to stringResource(R.string.processing_step_2),
                            PipelineStep.STEP_3_NORMALIZE_WHITESPACE to stringResource(R.string.processing_step_3),
                            PipelineStep.STEP_4_AI_PROOFREAD_PACK to stringResource(R.string.processing_step_4)
                        )
                        steps.forEachIndexed { idx, (step, title) ->
                            val state = snapshot.stepStates[step] ?: StepState.PENDING
                            StepRow(
                                stepNumber = idx,
                                title = title,
                                state = state
                            )
                            if (idx < steps.lastIndex) {
                                IosHairlineDivider(startIndent = true)
                            }
                        }
                    }
                }
            }

            // Bottom Action Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (snapshot.phase) {
                    ProcessingPhase.RUNNING -> {
                        NeoBrutalistPressBox(
                            onClick = onCancelClick,
                            isPill = true,
                            shadowOffset = 3.dp,
                            pressDepth = 2.dp,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            borderColor = neoInkBorderColor(),
                            shadowColor = neoHardShadowColor(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(49.dp)
                                .testTag("cancel_processing_button")
                        ) {
                            Text(
                                text = stringResource(R.string.processing_cancel),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    ProcessingPhase.ERROR, ProcessingPhase.CANCELLED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Ios3DSurfacePillButton(
                                text = stringResource(R.string.processing_back_home),
                                height = 46.dp,
                                onClick = onBackHomeClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("processing_back_home_button")
                            )
                            IosPrimaryButton(
                                text = stringResource(R.string.processing_retry),
                                height = 46.dp,
                                onClick = onRetryClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("retry_processing_button")
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun LiveMetricCard(
    modifier: Modifier,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    count: Int,
    label: String
) {
    IosCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg)
                .border(1.2.dp, neoInkBorderColor(), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = JetBrainsMonoFamily
            ),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StepRow(
    stepNumber: Int,
    title: String,
    state: StepState
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            StepState.COMPLETED -> {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Hoàn tất",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            StepState.RUNNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.4.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            StepState.FAILED -> {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = "Lỗi",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }

            StepState.PENDING -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                StepState.RUNNING -> MaterialTheme.colorScheme.primary
                StepState.FAILED -> MaterialTheme.colorScheme.error
                StepState.COMPLETED -> MaterialTheme.colorScheme.onSurface
                StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (state == StepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}
