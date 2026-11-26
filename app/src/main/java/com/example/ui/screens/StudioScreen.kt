package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.NeoPaperStackContainer
import com.example.ui.components.neoHardOffsetShadow
import com.example.ui.components.neoInkBorderColor
import com.example.ui.theme.IosOrange
import com.example.ui.theme.NeoInkLight

/**
 * Tab 3: Studio (Empty State)
 * Swiss Neo-Brutalist 3D Paper & Bookbinder stacked archival sheets with crisp ink borders.
 */
@Composable
fun StudioScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("studio_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp)
        ) {
            Text(
                text = "Studio",
                fontSize = 22.sp,
                lineHeight = 26.sp,
                letterSpacing = (-0.4).sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("studio_header_title")
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "Không gian âm thanh & giọng đọc sách",
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Layered Bookbinder Paper Stack peeking bottom & right edges
            NeoPaperStackContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 8.dp),
                cornerRadius = 14.dp,
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 3.dp, bottom = 3.dp)
                            .size(66.dp)
                            .neoHardOffsetShadow(isCircle = true, offsetX = 3.dp, offsetY = 3.dp)
                            .clip(CircleShape)
                            .background(IosOrange)
                            .border(1.5.dp, neoInkBorderColor(), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Studio âm thanh",
                            tint = NeoInkLight,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Chưa có nội dung",
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("studio_empty_label")
                    )
                }
            }
        }
    }
}
