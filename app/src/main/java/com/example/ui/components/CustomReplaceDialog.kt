package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CustomReplaceRule
import com.example.ui.theme.JetBrainsMonoFamily

/**
 * Floating iOS Rounded-Square Dialog for managing Personal Quick-Replace Rules (Upgrade 1.2):
 * - Accessible from both `SettingsDialog` and `ManualEditorScreen`'s Find & Replace bar.
 * - Supports adding `Từ lỗi -> Từ đúng` pairs, deleting rules, and 1-tap `Áp dụng toàn bộ sách`
 *   when opened from an active manual book session.
 */
@Composable
fun CustomReplaceDialog(
    visible: Boolean,
    rules: List<CustomReplaceRule>,
    canApplyToBook: Boolean,
    onAddRule: (findText: String, replaceText: String) -> Unit,
    onDeleteRule: (id: String) -> Unit,
    onApplyAllToBook: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var findInput by remember { mutableStateOf("") }
    var replaceInput by remember { mutableStateOf("") }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header
            val inkBorder = neoInkBorderColor()
            val hardShadow = neoHardShadowColor()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.5.dp, inkBorder, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FindReplace,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thay thế nhanh cá nhân",
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Lưu cặp từ lỗi hay gặp (${rules.size} cặp)",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Ios3DCircleIconButton(
                    onClick = onDismiss,
                    size = 30.dp,
                    modifier = Modifier.testTag("custom_replace_close")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add new pair input row: [Từ lỗi] -> [Từ đúng] + [+]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DialogCompactInput(
                    value = findInput,
                    onValueChange = { findInput = it },
                    placeholder = "Từ lỗi (vd: Iàm)",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_replace_find_input")
                )

                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )

                DialogCompactInput(
                    value = replaceInput,
                    onValueChange = { replaceInput = it },
                    placeholder = "Từ đúng (vd: làm)",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_replace_target_input")
                )

                val canAdd = findInput.isNotEmpty()
                NeoBrutalistPressBox(
                    onClick = {
                        onAddRule(findInput, replaceInput)
                        findInput = ""
                        replaceInput = ""
                    },
                    enabled = canAdd,
                    isCircle = true,
                    shadowOffset = 3.dp,
                    pressDepth = 2.dp,
                    containerColor = if (canAdd) MaterialTheme.colorScheme.primary else neoSecondaryPaperColor(),
                    borderColor = inkBorder,
                    shadowColor = hardShadow,
                    modifier = Modifier
                        .size(39.dp)
                        .testTag("custom_replace_add_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Thêm cặp thay thế",
                        tint = if (canAdd) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.7.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(8.dp))

            // Saved rules list
            if (rules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Chưa có cặp từ nào. Thêm cặp lỗi OCR (như rn → m, Iàm → làm) hoặc tên nhân vật để sửa nhanh.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .testTag("custom_replace_list"),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = rules,
                        key = { it.id }
                    ) { rule ->
                        val ruleShape = RoundedCornerShape(10.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 3.dp, bottom = 3.dp)
                                .neoHardOffsetShadow(cornerRadius = 10.dp, offsetX = 3.dp, offsetY = 3.dp, shadowColor = hardShadow)
                                .clip(ruleShape)
                                .background(neoSecondaryPaperColor())
                                .border(1.5.dp, inkBorder, ruleShape)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = rule.findText,
                                    fontSize = 12.sp,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp)
                                        .size(13.dp)
                                )
                                Text(
                                    text = rule.replaceText.ifEmpty { "(xóa)" },
                                    fontSize = 12.sp,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { onDeleteRule(rule.id) },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag("custom_replace_delete_${rule.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Xóa cặp từ",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Optional 1-tap "Áp dụng toàn bộ sách" CTA when inside ManualEditorScreen
            if (canApplyToBook && onApplyAllToBook != null) {
                IosPrimaryButton(
                    text = "Áp dụng toàn bộ sách (${rules.size} cặp)",
                    enabled = rules.isNotEmpty(),
                    height = 44.dp,
                    onClick = {
                        onApplyAllToBook()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("custom_replace_apply_book_button"),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                )
            } else {
                IosPrimaryButton(
                    text = "Xong",
                    height = 42.dp,
                    onClick = onDismiss,
                    modifier = Modifier.testTag("custom_replace_done_button")
                )
            }
        }
    }
}

@Composable
private fun DialogCompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(neoSecondaryPaperColor())
            .border(1.5.dp, neoInkBorderColor(), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
