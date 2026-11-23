package com.vibecoder.vibedownloader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.ui.theme.Vd

/** A rounded dark card with a soft top highlight (the "glossy" look of the desktop app). */
@Composable
fun GlassCard(modifier: Modifier = Modifier, padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(Vd.Card)
            .border(1.dp, Vd.Line, shape)
            .padding(padding.dp),
        content = content,
    )
}

/** Main action button: purple-to-cyan gradient, gently shrinks while pressed. */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 600f), label = "press")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(if (enabled) Vd.AccentGradient else (if (Vd.isDark) Vd.CardHi else Vd.Line).let { Brush.linearGradient(listOf(it, it)) })
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .height(54.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = if (enabled) Color.White else Vd.Faint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Vd.Faint)
        }
    }
}

/** A quiet secondary button. */
@Composable
fun SoftButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(Vd.CardHi)
            .border(1.dp, Vd.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Vd.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Vd.Text)
    }
}

/** Small uppercase label above a group, like the desktop app. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Vd.Faint,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** The page title with a subtitle under it. */
@Composable
fun PageHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Vd.Text)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Vd.Muted)
    }
}

/** Round icon "badge" with the accent glow. */
@Composable
fun IconBadge(icon: ImageVector, size: Int = 44, tint: Color = Vd.AccentLight) {
    // the soft background takes the icon's colour (purple by default, category colours on cards)
    val soft = if (tint == Vd.AccentLight) Vd.AccentSoft else tint.copy(alpha = if (Vd.isDark) 0.14f else 0.11f)
    val line = if (tint == Vd.AccentLight) Vd.AccentSoftLine else tint.copy(alpha = if (Vd.isDark) 0.24f else 0.2f)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(soft)
            .border(1.dp, line, RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}
