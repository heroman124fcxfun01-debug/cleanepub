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
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import com.example.pipeline.ManualChapterContent
import com.example.pipeline.ManualTocEntry

// Warm Vintage Old-Book Sepia Palette
internal val SepiaBg = Color(0xFFF4ECD8)
internal val SepiaChrome = Color(0xFFEFE5CE)
internal val SepiaCard = Color(0xFFFAF4E6)
internal val SepiaBorder = Color(0xFFD8CAAE)
internal val SepiaInk = Color(0xFF3B2F2F)
internal val SepiaInkMuted = Color(0xFF75624E)
internal val SepiaAccent = Color(0xFFB35A28)
internal val SepiaAccentSoft = Color(0x22B35A28)
internal val SepiaSearchHighlight = Color(0xFFF3CA6E)
internal val SepiaSearchActive = Color(0xFFE59736)
internal val SepiaDiffOrigBg = Color(0x1EC0392B)
internal val SepiaDiffCurrBg = Color(0x1E2E7D32)

/**
 * Compact Sepia Find & Replace panel shown directly below the Top Bar when Search is active.
 */
@Composable
fun SepiaFindReplaceBar(
    searchQuery: String,
    replaceQuery: String,
    matchCount: Int,
    activeMatchIndex: Int,
    onSearchQueryChange: (String) -> Unit,
    onReplaceQueryChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit,
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
                text = if (searchQuery.isBlank()) "0/0"
                else if (matchCount == 0) "0/0"
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

        // Row 2: Replace field + Thay + Tất cả
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
 * Floating Sepia Table of Contents Dialog listing all chapters + user's manual TOC bookmarks.
 */
@Composable
fun ManualTocOverlayDialog(
    visible: Boolean,
    chapters: List<ManualChapterContent>,
    currentChapterIndex: Int,
    manualBookmarks: List<ManualTocEntry>,
    onSelectChapter: (chapterIndex: Int, paragraphIndex: Int) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SepiaBg)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = SepiaAccent,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Mục lục (${chapters.size + manualBookmarks.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = SepiaInk,
                        maxLines = 1
                    )
                }
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

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.8.dp, color = SepiaBorder)
            Spacer(modifier = Modifier.height(8.dp))

            val bookmarksByChapter = remember(manualBookmarks) {
                manualBookmarks.groupBy { it.chapterIndex }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
                    items = chapters,
                    key = { idx, c -> "${c.fileName}_$idx" }
                ) { idx, chapter ->
                    val isSelected = idx == currentChapterIndex
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) SepiaAccentSoft else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectChapter(idx, 0)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) SepiaAccent else SepiaInkMuted,
                                modifier = Modifier.width(26.dp)
                            )
                            Text(
                                text = chapter.title,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) SepiaAccent else SepiaInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Nested manual bookmarks belonging to this chapter
                    val chapterBms = bookmarksByChapter[idx].orEmpty()
                    for (bm in chapterBms) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, top = 2.dp, bottom = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SepiaCard)
                                .border(0.6.dp, SepiaBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelectChapter(bm.chapterIndex, bm.paragraphIndex)
                                    onDismiss()
                                }
                                .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bookmark,
                                contentDescription = null,
                                tint = SepiaAccent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = bm.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SepiaInk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onDeleteBookmark(bm.id) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Xóa mục lục thủ công",
                                    tint = SepiaInkMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
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
