package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.Subscription
import com.palazik.vpn.data.model.VpnProfile
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.data.model.PingDisplayMode
import com.palazik.vpn.R
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
    val animationsEnabled = ui.settings.uiAnimationsEnabled
    val activeSubscription = remember(ui.subscriptions, ui.activeProfile?.subscriptionId) {
        ui.subscriptions.firstOrNull { it.id == ui.activeProfile?.subscriptionId }
    }
    val homeSubscription = activeSubscription ?: ui.subscriptions.firstOrNull().takeIf { ui.activeProfile == null }
    val homeProfiles = remember(ui.profiles, homeSubscription?.id, ui.activeProfile?.subscriptionId) {
        val subscriptionId = homeSubscription?.id ?: ui.activeProfile?.subscriptionId
        if (subscriptionId == null) ui.profiles.filter { it.subscriptionId == null }
        else ui.profiles.filter { it.subscriptionId == subscriptionId }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    // NOTE: per-second traffic/duration updates live in ConnectedStats so they don't
    // recompose this whole screen every second.

    // ── Animations ────────────────────────────────────────────────────────────

    val glowAlpha = if (isConnected && animationsEnabled) {
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

    val pulseScale = if (isTransition && animationsEnabled) {
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

    val haloRotation = if (isTransition && animationsEnabled) {
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

    val lightTheme = MaterialTheme.colorScheme.background.luminance() > 0.5f
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primary.copy(alpha = if (lightTheme) 0.08f else 0.13f),
                        if (lightTheme) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF080311),
                    )
                )
            )
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_logo),
                    contentDescription = "AetherLink X",
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text  = "AetherLink X",
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
                            imageVector       = if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.Shield,
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
                                VpnState.CONNECTED     -> stringResource(R.string.disconnect)
                                VpnState.CONNECTING    -> stringResource(R.string.connecting)
                                VpnState.DISCONNECTING -> stringResource(R.string.stopping)
                                else                   -> stringResource(R.string.connect)
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
                        text  = when (state) {
                            VpnState.CONNECTED -> stringResource(R.string.connected)
                            VpnState.CONNECTING -> stringResource(R.string.connecting)
                            VpnState.DISCONNECTING -> stringResource(R.string.stopping)
                            else -> stringResource(R.string.disconnected)
                        },
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
                message = ui.lastError ?: stringResource(R.string.connection_failed),
                onRetry = { vm.toggleVpn(permLauncher) },
            )
        }

        if (homeSubscription != null || ui.activeProfile != null) {
            ProviderSummaryCard(
                subscription = homeSubscription,
                profile = ui.activeProfile?.takeIf { homeSubscription == null || it.subscriptionId == homeSubscription.id },
                onPingAll = { homeSubscription?.let { vm.pingSubscription(it.id) } ?: vm.pingAll() },
                onUpdate = { homeSubscription?.let(vm::updateSubscription) },
                onDelete = { confirmDelete = true },
            )
        }

        // Current connection stats stay immediately below the connect control and
        // provider card; the potentially long location list follows afterwards.
        AnimatedVisibility(
            visible = isConnected,
            enter   = expandVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) +
                      fadeIn(tween(300)),
            exit    = shrinkVertically(tween(250)) + fadeOut(tween(200)),
        ) {
            ConnectedStats(vm = vm, profileName = ui.activeProfile?.name ?: "", isConnected = isConnected)
        }

        if (homeProfiles.isNotEmpty()) {
            HomeServerSwitcher(
                profiles = homeProfiles,
                activeId = ui.activeProfile?.id,
                switching = isTransition,
                displayMode = ui.settings.pingDisplayMode,
                onSelect = vm::selectProfile,
                onPing = vm::pingProfile,
            )
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
                    Text(stringResource(R.string.home_ping_profile, profile.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    PingIndicator(profile.latencyMs, ui.settings.pingDisplayMode)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null) },
            title = { Text("Полностью удалить профиль?") },
            text = {
                Text(
                    if (homeSubscription != null) "Будут удалены подписка «${homeSubscription.displayName}» и все её локации."
                    else "Будет удалён выбранный локальный профиль.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        homeSubscription?.let { vm.removeSubscription(it.id) }
                            ?: ui.activeProfile?.let { vm.removeProfile(it.id) }
                        confirmDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Полностью удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun HomeServerSwitcher(
    profiles: List<VpnProfile>,
    activeId: String?,
    switching: Boolean,
    displayMode: PingDisplayMode,
    onSelect: (String) -> Unit,
    onPing: (VpnProfile) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.70f), MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        ),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (switching) "Переключение профиля…" else "Можно менять без отключения VPN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = CircleShape) {
                    Text(
                        profiles.size.toString(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            profiles.forEachIndexed { index, profile ->
                val selected = profile.id == activeId
                Surface(
                    onClick = { onSelect(profile.id) },
                    enabled = !switching,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                Icons.Rounded.Public,
                                null,
                                Modifier.padding(9.dp).size(20.dp),
                                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${profile.protocol.name.replace('_', ' ')} · ${profile.transport.name.replace('_', ' ')} · ${profile.security.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onPing(profile) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            if (profile.latencyMs >= 0) PingIndicator(profile.latencyMs, displayMode)
                            else Text("Пинг")
                        }
                        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (index != profiles.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
        }
    }

}

@Composable
private fun ProviderSummaryCard(
    subscription: Subscription?,
    profile: VpnProfile?,
    onPingAll: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f), MaterialTheme.shapes.extraLarge),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                subscription?.displayName ?: "Локальный профиль",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onPingAll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Пинг всех", maxLines = 1)
                }
                if (subscription != null) {
                    OutlinedButton(onClick = onUpdate, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Обновить", maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.Public, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile?.name ?: "Нет доступных локаций", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        profile?.let { "${it.protocol.name.replace('_', ' ')} · ${it.transport.name.replace('_', ' ')} · ${it.security.name}" }
                            ?: subscription?.availabilityMessage.orEmpty().ifBlank { "Обновите подписку или обратитесь к провайдеру" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            subscription?.takeIf { it.hasUsageInfo }?.let { sub ->
                val used = sub.usedBytes.coerceAtLeast(0L)
                val total = sub.totalBytes
                val fraction = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Использовано ${formatBytes(used)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (total > 0) "из ${formatBytes(total)}" else "без лимита", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (subscription != null && (subscription.supportUrl.isNotBlank() || subscription.websiteUrl.isNotBlank())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (subscription.supportUrl.isNotBlank()) {
                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(subscription.supportUrl) } }) {
                            Icon(Icons.Rounded.HelpOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Поддержка")
                        }
                    }
                    if (subscription.websiteUrl.isNotBlank()) {
                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(subscription.websiteUrl) } }) {
                            Icon(Icons.Rounded.Language, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Сайт")
                        }
                    }
                }
            }
            subscription?.announcement?.takeIf { it.isNotBlank() }?.let { announcement ->
                Text(announcement, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Rounded.DeleteForever, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Полностью удалить профиль")
            }
        }
    }
}

