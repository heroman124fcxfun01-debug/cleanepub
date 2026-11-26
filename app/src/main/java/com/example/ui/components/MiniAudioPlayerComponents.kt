package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.BookAudioPlaybackState
import com.example.ui.theme.IosBlue

/**
 * Animated 3-bar Apple-style live equalizer icon shown right next to the Headphones icon
 * whenever audiobook narration is actively running in the background.
 */
@Composable
fun AnimatedAudioPlayingBars(
    color: Color = IosBlue,
    maxHeight: Dp = 13.dp,
    barWidth: Dp = 2.4.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "audio_playing_bars")
    val bar1 by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        modifier = modifier.height(maxHeight),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(bar1, bar2, bar3).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(maxHeight * fraction)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

/**
 * Compact Apple-style Now Playing mini pill displayed on MainScreen / BookLibraryScreen
 * when the user leaves BookReaderScreen while audio keeps playing in the background.
 */
@Composable
fun FloatingBackgroundAudioBanner(
    playbackState: BookAudioPlaybackState,
    onOpenBookClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = pillShape,
                ambientColor = Color.Black.copy(alpha = 0.20f),
                spotColor = Color.Black.copy(alpha = 0.30f)
            )
            .clip(pillShape)
            .background(Color(0xF21C1C1E))
            .border(0.6.dp, Color.White.copy(alpha = 0.16f), pillShape)
            .clickable(onClick = onOpenBookClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("background_audio_mini_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Headphones + Live animated running bars side-by-side
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Headphones,
                contentDescription = "Đang nghe sách",
                tint = IosBlue,
                modifier = Modifier.size(16.dp)
            )
            if (playbackState.isPlaying) {
                AnimatedAudioPlayingBars(color = IosBlue, maxHeight = 12.dp, barWidth = 2.2.dp)
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = playbackState.bookTitle.ifBlank { "Đang nghe sách" },
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(playbackState.chapterTitle.ifBlank { "Chương ${playbackState.chapterIndex + 1}" })
                    if (!playbackState.activeSpeakerName.isNullOrBlank()) {
                        append(" • ")
                        append(playbackState.activeSpeakerName)
                    } else if (!playbackState.isPlaying) {
                        append(" • Đã tạm dừng")
                    }
                },
                fontSize = 10.sp,
                lineHeight = 12.sp,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(IosBlue)
                .clickable(onClick = onTogglePlayPause)
                .testTag("background_audio_toggle_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playbackState.isPlaying) "Tạm dừng" else "Phát tiếp",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onStopClick)
                .testTag("background_audio_stop_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Tắt nghe sách",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
