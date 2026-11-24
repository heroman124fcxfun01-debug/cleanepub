package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.pipeline.SaveMode
import com.example.pipeline.SaveResult
import com.example.pipeline.SourceFileInfo
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosCardSurface
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosRed
import com.example.ui.theme.IosScrim
import com.example.ui.theme.IosSeparator
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = IosCardSurface,
        border = BorderStroke(0.5.dp, IosSeparator),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun IosHairlineDivider(
    modifier: Modifier = Modifier,
    startIndent: Boolean = false
) {
    HorizontalDivider(
        modifier = modifier.padding(start = if (startIndent) 16.dp else 0.dp),
        thickness = 0.5.dp,
        color = IosSeparator
    )
}

@Composable
fun IosSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = IosLabelSecondary,
            fontWeight = FontWeight.SemiBold
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
fun IosPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = IosBlue,
            contentColor = Color.White,
            disabledContainerColor = IosBlue.copy(alpha = 0.45f),
            disabledContentColor = Color.White.copy(alpha = 0.85f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        )
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Authentic iOS Action Sheet dialog with two save options (Replace Original vs Keep Original _clean.epub)
 * and a separated floating Cancel card.
 */
@Composable
fun IosSaveActionSheet(
    visible: Boolean,
    sourceFile: SourceFileInfo,
    onSelectMode: (SaveMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(IosScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 }
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    // Main grouped options card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = IosCardSurface.copy(alpha = 0.97f),
                        border = BorderStroke(0.5.dp, IosSeparator)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Sheet Header
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.sheet_save_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = IosLabelPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Thư mục: ${sourceFile.displayParentFolder}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFamily
                                    ),
                                    color = IosLabelSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }

                            IosHairlineDivider()

                            // Option 1: Replace original
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .testTag("action_sheet_replace")
                                    .clickable { onSelectMode(SaveMode.REPLACE_ORIGINAL) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.sheet_option_replace),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = IosRed,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.sheet_option_replace_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosLabelSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }

                            IosHairlineDivider()

                            // Option 2: Keep original -> _clean.epub
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp)
                                    .testTag("action_sheet_keep")
                                    .clickable { onSelectMode(SaveMode.KEEP_ORIGINAL) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.sheet_option_keep),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = IosBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(
                                        R.string.sheet_option_keep_sub,
                                        sourceFile.cleanCopyFileName
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosLabelSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Separated iOS Cancel Card
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .testTag("action_sheet_cancel")
                            .clickable(onClick = onDismiss),
                        shape = RoundedCornerShape(16.dp),
                        color = IosCardSurface,
                        border = BorderStroke(0.5.dp, IosSeparator)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(R.string.sheet_cancel),
                                style = MaterialTheme.typography.titleMedium,
                                color = IosBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IosSaveSuccessDialog(
    result: SaveResult.Success,
    onOpenEpub: () -> Unit,
    onDone: () -> Unit
) {
    Dialog(onDismissRequest = onDone) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = IosCardSurface,
            border = BorderStroke(0.5.dp, IosSeparator)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(IosGreenSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = IosGreen,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = stringResource(R.string.save_success_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = IosLabelPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = result.outputFileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = IosLabelPrimary,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = result.outputDisplayPath,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFamily
                    ),
                    color = IosLabelSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))
                IosHairlineDivider()

                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("dialog_open_epub_button")
                            .clickable(onClick = onOpenEpub),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.save_success_open),
                            style = MaterialTheme.typography.labelLarge,
                            color = IosBlue
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .height(50.dp)
                            .background(IosSeparator)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("dialog_done_button")
                            .clickable(onClick = onDone),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.save_success_done),
                            style = MaterialTheme.typography.labelLarge,
                            color = IosBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
