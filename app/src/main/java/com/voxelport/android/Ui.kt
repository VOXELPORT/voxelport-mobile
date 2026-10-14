package com.voxelport.android

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** VoxelPort's palette (same as the website and desktop app). */
object VP {
    val Paper = Color(0xFFF3EBDC)
    val Card = Color(0xFFFFFBF2)
    val Ink = Color(0xFF15120F)
    val Muted = Color(0xFF6B6052)
    val Red = Color(0xFFC8262B)
    val Grass = Color(0xFF5FAE3B)
    val GrassDark = Color(0xFF3F7F25)
    val Gold = Color(0xFFF6CB2F)
    val Diamond = Color(0xFF43C8D8)
    val Console = Color(0xFF1D1B1A)
}

private val scheme = lightColorScheme(
    primary = VP.GrassDark, onPrimary = Color.White,
    secondary = VP.Gold, onSecondary = VP.Ink,
    tertiary = VP.Diamond, onTertiary = VP.Ink,
    background = VP.Paper, onBackground = VP.Ink,
    surface = VP.Card, onSurface = VP.Ink,
    surfaceVariant = Color(0xFFE6DCC6), onSurfaceVariant = VP.Muted,
    secondaryContainer = Color(0xFFE6DCC6), onSecondaryContainer = VP.Ink,
    outline = VP.Ink, outlineVariant = Color(0xFFB9AE99),
    error = VP.Red,
)

/** Brand colours instead of Material's default purple (sliders, checkboxes, text fields, links). */
@Composable
fun VpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        Surface(color = VP.Paper, contentColor = VP.Ink, modifier = Modifier.fillMaxSize(), content = content)
    }
}

/** A bordered card; animates its height when sections open and close. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(VP.Card).border(3.dp, VP.Ink).padding(14.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
fun PanelTitle(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.Black, color = VP.Ink, fontSize = 15.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Button that always stays on one line, so a row of them can share the width with Modifier.weight(1f). */
@Composable
fun VpButton(label: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val dark = color == VP.Gold || color == VP.Card || color == VP.Diamond
    Button(
        onClick, enabled = enabled, shape = RectangleShape,
        modifier = modifier.heightIn(min = 48.dp).border(2.dp, if (enabled) VP.Ink else VP.outlineMuted),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = if (dark) VP.Ink else Color.White,
            disabledContainerColor = Color(0xFFE4DCCB), disabledContentColor = Color(0xFF9A8F7C),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) { Text(label, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
}

private val VP.outlineMuted get() = Color(0xFFB9AE99)

/** A coloured dot + label: Online / Starting / Stopped. */
@Composable
fun StatusPill(label: String, color: Color) {
    Row(
        Modifier.background(VP.Card).border(2.dp, VP.Ink).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(color))
        Text(label, fontWeight = FontWeight.Black, fontSize = 12.sp, color = VP.Ink, maxLines = 1)
    }
}
