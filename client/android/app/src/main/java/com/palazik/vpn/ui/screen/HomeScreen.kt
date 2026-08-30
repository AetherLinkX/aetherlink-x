package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.DecimalFormat

private val EaseInOutSine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private val EaseOutBack   = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

@Composable
fun HomeScreen(
    vm: MainViewModel,
    permLauncher: ActivityResultLauncher<Intent>,
) {
    val ui           by vm.ui.collectAsState()
    val vpnState     = ui.vpnState
    val isConnected  = vpnState == VpnState.CONNECTED
    val isTransition = vpnState == VpnState.CONNECTING || vpnState == VpnState.DISCONNECTING
    // NOTE: per-second traffic/duration updates live in ConnectedStats so they don't
    // recompose this whole screen every second.

    // ── Animations ────────────────────────────────────────────────────────────

    val glowAlpha = if (isConnected) {
        val transition = rememberInfiniteTransition(label = "home_glow")
        val value by transition.animateFloat(
            initialValue = 0.15f, targetValue = 0.40f,
            animationSpec = infiniteRepeatable(
                animation  = tween(2000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glow_alpha",
        )
        value
    } else {
        0f
    }

    val pulseScale = if (isTransition) {
        val transition = rememberInfiniteTransition(label = "home_pulse")
        val value by transition.animateFloat(
            initialValue = 0.85f, targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation  = tween(900, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse_scale",
        )
        value
    } else {
        1f
    }

    val haloRotation = if (isTransition) {
        val transition = rememberInfiniteTransition(label = "home_halo")
        val value by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
            ),
            label = "halo_rotation",
        )
        value
    } else {
        0f
    }

    // Button scale spring on state change
    val buttonScale by animateFloatAsState(
        targetValue   = when {
            isTransition -> pulseScale
            isConnected  -> 1.04f
            else         -> 1f
        },
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow),
        label         = "btn_scale",
    )

    val statusColor = when (vpnState) {
        VpnState.CONNECTED                          -> MaterialTheme.colorScheme.primary
        VpnState.CONNECTING, VpnState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
        else                                        -> MaterialTheme.colorScheme.outline
    }

    val buttonContainerColor by animateColorAsState(
        targetValue = when {
            isConnected  -> MaterialTheme.colorScheme.primary
            isTransition -> MaterialTheme.colorScheme.tertiary
            else         -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(400),
        label = "btn_color",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = "AetherLink X",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState = ui.activeProfile,
                transitionSpec = {
                    fadeIn(tween(250)) + slideInVertically(tween(250)) { -it / 3 } togetherWith
                        fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 3 }
                },
                label = "profile_name",
            ) { profile ->
                HomeProfilePill(profileName = profile?.name, endpoint = profile?.let { "${it.address}:${it.port}" })
            }
        }

        // ── Connect button ────────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center) {

            // Outer breathing glow (connected only)
            val outerGlowAlpha by animateFloatAsState(
                targetValue   = if (isConnected) glowAlpha else 0f,
                animationSpec = tween(600),
                label         = "outer_glow",
            )
            Box(
                Modifier
                    .size(300.dp)
                    .graphicsLayer { alpha = outerGlowAlpha }
                    .background(
                        Brush.radialGradient(listOf(statusColor.copy(alpha = 0.8f), Color.Transparent)),
                        CircleShape,
                    )
            )

            // Rotating dashed ring (connecting only)
            val ringAlpha by animateFloatAsState(
                targetValue   = if (isTransition) 0.6f else 0f,
                animationSpec = tween(400),
                label         = "ring_alpha",
            )
            Box(
                Modifier
                    .size(200.dp)
                    .graphicsLayer {
                        alpha          = ringAlpha
                        rotationZ      = haloRotation
                        scaleX         = pulseScale
                        scaleY         = pulseScale
                    }
                    .background(
                        Brush.sweepGradient(
                            listOf(Color.Transparent, statusColor.copy(alpha = 0.5f), Color.Transparent)
                        ),
                        CircleShape,
                    )
            )

            // Inner ring (always, fades in when connected)
            val innerAlpha by animateFloatAsState(
                targetValue   = if (isConnected) 1f else 0f,
                animationSpec = tween(500),
                label         = "inner_ring",
            )
            Box(
                Modifier
                    .size(195.dp)
                    .graphicsLayer { alpha = innerAlpha }
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = if (isConnected) glowAlpha * 0.5f else 0f),
                                Color.Transparent,
                            )
                        ),
                        CircleShape,
                    )
            )

            // Main button
            Button(
                onClick  = { vm.toggleVpn(permLauncher) },
                modifier = Modifier
                    .size(164.dp)
                    .scale(buttonScale),
                shape  = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = buttonContainerColor),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (isConnected) 20.dp else 4.dp,
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(
                        targetState = isConnected,
                        transitionSpec = {
                            (scaleIn(EaseOutBack.toAnimationSpec(300)) + fadeIn(tween(200))) togetherWith
                                (scaleOut(tween(150)) + fadeOut(tween(100)))
                        },
                        label = "lock_icon",
                    ) { connected ->
                        Icon(
                            imageVector       = if (connected) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = null,
                            modifier          = Modifier.size(40.dp),
                            tint              = if (connected || isTransition)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AnimatedContent(
                        targetState = vpnState,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(100))
                        },
                        label = "btn_label",
                    ) { state ->
                        Text(
                            text  = when (state) {
                                VpnState.CONNECTED     -> "Disconnect"
                                VpnState.CONNECTING    -> "Connecting"
                                VpnState.DISCONNECTING -> "Stopping"
                                else                   -> "Connect"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isConnected || isTransition)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // ── Status pill ───────────────────────────────────────────────────────
        AnimatedContent(
            targetState = vpnState,
            transitionSpec = {
                fadeIn(tween(200)) + slideInVertically(tween(250, easing = EaseOutBack)) { it / 2 } togetherWith
                    fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 }
            },
            label = "status_badge",
        ) { state ->
            Surface(
                shape = CircleShape,
                color = statusColor.copy(alpha = 0.14f),
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dotScale by animateFloatAsState(
                        targetValue   = if (isTransition) pulseScale else 1f,
                        animationSpec = spring(),
                        label         = "dot_scale",
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .scale(dotScale)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = state.name.replace('_', ' '),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = vpnState == VpnState.ERROR,
            enter = fadeIn(tween(200)) + expandVertically(),
            exit = fadeOut(tween(150)) + shrinkVertically(),
        ) {
            ErrorCard(
                message = ui.lastError ?: "Connection failed",
                onRetry = { vm.toggleVpn(permLauncher) },
            )
        }

        // ── Traffic stats ─────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = isConnected,
            enter   = expandVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) +
                      fadeIn(tween(300)),
            exit    = shrinkVertically(tween(250)) + fadeOut(tween(200)),
        ) {
            ConnectedStats(vm = vm, profileName = ui.activeProfile?.name ?: "", isConnected = isConnected)
        }

        // ── Quick ping ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = ui.activeProfile != null,
            enter   = fadeIn(tween(250)) + expandVertically(spring(Spring.DampingRatioMediumBouncy)),
            exit    = fadeOut(tween(180)) + shrinkVertically(tween(200)),
        ) {
            ui.activeProfile?.let { profile ->
                OutlinedButton(
                    onClick  = { vm.pingProfile(profile) },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ping  ${profile.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimatedVisibility(
                        visible = profile.latencyMs >= 0,
                        enter   = fadeIn() + scaleIn(EaseOutBack.toAnimationSpec(300)),
                        exit    = fadeOut() + scaleOut(),
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = latencyColor(profile.latencyMs).copy(alpha = 0.15f),
                                shape = CircleShape,
                            ) {
                                Text(
                                    "${profile.latencyMs}ms",
                                    modifier   = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style      = MaterialTheme.typography.labelMedium,
                                    color      = latencyColor(profile.latencyMs),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeProfilePill(profileName: String?, endpoint: String?) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (profileName == null) Icons.Rounded.ReportProblem else Icons.Rounded.Dns,
                null,
                Modifier.size(18.dp),
                tint = if (profileName == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.widthIn(max = 240.dp)) {
                Text(
                    profileName ?: "No profile selected",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (endpoint != null) {
                    Text(
                        endpoint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    null,
                    Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Connection Error",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
        }
    }
}

/**
 * Self-contained traffic + duration block. It collects the high-frequency flows and
 * runs its own 1s ticker, so the per-second updates recompose only this subtree —
 * not the whole HomeScreen.
 */
@Composable
private fun ConnectedStats(vm: MainViewModel, profileName: String, isConnected: Boolean) {
    val bytesIn        by vm.bytesIn.collectAsState()
    val bytesOut       by vm.bytesOut.collectAsState()
    val connectedSince by vm.connectedSince.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isConnected) {
        while (isConnected) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConnectionHealthCard(
            profileName = profileName,
            connectedFor = formatDuration((now - connectedSince).coerceAtLeast(0L)),
            total = bytesIn + bytesOut,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrafficCard(Modifier.weight(1f), "↓  Download", bytesIn)
            TrafficCard(Modifier.weight(1f), "↑  Upload",   bytesOut)
        }
    }
}

@Composable
private fun ConnectionHealthCard(profileName: String, connectedFor: String, total: Long) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.VerifiedUser,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Connected $connectedFor • ${formatBytes(total)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TrafficCard(modifier: Modifier, label: String, bytes: Long) {
    ElevatedCard(modifier = modifier, shape = MaterialTheme.shapes.large) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(6.dp))
            AnimatedContent(
                targetState  = formatBytes(bytes),
                transitionSpec = {
                    fadeIn(tween(100)) + slideInVertically(tween(150)) { -it / 2 } togetherWith
                        fadeOut(tween(80)) + slideOutVertically(tween(100)) { it / 2 }
                },
                label = "traffic_$label",
            ) { value ->
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun latencyColor(ms: Long): Color = when {
    ms < 150 -> MaterialTheme.colorScheme.primary
    ms < 400 -> Color(0xFFFFC107)
    else     -> MaterialTheme.colorScheme.error
}

private fun formatBytes(b: Long): String {
    val df = DecimalFormat("#.##")
    return when {
        b >= 1_073_741_824 -> "${df.format(b / 1_073_741_824.0)} GB"
        b >= 1_048_576     -> "${df.format(b / 1_048_576.0)} MB"
        b >= 1_024         -> "${df.format(b / 1_024.0)} KB"
        else               -> "$b B"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun CubicBezierEasing.toAnimationSpec(durationMs: Int) =
    tween<Float>(durationMs, easing = this)
