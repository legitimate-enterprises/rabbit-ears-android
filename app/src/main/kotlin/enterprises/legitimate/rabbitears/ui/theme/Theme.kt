package enterprises.legitimate.rabbitears.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens from the Android boards; every client shares one dark palette. */
object Palette {
    val ground = Color(0xFF1C1C1E)
    val raised = Color(0xFF2A2A2D)
    // Goldenrod marks a transcode or anything that costs the server work.
    val accent = Color(0xFFF2B33D)
    val onAccent = ground
    val text = Color(0xFFF2F2F7)
    val secondary = Color(0xFFC7C7CC)
    val dim = Color(0xFF98989D)
    // Green marks direct play and anything free.
    val ok = Color(0xFF5BD47A)
    val outline = Color.White.copy(alpha = 0.38f)
    val divider = Color.White.copy(alpha = 0.08f)
    val tonal = Color.White.copy(alpha = 0.12f)
    val tag = Color.White.copy(alpha = 0.16f)
    val indicator = accent.copy(alpha = 0.24f)
}

private val colors =
    darkColorScheme(
        primary = Palette.accent,
        onPrimary = Palette.onAccent,
        secondaryContainer = Palette.indicator,
        onSecondaryContainer = Palette.accent,
        background = Palette.ground,
        onBackground = Palette.text,
        surface = Palette.ground,
        onSurface = Palette.text,
        surfaceVariant = Palette.raised,
        onSurfaceVariant = Palette.secondary,
        surfaceContainer = Palette.raised,
        outline = Palette.outline,
        outlineVariant = Palette.divider,
    )

private val typography =
    Typography(
        headlineMedium =
            TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Medium, lineHeight = 36.sp),
        titleMedium =
            TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge =
            TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp),
    )

private val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

@Composable
fun RabbitEarsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
