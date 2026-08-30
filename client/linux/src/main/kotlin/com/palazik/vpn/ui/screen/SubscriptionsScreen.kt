package com.palazik.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.Subscription
import com.palazik.vpn.ui.theme.miuixSpringScroll
import com.palazik.vpn.ui.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ln
import kotlin.math.pow

/** Human-readable byte count, e.g. 10737418240 → "10.0 GB". */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[exp - 1])
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionsScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var subName by remember { mutableStateOf("") }
    var subUrl  by remember { mutableStateOf("") }
    var deleteSub by remember { mutableStateOf<Subscription?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        Column(
            Modifier.fillMaxWidth(),
        ) {
            Text("Subscriptions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            AnimatedVisibility(visible = ui.subscriptions.isNotEmpty()) {
                Text(
                    "${ui.subscriptions.size} active",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedVisibility(visible = ui.subscriptions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { vm.updateAllSubscriptions() },
                        enabled = !ui.isUpdatingSubscriptions,
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Update All")
                    }
                }
                FilledTonalButton(onClick = { showAdd = true }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = ui.subscriptions.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "subs_content",
        ) { isEmpty ->
            if (isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.Subscriptions, null,
                            Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No subscriptions yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Add a subscription URL to import profiles automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(24.dp))
                        FilledTonalButton(onClick = { showAdd = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Subscription", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.miuixSpringScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.subscriptions, key = { it.id }) { sub ->
                        // Fade only — no slide
                        AnimatedVisibility(
                            visible = true,
                            enter   = fadeIn(tween(200)),
                        ) {
                            SubscriptionCard(
                                sub      = sub,
                                isUpdating = ui.isUpdatingSubscriptions || sub.id in ui.updatingSubscriptionIds,
                                autoUpdateEnabled = ui.settings.autoUpdateSubscriptions,
                                updateIntervalHours = ui.settings.subscriptionUpdateIntervalHours,
                                onUpdate = { vm.updateSubscription(sub) },
                                onChooseBest = { vm.chooseBestProfileForSubscription(sub) },
                                onDelete = { deleteSub = sub },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    // ── Add dialog ───────────────────────────────────────────────────────────
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; subName = ""; subUrl = "" },
            title = { Text("Add Subscription") },
            icon  = { Icon(Icons.Rounded.Subscriptions, null) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value         = subName,
                        onValueChange = { subName = it },
                        label         = { Text("Name") },
                        placeholder   = { Text("My Server") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                    )
                    OutlinedTextField(
                        value         = subUrl,
                        onValueChange = { subUrl = it },
                        label         = { Text("Subscription URL") },
                        placeholder   = { Text("https://…") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick  = {
                        if (subUrl.isNotBlank()) {
                            vm.addSubscription(subName.ifBlank { "Subscription" }, subUrl.trim())
                            showAdd = false; subName = ""; subUrl = ""
                        }
                    },
                    enabled = subUrl.isNotBlank(),
                ) { Text("Add & Fetch") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; subName = ""; subUrl = "" }) { Text("Cancel") }
            },
        )
    }

    deleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deleteSub = null },
            title = { Text("Delete Subscription") },
            icon = { Icon(Icons.Rounded.Delete, null) },
            text = { Text("Delete \"${sub.name}\" and all profiles from it?") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeSubscription(sub.id)
                        deleteSub = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSub = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionCard(
    sub: Subscription,
    isUpdating: Boolean,
    autoUpdateEnabled: Boolean,
    updateIntervalHours: Long,
    onUpdate: () -> Unit,
    onChooseBest: () -> Unit,
    onDelete: () -> Unit,
) {
    val sdf = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val expDateFmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val cardColor by animateColorAsState(
        targetValue = if (isUpdating) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220),
        label = "subscription_card_color_${sub.id}",
    )
    val refreshRotation = if (isUpdating) {
        val rotation by rememberInfiniteTransition(label = "subscription_refresh_${sub.id}")
            .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "subscription_refresh_rotation_${sub.id}",
        )
        rotation
    } else {
        0f
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(240)),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        shape    = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Rounded.Subscriptions,
                        null,
                        Modifier.padding(8.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(sub.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub.url,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = CircleShape,
                ) {
                    AnimatedContent(
                        targetState = sub.profileCount,
                        transitionSpec = { fadeIn(tween(140)) + scaleIn() togetherWith fadeOut(tween(90)) + scaleOut() },
                        label = "sub_profile_count_${sub.id}",
                    ) { count ->
                        Text(
                            "$count profiles",
                            modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style      = MaterialTheme.typography.labelSmall,
                            color      = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (sub.lastUpdated > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape,
                    ) {
                        Text(
                            "Updated ${sdf.format(Date(sub.lastUpdated))}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (sub.hasUsageInfo) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.13f),
                        shape = CircleShape,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.DataUsage, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                buildString {
                                    append(formatBytes(sub.usedBytes.coerceAtLeast(0)))
                                    if (sub.totalBytes > 0) append(" / ${formatBytes(sub.totalBytes)}")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
                if (sub.hasExpiry) {
                    val daysLeft = ((sub.expireEpochSec * 1000 - System.currentTimeMillis()) / 86_400_000L)
                    val expiring = daysLeft in 0L..7L
                    val expired  = daysLeft < 0
                    val tint = when {
                        expired  -> MaterialTheme.colorScheme.error
                        expiring -> MaterialTheme.colorScheme.tertiary
                        else     -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(color = tint.copy(alpha = 0.13f), shape = CircleShape) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(13.dp), tint = tint)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                when {
                                    expired  -> "Expired"
                                    else     -> "${expDateFmt.format(Date(sub.expireEpochSec * 1000))} ($daysLeft d)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                            )
                        }
                    }
                }
                Surface(
                    color = if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Schedule,
                            null,
                            Modifier.size(13.dp),
                            tint = if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (autoUpdateEnabled) "Auto ${updateIntervalHours}h" else "Manual",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autoUpdateEnabled) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(
                color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )
            Spacer(Modifier.height(2.dp))

            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onChooseBest,
                    enabled = !isUpdating && sub.profileCount > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Rounded.Speed, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Best", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    AnimatedContent(
                        targetState = isUpdating,
                        transitionSpec = { fadeIn(tween(120)) + scaleIn() togetherWith fadeOut(tween(90)) + scaleOut() },
                        label = "sub_update_icon_${sub.id}",
                    ) { updating ->
                        if (updating) {
                            Icon(
                                Icons.Rounded.Refresh,
                                null,
                                Modifier
                                    .size(16.dp)
                                    .graphicsLayer { rotationZ = refreshRotation },
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    AnimatedContent(
                        targetState = isUpdating,
                        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(90)) },
                        label = "sub_update_label_${sub.id}",
                    ) { updating ->
                        Text(if (updating) "Updating" else "Update", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(
                    onClick        = onDelete,
                    enabled        = !isUpdating,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors         = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
