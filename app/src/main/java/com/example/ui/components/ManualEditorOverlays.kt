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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pipeline.EditableTocItem

// Warm Vintage Old-Book Sepia Palette
internal val SepiaBg = Color(0xFFF4ECD8)
internal val SepiaChrome = Color(0xFFEFE5CE)
internal val SepiaCard = Color(0xFFFAF4E6)
internal val SepiaBorder = Color(0xFFD8CAAE)
internal val SepiaInk = Color(0xFF3B2F2F)
internal val SepiaInkMuted = Color(0xFF75624E)
internal val SepiaAccent = Color(0xFFB35A28)
internal val SepiaAccentSoft = Color(0x22B35A28)
internal val SepiaActiveParagraphBg = Color(0xFFE8D8B5)
internal val SepiaSearchHighlight = Color(0xFFF3CA6E)
internal val SepiaSearchActive = Color(0xFFE59736)

/**
 * Compact Sepia Find & Replace panel shown directly below the Top Bar when Search is active.
 */
@Composable
fun SepiaFindReplaceBar(
    searchQuery: String,
    replaceQuery: String,
    matchCount: Int,
    activeMatchIndex: Int,
    customReplaceCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit,
    onOpenCustomReplace: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SepiaChrome)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: Find field + match count + prev/next + close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SepiaCompactInput(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = "Tìm kiếm trong toàn sách…",
                modifier = Modifier
                    .weight(1f)
                    .testTag("manual_search_input")
            )

            Text(
                text = if (searchQuery.isBlank() || matchCount == 0) "0/0"
                else "${activeMatchIndex + 1}/$matchCount",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = SepiaInkMuted,
                maxLines = 1
            )

            IconButton(
                onClick = onPrevMatch,
                enabled = matchCount > 0,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("manual_search_prev")
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Kết quả trước",
                    tint = if (matchCount > 0) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onNextMatch,
                enabled = matchCount > 0,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("manual_search_next")
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Kết quả tiếp",
                    tint = if (matchCount > 0) SepiaInk else SepiaInkMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("manual_search_close")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Đóng tìm kiếm",
                    tint = SepiaInkMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        // Row 2: Replace field + Thay + Tất cả + Thay nhanh (Upgrade 1.2)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SepiaCompactInput(
                value = replaceQuery,
                onValueChange = onReplaceQueryChange,
                placeholder = "Thay thế bằng…",
                modifier = Modifier
                    .weight(1f)
                    .testTag("manual_replace_input")
            )

            SepiaSmallActionChip(
                label = "Thay",
                enabled = matchCount > 0 && searchQuery.isNotBlank(),
                onClick = onReplaceOne,
                modifier = Modifier.testTag("manual_replace_one")
            )

            SepiaSmallActionChip(
                label = "Tất cả",
                enabled = matchCount > 0 && searchQuery.isNotBlank(),
                emphasized = true,
                onClick = onReplaceAll,
                modifier = Modifier.testTag("manual_replace_all")
            )

            SepiaSmallActionChip(
                label = if (customReplaceCount > 0) "Nhanh ($customReplaceCount)" else "Nhanh",
                enabled = true,
                onClick = onOpenCustomReplace,
                modifier = Modifier.testTag("manual_custom_replace_open")
            )
        }
    }
    HorizontalDivider(thickness = 0.8.dp, color = SepiaBorder)
}

