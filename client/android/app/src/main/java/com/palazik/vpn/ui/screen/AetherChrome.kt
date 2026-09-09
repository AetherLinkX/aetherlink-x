package com.palazik.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.palazik.vpn.R

/** Shared backdrop used by the three retained main tabs. */
@Composable
internal fun Modifier.aetherScreenBackground(): Modifier {
    val colors = MaterialTheme.colorScheme
    val isLight = colors.background.luminance() > 0.5f
    val accent = colors.primary
    val arcAlpha = if (isLight) 0.10f else 0.20f

    val base = this
        .background(
            Brush.linearGradient(
                colors = listOf(
                    colors.background,
                    accent.copy(alpha = if (isLight) 0.055f else 0.085f),
                    colors.background,
                ),
                start = Offset.Zero,
                end = Offset(1100f, 2200f),
            )
        )

    if (!isLight) {
        return base.paint(
            painter = painterResource(R.drawable.bg_aether_cyber),
            contentScale = ContentScale.FillBounds,
        )
    }

    return base.drawBehind {
            val stroke = 1.1.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = arcAlpha), Color.Transparent),
                    center = Offset(size.width * 1.05f, size.height * 0.01f),
                    radius = size.minDimension * 0.62f,
                ),
                radius = size.minDimension * 0.62f,
                center = Offset(size.width * 1.05f, size.height * 0.01f),
            )
            drawCircle(
                color = accent.copy(alpha = arcAlpha * 0.75f),
                radius = size.minDimension * 0.57f,
                center = Offset(size.width * 1.05f, size.height * 0.01f),
                style = Stroke(stroke),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = arcAlpha * 0.75f), Color.Transparent),
                    center = Offset(-size.width * 0.08f, size.height * 0.91f),
                    radius = size.minDimension * 0.56f,
                ),
                radius = size.minDimension * 0.56f,
                center = Offset(-size.width * 0.08f, size.height * 0.91f),
            )
    }
}
