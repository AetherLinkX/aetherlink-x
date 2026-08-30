package com.palazik.vpn.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palazik.vpn.R
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.data.model.PingMode
import com.palazik.vpn.data.model.SplitTunnelMode
import com.palazik.vpn.ui.theme.LocalDesignSystem
import com.palazik.vpn.ui.theme.miuixSpringScroll
import com.palazik.vpn.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

private val PingModeOptions             = PingMode.values().toList()
private val SubscriptionIntervalOptions = listOf(2L, 6L, 12L, 24L)

// ── Routes for the settings sub-screens ─────────────────────────────────────
object SettingsRoutes {
    const val STYLE        = "style"
    const val LANGUAGE     = "settings/language"
    const val CONNECTION   = "settings/connection"
    const val DNS          = "settings/dns"
    const val ROUTING      = "settings/routing"
    const val GEO          = "settings/geo"
    const val SUBSCRIPTION  = "settings/subscriptions"
    const val SPLIT        = "settings/split"
    const val BACKUP       = "settings/backup"
    const val STARTUP      = "settings/startup"
    const val DIAGNOSTICS  = "settings/diagnostics"
    const val ABOUT        = "settings/about"
}

private data class SettingsEntry(
    val route: String,
    @androidx.annotation.StringRes val title: Int,
    @androidx.annotation.StringRes val summary: Int,
    val icon: ImageVector,
)

private data class SettingsGroup(@androidx.annotation.StringRes val title: Int, val entries: List<SettingsEntry>)

private val SettingsGroups = listOf(
    SettingsGroup(R.string.group_appearance, listOf(
        SettingsEntry(SettingsRoutes.STYLE, R.string.settings_style, R.string.settings_style_summary, Icons.Rounded.Palette),
        SettingsEntry(SettingsRoutes.LANGUAGE, R.string.settings_language, R.string.settings_language_summary, Icons.Rounded.Language),
    )),
    SettingsGroup(R.string.group_connection, listOf(
        SettingsEntry(SettingsRoutes.CONNECTION, R.string.settings_connection, R.string.settings_connection_summary, Icons.Rounded.NetworkCheck),
        SettingsEntry(SettingsRoutes.DNS, R.string.settings_dns, R.string.settings_dns_summary, Icons.Rounded.Dns),
        SettingsEntry(SettingsRoutes.ROUTING, R.string.settings_routing, R.string.settings_routing_summary, Icons.Rounded.AltRoute),
        SettingsEntry(SettingsRoutes.GEO, R.string.settings_geo, R.string.settings_geo_summary, Icons.Rounded.Public),
    )),
    SettingsGroup(R.string.group_profiles_data, listOf(
        SettingsEntry(SettingsRoutes.SUBSCRIPTION, R.string.settings_subscriptions, R.string.settings_subscriptions_summary, Icons.Rounded.Subscriptions),
        SettingsEntry(SettingsRoutes.SPLIT, R.string.settings_split, R.string.settings_split_summary, Icons.Rounded.Apps),
        SettingsEntry(SettingsRoutes.BACKUP, R.string.settings_backup, R.string.settings_backup_summary, Icons.Rounded.Backup),
    )),
    SettingsGroup(R.string.group_system, listOf(
        SettingsEntry(SettingsRoutes.STARTUP, R.string.settings_startup, R.string.settings_startup_summary, Icons.Rounded.PowerSettingsNew),
        SettingsEntry(SettingsRoutes.DIAGNOSTICS, R.string.settings_diagnostics, R.string.settings_diagnostics_summary, Icons.Rounded.BugReport),
        SettingsEntry(SettingsRoutes.ABOUT, R.string.settings_about, R.string.settings_about_summary, Icons.Rounded.Info),
    )),
)

// ─────────────────────────────────────────────────────────────────────────────
// Settings hub
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    Md3SettingsHub(onNavigate)
}

