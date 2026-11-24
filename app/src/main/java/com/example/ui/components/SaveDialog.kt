package com.example.ui.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pipeline.SaveMode
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosBlueBottom
import com.example.ui.theme.IosBlueTop
import com.example.ui.theme.IosPillBorderBottomLight
import com.example.ui.theme.IosPillBottomDark
import com.example.ui.theme.IosPillBottomLight
import com.example.ui.theme.IosPillTopDark
import com.example.ui.theme.IosPillTopLight
import com.example.ui.theme.LocalIsDarkTheme

/**
 * Compact Floating iOS Save Dialog (`RoundedCornerShape(24.dp)`):
 * - Title: "Lưu file EPUB" (17.sp SemiBold, single line)
 * - Tappable oval folder selector pill (opens system OpenDocumentTree picker)
 * - 2 clean single-line 3D pill buttons (no subtitles): "Lưu mới" (Blue) & "Lưu thay thế" (Soft Red/Orange)
 * - Bottom "Hủy" button (44.dp)
 */
@Composable
fun SaveDialog(
    visible: Boolean,
    displayFolderPath: String,
    onPickFolderClick: () -> Unit,
    onSelectMode: (SaveMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val isDark = LocalIsDarkTheme.current
    val pillShape = RoundedCornerShape(50)

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact single-line title
            Text(
                text = "Lưu file EPUB",
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tappable oval folder pill (Folder icon + path + Edit icon)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 3.dp,
                        shape = pillShape,
                        ambientColor = Color.Black.copy(alpha = 0.06f),
                        spotColor = Color.Black.copy(alpha = 0.12f)
                    )
                    .clip(pillShape)
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
                                listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f))
                            } else {
                                listOf(Color.White.copy(alpha = 0.95f), IosPillBorderBottomLight)
                            }
                        ),
                        shape = pillShape
                    )
                    .clickable(onClick = onPickFolderClick)
                    .testTag("save_dialog_pick_folder")
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Thư mục: $displayFolderPath",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Đổi thư mục lưu",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action 1: "Lưu mới" (3D Blue Pill, 46.dp, 15.sp SemiBold, NO subtitle)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = pillShape,
                        ambientColor = IosBlue.copy(alpha = 0.22f),
                        spotColor = IosBlue.copy(alpha = 0.42f)
                    )
                    .clip(pillShape)
                    .background(
                        brush = Brush.verticalGradient(listOf(IosBlueTop, IosBlueBottom))
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.08f)
                            )
                        ),
                        shape = pillShape
                    )
                    .testTag("action_sheet_keep")
                    .clickable { onSelectMode(SaveMode.KEEP_ORIGINAL) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lưu mới",
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action 2: "Lưu thay thế" (3D Soft Red/Orange Pill, 46.dp, 15.sp SemiBold, NO subtitle)
            val replaceBgColors = if (isDark) {
                listOf(Color(0xFF47211B), Color(0xFF351713))
            } else {
                listOf(Color(0xFFFFF1EC), Color(0xFFFFE1D6))
            }
            val replaceBorderColors = if (isDark) {
                listOf(Color(0xFFFF7A6B).copy(alpha = 0.38f), Color(0xFFFF453A).copy(alpha = 0.16f))
            } else {
                listOf(Color.White.copy(alpha = 0.92f), Color(0xFFFF8A65).copy(alpha = 0.40f))
            }
            val replaceContentColor = if (isDark) Color(0xFFFF7A6B) else Color(0xFFD93F2B)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .shadow(
                        elevation = 5.dp,
                        shape = pillShape,
                        ambientColor = Color(0xFFFF5722).copy(alpha = 0.10f),
                        spotColor = Color(0xFFFF3B30).copy(alpha = 0.20f)
                    )
                    .clip(pillShape)
                    .background(brush = Brush.verticalGradient(replaceBgColors))
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(replaceBorderColors),
                        shape = pillShape
                    )
                    .testTag("action_sheet_replace")
                    .clickable { onSelectMode(SaveMode.REPLACE_ORIGINAL) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAs,
                        contentDescription = null,
                        tint = replaceContentColor,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lưu thay thế",
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = replaceContentColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cancel button (44.dp)
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("action_sheet_cancel"),
                shape = pillShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = "Hủy",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
        }
    }
}
