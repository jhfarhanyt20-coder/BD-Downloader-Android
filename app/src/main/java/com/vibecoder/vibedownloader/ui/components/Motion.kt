package com.vibecoder.vibedownloader.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibecoder.vibedownloader.engine.Http
import com.vibecoder.vibedownloader.ui.theme.Vd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** Progress bar with the accent gradient; a soft light runs through it while active. */
@Composable
fun GradientProgressBar(progress: Float, modifier: Modifier = Modifier, active: Boolean = true, height: Dp = 8.dp) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500, easing = FastOutSlowInEasing), label = "progress")
    val dim by animateFloatAsState(if (active) 1f else 0.45f, tween(300), label = "dim")
    val t = rememberInfiniteTransition(label = "shine")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "shift")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Vd.Line, cornerRadius = r)
        val w = size.width * animated
        if (w > 0f) {
            val span = size.width.coerceAtLeast(1f)
            val x = if (active) shift * span * 2 - span else 0f
            val brush = Brush.linearGradient(
                listOf(Vd.AccentLight, Vd.Accent2, Vd.AccentLight),
                start = Offset(x, 0f), end = Offset(x + span, 0f), tileMode = TileMode.Mirror,
            )
            drawRoundRect(brush, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r, alpha = dim)
        }
    }
}

/** For "waiting" and "finishing": a short gradient piece gliding along the track. */
@Composable
fun IndeterminateBar(modifier: Modifier = Modifier, height: Dp = 8.dp, soft: Boolean = false) {
    val t = rememberInfiniteTransition(label = "busy")
    val pos by t.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "pos")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Vd.Line, cornerRadius = r)
        val pieceW = size.width * 0.35f
        val x = size.width * pos
        val left = x.coerceAtLeast(0f)
        val right = (x + pieceW).coerceAtMost(size.width)
        if (right > left) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(Vd.AccentLight, Vd.Accent2), startX = x, endX = x + pieceW),
                topLeft = Offset(left, 0f), size = Size(right - left, size.height), cornerRadius = r,
                alpha = if (soft) 0.5f else 1f,
            )
        }
    }
}

/** A soft shimmering block used while something loads. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(RoundedCornerShape(corner)).background(Vd.CardHi.copy(alpha = a)))
}

/** A selectable pill (filters, qualities). */
@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    compact: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color? = null,
) {
    val bg by animateColorAsState(if (selected) Vd.AccentSelected else Vd.CardHi, tween(220), label = "pillBg")
    val line by animateColorAsState(if (selected) Vd.AccentLight else Vd.Line, tween(220), label = "pillLine")
    val fg by animateColorAsState(if (selected) Vd.Text else Vd.Muted, tween(220), label = "pillFg")
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = if (compact) 6.dp else 14.dp, vertical = if (detail != null) 10.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconTint ?: fg, modifier = Modifier.size(16.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
            }
        } else {
            Text(label, style = if (compact) MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                else MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (selected) Vd.Accent2 else Vd.Faint, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small in-memory + disk cache for video thumbnails (no image library needed). */
object Thumbs {
    private val memory = LruCache<String, ImageBitmap>(48)

    fun peek(url: String?): ImageBitmap? = url?.let { memory.get(it) }

    suspend fun load(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        memory.get(url)?.let { return@withContext it }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, key)
        runCatching {
            if (!file.exists()) {
                val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()
                Http.client.newCall(req).execute().use { resp ->
                    val body = resp.body
                    if (!resp.isSuccessful || body == null || body.contentLength() > 6L * 1024 * 1024) return@runCatching null
                    val tmp = File(dir, "$key.tmp")
                    tmp.outputStream().use { body.byteStream().copyTo(it) }
                    tmp.renameTo(file)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.asImageBitmap()?.also { memory.put(url, it) }
        }.getOrNull()
    }
}

/** A thumbnail that fades in once loaded; a type icon until then (or when there is none). */
@Composable
fun Thumbnail(url: String?, fallback: ImageVector, modifier: Modifier = Modifier, corner: Dp = 14.dp, tint: Color? = null) {
    val context = LocalContext.current
    var image by remember(url) { mutableStateOf(Thumbs.peek(url)) }
    LaunchedEffect(url) {
        if (image == null && url != null) image = Thumbs.load(context, url)
    }
    val shape = RoundedCornerShape(corner)
    val soft = tint?.copy(alpha = if (Vd.isDark) 0.14f else 0.11f) ?: Vd.AccentSoft
    val line = tint?.copy(alpha = if (Vd.isDark) 0.26f else 0.2f) ?: Vd.AccentSoftLine
    Box(modifier.clip(shape).background(soft).border(1.dp, line, shape), contentAlignment = Alignment.Center) {
        Crossfade(image, animationSpec = tween(350), label = "thumb") { img ->
            if (img != null) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(fallback, null, tint = tint ?: Vd.AccentLight, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}
