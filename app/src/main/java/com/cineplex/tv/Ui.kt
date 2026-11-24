package com.cineplex.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Phones in portrait (narrow screens) get a compact, touch-first layout. */
@Composable
fun isCompact(): Boolean = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600

val BG = Color(0xFF0B0B0F)
val CARD = Color(0xFF1B1B22)
val SURFACE = Color(0xFF272731)
val ACCENT = Color(0xFFE50914)
val TEXT_DIM = Color(0xFF9A9AA8)

/** D-pad focusable box: white ring + slight zoom when focused. Opaque fill comes from the caller. */
@Composable
fun FocusBox(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    FocusSurface(
        modifier = modifier,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onClick = onClick
    ) { _ -> content() }
}

/**
 * Same as [FocusBox] but tells the content whether it has focus, and lets the caller pick
 * the shape, zoom and ring.
 */
@Composable
fun FocusSurface(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    focusScale: Float = 1.06f,
    ring: Boolean = true,
    onClick: () -> Unit,
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "focusScale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() }
            .then(
                if (ring) Modifier.border(2.dp, if (focused) Color.White else Color.Transparent, shape)
                else Modifier
            )
            .clip(shape)
            .clickable(onClick = onClick)
    ) { content(focused) }
}

/** On-screen Back button (touch + D-pad). */
@Composable
fun BackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(modifier, shape = RoundedCornerShape(50), onClick = onClick) { _ ->
        Text(
            "\u2039  Back",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.background(Color(0xCC1B1B22)).padding(horizontal = 20.dp, vertical = 9.dp)
        )
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
