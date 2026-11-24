package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.pipeline.CleaningOptions
import com.example.ui.components.IosCard
import com.example.ui.components.IosHairlineDivider
import com.example.ui.components.IosPrimaryButton
import com.example.ui.components.IosSectionHeader
import com.example.ui.theme.IosGreen
import com.example.ui.theme.JetBrainsMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    recentBooks: List<ProcessedBookEntity>,
    hasAllFilesAccess: Boolean,
    cleaningOptions: CleaningOptions,
    isLoadingMetadata: Boolean,
    errorBanner: String?,
    onSelectFileClick: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onUpdateCleaningOptions: (CleaningOptions) -> Unit,
    onOpenRecentBook: (ProcessedBookEntity) -> Unit,
    onDeleteRecentBook: (Long) -> Unit,
    onDismissError: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. iOS Top Bar: App Title + Compact Green Checkmark (when granted) / Permission Pill + Settings Gear
            item(key = "top_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Làm sạch & hiệu đính sách 100% Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (hasAllFilesAccess) {
                            // Compact green checkmark icon ONLY (no long "Toàn quyền bộ nhớ" text)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier
                                    .size(42.dp)
                                    .testTag("storage_granted_icon")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = stringResource(R.string.storage_permission_granted),
                                        tint = IosGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        } else {
                            // Compact oval permission request pill when not yet granted
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .testTag("grant_storage_button")
                                    .clickable(onClick = onRequestStoragePermission)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.storage_permission_action),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        // Circular Settings Button
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.size(44.dp)
                        ) {
                            IconButton(
                                onClick = onOpenSettingsClick,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("open_settings_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Cài đặt",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(21.dp)
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
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = errorBanner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissError) {
                                Text("Đóng", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // 2. Large Prominent Oval Select File Button
            item(key = "select_file_cta") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IosPrimaryButton(
                        text = if (isLoadingMetadata) "Đang đọc thông tin sách…" else "Chọn file EPUB / PDF",
                        enabled = !isLoadingMetadata,
                        onClick = onSelectFileClick,
                        modifier = Modifier
                            .height(58.dp)
                            .testTag("select_file_button"),
                        leadingIcon = {
                            if (isLoadingMetadata) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    )
                }
            }

            // 3. 5 Vertical Feature Pills (each inside its own separate oval background frame)
            item(key = "features_list") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FeatureOvalRow(
                        title = "Dọn link",
                        subtitle = "Gỡ URL, quảng cáo và chữ ký web rác",
                        icon = Icons.Rounded.LinkOff,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        accentContainer = MaterialTheme.colorScheme.tertiaryContainer,
                        enabled = cleaningOptions.cleanLinks,
                        testTagName = "feature_clean_links",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(cleanLinks = !cleaningOptions.cleanLinks)
                            )
                        }
                    )
                    FeatureOvalRow(
                        title = "Sửa phông",
                        subtitle = "Gỡ CSS cũ, bỏ chữ nghiêng & ép 1 font chuẩn",
                        icon = Icons.Rounded.FormatColorText,
                        accentColor = MaterialTheme.colorScheme.primary,
                        accentContainer = MaterialTheme.colorScheme.primaryContainer,
                        enabled = cleaningOptions.fixFonts,
                        testTagName = "feature_fix_fonts",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(fixFonts = !cleaningOptions.fixFonts)
                            )
                        }
                    )
                    FeatureOvalRow(
                        title = "Sửa chính tả",
                        subtitle = "Hiệu đính dấu tiếng Việt & ghép từ tiếng Anh",
                        icon = Icons.Rounded.Spellcheck,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                        enabled = cleaningOptions.fixSpelling,
                        testTagName = "feature_fix_spelling",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(fixSpelling = !cleaningOptions.fixSpelling)
                            )
                        }
                    )
                    FeatureOvalRow(
                        title = "Sửa khoảng trắng",
                        subtitle = "Gom khoảng trắng, sửa tách chữ & trượt âm tiết",
                        icon = Icons.Rounded.SpaceBar,
                        accentColor = MaterialTheme.colorScheme.primary,
                        accentContainer = MaterialTheme.colorScheme.primaryContainer,
                        enabled = cleaningOptions.fixWhitespace,
                        testTagName = "feature_fix_whitespace",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(fixWhitespace = !cleaningOptions.fixWhitespace)
                            )
                        }
                    )
                    FeatureOvalRow(
                        title = "Tối ưu lại nội dung",
                        subtitle = "Bảo vệ tên riêng, Metadata & đóng gói EPUB chuẩn",
                        icon = Icons.Rounded.AutoFixHigh,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        accentContainer = MaterialTheme.colorScheme.secondaryContainer,
                        enabled = cleaningOptions.optimizeContent,
                        testTagName = "feature_optimize_content",
                        onToggle = {
                            onUpdateCleaningOptions(
                                cleaningOptions.copy(optimizeContent = !cleaningOptions.optimizeContent)
                            )
                        }
                    )
                }
            }

            // 4. Recent Processed Files Section
            item(key = "recent_header") {
                IosSectionHeader(
                    title = stringResource(R.string.home_section_recent),
                    trailing = if (recentBooks.isNotEmpty()) {
                        {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "${recentBooks.size} tệp",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    } else null
                )
            }

            if (recentBooks.isEmpty()) {
                item(key = "empty_recent") {
                    IosCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.img_empty_library),
                                contentDescription = stringResource(R.string.cd_empty_illustration),
                                modifier = Modifier
                                    .fillMaxWidth(0.62f)
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.home_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.home_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                item(key = "recent_list_card") {
                    IosCard(
                        shape = RoundedCornerShape(24.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
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

/**
 * Single vertical feature row inside its own dedicated oval/pill frame (`RoundedCornerShape(50)`).
 */
@Composable
private fun FeatureOvalRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    accentContainer: Color,
    enabled: Boolean,
    testTagName: String,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .testTag(testTagName)
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (enabled) 1.dp else 0.5.dp,
            color = if (enabled) accentColor.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) accentContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Compact oval active status indicator
            Surface(
                shape = CircleShape,
                color = if (enabled) IosGreen else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (enabled) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Đang bật",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
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
                .clip(CircleShape)
                .background(
                    if (isPdf) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPdf) Icons.Rounded.PictureAsPdf else Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = stringResource(R.string.cd_open_file),
                tint = if (isPdf) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.outputFileName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$dateLabel · ${book.detectedGenre}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactFixPill("Link ${book.junkLinksFixed}", MaterialTheme.colorScheme.tertiary)
                CompactFixPill("Trắng ${book.whitespaceFixed}", MaterialTheme.colorScheme.primary)
                CompactFixPill("Chính tả ${book.spellingFixed}", MaterialTheme.colorScheme.secondary)
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.cd_delete_history),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CompactFixPill(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = JetBrainsMonoFamily
            ),
            color = accent,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}
