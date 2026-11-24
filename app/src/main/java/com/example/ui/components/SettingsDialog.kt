package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.CustomReplaceManager
import com.example.data.CustomReplaceRule
import com.example.ui.theme.IosPillBorderBottomLight
import com.example.ui.theme.IosPillBottomDark
import com.example.ui.theme.IosPillBottomLight
import com.example.ui.theme.IosPillTopDark
import com.example.ui.theme.IosPillTopLight
import com.example.ui.theme.IosScrim
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalIsDarkTheme

/**
 * Ultra-Compact 50%-Scaled Settings Dialog (100% Offline):
 * - Locks `LocalDensity(fontScale = 0.85f)` directly inside `Dialog { ... }` so system font size
 *   cannot inflate dialog typography.
 * - Zero outer `verticalScroll` to avoid nested height/scroll conflicts; embeds the entire
 *   "Thư viện thay thế" table inside a fixed `175.dp` rounded-square box with smooth internal `LazyColumn`.
 * - Isolated `28.dp` circular Search icon touch target with auto-focused `FocusRequester` search bar
 *   and real-time case-insensitive filtering across both "Từ lỗi" and "Từ đúng".
 */
@Composable
fun SettingsDialog(
    visible: Boolean,
    isDarkMode: Boolean,
    autoSaveDraft: Boolean,
    customReplaceRules: List<CustomReplaceRule>,
    errorMemoryAutoApply: Boolean,
    errorMemoryRuleCount: Int,
    onToggleDarkMode: (Boolean) -> Unit,
    onToggleAutoSaveDraft: (Boolean) -> Unit,
    onAddCustomReplaceRule: (findText: String, replaceText: String) -> Unit,
    onDeleteCustomReplaceRule: (id: String) -> Unit,
    onToggleErrorMemoryAutoApply: (Boolean) -> Unit,
    onExportErrorMemoryClick: () -> Unit,
    onImportErrorMemoryClick: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val isDark = LocalIsDarkTheme.current
    val dialogShape = RoundedCornerShape(18.dp)

    var findInput by remember { mutableStateOf("") }
    var replaceInput by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    val filteredRules = remember(customReplaceRules, searchQuery) {
        CustomReplaceManager.filterRules(customReplaceRules, searchQuery)
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Lock fontScale inside the Dialog window itself (Dialog ignores parent LocalDensity)
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = LocalDensity.current.density,
                fontScale = 0.85f
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(IosScrim)
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 390.dp)
                        .fillMaxWidth()
                        .shadow(
                            elevation = 14.dp,
                            shape = dialogShape,
                            ambientColor = Color.Black.copy(alpha = 0.22f),
                            spotColor = Color.Black.copy(alpha = 0.34f)
                        )
                        .clip(dialogShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 0.9.dp,
                            brush = Brush.verticalGradient(
                                colors = if (isDark) {
                                    listOf(
                                        Color.White.copy(alpha = 0.22f),
                                        Color.White.copy(alpha = 0.05f)
                                    )
                                } else {
                                    listOf(
                                        Color.White.copy(alpha = 0.92f),
                                        Color.White.copy(alpha = 0.35f)
                                    )
                                }
                            ),
                            shape = dialogShape
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    // NO outer verticalScroll — every item is ultra-compact & table scrolls via LazyColumn
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Header: "Cài đặt" (13.sp Bold) + circular X button (24.dp, icon 14.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Cài đặt",
                                fontSize = 13.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
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
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        // 1. Giao diện Sáng / Tối
                        CompactSwitchSectionCard(
                            icon = if (isDarkMode) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                            iconTint = MaterialTheme.colorScheme.primary,
                            iconContainer = MaterialTheme.colorScheme.primaryContainer,
                            title = "Giao diện Sáng/Tối",
                            subtitle = if (isDarkMode) "Đang bật Chế độ Tối" else "Đang bật Chế độ Sáng",
                            checked = isDarkMode,
                            switchTag = "settings_dark_mode_switch",
                            onCheckedChange = onToggleDarkMode
                        )

                        // 2. Tự động lưu nháp
                        CompactSwitchSectionCard(
                            icon = Icons.Rounded.SaveAs,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                            title = "Tự động lưu nháp",
                            subtitle = if (autoSaveDraft) {
                                "Lưu ngầm mỗi 2 phút & chuyển chương"
                            } else {
                                "Đang tắt (chỉ lưu khi bấm Lưu nháp)"
                            },
                            checked = autoSaveDraft,
                            switchTag = "settings_auto_save_draft_switch",
                            onCheckedChange = onToggleAutoSaveDraft
                        )

                        // 3. Thư viện ghi nhớ lỗi (Compact Switch + Xuất / Nhập pills)
                        CompactErrorMemoryCard(
                            ruleCount = errorMemoryRuleCount,
                            autoApplyEnabled = errorMemoryAutoApply,
                            onToggleAutoApply = onToggleErrorMemoryAutoApply,
                            onExportClick = onExportErrorMemoryClick,
                            onImportClick = onImportErrorMemoryClick
                        )

                        // 4. Thư viện thay thế (Isolated Search Button + Add Row + 175.dp Scrollable Square Table)
                        CompactCustomReplaceSectionCard(
                            totalCount = customReplaceRules.size,
                            filteredRules = filteredRules,
                            findInput = findInput,
                            replaceInput = replaceInput,
                            isSearchActive = isSearchActive,
                            searchQuery = searchQuery,
                            searchFocusRequester = searchFocusRequester,
                            onFindInputChange = { findInput = it },
                            onReplaceInputChange = { replaceInput = it },
                            onAddRule = {
                                if (findInput.isNotEmpty()) {
                                    onAddCustomReplaceRule(findInput, replaceInput)
                                    findInput = ""
                                    replaceInput = ""
                                }
                            },
                            onDeleteRule = onDeleteCustomReplaceRule,
                            onToggleSearch = {
                                isSearchActive = !isSearchActive
                                if (!isSearchActive) {
                                    searchQuery = ""
                                }
                            },
                            onSearchQueryChange = { searchQuery = it },
                            onClearOrCloseSearch = {
                                if (searchQuery.isNotEmpty()) {
                                    searchQuery = ""
                                } else {
                                    isSearchActive = false
                                }
                            }
                        )

                        // 5. Thông tin app (Compact single card)
                        CompactAppInfoCard()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSectionCardShell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.05f),
                spotColor = Color.Black.copy(alpha = 0.09f)
            )
            .clip(shape)
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
                width = 0.8.dp,
                brush = Brush.verticalGradient(
                    colors = if (isDark) {
                        listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))
                    } else {
                        listOf(Color.White.copy(alpha = 0.92f), IosPillBorderBottomLight)
                    }
                ),
                shape = shape
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        content()
    }
}

