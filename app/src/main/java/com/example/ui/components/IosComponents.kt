package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.pipeline.SaveMode
import com.example.pipeline.SaveResult
import com.example.pipeline.SourceFileInfo
import com.example.ui.theme.IosBlue
import com.example.ui.theme.IosCardSurface
import com.example.ui.theme.IosDarkCardSurface
import com.example.ui.theme.IosDarkSecondarySurface
import com.example.ui.theme.IosGreen
import com.example.ui.theme.IosScrim
import com.example.ui.theme.IosSecondarySurface
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.NeoInkDark
import com.example.ui.theme.NeoInkLight
import com.example.ui.theme.NeoPaperEdgeDark
import com.example.ui.theme.NeoPaperEdgeLight
import com.example.ui.theme.NeoShadowDark
import com.example.ui.theme.NeoShadowLight

/**
 * Crisp theme-aware Swiss Neo-Brutalist ink border & solid 3D offset shadow tokens.
 */
@Composable
fun neoInkBorderColor(): Color = if (LocalIsDarkTheme.current) NeoInkDark else NeoInkLight

@Composable
fun neoHardShadowColor(): Color = if (LocalIsDarkTheme.current) NeoShadowDark else NeoShadowLight

@Composable
fun neoPaperSurfaceColor(): Color = if (LocalIsDarkTheme.current) IosDarkCardSurface else IosCardSurface

@Composable
fun neoSecondaryPaperColor(): Color = if (LocalIsDarkTheme.current) IosDarkSecondarySurface else IosSecondarySurface

/**
 * Draws a crisp Swiss Neo-Brutalist hard-edge solid offset 3D shadow directly behind a rounded rectangle
 * without any blurry Android drop-shadow halo.
 */
fun Modifier.neoHardOffsetShadow(
    cornerRadius: Dp = 14.dp,
    offsetX: Dp = 3.dp,
    offsetY: Dp = 3.dp,
    shadowColor: Color? = null,
    isCircle: Boolean = false
): Modifier = composed {
    val resolvedShadow = shadowColor ?: neoHardShadowColor()
    this.drawBehind {
        val dx = offsetX.toPx()
        val dy = offsetY.toPx()
        if (isCircle) {
            val radius = size.minDimension / 2f
            drawCircle(
                color = resolvedShadow,
                radius = radius,
                center = Offset(size.width / 2f + dx, size.height / 2f + dy)
            )
        } else {
            val cr = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)
            drawRoundRect(
                color = resolvedShadow,
                topLeft = Offset(dx, dy),
                size = size,
                cornerRadius = CornerRadius(cr, cr)
            )
        }
    }
}

/**
 * Swiss Neo-Brutalist 3D Art-Paper Popup Menu (`Neo3DDropdownMenu`):
 * - Replaces flat plain-white Material 3 DropdownMenu across the entire app
 * - Warm ivory/slate art-paper card, razor-sharp `1.5.dp` solid ink border,
 *   and solid `3.5.dp, 3.5.dp` hard offset 3D shadow with zero blurry Android halo.
 */
@Composable
fun Neo3DDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = neoPaperSurfaceColor(),
    borderColor: Color = neoInkBorderColor(),
    shadowColor: Color = neoHardShadowColor(),
    cornerRadius: Dp = 14.dp,
    shadowOffset: Dp = 3.5.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardShape = RoundedCornerShape(cornerRadius)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        containerColor = Color.Transparent,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        shape = RectangleShape,
        border = null
    ) {
        Column(
            modifier = modifier
                .padding(
                    start = 2.dp,
                    top = 2.dp,
                    end = shadowOffset + 2.dp,
                    bottom = shadowOffset + 2.dp
                )
                .neoHardOffsetShadow(
                    cornerRadius = cornerRadius,
                    offsetX = shadowOffset,
                    offsetY = shadowOffset,
                    shadowColor = shadowColor
                )
                .clip(cardShape)
                .background(containerColor)
                .border(width = 1.5.dp, color = borderColor, shape = cardShape)
                .padding(vertical = 4.dp),
            content = content
        )
    }
}