@Composable
private fun PingIndicator(latency: Long, mode: PingDisplayMode) {
    val color = latencyColor(latency)
    val level = when {
        latency < 80 -> 4
        latency < 150 -> 3
        latency < 300 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (mode in listOf(PingDisplayMode.SCALE, PingDisplayMode.SCALE_AND_NUMBERS)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (1..4).forEach { bar ->
                    Box(
                        Modifier
                            .width(3.dp)
                            .height((4 + bar * 3).dp)
                            .background(if (bar <= level) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), CircleShape)
                    )
                }
            }
        }
        if (mode in listOf(PingDisplayMode.NUMBERS, PingDisplayMode.SCALE_AND_NUMBERS)) {
            Text("$latency мс", color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        if (mode == PingDisplayMode.DOTS) {
            repeat(4) { index ->
                Box(Modifier.size(5.dp).background(if (index < level) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), CircleShape))
            }
        }
    }
}

@Composable
private fun HomeProfilePill(profileName: String?, endpoint: String?) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
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
                    profileName ?: stringResource(R.string.no_profile_selected),
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
                    stringResource(R.string.connection_error),
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
                    Text(stringResource(R.string.retry))
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
            TrafficCard(Modifier.weight(1f), stringResource(R.string.home_download), bytesIn)
            TrafficCard(Modifier.weight(1f), stringResource(R.string.home_upload), bytesOut)
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
                    stringResource(R.string.home_connected_summary, connectedFor, formatBytes(total)),
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
