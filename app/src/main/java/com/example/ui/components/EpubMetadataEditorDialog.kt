package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.pipeline.EpubMetadata
import java.io.File

/**
 * Floating iOS Rounded-Square Metadata & Cover Editor Dialog (`RoundedCornerShape(28.dp)`).
 * Shown immediately after selecting an `.epub` or `.pdf` file (and optionally from Review)
 * so the user can edit dc:title, dc:creator, dc:identifier, dc:language, and replace the book cover.
 */
@Composable
fun EpubMetadataEditorDialog(
    metadata: EpubMetadata,
    fileDisplayName: String,
    confirmButtonLabel: String = "Bắt đầu xử lý",
    onPickCoverClick: () -> Unit,
    onConfirm: (EpubMetadata) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(metadata) { mutableStateOf(metadata.title) }
    var creator by remember(metadata) { mutableStateOf(metadata.creator) }
    var identifier by remember(metadata) { mutableStateOf(metadata.identifier) }
    var language by remember(metadata) { mutableStateOf(metadata.language) }

    val coverBitmap = remember(metadata.newCoverImagePath, metadata.existingCoverBytes) {
        runCatching {
            val newPath = metadata.newCoverImagePath
            if (!newPath.isNullOrBlank() && File(newPath).exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(newPath, opts)?.asImageBitmap()
            } else if (metadata.existingCoverBytes != null && metadata.existingCoverBytes.isNotEmpty()) {
                val bytes = metadata.existingCoverBytes
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            } else {
                null
            }
        }.getOrNull()
    }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thông tin sách",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = fileDisplayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Ios3DCircleIconButton(
                    onClick = onDismiss,
                    size = 32.dp,
                    modifier = Modifier.testTag("metadata_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cover Preview + Change Cover Pill Button (Neo-Brutalist 3D Card)
            val inkBorder = neoInkBorderColor()
            val hardShadow = neoHardShadowColor()
            val coverCardShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 3.dp, bottom = 3.dp)
                    .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp, shadowColor = hardShadow)
                    .clip(coverCardShape)
                    .background(neoSecondaryPaperColor())
                    .border(1.5.dp, inkBorder, coverCardShape)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(68.dp)
                            .height(94.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.5.dp, inkBorder, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverBitmap != null) {
                            Image(
                                bitmap = coverBitmap,
                                contentDescription = "Bìa sách hiện tại",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoStories,
                                contentDescription = "Chưa có bìa sách",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bìa sách (Cover)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                !metadata.newCoverImagePath.isNullOrBlank() -> "Đã chọn ảnh bìa mới (cover.jpg)"
                                coverBitmap != null -> "Đang dùng ảnh bìa gốc của sách"
                                else -> "Chưa có ảnh bìa — có thể thêm từ thư viện"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        NeoBrutalistPressBox(
                            onClick = onPickCoverClick,
                            isPill = true,
                            shadowOffset = 3.dp,
                            pressDepth = 2.dp,
                            containerColor = MaterialTheme.colorScheme.primary,
                            borderColor = inkBorder,
                            shadowColor = hardShadow,
                            modifier = Modifier.testTag("change_cover_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = "Đổi bìa sách",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4 Editable Metadata Fields
            MetadataTextField(
                value = title,
                onValueChange = { title = it },
                label = "Tiêu đề (dc:title)",
                testTagName = "metadata_title_input"
            )
            Spacer(modifier = Modifier.height(10.dp))

            MetadataTextField(
                value = creator,
                onValueChange = { creator = it },
                label = "Người tạo / Tác giả (dc:creator)",
                testTagName = "metadata_creator_input"
            )
            Spacer(modifier = Modifier.height(10.dp))

            MetadataTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = "Identifier (dc:identifier)",
                testTagName = "metadata_identifier_input"
            )
            Spacer(modifier = Modifier.height(10.dp))

            MetadataTextField(
                value = language,
                onValueChange = { language = it },
                label = "Ngôn ngữ (dc:language)",
                testTagName = "metadata_language_input"
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Language Pill Chips (Neo-Brutalist 3D tactile pills)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("vi", "en", "vi-VN").forEach { code ->
                    val selected = language.trim().equals(code, ignoreCase = true)
                    NeoBrutalistPressBox(
                        onClick = { language = code },
                        isPill = true,
                        shadowOffset = 3.dp,
                        pressDepth = 2.dp,
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            neoPaperSurfaceColor()
                        },
                        borderColor = inkBorder,
                        shadowColor = hardShadow
                    ) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom 3D Pill CTA Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ios3DSurfacePillButton(
                    text = "Hủy",
                    height = 44.dp,
                    recessed = true,
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(0.38f)
                        .testTag("metadata_cancel_button")
                )

                Box(modifier = Modifier.weight(0.62f)) {
                    IosPrimaryButton(
                        text = confirmButtonLabel,
                        height = 44.dp,
                        onClick = {
                            onConfirm(
                                metadata.copy(
                                    title = title.trim().ifBlank { metadata.title },
                                    creator = creator.trim().ifBlank { "Không rõ tác giả" },
                                    identifier = identifier.trim().ifBlank { metadata.identifier },
                                    language = language.trim().ifBlank { "vi" }
                                )
                            )
                        },
                        modifier = Modifier.testTag("metadata_confirm_button"),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    testTagName: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTagName),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    )
}