@Composable
private fun SepiaCompactInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SepiaCard)
            .border(0.8.dp, SepiaBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 12.sp,
                color = SepiaInkMuted.copy(alpha = 0.7f),
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
                color = SepiaInk
            ),
            cursorBrush = SolidColor(SepiaAccent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SepiaSmallActionChip(
    label: String,
    enabled: Boolean,
    emphasized: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            !enabled -> SepiaBorder.copy(alpha = 0.45f)
            emphasized -> SepiaAccent
            else -> SepiaCard
        },
        border = if (!emphasized && enabled) BorderStroke(0.8.dp, SepiaBorder) else null,
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    !enabled -> SepiaInkMuted.copy(alpha = 0.55f)
                    emphasized -> Color.White
                    else -> SepiaInk
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Floating Sepia Table of Contents Dialog with dedicated "Sửa" Edit Mode:
 * - Normal mode: click any TOC item to jump directly to chapter/paragraph.
 * - TOC Edit mode (top-right pill "Sửa" lit): each row displays Pencil (Rename) & Trash (Delete) icons.
 */
@Composable
fun ManualTocOverlayDialog(
    visible: Boolean,
    tocItems: List<EditableTocItem>,
    currentChapterIndex: Int,
    totalChapters: Int,
    onSelectTarget: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onAddNewChapter: (title: String) -> Unit,
    onRenameItem: (entryId: String, newTitle: String) -> Unit,
    onDeleteItem: (entryId: String) -> Unit,
    onMergeChapterWithNext: (chapterIndex: Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var isTocEditMode by remember { mutableStateOf(false) }
    var renamingItem by remember { mutableStateOf<EditableTocItem?>(null) }
    var showAddChapterDialog by remember { mutableStateOf(false) }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaBg)
                .padding(16.dp)
        ) {
            // Header: Title | "Sửa" pill | Close X
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = SepiaAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Mục lục (${tocItems.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SepiaInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Dedicated "Sửa" toggle button inside TOC header
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isTocEditMode) SepiaAccent else SepiaCard,
                    border = if (isTocEditMode) null else BorderStroke(0.9.dp, SepiaBorder),
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { isTocEditMode = !isTocEditMode }
                        .testTag("toc_edit_mode_button")
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Sửa",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isTocEditMode) Color.White else SepiaInk,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(SepiaChrome)
                        .clickable(onClick = onDismiss)
                        .testTag("manual_toc_close"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng mục lục",
                        tint = SepiaInkMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (isTocEditMode) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Bấm gộp để nối chương vào chương dưới, bút chì để đổi tên hoặc thùng rác để xóa.",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = SepiaAccent
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Upgrade 1.5: Compact "+ Thêm chương" pill button at the top of the TOC panel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SepiaAccentSoft,
                    border = BorderStroke(0.9.dp, SepiaAccent.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { showAddChapterDialog = true }
                        .testTag("toc_add_new_chapter_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "+ Thêm chương",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SepiaAccent,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.8.dp, color = SepiaBorder)
            Spacer(modifier = Modifier.height(8.dp))

            if (tocItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Danh sách mục lục đang trống.",
                        fontSize = 12.sp,
                        color = SepiaInkMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(
                        items = tocItems,
                        key = { _, item -> item.id }
                    ) { idx, item ->
                        val isCurrent = !item.isManualBookmark && item.chapterIndex == currentChapterIndex
                        val startPadding = if (item.isManualBookmark) 18.dp else 0.dp

                        Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = when {
                                isCurrent -> SepiaAccentSoft
                                item.isManualBookmark -> SepiaCard
                                else -> Color.Transparent
                            },
                            border = if (item.isManualBookmark) BorderStroke(0.6.dp, SepiaBorder) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = startPadding)
                                .clip(RoundedCornerShape(11.dp))
                                .clickable(enabled = !isTocEditMode) {
                                    onSelectTarget(item.chapterIndex, item.paragraphIndex)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 10.dp,
                                        end = if (isTocEditMode) 4.dp else 10.dp,
                                        top = 7.dp,
                                        bottom = 7.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.isManualBookmark) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bookmark,
                                        contentDescription = null,
                                        tint = SepiaAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                } else {
                                    Text(
                                        text = "${idx + 1}.",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isCurrent) SepiaAccent else SepiaInkMuted,
                                        modifier = Modifier.width(26.dp)
                                    )
                                }

                                Text(
                                    text = item.title,
                                    fontSize = if (item.isManualBookmark) 12.sp else 13.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) SepiaAccent else SepiaInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isTocEditMode) {
                                    if (!item.isManualBookmark && item.chapterIndex in 0 until (totalChapters - 1)) {
                                        IconButton(
                                            onClick = { onMergeChapterWithNext(item.chapterIndex) },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("toc_merge_${item.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CallMerge,
                                                contentDescription = "Gộp vào chương dưới",
                                                tint = SepiaInk,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { renamingItem = item },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("toc_rename_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = "Sửa tên mục lục",
                                            tint = SepiaAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteItem(item.id) },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("toc_delete_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Xóa mục lục",
                                            tint = Color(0xFFB3261E),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Small inline rename dialog for the chosen TOC item
    val activeRename = renamingItem
    if (activeRename != null) {
        RenameTocItemDialog(
            initialTitle = activeRename.title,
            onConfirm = { newTitle ->
                onRenameItem(activeRename.id, newTitle)
                renamingItem = null
            },
            onDismiss = { renamingItem = null }
        )
    }

    // Upgrade 1.5: Compact Add New Chapter dialog triggered from "+ Thêm chương" pill
    if (showAddChapterDialog) {
        AddNewChapterDialog(
            suggestedTitle = "Chương ${totalChapters + 1}",
            onConfirm = { newTitle ->
                showAddChapterDialog = false
                onAddNewChapter(newTitle)
                onDismiss()
            },
            onDismiss = { showAddChapterDialog = false }
        )
    }
}

@Composable
private fun AddNewChapterDialog(
    suggestedTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleText by remember(suggestedTitle) { mutableStateOf(suggestedTitle) }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaBg)
                .padding(18.dp)
        ) {
            Text(
                text = "Thêm chương mới",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SepiaInk,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Nhập tên để tạo và chèn thêm một chương .xhtml mới vào sách.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = SepiaInkMuted
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SepiaCard)
                    .border(1.dp, SepiaBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (titleText.isEmpty()) {
                    Text(
                        text = "Ví dụ: Chương ngoại truyện…",
                        fontSize = 13.sp,
                        color = SepiaInkMuted.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SepiaInk
                    ),
                    cursorBrush = SolidColor(SepiaAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toc_add_chapter_input")
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Hủy",
                        fontSize = 13.sp,
                        color = SepiaInkMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (titleText.isNotBlank()) SepiaAccent else SepiaBorder,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = titleText.isNotBlank()) {
                            onConfirm(titleText.trim())
                        }
                        .testTag("toc_add_chapter_confirm")
                ) {
                    Text(
                        text = "Thêm chương",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameTocItemDialog(
    initialTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var titleText by remember(initialTitle) { mutableStateOf(initialTitle) }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaBg)
                .padding(18.dp)
        ) {
            Text(
                text = "Đổi tên mục lục",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SepiaInk,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SepiaCard)
                    .border(1.dp, SepiaBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BasicTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SepiaInk
                    ),
                    cursorBrush = SolidColor(SepiaAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toc_rename_input")
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Hủy",
                        fontSize = 13.sp,
                        color = SepiaInkMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (titleText.isNotBlank()) SepiaAccent else SepiaBorder,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = titleText.isNotBlank()) {
                            onConfirm(titleText.trim())
                        }
                        .testTag("toc_rename_confirm")
                ) {
                    Text(
                        text = "OK",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Floating iOS-style Undo Confirmation Dialog with persisted "Không hỏi lại" Checkbox.
 */
@Composable
fun UndoConfirmDialog(
    visible: Boolean,
    onConfirmUndo: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var dontAskAgain by remember { mutableStateOf(false) }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaBg)
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Undo,
                    contentDescription = null,
                    tint = SepiaAccent,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Hoàn tác",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = SepiaInk,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Bạn có chắc muốn hoàn tác thay đổi gần nhất?",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = SepiaInk
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { dontAskAgain = !dontAskAgain }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = dontAskAgain,
                    onCheckedChange = { dontAskAgain = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SepiaAccent,
                        uncheckedColor = SepiaInkMuted,
                        checkmarkColor = Color.White
                    ),
                    modifier = Modifier.testTag("undo_skip_confirm_checkbox")
                )
                Text(
                    text = "Không hỏi lại",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = SepiaInk
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("undo_cancel_button")
                ) {
                    Text(
                        text = "Hủy",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SepiaInkMuted
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SepiaAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onConfirmUndo(dontAskAgain) }
                        .testTag("undo_confirm_button")
                ) {
                    Text(
                        text = "Hoàn tác",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Floating Sepia Dialog to add a manual TOC bookmark at the currently visible paragraph.
 */
@Composable
fun AddManualTocDialog(
    visible: Boolean,
    chapterTitle: String,
    paragraphIndex: Int,
    suggestedTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var titleText by remember(suggestedTitle, paragraphIndex) {
        mutableStateOf(suggestedTitle.take(80))
    }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaBg)
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.FindReplace,
                    contentDescription = null,
                    tint = SepiaAccent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Thêm vào Mục lục",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = SepiaInk,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "$chapterTitle · Đoạn #${paragraphIndex + 1}",
                fontSize = 11.sp,
                color = SepiaInkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SepiaCard)
                    .border(1.dp, SepiaBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (titleText.isEmpty()) {
                    Text(
                        text = "Nhập tên mục lục (ví dụ: Chương 3 - Bí mật)…",
                        fontSize = 13.sp,
                        color = SepiaInkMuted.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = SepiaInk
                    ),
                    cursorBrush = SolidColor(SepiaAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_add_toc_input")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Hủy",
                        fontSize = 13.sp,
                        color = SepiaInkMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (titleText.isNotBlank()) SepiaAccent else SepiaBorder,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = titleText.isNotBlank()) {
                            onConfirm(titleText.trim())
                        }
                        .testTag("manual_add_toc_confirm")
                ) {
                    Text(
                        text = "Thêm mục",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
