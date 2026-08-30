package com.palazik.vpn.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import com.palazik.vpn.ui.viewmodel.MainViewModel

private val DarkModeOptions  = DarkModePreference.values().toList()
private val AppThemeOptions  = AppTheme.values().toList()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleScreen(vm: MainViewModel, onBack: () -> Unit) {
    Md3StyleScreen(vm, onBack)
}

// ── MD3 Style Screen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Md3StyleScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Top bar with back button ─────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
            Text("Style", style = MaterialTheme.typography.headlineSmall)
        }

        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            // ── Animations ───────────────────────────────────────────────────
            StyleSection(title = "Animations") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Miuix animations", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Animated theme and dark-mode transitions, on top of Material 3.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = ui.designSystem == DesignSystem.MIUIX,
                        onCheckedChange = { vm.setDesignSystem(if (it) DesignSystem.MIUIX else DesignSystem.MD3) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Dark Mode ────────────────────────────────────────────────────
            StyleSection(title = "Dark Mode") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DarkModeOptions.forEachIndexed { idx, pref ->
                        SegmentedButton(
                            shape    = SegmentedButtonDefaults.itemShape(idx, DarkModeOptions.size),
                            selected = ui.darkMode == pref,
                            onClick  = { vm.setDarkMode(pref) },
                            label    = {
                                Text(when (pref) {
                                    DarkModePreference.SYSTEM       -> "System"
                                    DarkModePreference.ALWAYS_LIGHT -> "Light"
                                    DarkModePreference.ALWAYS_DARK  -> "Dark"
                                })
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Color Theme ──────────────────────────────────────────────────
            StyleSection(title = "Color Theme") {
                Text(
                    "Color palette used across the app.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppThemeOptions.forEach { theme ->
                        StyleThemeRow(theme, isSelected = ui.appTheme == theme) {
                            vm.setAppTheme(theme)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StyleSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style    = MaterialTheme.typography.labelLarge,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            content()
        }
    }
}

private fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.CYBER   -> "Cyber (Dark-first)"
    AppTheme.OCEAN   -> "Ocean"
    AppTheme.FOREST  -> "Forest"
    AppTheme.SUNSET  -> "Sunset"
    AppTheme.ROSE    -> "Rose"
    AppTheme.VIOLET  -> "Violet"
    AppTheme.AMOLED  -> "AMOLED (pure black)"
}

@Composable
private fun StyleThemeRow(theme: AppTheme, isSelected: Boolean, onClick: () -> Unit) {
    val label = themeLabel(theme)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        RadioButton(selected = isSelected, onClick = onClick)
    }
}