@Composable
private fun MiuixSettingsHub(onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SmallTitle(text = stringResource(R.string.settings_title))
        SettingsGroups.forEach { group ->
            SmallTitle(text = stringResource(group.title))
            MiuixCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    group.entries.forEach { entry ->
                        ArrowPreference(
                            title = stringResource(entry.title),
                            summary = stringResource(entry.summary),
                            onClick = { onNavigate(entry.route) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Md3SettingsHub(onNavigate: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .miuixSpringScroll()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        SettingsGroups.forEach { group ->
            Text(
                stringResource(group.title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    group.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent   = { Text(stringResource(entry.title)) },
                            supportingContent = { Text(stringResource(entry.summary)) },
                            leadingContent    = { Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent   = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            modifier = Modifier.clickable { onNavigate(entry.route) },
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-screen scaffold (shared by both design systems)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .miuixSpringScroll()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-screens
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_connection), onBack) {
        SettingsCard {
            Text("Ping Test Mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "TCP — raw socket connect (fastest, most accurate, default).\n" +
                "GET / HEAD — Cloudflare request through the running VPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PingModeOptions.forEachIndexed { idx, mode ->
                    SegmentedButton(
                        shape    = SegmentedButtonDefaults.itemShape(idx, PingModeOptions.size),
                        selected = ui.pingMode == mode,
                        onClick  = { vm.setPingMode(mode) },
                        label    = {
                            Text(when (mode) {
                                PingMode.TCP       -> "TCP"
                                PingMode.HTTP_GET  -> "GET"
                                PingMode.HTTP_HEAD -> "HEAD"
                            })
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun DnsSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_dns), onBack) {
        SettingsCard {
            var tunDns    by remember(ui.settings.dnsServers) { mutableStateOf(ui.settings.dnsServers.joinToString(", ")) }
            var remoteDns by remember(ui.settings.remoteDns)  { mutableStateOf(ui.settings.remoteDns) }
            var directDns by remember(ui.settings.directDns)  { mutableStateOf(ui.settings.directDns) }

            OutlinedTextField(
                value = tunDns, onValueChange = { tunDns = it },
                label = { Text("VPN DNS servers") },
                supportingText = { Text("Comma separated") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = remoteDns, onValueChange = { remoteDns = it },
                label = { Text("Remote DNS") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = directDns, onValueChange = { directDns = it },
                label = { Text("Direct DNS") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    vm.updateAppSettings(ui.settings.copy(
                        dnsServers = tunDns.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                        remoteDns  = remoteDns,
                        directDns  = directDns,
                    ))
                }) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save DNS")
                }
            }
        }
    }
}

@Composable
fun RoutingSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_routing), onBack) {
        SettingsCard { RoutingSettingsContent(vm, ui.settings) }
    }
}

@Composable
fun GeoFilesSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_geo), onBack) {
        SettingsCard {
            var geoip   by remember(ui.settings.geoipUrl)   { mutableStateOf(ui.settings.geoipUrl) }
            var geosite by remember(ui.settings.geositeUrl) { mutableStateOf(ui.settings.geositeUrl) }

            Text(
                "Override the bundled geoip.dat / geosite.dat with files from a URL. " +
                    "Leave blank to keep the built-in data. Reconnect to apply.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = geoip, onValueChange = { geoip = it },
                label = { Text("geoip.dat URL") }, placeholder = { Text("https://…/geoip.dat") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geosite, onValueChange = { geosite = it },
                label = { Text("geosite.dat URL") }, placeholder = { Text("https://…/geosite.dat") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    vm.updateAppSettings(ui.settings.copy(geoipUrl = geoip.trim(), geositeUrl = geosite.trim()))
                }) {
                    Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    vm.updateAppSettings(ui.settings.copy(geoipUrl = geoip.trim(), geositeUrl = geosite.trim()))
                    vm.updateGeoFiles()
                }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Update now")
                }
            }
        }
    }
}

@Composable
fun SubscriptionSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_subscriptions), onBack) {
        SettingsCard { StartupAutoUpdateContent(vm, ui.settings) }
        SettingsCard { SubscriptionUaContent(vm, ui.settings) }
    }
}

@Composable
fun SplitTunnelSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_split), onBack) {
        SettingsCard { SplitTunnelContent(vm, ui.settings, ui.installedApps) }
    }
}

