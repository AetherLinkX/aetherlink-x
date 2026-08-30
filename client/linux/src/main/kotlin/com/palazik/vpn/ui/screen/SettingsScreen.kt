package com.palazik.vpn.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.palazik.vpn.AppDirs
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.PingMode
import com.palazik.vpn.ui.i18n.AppLanguage
import com.palazik.vpn.ui.i18n.LocalStrings
import com.palazik.vpn.ui.i18n.Strings
import com.palazik.vpn.ui.theme.miuixSpringScroll
import com.palazik.vpn.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
    const val BACKUP       = "settings/backup"
    const val STARTUP      = "settings/startup"
    const val DIAGNOSTICS  = "settings/diagnostics"
    const val ABOUT        = "settings/about"
}

private data class SettingsEntry(
    val route: String,
    val title: (Strings) -> String,
    val summary: (Strings) -> String,
    val icon: ImageVector,
)

private data class SettingsGroup(val title: (Strings) -> String, val entries: List<SettingsEntry>)

private val SettingsGroups = listOf(
    SettingsGroup({ it.groupAppearance }, listOf(
        SettingsEntry(SettingsRoutes.STYLE, { it.settingsStyle }, { it.settingsStyleSummary }, Icons.Rounded.Palette),
        SettingsEntry(SettingsRoutes.LANGUAGE, { it.settingsLanguage }, { it.settingsLanguageSummary }, Icons.Rounded.Language),
    )),
    SettingsGroup({ it.groupConnection }, listOf(
        SettingsEntry(SettingsRoutes.CONNECTION, { it.settingsConnection }, { it.settingsConnectionSummary }, Icons.Rounded.NetworkCheck),
        SettingsEntry(SettingsRoutes.DNS, { it.settingsDns }, { it.settingsDnsSummary }, Icons.Rounded.Dns),
        SettingsEntry(SettingsRoutes.ROUTING, { it.settingsRouting }, { it.settingsRoutingSummary }, Icons.Rounded.AltRoute),
        SettingsEntry(SettingsRoutes.GEO, { it.settingsGeo }, { it.settingsGeoSummary }, Icons.Rounded.Public),
    )),
    SettingsGroup({ it.groupProfilesData }, listOf(
        SettingsEntry(SettingsRoutes.SUBSCRIPTION, { it.settingsSubscriptions }, { it.settingsSubscriptionsSummary }, Icons.Rounded.Subscriptions),
        SettingsEntry(SettingsRoutes.BACKUP, { it.settingsBackup }, { it.settingsBackupSummary }, Icons.Rounded.Backup),
    )),
    SettingsGroup({ it.groupSystem }, listOf(
        SettingsEntry(SettingsRoutes.STARTUP, { it.settingsStartup }, { it.settingsStartupSummary }, Icons.Rounded.PowerSettingsNew),
        SettingsEntry(SettingsRoutes.DIAGNOSTICS, { it.settingsDiagnostics }, { it.settingsDiagnosticsSummary }, Icons.Rounded.BugReport),
        SettingsEntry(SettingsRoutes.ABOUT, { it.settingsAbout }, { it.settingsAboutSummary }, Icons.Rounded.Info),
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
private fun Md3SettingsHub(onNavigate: (String) -> Unit) {
    val strings = LocalStrings.current
    Column(
        Modifier
            .fillMaxSize()
            .miuixSpringScroll()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(strings.settingsTitle, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        SettingsGroups.forEach { group ->
            Text(
                group.title(strings),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    group.entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent   = { Text(entry.title(strings)) },
                            supportingContent = { Text(entry.summary(strings)) },
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
// Sub-screen scaffold
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
// Desktop file dialogs (replace Android's SAF document pickers)
// ─────────────────────────────────────────────────────────────────────────────

private fun saveFileDialog(suggestedName: String): File? {
    val dialog = FileDialog(null as Frame?, "Save file", FileDialog.SAVE)
    dialog.file = suggestedName
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

private fun openFileDialog(): File? {
    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-screens
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(LocalStrings.current.settingsConnection, onBack) {
        SettingsCard {
            Text("Connection mode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "Proxy — xray exposes SOCKS5 127.0.0.1:10808 / HTTP 127.0.0.1:10809 (no root).\n" +
                "TUN — full-device tunnel like Android, via tun2socks (asks for root with pkexec).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            val tunMode = ui.settings.tunMode
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    shape    = SegmentedButtonDefaults.itemShape(0, 2),
                    selected = !tunMode,
                    onClick  = { vm.updateAppSettings(ui.settings.copy(tunMode = false)) },
                    label    = { Text("Proxy") },
                )
                SegmentedButton(
                    shape    = SegmentedButtonDefaults.itemShape(1, 2),
                    selected = tunMode,
                    onClick  = { vm.updateAppSettings(ui.settings.copy(tunMode = true)) },
                    label    = { Text("TUN (full device)") },
                )
            }
            AnimatedVisibility(visible = !tunMode) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    SettingRow(
                        title = "Set system proxy automatically",
                        subtitle = "Applies the desktop proxy (GNOME/KDE) while connected and restores it after.",
                        checked = ui.settings.systemProxy,
                        onChange = { vm.updateAppSettings(ui.settings.copy(systemProxy = it)) },
                    )
                }
            }
        }
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
    SettingsScaffold(LocalStrings.current.settingsDns, onBack) {
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
    SettingsScaffold(LocalStrings.current.settingsRouting, onBack) {
        SettingsCard { RoutingSettingsContent(vm, ui.settings) }
    }
}

@Composable
fun GeoFilesSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(LocalStrings.current.settingsGeo, onBack) {
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
    SettingsScaffold(LocalStrings.current.settingsSubscriptions, onBack) {
        SettingsCard { StartupAutoUpdateContent(vm, ui.settings) }
        SettingsCard { SubscriptionUaContent(vm, ui.settings) }
    }
}

@Composable
fun BackupSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    SettingsScaffold(LocalStrings.current.settingsBackup, onBack) {
        SettingsCard { BackupSettingsContent(vm) }
    }
}

@Composable
fun StartupSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(LocalStrings.current.settingsStartup, onBack) {
        SettingsCard {
            SettingRow(
                title = "Auto-connect on login",
                subtitle = "Launch AetherLink X and connect the selected profile when you log in.",
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
    val scope = rememberCoroutineScope()

    SettingsScaffold(LocalStrings.current.settingsDiagnostics, onBack) {
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
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val target = saveFileDialog("aetherlink-x-logs.txt")
                                    target?.writeText(diagnostics.joinToString("\n"))
                                    target != null
                                }.getOrDefault(false)
                            }
                            vm.showSnack(if (ok) "Logs saved" else "Save failed")
                        }
                    },
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
    SettingsScaffold(LocalStrings.current.settingsLanguage, onBack) {
        SettingsCard {
            val options = listOf(
                AppLanguage.ENGLISH to "English",
                AppLanguage.RUSSIAN to "Русский",
            )
            options.forEachIndexed { index, (lang, label) ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (ui.language != lang) vm.setLanguage(lang) }
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

@Composable
fun AboutSettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    SettingsScaffold(LocalStrings.current.settingsAbout, onBack) {
        SettingsCard {
            ListItem(
                headlineContent   = { Text("AetherLink X") },
                supportingContent = { Text("V${AppDirs.APP_VERSION} • based on xStarRay (GPLv3)") },
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
            text  = { Text("Version ${info.version} is available. You're on V${AppDirs.APP_VERSION}.") },
            confirmButton = {
                Button(onClick = {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(info.url)) }
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
private fun StartupAutoUpdateContent(vm: MainViewModel, settings: AppSettings) {
    SettingRow(
        title = "Auto-update subscriptions",
        subtitle = "Refreshes subscriptions every ${settings.subscriptionUpdateIntervalHours}h while the app is running.",
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

    // Routing preset — applies immediately
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

    // Domain strategy — applies immediately
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
        subtitle = "Off forces IPv4-only dialling. In TUN mode IPv6 is always captured to prevent leaks.",
        checked = settings.enableIpv6,
        onChange = { vm.updateAppSettings(settings.copy(enableIpv6 = it)) },
    )
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    SettingRow(
        title = "Kill switch (lockdown)",
        subtitle = "TUN mode: keep blackhole routes so traffic is dropped if the tunnel dies.",
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
    val scope = rememberCoroutineScope()

    Text(
        "Export all profiles to a .txt file (alxclient:// links), or import them back on another device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = {
            scope.launch {
                val body = withContext(Dispatchers.IO) {
                    runCatching { openFileDialog()?.readText() }.getOrNull()
                }
                if (body != null) vm.importProfilesText(body) else vm.showSnack("Could not read file")
            }
        }) {
            Icon(Icons.Rounded.FileUpload, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Import")
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        saveFileDialog("aetherlink-x-profiles.txt")
                            ?.writeText(vm.exportProfilesText()) != null
                    }.getOrDefault(false)
                }
                vm.showSnack(if (ok) "Profiles exported" else "Export failed")
            }
        }) {
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
