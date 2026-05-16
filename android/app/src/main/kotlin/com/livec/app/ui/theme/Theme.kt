package com.livec.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = LiveCColors.Accent,
    onPrimary = LiveCColors.BgBase,
    secondary = LiveCColors.SevMed,
    background = LiveCColors.BgBase,
    onBackground = LiveCColors.TextPrimary,
    surface = LiveCColors.BgSurface,
    onSurface = LiveCColors.TextPrimary,
    surfaceVariant = LiveCColors.BgElevated,
    onSurfaceVariant = LiveCColors.TextSecondary,
    outline = LiveCColors.Border,
    outlineVariant = LiveCColors.BorderSoft,
    error = LiveCColors.SevCritical,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private val AppTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp,
    ),
)

/**
 * Replicates the Windows app's dotted-grid background:
 *   background-image: radial-gradient(zinc-500/10% 1px, transparent 1px);
 *   background-size: 8px 8px;
 */
@Composable
fun DottedBackground(modifier: Modifier = Modifier) {
    // TextTertiary = zinc-500 (#71717A) at 10 % opacity
    val dotColor = Color(0x1A71717A)
    Canvas(modifier = modifier.fillMaxSize()) {
        val gridPx = 8.dp.toPx()
        val cols = (size.width / gridPx).toInt() + 1
        val rows = (size.height / gridPx).toInt() + 1
        for (col in 0..cols) {
            for (row in 0..rows) {
                drawCircle(
                    color = dotColor,
                    radius = 1f,
                    center = Offset(col * gridPx, row * gridPx),
                )
            }
        }
    }
}

@Composable
fun LiveCTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        shapes = AppShapes,
        typography = AppTypography,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LiveCColors.BgBase),
        ) {
            // Dotted grid — rendered once, sits beneath all content
            DottedBackground()
            content()
        }
    }
}