@Composable
fun BackupSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_backup), onBack) {
        SettingsCard { BackupSettingsContent(vm) }
    }
}

@Composable
fun StartupSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(stringResource(R.string.settings_startup), onBack) {
        SettingsCard {
            SettingRow(
                title = "Auto-connect on boot",
                subtitle = "Starts the selected profile after reboot when VPN permission is already granted.",
                checked = ui.settings.startOnBoot,
                onChange = { vm.updateAppSettings(ui.settings.copy(startOnBoot = it)) },
            )
        }
        SettingsCard { StartupAutoUpdateContent(vm, ui.settings) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val diagnostics by vm.diagnostics.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(diagnostics.joinToString("\n").toByteArray())
                    }
                }.isSuccess
            }
            vm.showSnack(if (ok) "Logs saved" else "Save failed")
        }
    }

    SettingsScaffold(stringResource(R.string.settings_diagnostics), onBack) {
        SettingsCard {
            if (diagnostics.isEmpty()) {
                Text("No connection events yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    diagnostics.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = { saveLauncher.launch("aetherlink-x-logs.txt") },
                    enabled = diagnostics.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.SaveAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save to file")
                }
                Button(
                    onClick = { clipboard.setText(AnnotatedString(diagnostics.joinToString("\n"))) },
                    enabled = diagnostics.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Logs")
                }
            }
        }
    }
}