/**
 * Legacy helper alias upgraded to Swiss Neo-Brutalist 3D Inked Paper Surface
 * (1.5.dp crisp ink border + solid 3.dp offset 3D shadow block).
 */
fun Modifier.tactile3DSurface(
    shape: Shape = RoundedCornerShape(14.dp),
    elevation: Dp = 3.dp,
    borderWidth: Dp = 1.5.dp
): Modifier = composed {
    val isDark = LocalIsDarkTheme.current
    val surfaceColor = if (isDark) IosDarkCardSurface else IosCardSurface
    val borderColor = if (isDark) NeoInkDark else NeoInkLight
    val shadowColor = if (isDark) NeoShadowDark else NeoShadowLight
    val isCircle = shape == CircleShape
    val shadowOffset = if (elevation >= 10.dp) 3.5.dp else 3.dp
    val corner = when {
        isCircle -> 999.dp
        shape == RoundedCornerShape(50) -> 999.dp
        else -> 14.dp
    }
    this
        .padding(end = shadowOffset, bottom = shadowOffset)
        .neoHardOffsetShadow(
            cornerRadius = corner,
            offsetX = shadowOffset,
            offsetY = shadowOffset,
            shadowColor = shadowColor,
            isCircle = isCircle
        )
        .clip(shape)
        .background(surfaceColor)
        .border(width = borderWidth, color = borderColor, shape = shape)
}

/**
 * Bookbinder Secondary Recessed Paper Tray with crisp 1.5.dp ink outline.
 */
fun Modifier.tactileRecessedWell(
    shape: Shape = RoundedCornerShape(14.dp),
    borderWidth: Dp = 1.5.dp
): Modifier = composed {
    val isDark = LocalIsDarkTheme.current
    val fill = if (isDark) IosDarkSecondarySurface else IosSecondarySurface
    val borderColor = if (isDark) NeoInkDark else NeoInkLight
    this
        .clip(shape)
        .background(fill)
        .border(width = borderWidth, color = borderColor, shape = shape)
}

/**
 * Tactile 3D Neo-Brutalist Pressable Surface:
 * - Stationary hard-edge solid 3D shadow block offset by `(shadowOffset, shadowOffset)`
 * - Thick art-paper foreground card with `1.5.dp` solid ink border
 * - On press, foreground physically depresses smoothly by `translate(pressDepth, pressDepth)`
 *   directly into the shadow block at 120fps!
 */
