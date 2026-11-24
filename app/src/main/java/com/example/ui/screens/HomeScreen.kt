package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ProcessedBookEntity
import com.example.ui.components.IosCard
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.IosSectionHeader
import com.example.ui.theme.IosBackground
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosBlueSoft
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosGreenSoft
import com.example.ui.theme.IosLabelPrimary
import com.example.ui.theme.IosLabelSecondary
import com.example.ui.theme.IosLabelTertiary
import com.example.ui.theme.IosOrange
import com.example.ui.theme.IosOrangeSoft
import com.example.ui.theme.IosRed
import com.example.ui.theme.IosRedSoft
import com.example.ui.theme.JetBrainsMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    recentBooks: List<ProcessedBookEntity>,
    hasAllFilesAccess: Boolean,
    errorBanner: String?,
    onSelectFileClick: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenRecentBook: (ProcessedBookEntity) -> Unit,
    onDeleteRecentBook: (Long) -> Unit,
    onDismissError: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IosBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Minimalist iOS Large Navigation Header
            item(key = "header") {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.displaySmall,
                            color = IosLabelPrimary
                        )
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (hasAllFilesAccess) IosGreenSoft else IosOrangeSoft
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable(enabled = !hasAllFilesAccess, onClick = onRequestStoragePermission)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (hasAllFilesAccess) Icons.Rounded.CheckCircle else Icons.Rounded.Security,
                                    contentDescription = null,
                                    tint = if (hasAllFilesAccess) IosGreen else IosOrange,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (hasAllFilesAccess) "Toàn quyền bộ nhớ" else "Cần quyền bộ nhớ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hasAllFilesAccess) Color(0xFF1A7F37) else Color(0xFFB25E00),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = IosLabelSecondary
                    )
                }
            }

            // 2. Storage Access Notice (only if MANAGE_EXTERNAL_STORAGE is not yet enabled)
            if (!hasAllFilesAccess) {
                item(key = "storage_banner") {
                    IosCard(contentPadding = PaddingValues(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(IosOrangeSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    tint = IosOrange,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.storage_permission_banner_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = IosLabelPrimary
                                )
                                Text(
                                    text = stringResource(R.string.storage_permission_banner_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosLabelSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = onRequestStoragePermission,
                                modifier = Modifier.testTag("grant_storage_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.storage_permission_action),
                                    color = IosBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Error notice if user picked an unsupported file
            if (!errorBanner.isNullOrBlank()) {
                item(key = "error_banner") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = IosRedSoft,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = errorBanner,
                                style = MaterialTheme.typography.bodySmall,
                                color = IosRed,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissError) {
                                Text("Đóng", color = IosRed)
                            }
                        }
                    }
                }
            }

            // 3. Central Hero Action Card (+ Chọn file EPUB / PDF)
            item(key = "hero_action") {
                IosCard(contentPadding = PaddingValues(20.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(IosBlueSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoFixHigh,
                                contentDescription = null,
                                tint = IosBlue,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Chuẩn hóa sách điện tử 5 bước",
                            style = MaterialTheme.typography.titleMedium,
                            color = IosLabelPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_supported_formats),
                            style = MaterialTheme.typography.bodySmall,
                            color = IosLabelSecondary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        IosPrimaryButton(
                            text = stringResource(R.string.btn_select_file),
                            onClick = onSelectFileClick,
                            modifier = Modifier.testTag("select_file_button"),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FeatureMicroPill("Dọn link rác")
                            FeatureMicroPill("Ép 1 Font chuẩn")
                            FeatureMicroPill("Hiệu đính AI")
                        }
                    }
                }
            }

            // 4. Recent Processed Files Section
            item(key = "recent_header") {
                IosSectionHeader(
                    title = stringResource(R.string.home_section_recent),
                    trailing = if (recentBooks.isNotEmpty()) {
                        {
                            Text(
                                text = "${recentBooks.size} tệp",
                                style = MaterialTheme.typography.labelSmall,
                                color = IosLabelTertiary
                            )
                        }
                    } else null
                )
            }

            if (recentBooks.isEmpty()) {
                item(key = "empty_recent") {
                    IosCard(contentPadding = PaddingValues(20.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_empty_library),
                                contentDescription = stringResource(R.string.cd_empty_illustration),
                                modifier = Modifier
                                    .fillMaxWidth(0.68f)
                                    .height(132.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.home_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = IosLabelPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.home_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = IosLabelSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                item(key = "recent_list_card") {
                    IosCard(contentPadding = PaddingValues(0.dp)) {
                        recentBooks.forEachIndexed { index, book ->
                            RecentBookRow(
                                book = book,
                                onClick = { onOpenRecentBook(book) },
                                onDelete = { onDeleteRecentBook(book.id) }
                            )
                            if (index < recentBooks.lastIndex) {
                                IosHairlineDivider(startIndent = true)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FeatureMicroPill(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = IosBackground
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = IosLabelSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun RecentBookRow(
    book: ProcessedBookEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val isPdf = book.sourceFileType.equals("PDF", ignoreCase = true)
    val dateLabel = remember(book.processedAtMillis) {
        SimpleDateFormat("dd/MM/yyyy · HH:mm", Locale.getDefault())
            .format(Date(book.processedAtMillis))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isPdf) IosRedSoft else IosBlueSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPdf) Icons.Rounded.PictureAsPdf else Icons.Rounded.MenuBook,
                contentDescription = stringResource(R.string.cd_open_file),
                tint = if (isPdf) IosRed else IosBlue,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.outputFileName,
                style = MaterialTheme.typography.titleSmall,
                color = IosLabelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$dateLabel · ${book.detectedGenre}",
                style = MaterialTheme.typography.bodySmall,
                color = IosLabelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFixTag("Link ${book.junkLinksFixed}", IosOrange)
                CompactFixTag("Trắng ${book.whitespaceFixed}", IosBlue)
                CompactFixTag("AI ${book.spellingFixed}", IosGreen)
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.cd_delete_history),
                tint = IosLabelTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CompactFixTag(text: String, accent: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = JetBrainsMonoFamily
        ),
        color = accent,
        fontWeight = FontWeight.Medium
    )
}