@Composable
fun LanguageSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_language), onBack) {
        SettingsCard {
            val options = listOf(
                com.palazik.vpn.ui.locale.AppLanguage.ENGLISH to "English",
                com.palazik.vpn.ui.locale.AppLanguage.RUSSIAN to "Русский",
            )
            options.forEachIndexed { index, (lang, label) ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (ui.language != lang) {
                                vm.setLanguage(lang)
                                // Resources bind at attach time — recreate to apply the locale.
                                (context as? androidx.activity.ComponentActivity)?.recreate()
                            }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = ui.language == lang, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    SettingsScaffold(stringResource(R.string.settings_about), onBack) {
        SettingsCard {
            ListItem(
                headlineContent   = { Text("AetherLink X") },
                supportingContent = { Text("V${stringResource(R.string.app_version)} • based on xStarRay (GPLv3)") },
                leadingContent    = { Icon(Icons.Rounded.Info, null) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { vm.checkForUpdate() }, enabled = !ui.checkingUpdate) {
                    if (ui.checkingUpdate) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Check for updates")
                }
            }
        }
    }

    ui.updateAvailable?.let { info ->
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            icon  = { Icon(Icons.Rounded.SystemUpdate, null) },
            title = { Text("Update available") },
            text  = { Text("Version ${info.version} is available. You're on V${stringResource(R.string.app_version)}.") },
            confirmButton = {
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(info.url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                    vm.dismissUpdate()
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("Later") } },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared section content
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitTunnelContent(
    vm: MainViewModel,
    settings: AppSettings,
    installedApps: List<com.palazik.vpn.data.model.InstalledApp>,
) {
    var showAppPicker by remember { mutableStateOf(false) }
    val onlyMode = settings.splitTunnelMode == SplitTunnelMode.ONLY

    Text("Mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !onlyMode,
            onClick = { vm.updateAppSettings(settings.copy(splitTunnelMode = SplitTunnelMode.BYPASS)) },
            label = { Text("Apps bypass VPN") },
            leadingIcon = if (!onlyMode) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null,
        )
        FilterChip(
            selected = onlyMode,
            onClick = { vm.updateAppSettings(settings.copy(splitTunnelMode = SplitTunnelMode.ONLY)) },
            label = { Text("Only these use VPN") },
            leadingIcon = if (onlyMode) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null,
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))

    Text(
        if (onlyMode) "${settings.bypassPackages.size} apps use the VPN exclusively"
        else "${settings.bypassPackages.size} apps bypass VPN",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (settings.bypassPackages.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            settings.bypassPackages.take(6).forEach { pkg ->
                AssistChip(
                    onClick = {},
                    label = { Text(pkg, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        IconButton(
                            onClick = { vm.updateAppSettings(settings.copy(bypassPackages = settings.bypassPackages - pkg)) },
                            modifier = Modifier.size(24.dp),
                        ) { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) }
                    },
                )
            }
            if (settings.bypassPackages.size > 6) {
                Text("+${settings.bypassPackages.size - 6} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { vm.updateAppSettings(settings.copy(bypassPackages = emptyList())) }) {
            Icon(Icons.Rounded.Clear, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Clear")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { showAppPicker = true }) {
            Icon(Icons.Rounded.Apps, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose Apps")
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            apps = installedApps,
            selected = settings.bypassPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave = { selected ->
                vm.updateAppSettings(settings.copy(bypassPackages = selected.sorted()))
                showAppPicker = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartupAutoUpdateContent(vm: MainViewModel, settings: AppSettings) {
    SettingRow(
        title = "Auto-update subscriptions",
        subtitle = "Refreshes subscriptions every ${settings.subscriptionUpdateIntervalHours}h when network is available.",
        checked = settings.autoUpdateSubscriptions,
        onChange = { vm.updateAppSettings(settings.copy(autoUpdateSubscriptions = it)) },
    )
    AnimatedVisibility(visible = settings.autoUpdateSubscriptions) {
        Column {
            Spacer(Modifier.height(12.dp))
            Text("Update interval", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubscriptionIntervalOptions.forEach { hours ->
                    FilterChip(
                        selected = settings.subscriptionUpdateIntervalHours == hours,
                        onClick = { vm.updateAppSettings(settings.copy(subscriptionUpdateIntervalHours = hours)) },
                        label = { Text("${hours}h") },
                        leadingIcon = if (settings.subscriptionUpdateIntervalHours == hours) {
                            { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutingSettingsContent(vm: MainViewModel, settings: AppSettings) {
    var directDomains by remember(settings.customDirectDomains) {
        mutableStateOf(settings.customDirectDomains.joinToString(", "))
    }
    var blockedDomains by remember(settings.customBlockedDomains) {
        mutableStateOf(settings.customBlockedDomains.joinToString(", "))
    }
    var fragPackets  by remember(settings.fragmentPackets)  { mutableStateOf(settings.fragmentPackets) }
    var fragLength   by remember(settings.fragmentLength)   { mutableStateOf(settings.fragmentLength) }
    var fragInterval by remember(settings.fragmentInterval) { mutableStateOf(settings.fragmentInterval) }

    // Routing preset (#2) — applies immediately
    Text("Routing mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.palazik.vpn.data.model.RoutingMode.values().forEach { mode ->
            val label = when (mode) {
                com.palazik.vpn.data.model.RoutingMode.RULE_BASED -> "Rule-based"
                com.palazik.vpn.data.model.RoutingMode.GLOBAL     -> "Global"
                com.palazik.vpn.data.model.RoutingMode.BYPASS_LAN -> "Bypass LAN"
            }
            FilterChip(
                selected = settings.routingMode == mode,
                onClick = { vm.updateAppSettings(settings.copy(routingMode = mode)) },
                label = { Text(label) },
                leadingIcon = if (settings.routingMode == mode) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))

    // Domain strategy (#7) — applies immediately
    Text("Domain strategy", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        com.palazik.vpn.data.model.DomainStrategy.values().forEach { ds ->
            FilterChip(
                selected = settings.domainStrategy == ds,
                onClick = { vm.updateAppSettings(settings.copy(domainStrategy = ds)) },
                label = { Text(ds.name) },
                leadingIcon = if (settings.domainStrategy == ds) {
                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))

    SettingRow(
        title = "FakeDNS",
        subtitle = "Resolve via an internal fake-IP pool — faster routing and no DNS leaks",
        checked = settings.enableFakeDns,
        onChange = { vm.updateAppSettings(settings.copy(enableFakeDns = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Block ads",
        subtitle = "Drop requests matching the ad/tracker domain list",
        checked = settings.blockAds,
        onChange = { vm.updateAppSettings(settings.copy(blockAds = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Bypass China",
        subtitle = "Route mainland China domains & IPs directly (outside the proxy)",
        checked = settings.bypassChina,
        onChange = { vm.updateAppSettings(settings.copy(bypassChina = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Route IPv6 through tunnel",
        subtitle = "Off forces IPv4-only dialling. IPv6 is always captured to prevent leaks.",
        checked = settings.enableIpv6,
        onChange = { vm.updateAppSettings(settings.copy(enableIpv6 = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Kill switch (lockdown)",
        subtitle = "Block traffic while the tunnel isn't ready. For full effect also enable Android's Always-on VPN.",
        checked = settings.lockdownMode,
        onChange = { vm.updateAppSettings(settings.copy(lockdownMode = it)) },
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = directDomains,
        onValueChange = { directDomains = it },
        label = { Text("Direct domains") },
        supportingText = { Text("Comma separated, e.g. geosite:google, example.com") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = blockedDomains,
        onValueChange = { blockedDomains = it },
        label = { Text("Blocked domains") },
        supportingText = { Text("Comma separated") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Text("TLS fragment (anti-DPI)", style = MaterialTheme.typography.titleSmall)
    Text(
        "Global parameters. Enable per profile in its edit screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    OutlinedTextField(
        value = fragPackets, onValueChange = { fragPackets = it },
        label = { Text("Packets") }, supportingText = { Text("e.g. tlshello or 1-3") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fragLength, onValueChange = { fragLength = it },
            label = { Text("Length") }, modifier = Modifier.weight(1f), singleLine = true,
        )
        OutlinedTextField(
            value = fragInterval, onValueChange = { fragInterval = it },
            label = { Text("Interval") }, modifier = Modifier.weight(1f), singleLine = true,
        )
    }

    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = {
            vm.updateAppSettings(settings.copy(
                customDirectDomains = directDomains.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                customBlockedDomains = blockedDomains.split(",", "\n").map { it.trim() }.filter { it.isNotBlank() },
                fragmentPackets  = fragPackets.trim().ifBlank { "tlshello" },
                fragmentLength   = fragLength.trim().ifBlank { "100-200" },
                fragmentInterval = fragInterval.trim().ifBlank { "10-20" },
            ))
        }) {
            Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save Routing")
        }
    }
}

@Composable
private fun SubscriptionUaContent(vm: MainViewModel, settings: AppSettings) {
    var ua by remember(settings.subscriptionUserAgent) { mutableStateOf(settings.subscriptionUserAgent) }
    Text("Subscription fetch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
    OutlinedTextField(
        value = ua,
        onValueChange = { ua = it },
        label = { Text("Subscription User-Agent") },
        supportingText = { Text("Some providers serve configs based on this header") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = { vm.updateAppSettings(settings.copy(subscriptionUserAgent = ua.trim())) }) {
            Icon(Icons.Rounded.Save, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save")
        }
    }
}

@Composable
private fun BackupSettingsContent(vm: MainViewModel) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(vm.exportProfilesText().toByteArray())
                    }
                }.isSuccess
            }
            vm.showSnack(if (ok) "Profiles exported" else "Export failed")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                }.getOrNull()
            }
            if (body != null) vm.importProfilesText(body) else vm.showSnack("Could not read file")
        }
    }

    Text(
        "Export all profiles to a .txt file (alxclient:// links), or import them back on another device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/plain", "*/*")) }) {
            Icon(Icons.Rounded.FileUpload, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { exportLauncher.launch("aetherlink-x-profiles.txt") }) {
            Icon(Icons.Rounded.FileDownload, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Export")
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<com.palazik.vpn.data.model.InstalledApp>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var query  by remember { mutableStateOf("") }
    var picked by remember(selected) { mutableStateOf(selected) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title  = { Text("Bypass Apps") },
        icon   = { Icon(Icons.Rounded.Apps, null) },
        text   = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (app.packageName in picked) picked - app.packageName else picked + app.packageName
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.packageName in picked,
                                onCheckedChange = { checked ->
                                    picked = if (checked) picked + app.packageName else picked - app.packageName
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(picked.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