@Composable
fun NeoBrutalistPressBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 14.dp,
    isPill: Boolean = false,
    isCircle: Boolean = false,
    shadowOffset: Dp = 3.dp,
    pressDepth: Dp = 2.dp,
    borderWidth: Dp = 1.5.dp,
    containerColor: Color = neoPaperSurfaceColor(),
    borderColor: Color = neoInkBorderColor(),
    shadowColor: Color = neoHardShadowColor(),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val shape: Shape = when {
        isCircle -> CircleShape
        isPill -> RoundedCornerShape(50)
        else -> RoundedCornerShape(cornerRadius)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shiftDp by animateDpAsState(
        targetValue = if (isPressed && enabled) pressDepth else 0.dp,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 720f),
        label = "neo_press_translate"
    )
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .padding(end = shadowOffset, bottom = shadowOffset)
            .neoHardOffsetShadow(
                cornerRadius = if (isPill) 999.dp else cornerRadius,
                offsetX = shadowOffset,
                offsetY = shadowOffset,
                shadowColor = shadowColor,
                isCircle = isCircle
            ),
        propagateMinConstraints = true
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val shiftPx = with(density) { shiftDp.toPx() }
                    translationX = shiftPx
                    translationY = shiftPx
                }
                .clip(shape)
                .background(containerColor)
                .border(width = borderWidth, color = borderColor, shape = shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.material3.ripple(),
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/**
 * Bookbinder Archival Folder Card (`NeoFolderCard`):
 * - Clean archival manila/bookbinder folder with a minimal integrated folder tab rising smoothly
 *   from the top-left edge (clean, empty tab surface — zero cluttered floating badges!)
 * - Crisp `1.5.dp` continuous ink outline around both tab + folder body
 * - Hard-edged solid 3D offset ink shadow (`4.dp, 4.dp`) behind the entire folder silhouette
 */
@Composable
fun NeoFolderCard(
    modifier: Modifier = Modifier,
    tabWidth: Dp = 74.dp,
    tabHeight: Dp = 11.dp,
    cornerRadius: Dp = 14.dp,
    shadowOffset: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(13.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val fill = neoPaperSurfaceColor()
    val ink = neoInkBorderColor()
    val shadow = neoHardShadowColor()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val tw = tabWidth.toPx().coerceAtMost(w * 0.42f)
            val th = tabHeight.toPx()
            val cr = cornerRadius.toPx().coerceAtMost((h - th) / 2f)
            val tcr = (th * 0.65f).coerceAtMost(8.dp.toPx())
            val slope = 10.dp.toPx()
            val sx = shadowOffset.toPx()
            val sy = shadowOffset.toPx()

            fun buildFolderPath(ox: Float, oy: Float): Path = Path().apply {
                // Start on top-left corner of folder tab
                moveTo(ox + tcr, oy)
                lineTo(ox + tw - tcr, oy)
                quadraticTo(ox + tw, oy, ox + tw + slope * 0.45f, oy + th * 0.55f)
                lineTo(ox + tw + slope, oy + th)
                // Top edge of main folder body
                lineTo(ox + w - cr, oy + th)
                quadraticTo(ox + w, oy + th, ox + w, oy + th + cr)
                // Right edge
                lineTo(ox + w, oy + h - cr)
                quadraticTo(ox + w, oy + h, ox + w - cr, oy + h)
                // Bottom edge
                lineTo(ox + cr, oy + h)
                quadraticTo(ox, oy + h, ox, oy + h - cr)
                // Left edge back up into folder tab seamlessly
                lineTo(ox, oy + tcr)
                quadraticTo(ox, oy, ox + tcr, oy)
                close()
            }

            // 1. Solid hard-edge 3D offset ink shadow silhouette
            drawPath(path = buildFolderPath(sx, sy), color = shadow)
            // 2. Main thick art-paper folder face
            val bodyPath = buildFolderPath(0f, 0f)
            drawPath(path = bodyPath, color = fill)
            // 3. Razor-sharp 1.5dp ink border
            drawPath(
                path = bodyPath,
                color = ink,
                style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Round)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = tabHeight)
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Bookbinder Layered Paper Stack Container (`NeoPaperStackContainer`):
 * - Displays stacked archival paper sheets peeking cleanly along the bottom & right edges,
 *   with crisp `1.5.dp` ink borders on both the lower sheet and top sheet.
 */
@Composable
fun NeoPaperStackContainer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    topSurfaceColor: Color = neoPaperSurfaceColor(),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val ink = neoInkBorderColor()
    val shadow = neoHardShadowColor()
    val lowerSheetColor = if (isDark) NeoPaperEdgeDark else NeoPaperEdgeLight
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .padding(end = 6.dp, bottom = 6.dp)
    ) {
        // Bottom-most solid ink shadow block (+6.dp, +6.dp)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 6.dp, y = 6.dp)
                .clip(shape)
                .background(shadow)
        )
        // Middle peeking archival paper sheet (+3.dp, +3.dp) with crisp ink border
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 3.dp, y = 3.dp)
                .clip(shape)
                .background(lowerSheetColor)
                .border(1.5.dp, ink, shape)
        )
        // Top crisp paper sheet (0.dp, 0.dp)
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(topSurfaceColor)
                .border(1.5.dp, ink, shape),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/**
 * Swiss Neo-Brutalist 3D Art-Paper Card (`1.5.dp` ink border + `3.dp, 3.dp` solid hard shadow)
 * used uniformly across ProcessingScreen, ReviewScreen, Settings, and secondary screens.
 */
