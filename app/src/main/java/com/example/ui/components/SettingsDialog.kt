package com.example.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IosPillBorderBottomLight
import com.example.ui.theme.IosPillBottomDark
import com.example.ui.theme.IosPillBottomLight
import com.example.ui.theme.IosPillTopDark
import com.example.ui.theme.IosPillTopLight
import com.example.ui.theme.LocalIsDarkTheme

/**
 * Compact Floating iOS Rounded-Square Settings Dialog (Upgrade 1.3):
 * - Light / Dark mode 3D pill switch
 * - "Tự động lưu nháp" 3D pill switch (default OFF — saves silently every 2 min & on chapter switch)
 * - "Thay thế nhanh cá nhân" 3D pill entry opening the personal replace rules manager
 * - "Thư viện ghi nhớ lỗi" section with live count, auto-apply switch (default ON), Export & Import JSON
 * - App info card synced to `EpubCleanAI — Bản nâng cấp 1.3 (Offline)`
 */
@Composable
fun SettingsDialog(
    visible: Boolean,
    isDarkMode: Boolean,
    autoSaveDraft: Boolean,
    customReplaceCount: Int,
    errorMemoryAutoApply: Boolean,
    errorMemoryRuleCount: Int,
    onToggleDarkMode: (Boolean) -> Unit,
    onToggleAutoSaveDraft: (Boolean) -> Unit,
    onOpenCustomReplaceClick: () -> Unit,
    onToggleErrorMemoryAutoApply: (Boolean) -> Unit,
    onExportErrorMemoryClick: () -> Unit,
    onImportErrorMemoryClick: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            // Header: "Cài đặt" (18.sp) + circular X button (32.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cài đặt",
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onDismiss)
                        .testTag("settings_close_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng cài đặt",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Section 1: Light / Dark Mode 3D Pill Row
            Settings3DPillSwitchRow(
                icon = if (isDarkMode) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                iconTint = MaterialTheme.colorScheme.primary,
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                title = "Giao diện Sáng / Tối",
                subtitle = if (isDarkMode) "Đang bật Chế độ Tối" else "Đang bật Chế độ Sáng",
                checked = isDarkMode,
                switchTag = "settings_dark_mode_switch",
                onCheckedChange = onToggleDarkMode
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Section 2 (Upgrade 1.2): Auto-Save Draft 3D Pill Switch Row (default OFF)
            Settings3DPillSwitchRow(
                icon = Icons.Rounded.SaveAs,
                iconTint = MaterialTheme.colorScheme.secondary,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                title = "Tự động lưu nháp",
                subtitle = if (autoSaveDraft) {
                    "Lưu ngầm mỗi 2 phút & khi chuyển chương"
                } else {
                    "Đang tắt (chỉ lưu khi bấm Lưu nháp)"
                },
                checked = autoSaveDraft,
                switchTag = "settings_auto_save_draft_switch",
                onCheckedChange = onToggleAutoSaveDraft
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Section 3 (Upgrade 1.2): Personal Quick-Replace Dictionary 3D Pill Action Row
            Settings3DPillActionRow(
                icon = Icons.Rounded.FindReplace,
                iconTint = MaterialTheme.colorScheme.tertiary,
                iconContainer = MaterialTheme.colorScheme.tertiaryContainer,
                title = "Thay thế nhanh cá nhân",
                subtitle = if (customReplaceCount > 0) {
                    "Đã lưu $customReplaceCount cặp từ sửa lỗi nhanh"
                } else {
                    "Quản lý cặp từ lỗi OCR & tên nhân vật"
                },
                rowTag = "settings_custom_replace_row",
                onClick = onOpenCustomReplaceClick
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Section 4 (Upgrade 1.3): Self-Learning Error Memory Library Card
            ErrorMemoryLibrarySectionCard(
                ruleCount = errorMemoryRuleCount,
                autoApplyEnabled = errorMemoryAutoApply,
                onToggleAutoApply = onToggleErrorMemoryAutoApply,
                onExportClick = onExportErrorMemoryClick,
                onImportClick = onImportErrorMemoryClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Section 5: Compact App Information Card (Version 1.3 Synced)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "EpubCleanAI — Bản nâng cấp 1.3 (Offline)",
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Phiên bản 1.3 • Xử lý 100% trên thiết bị",
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IosHairlineDivider()

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Bộ từ điển tích hợp",
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Tiếng Việt + Tiếng Anh Offline",
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Tách & gộp chương trực tiếp, thư viện tự học & ghi nhớ lỗi cá nhân có sao lưu JSON, nối đoạn lỗi thông minh, nén ảnh EPUB, lưu nháp và đọc – sửa Sepia 100% ngoại tuyến.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            IosPrimaryButton(
                text = "Hoàn tất",
                onClick = onDismiss,
                height = 44.dp,
                modifier = Modifier.testTag("settings_done_button")
            )
        }
    }
}

@Composable
private fun Settings3DPillSwitchRow(
    icon: ImageVector,
    iconTint: Color,
    iconContainer: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    switchTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
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
                        listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.95f), IosPillBorderBottomLight)
                    }
                ),
                shape = pillShape
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .scale(0.82f)
                    .testTag(switchTag),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun Settings3DPillActionRow(
    icon: ImageVector,
    iconTint: Color,
    iconContainer: Color,
    title: String,
    subtitle: String,
    rowTag: String,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val pillShape = RoundedCornerShape(50)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
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
                        listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.95f), IosPillBorderBottomLight)
                    }
                ),
                shape = pillShape
            )
            .clickable(onClick = onClick)
            .testTag(rowTag)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = "Mở",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ErrorMemoryLibrarySectionCard(
    ruleCount: Int,
    autoApplyEnabled: Boolean,
    onToggleAutoApply: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val cardShape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = cardShape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(cardShape)
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
                        listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.95f), IosPillBorderBottomLight)
                    }
                ),
                shape = cardShape
            )
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Icon + Title + Live Rule Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thư viện ghi nhớ lỗi",
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Đang ghi nhớ: $ruleCount lỗi",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("settings_error_memory_count")
                    )
                }
            }

            IosHairlineDivider()

            // Switch: "Tự động áp dụng khi quét" (default ON)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggleAutoApply(!autoApplyEnabled) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tự động áp dụng khi quét",
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (autoApplyEnabled) {
                            "Ưu tiên sửa từ lỗi & xóa rác đã học"
                        } else {
                            "Đang tắt tự động áp dụng"
                        },
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = autoApplyEnabled,
                    onCheckedChange = onToggleAutoApply,
                    modifier = Modifier
                        .scale(0.82f)
                        .testTag("settings_error_memory_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.secondary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }

            // Export & Import JSON Pill Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExportClick,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("settings_export_error_lib_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Xuất thư viện",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onImportClick,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("settings_import_error_lib_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Nhập thư viện",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
