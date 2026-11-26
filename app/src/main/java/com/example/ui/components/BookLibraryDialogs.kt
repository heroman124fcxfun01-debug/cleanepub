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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BookCollection
import com.example.data.LibraryBook
import com.example.ui.LibraryImportFlowState
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosDarkBlue
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosHairline
import com.example.ui.theme.IosRed
import com.example.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reusable minimalist Book Cover thumbnail with asynchronous off-UI-thread decoding
 * and clean gradient typography fallback when a book has no embedded cover artwork.
 */
@Composable
fun LibraryBookCoverThumbnail(
    title: String,
    coverPath: String?,
    width: Dp = 50.dp,
    height: Dp = 72.dp,
    cornerRadius: Dp = 8.dp
) {
    var bitmap by remember(coverPath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(coverPath) {
        bitmap = if (!coverPath.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(coverPath)
                    if (file.exists()) {
                        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    } else {
                        null
                    }
                }.getOrNull()
            }
        } else {
            null
        }
    }

    val coverShape = RoundedCornerShape(cornerRadius)
    val initial = remember(title) {
        title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "S"
    }
    val gradientColors = remember(title) {
        val palettes = listOf(
            listOf(Color(0xFF4F7DF3), Color(0xFF2B56C5)),
            listOf(Color(0xFFE07A5F), Color(0xFFB8543B)),
            listOf(Color(0xFF3E9C7B), Color(0xFF257157)),
            listOf(Color(0xFF8368CC), Color(0xFF5C43A4)),
            listOf(Color(0xFF5E6E82), Color(0xFF3C4959))
        )
        palettes[(title.hashCode().and(0x7fffffff)) % palettes.size]
    }

    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()
    Box(
        modifier = Modifier
            .padding(end = 2.dp, bottom = 2.dp)
            .size(width = width, height = height)
            .neoHardOffsetShadow(
                cornerRadius = cornerRadius,
                offsetX = 2.dp,
                offsetY = 2.dp,
                shadowColor = hardShadow
            )
            .clip(coverShape)
            .background(Brush.verticalGradient(gradientColors))
            .border(1.5.dp, inkBorder, coverShape),
        contentAlignment = Alignment.Center
    ) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = "Bìa sách $title",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initial,
                fontSize = if (width >= 40.dp) 18.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

@Composable
fun LibraryInfoStatsDialog(
    books: List<LibraryBook>,
    collectionsCount: Int,
    onOpenReaderSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val accentBlue = if (isDark) IosDarkBlue else IosBlue
    val epubCount = remember(books) { books.count { it.sourceType.equals("EPUB", ignoreCase = true) } }
    val pdfCount = remember(books) { books.count { it.sourceType.equals("PDF", ignoreCase = true) } }
    val totalChapters = remember(books) { books.sumOf { it.chapterCount } }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accentBlue.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = accentBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "Thông tin Thư viện sách",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Lưu trữ 100% ngoại tuyến trên thiết bị",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val statsShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 3.dp, bottom = 3.dp)
                    .neoHardOffsetShadow(cornerRadius = 14.dp, offsetX = 3.dp, offsetY = 3.dp)
                    .clip(statsShape)
                    .background(neoSecondaryPaperColor())
                    .border(1.5.dp, neoInkBorderColor(), statsShape)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LibraryStatRow("Tổng số sách", "${books.size} cuốn")
                    LibraryStatRow("Định dạng EPUB / PDF", "$epubCount EPUB · $pdfCount PDF")
                    LibraryStatRow("Tổng số chương đã tách", "$totalChapters chương")
                    LibraryStatRow("Bộ sưu tập", "$collectionsCount bộ")
                }
            }

            Ios3DSurfacePillButton(
                text = "Cài đặt chung trình đọc & nghe",
                height = 42.dp,
                onClick = {
                    onDismiss()
                    onOpenReaderSettings()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = accentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )

            IosPrimaryButton(
                text = "Đóng",
                height = 40.dp,
                onClick = onDismiss
            )
        }
    }
}

@Composable
private fun LibraryStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun CreateCollectionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val isDark = LocalIsDarkTheme.current

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tạo bộ sưu tập mới",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(neoSecondaryPaperColor())
                    .border(
                        width = 1.5.dp,
                        color = neoInkBorderColor(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = "Ví dụ: Tiểu thuyết yêu thích, Kinh tế…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("collection_name_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Hủy", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.width(104.dp)) {
                    IosPrimaryButton(
                        text = "Tạo",
                        height = 36.dp,
                        enabled = name.trim().isNotEmpty(),
                        onClick = { onConfirm(name.trim()) },
                        modifier = Modifier.testTag("confirm_create_collection_button")
                    )
                }
            }
        }
    }
}