@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    elevation: Dp = 3.dp,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val ink = neoInkBorderColor()
    val shadow = neoHardShadowColor()
    val fill = neoPaperSurfaceColor()
    val offset = if (elevation >= 10.dp) 3.5.dp else 3.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = offset, bottom = offset)
            .neoHardOffsetShadow(
                cornerRadius = 14.dp,
                offsetX = offset,
                offsetY = offset,
                shadowColor = shadow
            )
            .clip(shape)
            .background(fill)
            .border(width = 1.5.dp, color = ink, shape = shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Circular Tactile 3D Inked Stamp Button (Settings gear, Search, Filter, Back).
 * Depresses smoothly by `(1.8.dp, 1.8.dp)` into its hard solid ink shadow on press.
 */
@Composable
fun Ios3DCircleIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    elevation: Dp = 3.dp,
    highlighted: Boolean = false,
    highlightBg: Color = Color(0xFFF5B82E),
    highlightBorder: Color = neoInkBorderColor(),
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val shadowOffset = 3.dp
    NeoBrutalistPressBox(
        onClick = onClick,
        enabled = enabled,
        isCircle = true,
        shadowOffset = shadowOffset,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = if (highlighted) highlightBg else neoPaperSurfaceColor(),
        borderColor = if (highlighted) highlightBorder else neoInkBorderColor(),
        shadowColor = neoHardShadowColor(),
        modifier = modifier.size(size + shadowOffset)
    ) {
        content()
    }
}

@Composable
fun IosHairlineDivider(
    modifier: Modifier = Modifier,
    startIndent: Boolean = false
) {
    HorizontalDivider(
        modifier = modifier.padding(start = if (startIndent) 14.dp else 0.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
fun IosSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 2.dp, bottom = 2.dp)
                .neoHardOffsetShadow(cornerRadius = 50.dp, offsetX = 2.dp, offsetY = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(neoPaperSurfaceColor())
                .border(1.2.dp, neoInkBorderColor(), RoundedCornerShape(50))
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Swiss Neo-Brutalist Cobalt Primary 3D Pill Button (`#1D5CD6`):
 * - `1.5.dp` crisp ink border + solid `3.dp, 3.dp` offset shadow block
 * - Physical `translate(2.dp, 2.dp)` press depression into the shadow
 * - Strictly single-line crisp bold label (`13.sp`)
 */
@Composable
fun IosPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 46.dp,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    val shadowOffset = 3.dp
    val bgColor = if (enabled) IosBlue else IosBlue.copy(alpha = 0.45f)
    val shadowColor = if (isDark) NeoShadowDark else NeoInkLight

    NeoBrutalistPressBox(
        onClick = onClick,
        enabled = enabled,
        isPill = true,
        shadowOffset = shadowOffset,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = bgColor,
        borderColor = neoInkBorderColor(),
        shadowColor = shadowColor,
        modifier = modifier
            .fillMaxWidth()
            .height(height + shadowOffset)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Swiss Neo-Brutalist 3D Paper Pill Button (for Batch Auto-Clean, Secondary Actions):
 * - Crisp `1.5.dp` ink border + `3.dp, 3.dp` solid offset ink shadow
 * - Physical `translate(2.dp, 2.dp)` depression on press
 * - Strictly single-line crisp label (`12.sp` SemiBold)
 */
@Composable
fun Ios3DSurfacePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 42.dp,
    enabled: Boolean = true,
    recessed: Boolean = false,
    accentColor: Color = IosBlue,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val shadowOffset = 3.dp
    val bgColor = if (recessed) neoSecondaryPaperColor() else neoPaperSurfaceColor()

    NeoBrutalistPressBox(
        onClick = onClick,
        enabled = enabled,
        isPill = true,
        shadowOffset = shadowOffset,
        pressDepth = 2.dp,
        borderWidth = 1.5.dp,
        containerColor = bgColor,
        borderColor = neoInkBorderColor(),
        shadowColor = neoHardShadowColor(),
        modifier = modifier
            .fillMaxWidth()
            .height(height + shadowOffset)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(7.dp))
            }
            Text(
                text = text,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(7.dp))
                trailingContent()
            }
        }
    }
}

