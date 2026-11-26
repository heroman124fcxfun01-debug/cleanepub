package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pipeline.SaveMode
import com.example.ui.theme.LocalIsDarkTheme

/**
 * Swiss Neo-Brutalist 3D Floating Save Dialog:
 * - Tappable inked paper folder selector pill with 3.dp hard offset shadow & press depression
 * - 2 crisp single-line 3D buttons: "Lưu mới" (Cobalt Blue) & "Lưu thay thế" (Archival Red)
 * - Bottom "Hủy" tactile paper pill button
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
    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Lưu file EPUB",
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tappable 3D inked folder selector pill
            NeoBrutalistPressBox(
                onClick = onPickFolderClick,
                isPill = true,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                containerColor = neoSecondaryPaperColor(),
                borderColor = inkBorder,
                shadowColor = hardShadow,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("save_dialog_pick_folder")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
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
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
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

            Spacer(modifier = Modifier.height(14.dp))

            // Action 1: "Lưu mới" (Cobalt 3D Neo-Brutalist Pill)
            IosPrimaryButton(
                text = "Lưu mới",
                height = 44.dp,
                onClick = { onSelectMode(SaveMode.KEEP_ORIGINAL) },
                modifier = Modifier.testTag("action_sheet_keep"),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.FileCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action 2: "Lưu thay thế" (Tactile 3D Red-Tinted Paper Pill)
            val replaceBg = if (isDark) Color(0xFF47211B) else Color(0xFFFDE8E6)
            val replaceInk = if (isDark) Color(0xFFFF7A6B) else Color(0xFFDC2626)

            NeoBrutalistPressBox(
                onClick = { onSelectMode(SaveMode.REPLACE_ORIGINAL) },
                isPill = true,
                shadowOffset = 3.dp,
                pressDepth = 2.dp,
                containerColor = replaceBg,
                borderColor = inkBorder,
                shadowColor = hardShadow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(47.dp)
                    .testTag("action_sheet_replace")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAs,
                        contentDescription = null,
                        tint = replaceInk,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = "Lưu thay thế",
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = replaceInk,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Cancel button
            Ios3DSurfacePillButton(
                text = "Hủy",
                height = 40.dp,
                recessed = true,
                onClick = onDismiss,
                modifier = Modifier.testTag("action_sheet_cancel")
            )
        }
    }
}
