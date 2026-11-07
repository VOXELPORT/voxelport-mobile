package com.voxelport.android

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VoxelPort's palette. The app is dark only, matching the desktop app's dark theme
 * (charcoal background, soft cards, hairline borders, one green accent). The names are kept
 * from the old light theme so the rest of the code does not change: only the values moved.
 */
object VP {
    val Paper = Color(0xFF181A1B) // app background
    val Paper2 = Color(0xFF252829) // raised surface (inputs, pills)
    val Card = Color(0xFF202324) // panels
    val Ink = Color(0xFFF4F4F5) // primary text (was the black outline/ink; now the near-white text)
    val Line = Color(0x22FFFFFF) // hairline border (what used to be the thick black outline)
    val Muted = Color(0xFFA1AAA7)
    val Red = Color(0xFFEF4444)
    val Grass = Color(0xFF22C55E)
    val GrassDark = Color(0xFF4ADE80)
    val Gold = Color(0xFFFACC15)
    val Diamond = Color(0xFF38BDF8)
    val Console = Color(0xFF15181A)
    val OnGold = Color(0xFF1A1600) // dark text for use on the Gold / coloured banners

    val Radius = 16.dp
    val RadiusSm = 10.dp
}

/** A coloured call-out banner (announcements, warnings), rounded with dark text on the colour. */
@Composable
fun Banner(
    color: Color = VP.Gold,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(VP.RadiusSm))
            .background(color)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

private val scheme = darkColorScheme(
    primary = VP.Grass, onPrimary = Color(0xFF07140B),
    secondary = VP.Gold, onSecondary = Color(0xFF1A1600),
    tertiary = VP.Diamond, onTertiary = Color(0xFF04171F),
    background = VP.Paper, onBackground = VP.Ink,
    surface = VP.Card, onSurface = VP.Ink,
    surfaceVariant = VP.Paper2, onSurfaceVariant = VP.Muted,
    secondaryContainer = VP.Paper2, onSecondaryContainer = VP.Ink,
    outline = VP.Line, outlineVariant = VP.Line,
    error = VP.Red,
)

/** Dark brand theme (sliders, checkboxes, text fields, links all use the green accent). */
@Composable
fun VpTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) {
        Surface(color = VP.Paper, contentColor = VP.Ink, modifier = Modifier.fillMaxSize(), content = content)
    }
}

/** A soft rounded card with a hairline border; animates its height when sections open and close. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(VP.Radius))
            .background(VP.Card)
            .border(1.dp, VP.Line, RoundedCornerShape(VP.Radius))
            .padding(16.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
fun PanelTitle(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.SemiBold, color = VP.Ink, fontSize = 15.sp, letterSpacing = 0.2.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Button that always stays on one line, so a row of them can share the width with Modifier.weight(1f). */
@Composable
fun VpButton(label: String, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    // Call sites pass VP.Card for a secondary button and VP.Ink for the primary one (both were the old
    // light theme's conventions). On dark that means: VP.Card -> ghost, VP.Ink -> the green primary.
    if (color == VP.Card) {
        VpGhostButton(label, modifier, enabled, onClick)
        return
    }
    val fill = if (color == VP.Ink) VP.Grass else color
    val light = fill == VP.Gold || fill == VP.Diamond || fill == VP.GrassDark || fill == VP.Grass
    Button(
        onClick, enabled = enabled, shape = RoundedCornerShape(VP.RadiusSm),
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill, contentColor = if (light) Color(0xFF07140B) else Color.White,
            disabledContainerColor = VP.Paper2, disabledContentColor = VP.Muted,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
}

/** A secondary button: subtle raised surface with a hairline border, for "SHARE", "STOP PUBLIC". */
@Composable
fun VpGhostButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, enabled = enabled, shape = RoundedCornerShape(VP.RadiusSm),
        modifier = modifier.heightIn(min = 48.dp).border(1.dp, VP.Line, RoundedCornerShape(VP.RadiusSm)),
        colors = ButtonDefaults.buttonColors(
            containerColor = VP.Paper2, contentColor = VP.Ink,
            disabledContainerColor = VP.Paper2, disabledContentColor = VP.Muted,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
}

/** A coloured dot + label: Online / Starting / Stopped. */
@Composable
fun StatusPill(label: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(VP.Paper2).border(1.dp, VP.Line, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(color))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = VP.Ink, maxLines = 1)
    }
}
