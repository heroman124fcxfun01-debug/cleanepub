package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.IosOrange
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.NeoInkLight

enum class AppMainTab(val label: String, val testTagName: String) {
    CLEAN_EPUB("Cleanbook", "nav_tab_clean_epub"),
    BOOK_LIBRARY("Thư viện sách", "nav_tab_book_library"),
    STUDIO("Studio", "nav_tab_studio")
}

/**
 * Swiss Neo-Brutalist 3D Paper & Bookbinder Floating Pill Dock:
 * - Full capsule (`RoundedCornerShape(50)`) in thick archival ivory/slate paper
 * - Crisp `1.5.dp` solid ink border + hard `3.5.dp, 3.5.dp` solid offset 3D shadow block
 * - 120fps spring-gliding inked Amber bookmark tab behind the active destination
 * - Strictly single-line compact labels (`10.5.sp`) with zero wrapping
 */
@Composable
fun FloatingBottomNavBar(
    selectedTab: AppMainTab,
    onSelectTab: (AppMainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val outerShape = RoundedCornerShape(50)
    val dockFill = neoPaperSurfaceColor()
    val inkBorder = neoInkBorderColor()
    val hardShadow = neoHardShadowColor()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(end = 3.5.dp, bottom = 3.5.dp)
                .height(60.dp)
                .neoHardOffsetShadow(
                    cornerRadius = 999.dp,
                    offsetX = 3.5.dp,
                    offsetY = 3.5.dp,
                    shadowColor = hardShadow
                )
                .clip(outerShape)
                .background(dockFill)
                .border(width = 1.5.dp, color = inkBorder, shape = outerShape)
                .padding(4.dp)
                .testTag("floating_bottom_nav_bar")
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val tabCount = AppMainTab.entries.size
                val segmentWidth = maxWidth / tabCount
                val targetOffset = segmentWidth * selectedTab.ordinal

                val animatedOffset by animateDpAsState(
                    targetValue = targetOffset,
                    animationSpec = spring(
                        dampingRatio = 0.80f,
                        stiffness = 500f
                    ),
                    label = "neo_nav_pill_offset"
                )

                val indicatorShape = RoundedCornerShape(50)
                Box(
                    modifier = Modifier
                        .offset(x = animatedOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 1.dp)
                        .clip(indicatorShape)
                        .background(IosOrange)
                        .border(
                            width = 1.4.dp,
                            color = if (isDark) NeoInkLight else inkBorder,
                            shape = indicatorShape
                        )
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppMainTab.entries.forEach { tab ->
                        FloatingNavTabItem(
                            tab = tab,
                            selected = tab == selectedTab,
                            isDark = isDark,
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingNavTabItem(
    tab: AppMainTab,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // High-contrast ink on Amber bookmark pill when selected
    val activeTint = NeoInkLight
    val inactiveTint = if (isDark) Color(0xFFA1A1AA) else Color(0xFF52525B)

    val contentTint by animateColorAsState(
        targetValue = if (selected) activeTint else inactiveTint,
        animationSpec = tween(150),
        label = "nav_tab_tint_${tab.name}"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressShift by animateDpAsState(
        targetValue = if (isPressed) 1.5.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 700f),
        label = "nav_tab_press_${tab.name}"
    )
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                val px = with(density) { pressShift.toPx() }
                translationY = px
            }
            .clip(RoundedCornerShape(50))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .testTag(tab.testTagName),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (tab) {
                AppMainTab.CLEAN_EPUB -> {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = tab.label,
                        tint = contentTint,
                        modifier = Modifier.size(19.dp)
                    )
                }

                AppMainTab.BOOK_LIBRARY -> {
                    StandingThreeBooksIcon(
                        tint = contentTint,
                        filled = selected,
                        modifier = Modifier.size(19.dp)
                    )
                }

                AppMainTab.STUDIO -> {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = tab.label,
                        tint = contentTint,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = tab.label,
                fontSize = 10.5.sp,
                lineHeight = 12.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = contentTint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StandingThreeBooksIcon(
    tint: Color,
    filled: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = if (filled) w * 0.09f else w * 0.08f
        val corner = CornerRadius(w * 0.07f, w * 0.07f)

        val b1Left = w * 0.11f
        val b1Width = w * 0.21f
        val b1Top = h * 0.18f
        val b1Height = h * 0.68f
        if (filled) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(b1Left, b1Top),
                size = Size(b1Width, b1Height),
                cornerRadius = corner
            )
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(b1Left, b1Top),
                size = Size(b1Width, b1Height),
                cornerRadius = corner,
                style = Stroke(width = strokeWidth)
            )
        }

        val b2Left = w * 0.39f
        val b2Width = w * 0.21f
        val b2Top = h * 0.12f
        val b2Height = h * 0.74f
        if (filled) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(b2Left, b2Top),
                size = Size(b2Width, b2Height),
                cornerRadius = corner
            )
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(b2Left, b2Top),
                size = Size(b2Width, b2Height),
                cornerRadius = corner,
                style = Stroke(width = strokeWidth)
            )
        }

        val b3Left = w * 0.67f
        val b3Width = w * 0.22f
        val b3Top = h * 0.22f
        val b3Height = h * 0.64f
        if (filled) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(b3Left, b3Top),
                size = Size(b3Width, b3Height),
                cornerRadius = corner
            )
        } else {
            drawRoundRect(
                color = tint,
                topLeft = Offset(b3Left, b3Top),
                size = Size(b3Width, b3Height),
                cornerRadius = corner,
                style = Stroke(width = strokeWidth)
            )
        }

        drawLine(
            color = tint.copy(alpha = if (filled) 0.75f else 0.50f),
            start = Offset(w * 0.08f, h * 0.91f),
            end = Offset(w * 0.92f, h * 0.91f),
            strokeWidth = strokeWidth * 0.85f,
            cap = StrokeCap.Round
        )
    }
}
