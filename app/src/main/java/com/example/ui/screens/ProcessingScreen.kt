package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.example.R
import com.example.pipeline.PipelineStep
import com.example.pipeline.ProcessingPhase
import com.example.pipeline.ProcessingSnapshot
import com.example.pipeline.StepState
import com.example.ui.components.IosCard
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.IosSectionHeader
import com.example.ui.theme.IosBackground
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosBlueSoft
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosLabelTertiary
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosOrangeSoft
import com.example.ui.theme.IosRed
import com.example.ui.theme.IosRedSoft
import com.example.ui.theme.IosSeparator
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
            .background(IosBackground),
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
                                style = MaterialTheme.typography.headlineMedium,
                                color = IosLabelPrimary
                            )
                            if (snapshot.phase == ProcessingPhase.RUNNING) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = IosBlueSoft
                                ) {
                                    Text(
                                        text = stringResource(R.string.processing_background_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = IosBlue,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = snapshot.sourceFile?.displayName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = IosLabelSecondary,
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
                                color = if (snapshot.phase == ProcessingPhase.ERROR) IosRed else IosBlue
                            )
                            if (snapshot.stats.totalChapters > 0) {
                                Text(
                                    text = "${snapshot.stats.totalChapters} chương",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = IosLabelSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (snapshot.phase == ProcessingPhase.ERROR) IosRed else IosBlue,
                            trackColor = IosSeparator,
                            strokeCap = StrokeCap.Round
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = snapshot.statusDetail.ifBlank { "Đang chuẩn bị…" },
                            style = MaterialTheme.typography.bodySmall,
                            color = IosLabelSecondary
                        )

                        // Global Context Pill once Step 0 detects genre + proper nouns
                        val ctx = snapshot.bookContext
                        if (ctx != null && ctx.genre.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = IosBackground,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        tint = IosBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${ctx.genre} · Bảo vệ ${ctx.properNouns.size} tên riêng",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = IosLabelPrimary,
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
                            iconTint = IosOrange,
                            iconBg = IosOrangeSoft,
                            count = snapshot.stats.junkLinksRemoved,
                            label = stringResource(R.string.processing_metric_junk)
                        )
                        LiveMetricCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.SpaceBar,
                            iconTint = IosBlue,
                            iconBg = IosBlueSoft,
                            count = snapshot.stats.whitespaceFixes,
                            label = stringResource(R.string.processing_metric_whitespace)
                        )
                        LiveMetricCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Spellcheck,
                            iconTint = IosGreen,
                            iconBg = IosGreenSoft,
                            count = snapshot.stats.spellingFixes,
                            label = stringResource(R.string.processing_metric_spelling)
                        )
                    }
                }

                // Error Banner (if failed)
                if (snapshot.phase == ProcessingPhase.ERROR && !snapshot.errorMessage.isNullOrBlank()) {
                    item(key = "error_card") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = IosRedSoft,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Error,
                                        contentDescription = null,
                                        tint = IosRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Xử lý gặp sự cố",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = IosRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = snapshot.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosLabelPrimary
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
                        OutlinedButton(
                            onClick = onCancelClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("cancel_processing_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.processing_cancel),
                                style = MaterialTheme.typography.labelLarge,
                                color = IosRed
                            )
                        }
                    }

                    ProcessingPhase.ERROR, ProcessingPhase.CANCELLED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onBackHomeClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("processing_back_home_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.processing_back_home),
                                    color = IosLabelPrimary
                                )
                            }
                            IosPrimaryButton(
                                text = stringResource(R.string.processing_retry),
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
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = JetBrainsMonoFamily
            ),
            color = IosLabelPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = IosLabelSecondary,
            maxLines = 1,
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
                    tint = IosGreen,
                    modifier = Modifier.size(22.dp)
                )
            }

            StepState.RUNNING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.4.dp,
                    color = IosBlue
                )
            }

            StepState.FAILED -> {
                Icon(
                    imageVector = Icons.Rounded.Error,
                    contentDescription = "Lỗi",
                    tint = IosRed,
                    modifier = Modifier.size(22.dp)
                )
            }

            StepState.PENDING -> {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(IosBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = IosLabelTertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                StepState.RUNNING -> IosBlue
                StepState.FAILED -> IosRed
                StepState.COMPLETED -> IosLabelPrimary
                StepState.PENDING -> IosLabelSecondary
            },
            fontWeight = if (state == StepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}