@Composable
fun AddBooksToCollectionDialog(
    collection: BookCollection,
    allBooks: List<LibraryBook>,
    onSaveSelection: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember(collection.id) { mutableStateOf(collection.bookIds.toSet()) }

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Thêm sách vào \"${collection.name}\"",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (allBooks.isEmpty()) {
                Text(
                    text = "Chưa có sách nào trong thư viện để thêm.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    itemsIndexed(allBooks, key = { _, b -> b.id }) { idx, book ->
                        val checked = book.id in selectedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable {
                                    selectedIds = if (checked) {
                                        selectedIds - book.id
                                    } else {
                                        selectedIds + book.id
                                    }
                                }
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedIds = if (isChecked) {
                                        selectedIds + book.id
                                    } else {
                                        selectedIds - book.id
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            LibraryBookCoverThumbnail(
                                title = book.title,
                                coverPath = book.coverPath,
                                width = 22.dp,
                                height = 32.dp,
                                cornerRadius = 4.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = book.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (idx < allBooks.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Đóng", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(modifier = Modifier.width(104.dp)) {
                    IosPrimaryButton(
                        text = "Xong",
                        height = 36.dp,
                        onClick = { onSaveSelection(selectedIds) }
                    )
                }
            }
        }
    }
}

@Composable
fun PickCollectionForBookDialog(
    titleLabel: String,
    existingBookIds: Set<String>,
    collections: List<BookCollection>,
    onSelectCollection: (BookCollection) -> Unit,
    onCreateNewClick: () -> Unit,
    onDismiss: () -> Unit
) {
    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Thêm vào bộ sưu tập",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = titleLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (collections.isEmpty()) {
                Text(
                    text = "Bạn chưa có bộ sưu tập nào.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                ) {
                    itemsIndexed(collections, key = { _, c -> c.id }) { idx, col ->
                        val allAlreadyIn = existingBookIds.isNotEmpty() && existingBookIds.all { it in col.bookIds }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clickable { onSelectCollection(col) }
                                .padding(horizontal = 6.dp),
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
                                text = col.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (allAlreadyIn) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Đã có",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        if (idx < collections.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCreateNewClick) {
                    Text("+ Bộ sưu tập mới", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Đóng", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * iOS Floating Dialog sequence for Book Library Import:
 * 1. AskCleanConfirmation: "Bạn có muốn dọn sách không?" (Không | Có)
 * 2. CleaningInProgress / ImportingDirect / FinalizingCleanImport: Spinning loader + live progress
 * 3. CleanCompleted: Green checkmark + "Sửa tiếp" | "OK"
 * 4. Error: Friendly error message
 */
@Composable
fun LibraryImportFlowDialogs(
    flowState: LibraryImportFlowState,
    cleaningProgressPercent: Int,
    cleaningStatusMessage: String,
    onConfirmClean: () -> Unit,
    onConfirmNoClean: () -> Unit,
    onConfirmBatchClean: () -> Unit = {},
    onConfirmBatchNoClean: () -> Unit = {},
    onCancelBatch: () -> Unit = {},
    onCancelClean: () -> Unit,
    onContinueEdit: () -> Unit,
    onFinishOk: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val accentBlue = if (isDark) IosDarkBlue else IosBlue

    when (flowState) {
        is LibraryImportFlowState.Idle -> Unit

        is LibraryImportFlowState.AskCleanConfirmation -> {
            IosFloatingDialog(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(accentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = accentBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Text(
                        text = "Bạn có muốn dọn sách không?",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = flowState.source.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Chọn \"Có\" để tự động dọn link rác, chuẩn hóa phông, khoảng trắng và chính tả trước khi lưu vào Thư viện sách.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ios3DSurfacePillButton(
                            text = "Không",
                            height = 42.dp,
                            recessed = true,
                            onClick = onConfirmNoClean,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("library_import_no_clean_button")
                        )

                        Box(modifier = Modifier.weight(1f)) {
                            IosPrimaryButton(
                                text = "Có",
                                height = 42.dp,
                                onClick = onConfirmClean,
                                modifier = Modifier.testTag("library_import_yes_clean_button")
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.AskBatchCleanConfirmation -> {
            val count = flowState.items.size
            val previewNames = remember(flowState.items) {
                val top = flowState.items.take(3).joinToString(", ") { it.second.displayName }
                if (count > 3) "$top và ${count - 3} cuốn khác" else top
            }
            IosFloatingDialog(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(accentBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            tint = accentBlue,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = "Quét & nhập $count cuốn sách?",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = previewNames,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Chọn \"Có, dọn tự động\" để làm sạch toàn bộ $count cuốn trước khi lưu vào Thư viện, hoặc \"Không\" để nhập trực tiếp.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ios3DSurfacePillButton(
                            text = "Không",
                            height = 42.dp,
                            recessed = true,
                            onClick = onConfirmBatchNoClean,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("library_batch_no_clean_button")
                        )

                        Box(modifier = Modifier.weight(1.3f)) {
                            IosPrimaryButton(
                                text = "Có, dọn tự động",
                                height = 42.dp,
                                onClick = onConfirmBatchClean,
                                modifier = Modifier.testTag("library_batch_yes_clean_button")
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.BatchProcessing -> {
            val overallProgress = ((flowState.currentIndex.toFloat() + flowState.currentBookProgress) /
                flowState.totalCount.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val overallPercent = (overallProgress * 100f).toInt().coerceIn(0, 100)

            IosFloatingDialog(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("library_batch_spinner"),
                        strokeWidth = 3.2.dp,
                        color = accentBlue
                    )

                    Text(
                        text = if (flowState.isCleaning) {
                            "Đang dọn sách ${flowState.currentIndex + 1}/${flowState.totalCount}"
                        } else {
                            "Đang nhập sách ${flowState.currentIndex + 1}/${flowState.totalCount}"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = flowState.currentBookName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = accentBlue,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = accentBlue,
                        trackColor = accentBlue.copy(alpha = 0.16f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = flowState.statusDetail,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$overallPercent%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentBlue
                        )
                    }

                    TextButton(
                        onClick = onCancelBatch,
                        modifier = Modifier.testTag("library_batch_cancel_button")
                    ) {
                        Text(
                            text = "Hủy quét hàng loạt",
                            fontSize = 12.sp,
                            color = IosRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        is LibraryImportFlowState.BatchCompleted -> {
            IosFloatingDialog(onDismissRequest = onFinishOk) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isDark) IosGreen.copy(alpha = 0.18f) else IosGreenSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = IosGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Text(
                        text = when {
                            flowState.wasCancelled -> "Đã dừng quét hàng loạt"
                            flowState.wasCleaned -> "Đã dọn & thêm sách xong!"
                            else -> "Đã nhập sách vào Thư viện!"
                        },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    val summaryText = buildString {
                        append("Thành công ${flowState.successCount}/${flowState.totalCount} cuốn sách")
                        if (flowState.wasCleaned && flowState.totalFixes > 0) {
                            append(" · Đã tự động sửa ${flowState.totalFixes} lỗi")
                        }
                        if (flowState.failedCount > 0) {
                            append(" · ${flowState.failedCount} tệp lỗi")
                        }
                    }

                    Text(
                        text = summaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    IosPrimaryButton(
                        text = "OK",
                        height = 42.dp,
                        onClick = onFinishOk,
                        modifier = Modifier.testTag("library_batch_ok_button")
                    )
                }
            }
        }

        is LibraryImportFlowState.ImportingDirect -> {
            IosFloatingDialog(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 3.dp,
                        color = accentBlue
                    )
                    Text(
                        text = "Đang thêm sách vào thư viện…",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = flowState.fileName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        is LibraryImportFlowState.CleaningInProgress,
        is LibraryImportFlowState.FinalizingCleanImport -> {
            val isFinalizing = flowState is LibraryImportFlowState.FinalizingCleanImport
            val displayPercent = if (isFinalizing) 100 else cleaningProgressPercent
            val displayTitle = if (isFinalizing) {
                "Đang lưu vào Thư viện sách…"
            } else {
                "Đang tự động dọn sách…"
            }
            val displaySubtitle = if (isFinalizing) {
                (flowState as LibraryImportFlowState.FinalizingCleanImport).bookTitle
            } else {
                cleaningStatusMessage.ifBlank { "Đang phân tích và làm sạch nội dung…" }
            }

            IosFloatingDialog(onDismissRequest = {}) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("library_auto_clean_spinner"),
                        strokeWidth = 3.2.dp,
                        color = accentBlue
                    )

                    Text(
                        text = displayTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    LinearProgressIndicator(
                        progress = { (displayPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = accentBlue,
                        trackColor = accentBlue.copy(alpha = 0.16f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displaySubtitle,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "$displayPercent%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentBlue
                        )
                    }

                    if (!isFinalizing) {
                        TextButton(
                            onClick = onCancelClean,
                            modifier = Modifier.testTag("library_cancel_auto_clean_button")
                        ) {
                            Text(
                                text = "Hủy quét",
                                fontSize = 12.sp,
                                color = IosRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.CleanCompleted -> {
            IosFloatingDialog(onDismissRequest = onFinishOk) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (isDark) IosGreen.copy(alpha = 0.18f) else IosGreenSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = IosGreen,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Text(
                        text = "Đã dọn sách xong!",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = flowState.book.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = if (flowState.totalFixes > 0) {
                            "Đã tự động sửa ${flowState.totalFixes} lỗi và thêm sẵn vào Thư viện sách."
                        } else {
                            "Nội dung đã chuẩn sạch và được thêm sẵn vào Thư viện sách."
                        },
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Ios3DSurfacePillButton(
                            text = "Sửa tiếp",
                            height = 42.dp,
                            onClick = onContinueEdit,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("library_clean_continue_edit_button")
                        )

                        Box(modifier = Modifier.weight(1f)) {
                            IosPrimaryButton(
                                text = "OK",
                                height = 42.dp,
                                onClick = onFinishOk,
                                modifier = Modifier.testTag("library_clean_ok_button")
                            )
                        }
                    }
                }
            }
        }

        is LibraryImportFlowState.Error -> {
            IosFloatingDialog(onDismissRequest = onDismiss) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Không thể xử lý tệp",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = IosRed
                    )
                    Text(
                        text = flowState.message,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    IosPrimaryButton(
                        text = "Đóng",
                        height = 40.dp,
                        onClick = onDismiss
                    )
                }
            }
        }
    }
}
