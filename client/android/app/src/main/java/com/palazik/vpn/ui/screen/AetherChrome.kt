package com.palazik.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.palazik.vpn.R

/** Shared backdrop used by the three retained main tabs. */
@Composable
internal fun Modifier.aetherScreenBackground(): Modifier {
    val colors = MaterialTheme.colorScheme
    val isLight = colors.background.luminance() > 0.5f
    val backgroundResource = if (isLight) R.drawable.bg_aether_light else R.drawable.bg_aether_cyber

    return this
        .background(colors.background)
        .paint(
            painter = painterResource(backgroundResource),
            contentScale = ContentScale.FillBounds,
        )
}