/**
 * Swiss Neo-Brutalist Bookbinder Floating Dialog (`RoundedCornerShape(18.dp)`):
 * - Thick ivory/slate paper card with `1.8.dp` solid ink border & hard `5.dp, 5.dp` solid offset shadow
 * - Locks `fontScale <= 1.0f` inside Android Dialog windows
 */
@Composable
fun IosFloatingDialog(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val dialogShape = RoundedCornerShape(18.dp)
    val ink = neoInkBorderColor()
    val shadow = neoHardShadowColor()
    val fill = neoPaperSurfaceColor()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val currentDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = currentDensity.density,
                fontScale = currentDensity.fontScale.coerceAtMost(1.0f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(IosScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .padding(end = 5.dp, bottom = 5.dp)
                        .neoHardOffsetShadow(
                            cornerRadius = 18.dp,
                            offsetX = 5.dp,
                            offsetY = 5.dp,
                            shadowColor = shadow
                        )
                        .clip(dialogShape)
                        .background(fill)
                        .border(width = 1.8.dp, color = ink, shape = dialogShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
fun IosSaveActionSheet(
    visible: Boolean,
    sourceFile: SourceFileInfo,
    onSelectMode: (SaveMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    IosFloatingDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.sheet_save_title),
                fontSize = 17.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, neoInkBorderColor())
            ) {
                Text(
                    text = "Thư mục: ${sourceFile.displayParentFolder}",
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontFamily = JetBrainsMonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            NeoBrutalistPressBox(
                onClick = { onSelectMode(SaveMode.REPLACE_ORIGINAL) },
                isPill = false,
                cornerRadius = 14.dp,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("action_sheet_replace")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAs,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sheet_option_replace),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.sheet_option_replace_sub),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            NeoBrutalistPressBox(
                onClick = { onSelectMode(SaveMode.KEEP_ORIGINAL) },
                isPill = false,
                cornerRadius = 14.dp,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("action_sheet_keep")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sheet_option_keep),
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(
                                R.string.sheet_option_keep_sub,
                                sourceFile.cleanCopyFileName
                            ),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Ios3DSurfacePillButton(
                text = stringResource(R.string.sheet_cancel),
                height = 40.dp,
                recessed = true,
                onClick = onDismiss,
                modifier = Modifier.testTag("action_sheet_cancel")
            )
        }
    }
}

@Composable
fun IosSaveSuccessDialog(
    result: SaveResult.Success,
    onOpenEpub: () -> Unit,
    onDone: () -> Unit
) {
    IosFloatingDialog(onDismissRequest = onDone) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 3.dp, bottom = 3.dp)
                    .size(48.dp)
                    .neoHardOffsetShadow(isCircle = true, offsetX = 3.dp, offsetY = 3.dp)
                    .clip(CircleShape)
                    .background(IosGreen)
                    .border(1.5.dp, neoInkBorderColor(), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.save_success_title),
                fontSize = 17.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = result.outputFileName,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = result.outputDisplayPath,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Ios3DSurfacePillButton(
                    text = stringResource(R.string.save_success_open),
                    height = 42.dp,
                    onClick = onOpenEpub,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("dialog_open_epub_button")
                )

                Box(modifier = Modifier.weight(1f)) {
                    IosPrimaryButton(
                        text = stringResource(R.string.save_success_done),
                        onClick = onDone,
                        height = 42.dp,
                        modifier = Modifier.testTag("dialog_done_button")
                    )
                }
            }
        }
    }
}