@Composable
private fun CompactCircleIcon(
    icon: ImageVector,
    tint: Color,
    containerColor: Color
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun CompactSwitchSectionCard(
    icon: ImageVector,
    iconTint: Color,
    iconContainer: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    switchTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    CompactSectionCardShell(
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactCircleIcon(
                icon = icon,
                tint = iconTint,
                containerColor = iconContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier
                    .scale(0.65f)
                    .height(22.dp)
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
private fun CompactErrorMemoryCard(
    ruleCount: Int,
    autoApplyEnabled: Boolean,
    onToggleAutoApply: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit
) {
    CompactSectionCardShell {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Top row: Icon + Title + Status + Auto-Apply Switch (no clickable on parent card)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactCircleIcon(
                    icon = Icons.Rounded.Psychology,
                    tint = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thư viện ghi nhớ lỗi",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Đang ghi nhớ: $ruleCount lỗi • ${if (autoApplyEnabled) "Tự áp dụng" else "Đang tắt"}",
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("settings_error_memory_count")
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = autoApplyEnabled,
                    onCheckedChange = onToggleAutoApply,
                    modifier = Modifier
                        .scale(0.65f)
                        .height(22.dp)
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

            // Bottom row: Compact Xuất / Nhập pill buttons (height 24.dp, 10.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onExportClick,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .testTag("settings_export_error_lib_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Xuất",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                OutlinedButton(
                    onClick = onImportClick,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .testTag("settings_import_error_lib_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Nhập",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactCustomReplaceSectionCard(
    totalCount: Int,
    filteredRules: List<CustomReplaceRule>,
    findInput: String,
    replaceInput: String,
    isSearchActive: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    onFindInputChange: (String) -> Unit,
    onReplaceInputChange: (String) -> Unit,
    onAddRule: () -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearOrCloseSearch: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val tableSurfaceColor = if (isDark) {
        MaterialTheme.colorScheme.surface
    } else {
        Color.White
    }
    val tableHeaderColor = MaterialTheme.colorScheme.surfaceVariant
    val tableBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.55f else 0.75f)
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    // NO clickable on parent card so Search icon & LazyColumn touches are never intercepted!
    CompactSectionCardShell {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header row with isolated 28.dp circular Search icon button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactCircleIcon(
                    icon = Icons.Rounded.FindReplace,
                    tint = MaterialTheme.colorScheme.tertiary,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Thư viện thay thế",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (searchQuery.isNotBlank()) {
                            "Tìm thấy ${filteredRules.size}/$totalCount cặp từ"
                        } else {
                            "Đã lưu $totalCount cặp từ sửa nhanh"
                        },
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Isolated Search icon touch target (28.dp circle, 15.dp icon)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSearchActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable(onClick = onToggleSearch)
                        .testTag("settings_replace_search_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Tìm kiếm cặp từ thay thế",
                        tint = if (isSearchActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Compact quick-add pair row: [Từ lỗi] -> [Từ đúng] + [+ Lưu] (24.dp pill)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompactInlineTextField(
                    value = findInput,
                    onValueChange = onFindInputChange,
                    placeholder = "Từ lỗi",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_replace_find_input")
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                CompactInlineTextField(
                    value = replaceInput,
                    onValueChange = onReplaceInputChange,
                    placeholder = "Từ đúng",
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_replace_target_input")
                )

                val canAdd = findInput.isNotEmpty()
                Button(
                    onClick = onAddRule,
                    enabled = canAdd,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier
                        .height(24.dp)
                        .testTag("settings_replace_save_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "Lưu",
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Active pill search bar right above the fixed square table (28.dp, 10.sp BasicTextField + FocusRequester)
            if (isSearchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(tableSurfaceColor)
                        .border(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f), RoundedCornerShape(50))
                        .padding(start = 9.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Lọc theo Từ lỗi hoặc Từ đúng…",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .testTag("settings_replace_search_input")
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onClearOrCloseSearch)
                            .testTag("settings_replace_search_clear"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Xóa từ khóa tìm kiếm",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Fixed-height 175.dp square scrollable table box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tableSurfaceColor)
                    .border(0.8.dp, tableBorderColor, RoundedCornerShape(12.dp))
                    .testTag("settings_replace_table_box")
            ) {
                // Fixed Header Row: light gray bg, height 24.dp, 3 aligned columns
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(tableHeaderColor),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Từ lỗi",
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .fillMaxHeight()
                            .background(dividerColor)
                    )
                    Text(
                        text = "Từ đúng",
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(0.5.dp)
                            .fillMaxHeight()
                            .background(dividerColor)
                    )
                    Text(
                        text = "Xóa",
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.width(36.dp)
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

                // Smooth internal vertical LazyColumn
                if (filteredRules.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "Không có cặp từ nào khớp \"$searchQuery\""
                            } else {
                                "Chưa có cặp từ nào. Nhập Từ lỗi → Từ đúng rồi bấm + Lưu."
                            },
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("settings_replace_table_list")
                    ) {
                        items(
                            items = filteredRules,
                            key = { it.id }
                        ) { rule ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = rule.findText,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .fillMaxHeight()
                                            .background(dividerColor)
                                    )
                                    Text(
                                        text = rule.replaceText.ifEmpty { "(xóa)" },
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(0.5.dp)
                                            .fillMaxHeight()
                                            .background(dividerColor)
                                    )
                                    Box(
                                        modifier = Modifier.width(36.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .clickable { onDeleteRule(rule.id) }
                                                .testTag("settings_replace_delete_${rule.id}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Xóa cặp từ ${rule.findText}",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactInlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 0.7.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 10.sp,
                lineHeight = 12.sp,
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
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun CompactAppInfoCard() {
    CompactSectionCardShell {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactCircleIcon(
                icon = Icons.Rounded.Info,
                tint = MaterialTheme.colorScheme.secondary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Thông tin app",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "EpubCleanAI v1.3 • Từ điển Việt-Anh 100% Offline",
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
