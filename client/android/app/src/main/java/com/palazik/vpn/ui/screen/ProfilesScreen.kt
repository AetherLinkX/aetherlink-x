package com.palazik.vpn.ui.screen

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.codec.QrCodec
import com.palazik.vpn.data.model.*
import com.palazik.vpn.ui.theme.miuixSpringScroll
import com.palazik.vpn.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    vm: MainViewModel,
    onOpenSubscriptions: () -> Unit = {},
    onOpenJson: (String) -> Unit = {},
) {
    val ui        by vm.profilesUi.collectAsStateWithLifecycle()
    val context   = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val keyboard  = LocalSoftwareKeyboardController.current
    val scope     = rememberCoroutineScope()

    var showImport    by remember { mutableStateOf(false) }
    var showManual    by remember { mutableStateOf(false) }
    var editProfile   by remember { mutableStateOf<VpnProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var deleteSubscription by remember { mutableStateOf<Subscription?>(null) }
    var showShareLink by remember { mutableStateOf(false) }
    var showQr        by remember { mutableStateOf(false) }
    var qrBitmap      by remember { mutableStateOf<Bitmap?>(null) }
    var importText    by remember { mutableStateOf("") }
    var previewProfile by remember { mutableStateOf<VpnProfile?>(null) }
    var previewErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(ProfileSortMode.DEFAULT) }

    val visibleProfiles = remember(ui.profiles, searchQuery, sortMode) {
        ui.profiles
            .filter {
                searchQuery.isBlank() ||
                    it.name.contains(searchQuery, true) ||
                    it.address.contains(searchQuery, true)
            }
            .let { list ->
                when (sortMode) {
                    ProfileSortMode.DEFAULT -> list
                    ProfileSortMode.NAME    -> list.sortedBy { it.name.lowercase() }
                    ProfileSortMode.LATENCY -> list.sortedBy { if (it.latencyMs < 0) Long.MAX_VALUE else it.latencyMs }
                }
            }
    }

    fun previewImport(raw: String) {
        val scheme = runCatching { android.net.Uri.parse(raw.trim()).scheme?.lowercase() }.getOrNull()
        if (scheme == "http" || scheme == "https") {
            vm.importFromText(raw)
            importText = ""
            return
        }
        val profile = ProfileCodec.decode(raw)
        if (profile == null) {
            vm.importFromText(raw)
            return
        }
        previewProfile = profile
        previewErrors = ProfileValidator.validate(profile)
    }

    val qrImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    QrCodec.decodeFromUri(context.applicationContext, uri)
                }
                if (decoded != null) {
                    importText = decoded
                    previewImport(decoded)
                } else {
                    vm.showSnack("На изображении нет QR-кода")
                }
            }
        }
    }

    val cameraQrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.Default) {
                    QrCodec.decodeFromBitmap(bitmap)
                }
                if (decoded != null) {
                    importText = decoded
                    previewImport(decoded)
                } else {
                    vm.showSnack("На фото нет QR-кода")
                }
            }
        }
    }

    fun importFromClipboard() {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        if (text.isBlank()) {
            vm.showSnack("Буфер обмена пуст")
            return
        }
        importText = text
        previewImport(text)
    }

    Column(
        Modifier
            .fillMaxSize()
            .aetherScreenBackground()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.17f),
                shape = RoundedCornerShape(15.dp),
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    null,
                    Modifier.padding(7.dp).size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Сервера", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                AnimatedVisibility(visible = ui.profiles.isNotEmpty() || ui.subscriptions.isNotEmpty()) {
                    Text(
                        "Локаций: ${ui.profiles.size} · подписок: ${ui.subscriptions.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            ProfileTopAction(Icons.Rounded.Subscriptions, "Подписки", onOpenSubscriptions)
            if (ui.profiles.isNotEmpty()) {
                ProfileTopAction(Icons.Rounded.NetworkCheck, "Проверить все") { vm.pingAll() }
            }
            if (ui.activeProfile != null) {
                ProfileTopAction(Icons.Rounded.Share, "Поделиться активным") {
                    vm.generateShareLink()
                    showShareLink = true
                }
            }
            Box {
                Button(
                    onClick = { importMenuExpanded = true },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Добавить", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                    ImportMenu(
                        expanded = importMenuExpanded,
                        onDismiss = { importMenuExpanded = false },
                        onLink = {
                            importMenuExpanded = false
                            showImport = true
                        },
                        onClipboard = {
                            importMenuExpanded = false
                            importFromClipboard()
                        },
                        onCameraQr = {
                            importMenuExpanded = false
                            cameraQrLauncher.launch(null)
                        },
                        onImageQr = {
                            importMenuExpanded = false
                            qrImportLauncher.launch("image/*")
                        },
                        onManual = {
                            importMenuExpanded = false
                            showManual = true
                        },
                        onWarp = {
                            importMenuExpanded = false
                            vm.generateWarpProfile()
                        },
                    )
            }
        }

        // ── Search + sort ────────────────────────────────────────────────────
        AnimatedVisibility(visible = ui.profiles.size > 3) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    var sortExpanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { sortExpanded = true }) {
                        Icon(Icons.Rounded.Sort, "Сортировать профили")
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        ProfileSortMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                trailingIcon = if (sortMode == mode) {
                                    { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }
                                } else null,
                                onClick = { sortMode = mode; sortExpanded = false },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = ui.profiles.isEmpty() && ui.subscriptions.isEmpty(),
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "profiles_content",
        ) { isEmpty ->
            if (isEmpty) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.VpnKey, null,
                            Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Профилей пока нет",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Импортируйте ссылку или добавьте профиль вручную",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(24.dp))
                        FilledTonalButton(onClick = { importMenuExpanded = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Импортировать профиль", color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            } else {
                // Bug fix #6: group profiles by subscription with collapsible sections
                GroupedProfilesList(
                    profiles      = visibleProfiles,
                    subscriptions = ui.subscriptions,
                    onSelect      = { vm.selectProfile(it) },
                    onDelete      = { profile -> deleteProfile = profile },
                    onEdit        = { editProfile = it },
                    onPing        = { vm.pingProfile(it) },
                    onViewJson    = { onOpenJson(it.id) },
                    onRefreshSub  = { sub -> vm.updateSubscription(sub) },
                    onDeleteSub   = { sub -> deleteSubscription = sub },
                )
            }
        }
    }

    // ── Import dialog ─────────────────────────────────────────────────────────
    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importText = "" },
            title = { Text("Импорт по ссылке") },
            icon  = { Icon(Icons.Rounded.Link, null) },
            text = {
                OutlinedTextField(
                    value         = importText,
                    onValueChange = { importText = it },
                    label         = { Text("Ссылка или конфигурация") },
                    placeholder   = { Text("vmess://, vless://, ss://…", style = MaterialTheme.typography.bodySmall) },
                    modifier      = Modifier.fillMaxWidth(),
                    minLines      = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
            },
            confirmButton = {
                Button(
                    onClick  = { previewImport(importText.trim()); showImport = false },
                    enabled  = importText.isNotBlank(),
                ) { Text("Импорт") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importText = "" }) { Text("Отмена") }
            },
        )
    }

    previewProfile?.let { profile ->
        ImportPreviewDialog(
            profile = profile,
            errors = previewErrors,
            onDismiss = { previewProfile = null },
            onImport = {
                if (vm.addManualProfile(profile)) {
                    previewProfile = null
                    importText = ""
                }
            },
        )
    }

    // ── Manual add ───────────────────────────────────────────────────────────
    if (showManual) {
        ManualProfileDialog(
            initial   = null,
            onSave    = { if (vm.addManualProfile(it)) showManual = false },
            onDismiss = { showManual = false },
        )
    }

    // ── Edit ─────────────────────────────────────────────────────────────────
    editProfile?.let { toEdit ->
        ManualProfileDialog(
            initial   = toEdit,
            onSave    = { if (vm.updateProfile(it)) editProfile = null },
            onDismiss = { editProfile = null },
        )
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text("Удалить профиль") },
            icon = { Icon(Icons.Rounded.Delete, null) },
            text = { Text("Удалить «${profile.name}»?") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeProfile(profile.id)
                        deleteProfile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProfile = null }) { Text("Отмена") }
            },
        )
    }

    deleteSubscription?.let { subscription ->
        AlertDialog(
            onDismissRequest = { deleteSubscription = null },
            title = { Text("Полностью удалить профиль") },
            icon = { Icon(Icons.Rounded.DeleteForever, null) },
            text = { Text("Удалить подписку «${subscription.displayName}» и все её локации?") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.removeSubscription(subscription.id)
                        deleteSubscription = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Полностью удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteSubscription = null }) { Text("Отмена") } },
        )
    }

    // ── Share link ────────────────────────────────────────────────────────────
    if (showShareLink && ui.shareLink != null) {
        AlertDialog(
            onDismissRequest = { showShareLink = false; vm.clearShareLink() },
            title = { Text("Поделиться профилем") },
            icon  = { Icon(Icons.Rounded.Share, null) },
            text = {
                Column {
                    Text(
                        "Отправьте эту ссылку на другое устройство AetherLink X:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        SelectionContainer {
                            Text(
                                ui.shareLink!!,
                                modifier = Modifier
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                style    = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(ui.shareLink!!))
                    showShareLink = false; vm.clearShareLink()
                }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Копировать")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        qrBitmap = QrCodec.encode(ui.shareLink!!)
                        showShareLink = false
                        showQr = true
                    }) { Text("QR") }
                    TextButton(onClick = { showShareLink = false; vm.clearShareLink() }) { Text("Закрыть") }
                }
            },
        )
    }

    if (showQr && qrBitmap != null) {
        AlertDialog(
            onDismissRequest = { showQr = false; vm.clearShareLink() },
            title = { Text("QR-код профиля") },
            icon = { Icon(Icons.Rounded.QrCode, null) },
            text = {
                Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = "Profile QR",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .aspectRatio(1f),
                )
            },
            confirmButton = {
                Button(onClick = { showQr = false; vm.clearShareLink() }) { Text("Готово") }
            },
        )
    }
}

@Composable
private fun ImportMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onLink: () -> Unit,
    onClipboard: () -> Unit,
    onCameraQr: () -> Unit,
    onImageQr: () -> Unit,
    onManual: () -> Unit,
    onWarp: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Вставить ссылку") },
            leadingIcon = { Icon(Icons.Rounded.Link, null) },
            onClick = onLink,
        )
        DropdownMenuItem(
            text = { Text("Из буфера") },
            leadingIcon = { Icon(Icons.Rounded.ContentPaste, null) },
            onClick = onClipboard,
        )
        DropdownMenuItem(
            text = { Text("QR с камеры") },
            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
            onClick = onCameraQr,
        )
        DropdownMenuItem(
            text = { Text("QR из изображения") },
            leadingIcon = { Icon(Icons.Rounded.ImageSearch, null) },
            onClick = onImageQr,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Ввести вручную") },
            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
            onClick = onManual,
        )
        DropdownMenuItem(
            text = { Text("Создать WARP") },
            leadingIcon = { Icon(Icons.Rounded.Cloud, null) },
            onClick = onWarp,
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    profile: VpnProfile,
    errors: List<String>,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проверка перед импортом") },
        icon = { Icon(if (errors.isEmpty()) Icons.Rounded.Preview else Icons.Rounded.Warning, null) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewRow("Название", profile.name)
                PreviewRow("Протокол", profile.protocol.name)
                PreviewRow("Сервер", "${profile.address}:${profile.port}")
                PreviewRow("Транспорт", profile.transport.name)
                PreviewRow("Защита", profile.security.name)
                if (profile.sni.isNotBlank()) PreviewRow("SNI", profile.sni)
                if (errors.isNotEmpty()) {
                    HorizontalDivider()
                    errors.forEach { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onImport, enabled = errors.isEmpty()) { Text("Импорт") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun PreviewRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyMedium)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Grouped profiles list (collapsible per subscription + manual group)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileTopAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Surface(onClick) enforces Material's 48 dp minimum interactive size and
        // consequently made adjacent circular outlines overlap. Keep the generous
        // touch target on the outer Box while drawing an explicitly smaller circle.
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    CircleShape,
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.62f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                description,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// Sealed list item types for the flat LazyColumn
private sealed class ProfileListItem {
    data class Header(val key: String, val title: String, val count: Int, val subId: String?) : ProfileListItem()
    data class Card(val key: String, val profile: VpnProfile) : ProfileListItem()
}

@Composable
private fun GroupedProfilesList(
    profiles: List<VpnProfile>,
    subscriptions: List<Subscription>,
    onSelect: (String) -> Unit,
    onDelete: (VpnProfile) -> Unit,
    onEdit: (VpnProfile) -> Unit,
    onPing: (VpnProfile) -> Unit,
    onViewJson: (VpnProfile) -> Unit,
    onRefreshSub: (Subscription) -> Unit,
    onDeleteSub: (Subscription) -> Unit,
) {
    val expandedGroups = remember { mutableStateMapOf("manual" to true) }
    LaunchedEffect(subscriptions) {
        subscriptions.forEach { if (!expandedGroups.containsKey(it.id)) expandedGroups[it.id] = true }
    }

    val manualProfiles = remember(profiles) { profiles.filter { it.subscriptionId == null } }
    val subGroups = remember(profiles, subscriptions) {
        subscriptions.map { sub -> sub to profiles.filter { it.subscriptionId == sub.id } }
    }

    // Build a flat list of items — this is the ONLY correct way to have dynamic
    // groups in LazyColumn without DuplicateKeyException during state transitions
    val flatItems = remember(manualProfiles, subGroups, expandedGroups.toMap()) {
        buildList {
            if (manualProfiles.isNotEmpty()) {
                add(ProfileListItem.Header("header_manual", "Вручную", manualProfiles.size, null))
                if (expandedGroups["manual"] != false) {
                    manualProfiles.forEachIndexed { index, profile ->
                        add(ProfileListItem.Card("manual_${index}_${profile.id}", profile))
                    }
                }
            }
            subGroups.forEach { (sub, subProfiles) ->
                add(ProfileListItem.Header("header_${sub.id}", sub.displayName, subProfiles.size, sub.id))
                if (expandedGroups[sub.id] != false) {
                    subProfiles.forEachIndexed { index, profile ->
                        add(ProfileListItem.Card("${sub.id}_${index}_${profile.id}", profile))
                    }
                }
            }
        }
    }

    LazyColumn(modifier = Modifier.miuixSpringScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(
            items = flatItems,
            key   = { item ->
                when (item) {
                    is ProfileListItem.Header -> item.key
                    is ProfileListItem.Card   -> item.key
                }
            },
            contentType = { item -> if (item is ProfileListItem.Header) "header" else "profile" },
        ) { item ->
            when (item) {
                is ProfileListItem.Header -> {
                    val subId = item.subId
                    val sub   = subGroups.firstOrNull { it.first.id == subId }?.first
                    GroupHeader(
                        title     = item.title,
                        count     = item.count,
                        expanded  = expandedGroups[item.subId ?: "manual"] ?: true,
                        onToggle  = {
                            val k = item.subId ?: "manual"
                            expandedGroups[k] = !(expandedGroups[k] ?: true)
                        },
                        onRefresh = if (sub != null) ({ onRefreshSub(sub) }) else null,
                        subscription = sub,
                        onDelete = if (sub != null) ({ onDeleteSub(sub) }) else null,
                    )
                }
                is ProfileListItem.Card -> {
                    ProfileCard(
                        profile  = item.profile,
                        subName  = null,
                        isActive = item.profile.isActive,
                        onSelect = { onSelect(item.profile.id) },
                        onDelete = { onDelete(item.profile) },
                        onEdit   = { onEdit(item.profile) },
                        onPing   = { onPing(item.profile) },
                        onViewJson = { onViewJson(item.profile) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: (() -> Unit)?,
    subscription: Subscription?,
    onDelete: (() -> Unit)?,
) {
    val rotation by animateFloatAsState(
        targetValue   = if (expanded) 0f else -90f,
        animationSpec = tween(200),
        label         = "arrow_$title",
    )
    val containerColor by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        animationSpec = tween(220),
        label = "group_header_bg_$title",
    )
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.70f),
            MaterialTheme.shapes.medium,
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .animateContentSize(tween(220))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown, null,
                    Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subscription?.let { sub ->
                    val expiry = if (sub.expireEpochSec > 0) {
                        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
                            .format(Date(sub.expireEpochSec * 1000L))
                        "Действует до $date"
                    } else "Срок не указан"
                    Text(
                        listOf(sub.name.takeIf { it != sub.displayName }, expiry)
                            .filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (count == 0) {
                        Text(
                            sub.availabilityMessage.ifBlank { "Нет доступных локаций" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = CircleShape,
            ) {
                AnimatedContent(
                    targetState = count,
                    transitionSpec = { fadeIn(tween(150)) + scaleIn() togetherWith fadeOut(tween(100)) + scaleOut() },
                    label = "group_count_$title",
                ) { value ->
                    Text(
                        "$value",
                        modifier   = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style      = MaterialTheme.typography.labelSmall,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (onRefresh != null) {
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Refresh, "Обновить подписку", Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        "Полностью удалить профиль",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Profile card
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ProfileCard(
    profile: VpnProfile,
    subName: String?,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onPing: () -> Unit,
    onViewJson: () -> Unit,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant

    ElevatedCard(
        colors   = CardDefaults.elevatedCardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = { actionsExpanded = true })
            .border(
                if (isActive) 2.dp else 1.dp,
                if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                MaterialTheme.shapes.large,
            ),
        shape    = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isActive) 4.dp else 1.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            // ── Badge row ──────────────────────────────────────────────────────
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
                    Text(
                        profile.protocol.name,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        style      = MaterialTheme.typography.labelSmall,
                        modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                AnimatedVisibility(visible = profile.transport != Transport.TCP) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)) {
                        Text(
                            profile.transport.name,
                            color  = MaterialTheme.colorScheme.secondary,
                            style  = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                AnimatedVisibility(visible = isActive) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)) {
                        Text(
                            "АКТИВЕН",
                            color      = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            style      = MaterialTheme.typography.labelSmall,
                            modifier   = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${profile.address}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.lastTested > 0L) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "Проверен ${relativeTime(profile.lastTested)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (profile.lastTested > 0L) {
                    val latencyColor = when {
                        profile.latencyMs < 0L -> MaterialTheme.colorScheme.error
                        profile.latencyMs < 150L -> MaterialTheme.colorScheme.primary
                        profile.latencyMs < 400L -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                    AnimatedContent(
                        targetState = profile.latencyMs,
                        transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                        label = "latency_${profile.id}",
                    ) { latency ->
                        Text(
                            text = if (latency >= 0L) "$latency мс" else "н/д",
                            style = MaterialTheme.typography.labelLarge,
                            color = latencyColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Subscription source
            AnimatedVisibility(visible = subName != null) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Subscriptions, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            subName ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            Box {
                    DropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Проверить пинг только на этой локации") },
                            leadingIcon = { Icon(Icons.Rounded.NetworkCheck, null) },
                            onClick = { actionsExpanded = false; onPing() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Посмотреть JSON-конфиг") },
                            leadingIcon = { Icon(Icons.Rounded.DataObject, null) },
                            onClick = { actionsExpanded = false; onViewJson() },
                        )
                        DropdownMenuItem(
                            text = { Text("Изменить") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { actionsExpanded = false; onEdit() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { actionsExpanded = false; onDelete() },
                        )
                    }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Manual add / edit dialog
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualProfileDialog(
    initial: VpnProfile?,
    onSave: (VpnProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEdit = initial != null

    var name        by remember { mutableStateOf(initial?.name        ?: "") }
    var protocol    by remember { mutableStateOf(initial?.protocol    ?: Protocol.VLESS) }
    var address     by remember { mutableStateOf(initial?.address     ?: "") }
    var port        by remember { mutableStateOf(initial?.port?.toString() ?: "443") }
    var uuid        by remember { mutableStateOf(initial?.uuid        ?: "") }
    var transport   by remember { mutableStateOf(initial?.transport   ?: Transport.TCP) }
    var path        by remember { mutableStateOf(initial?.path        ?: "/") }
    var host        by remember { mutableStateOf(initial?.host        ?: "") }
    var transportMode by remember { mutableStateOf(initial?.transportMode ?: "") }
    var transportExtraJson by remember { mutableStateOf(initial?.transportExtraJson ?: "{}") }
    var transportHeader by remember { mutableStateOf(initial?.transportHeader ?: "none") }
    var transportSeed by remember { mutableStateOf(initial?.transportSeed ?: "") }
    var transportSecurity by remember { mutableStateOf(initial?.transportSecurity ?: "none") }
    var transportKey by remember { mutableStateOf(initial?.transportKey ?: "") }
    var security    by remember { mutableStateOf(initial?.security    ?: Security.TLS) }
    var sni         by remember { mutableStateOf(initial?.sni         ?: "") }
    var fingerprint by remember { mutableStateOf(initial?.fingerprint ?: "chrome") }
    var alpn        by remember { mutableStateOf(initial?.alpn ?: "") }
    var publicKey   by remember { mutableStateOf(initial?.publicKey   ?: "") }
    var shortId     by remember { mutableStateOf(initial?.shortId     ?: "") }
    var spiderX     by remember { mutableStateOf(initial?.spiderX     ?: "") }
    var flow        by remember { mutableStateOf(initial?.flow        ?: "") }
    var allowInsecure by remember { mutableStateOf(initial?.allowInsecure ?: false) }
    var vmessSecurity by remember { mutableStateOf(initial?.vmessSecurity ?: "auto") }
    var ssMethod    by remember { mutableStateOf(initial?.ssMethod    ?: "chacha20-ietf-poly1305") }
    var ssPassword  by remember { mutableStateOf(initial?.ssPassword  ?: "") }
    var hystPwd     by remember { mutableStateOf(initial?.hystPassword ?: "") }
    var wgPrivKey   by remember { mutableStateOf(initial?.wgPrivateKey    ?: "") }
    var wgPubKey    by remember { mutableStateOf(initial?.wgPeerPublicKey  ?: "") }
    var wgPsk       by remember { mutableStateOf(initial?.wgPreSharedKey  ?: "") }
    var wgEndpoint  by remember { mutableStateOf(initial?.wgEndpoint ?: "") }
    var wgDns       by remember { mutableStateOf(initial?.wgDns       ?: "1.1.1.1") }
    var wgMtu       by remember { mutableStateOf(initial?.wgMtu?.toString() ?: "1280") }
    var wgReserved  by remember { mutableStateOf(initial?.wgReserved  ?: "") }
    var protoExpanded     by remember { mutableStateOf(false) }
    var transportExpanded by remember { mutableStateOf(false) }
    var securityExpanded  by remember { mutableStateOf(false) }
    var vmessCipherExpanded by remember { mutableStateOf(false) }

    var muxEnabled  by remember { mutableStateOf(initial?.muxEnabled ?: true) }
    var fragmentEnabled by remember { mutableStateOf(initial?.fragmentEnabled ?: false) }

    val noTransportProtos = remember {
        listOf(Protocol.WIREGUARD, Protocol.SHADOWSOCKS, Protocol.HYSTERIA2, Protocol.SOCKS5, Protocol.HTTP, Protocol.ANYTLS)
    }
    val noSecurityProtos = remember {
        listOf(Protocol.WIREGUARD, Protocol.SHADOWSOCKS, Protocol.SOCKS5, Protocol.HTTP)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Изменить профиль" else "Добавить профиль") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название профиля") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                ExposedDropdownMenuBox(expanded = protoExpanded, onExpandedChange = { protoExpanded = it }) {
                    OutlinedTextField(
                        value = protocol.name, onValueChange = {}, readOnly = true,
                        label = { Text("Протокол") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protoExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = protoExpanded, onDismissRequest = { protoExpanded = false }) {
                        listOf(Protocol.AETHERLINK_NATIVE, Protocol.AETHERLINK_X, Protocol.VLESS, Protocol.VMESS, Protocol.SHADOWSOCKS, Protocol.TROJAN,
                            Protocol.HYSTERIA2, Protocol.WIREGUARD, Protocol.SOCKS5, Protocol.HTTP, Protocol.TUIC,
                            Protocol.ANYTLS).forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { protocol = p; protoExpanded = false })
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Адрес") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт") }, modifier = Modifier.width(88.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }

                // Protocol-specific fields
                when (protocol) {
                    Protocol.AETHERLINK_NATIVE, Protocol.AETHERLINK_X, Protocol.VMESS, Protocol.VLESS, Protocol.TROJAN, Protocol.SOCKS5, Protocol.HTTP, Protocol.TUIC, Protocol.ANYTLS -> {
                        if (protocol == Protocol.TROJAN || protocol == Protocol.ANYTLS) {
                            SecretTextField(value = uuid, onValueChange = { uuid = it }, label = "Пароль")
                        } else if (protocol == Protocol.SOCKS5 || protocol == Protocol.HTTP) {
                            OutlinedTextField(
                                value = uuid,
                                onValueChange = { uuid = it },
                                label = { Text("Логин:пароль (необязательно)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        } else {
                            OutlinedTextField(
                                value = uuid, onValueChange = { uuid = it },
                                label = { Text("UUID") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                trailingIcon = {
                                    IconButton(onClick = { uuid = UUID.randomUUID().toString() }) {
                                        Icon(Icons.Rounded.Refresh, "Generate", Modifier.size(18.dp))
                                    }
                                },
                            )
                        }
                        // BUG FIX: TUIC needs a password too — without this field, manual TUIC
                        // profiles always failed validation ("TUIC password is required").
                        if (protocol == Protocol.TUIC) {
                            SecretTextField(value = ssPassword, onValueChange = { ssPassword = it }, label = "Пароль")
                        }
                    }
                    Protocol.SHADOWSOCKS -> {
                        OutlinedTextField(value = ssMethod,   onValueChange = { ssMethod   = it }, label = { Text("Шифр")   }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        SecretTextField(value = ssPassword, onValueChange = { ssPassword = it }, label = "Пароль")
                    }
                    Protocol.HYSTERIA2 -> {
                        SecretTextField(value = hystPwd, onValueChange = { hystPwd = it }, label = "Пароль")
                    }
                    Protocol.WIREGUARD -> {
                        SecretTextField(value = wgPrivKey, onValueChange = { wgPrivKey = it }, label = "Закрытый ключ")
                        OutlinedTextField(value = wgPubKey,  onValueChange = { wgPubKey  = it }, label = { Text("Открытый ключ узла") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        SecretTextField(value = wgPsk, onValueChange = { wgPsk = it }, label = "Предварительный ключ (необязательно)")
                        OutlinedTextField(value = wgEndpoint, onValueChange = { wgEndpoint = it }, label = { Text("Конечная точка") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = wgDns, onValueChange = { wgDns = it }, label = { Text("DNS") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = wgMtu, onValueChange = { wgMtu = it }, label = { Text("MTU") }, modifier = Modifier.width(88.dp), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                        OutlinedTextField(
                            value = wgReserved,
                            onValueChange = { wgReserved = it },
                            label = { Text("Резерв WARP (необязательно)") },
                            placeholder = { Text("например 12,34,56") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    else -> {}
                }

                // Transport
                AnimatedVisibility(visible = protocol !in noTransportProtos) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExposedDropdownMenuBox(expanded = transportExpanded, onExpandedChange = { transportExpanded = it }) {
                            OutlinedTextField(
                                value = transport.name, onValueChange = {}, readOnly = true,
                                label = { Text("Транспорт") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(transportExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = transportExpanded, onDismissRequest = { transportExpanded = false }) {
                                Transport.values().forEach { t ->
                                    DropdownMenuItem(text = { Text(t.name) }, onClick = { transport = t; transportExpanded = false })
                                }
                            }
                        }
                        AnimatedVisibility(visible = transport in listOf(
                            Transport.WS, Transport.H2, Transport.QUIC, Transport.XHTTP,
                            Transport.GRPC, Transport.HTTP_UPGRADE,
                        )) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text(if (transport == Transport.GRPC) "Имя службы" else "Путь") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(if (transport == Transport.GRPC) "Authority (необязательно)" else "Хост") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                if (transport in listOf(Transport.XHTTP, Transport.H2, Transport.QUIC)) {
                                    OutlinedTextField(
                                        value = transportMode,
                                        onValueChange = { transportMode = it },
                                        label = { Text("Режим XHTTP") },
                                        supportingText = { Text("auto, stream-one, stream-up или packet-up") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = transportExtraJson,
                                        onValueChange = { transportExtraJson = it },
                                        label = { Text("Дополнительные параметры XHTTP (JSON)") },
                                        supportingText = { Text("Передаются ядру без изменений: padding, xmux, upload/download") },
                                        modifier = Modifier.fillMaxWidth(),
                                        minLines = 2,
                                    )
                                }
                            }
                        }
                        AnimatedVisibility(visible = transport == Transport.KCP) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = transportSeed, onValueChange = { transportSeed = it }, label = { Text("Seed mKCP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = transportHeader, onValueChange = { transportHeader = it }, label = { Text("Тип заголовка") }, supportingText = { Text("none, srtp, utp, wechat-video, dtls, wireguard") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                }

                // Security
                AnimatedVisibility(visible = protocol !in noSecurityProtos) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExposedDropdownMenuBox(expanded = securityExpanded, onExpandedChange = { securityExpanded = it }) {
                            OutlinedTextField(
                                value = security.name, onValueChange = {}, readOnly = true,
                                label = { Text("Защита") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(securityExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = securityExpanded, onDismissRequest = { securityExpanded = false }) {
                                Security.values().forEach { s ->
                                    DropdownMenuItem(text = { Text(s.name) }, onClick = { security = s; securityExpanded = false })
                                }
                            }
                        }
                        AnimatedVisibility(visible = security != Security.NONE) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = sni,         onValueChange = { sni         = it }, label = { Text("SNI") },         modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("Отпечаток") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = alpn, onValueChange = { alpn = it }, label = { Text("ALPN (через запятую)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                        AnimatedVisibility(visible = security == Security.REALITY) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(value = publicKey, onValueChange = { publicKey = it }, label = { Text("Открытый ключ (pbk)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = shortId,   onValueChange = { shortId   = it }, label = { Text("Короткий ID (sid)") },  modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(value = spiderX, onValueChange = { spiderX = it }, label = { Text("REALITY spiderX") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                        AnimatedVisibility(visible = protocol in listOf(Protocol.VLESS, Protocol.AETHERLINK_X) && transport == Transport.TCP) {
                            OutlinedTextField(
                                value = flow,
                                onValueChange = { flow = it },
                                label = { Text("VLESS flow") },
                                supportingText = { Text("Например xtls-rprx-vision; пусто — не подставлять") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        // Allow insecure (skip cert verification) — for TLS-based security only
                        AnimatedVisibility(visible = security == Security.TLS || security == Security.XTLS) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Разрешить незащищённое", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Не проверять TLS-сертификат (для самоподписанных серверов)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = allowInsecure, onCheckedChange = { allowInsecure = it })
                            }
                        }
                    }
                }

                // VMess cipher
                AnimatedVisibility(visible = protocol == Protocol.VMESS) {
                    ExposedDropdownMenuBox(expanded = vmessCipherExpanded, onExpandedChange = { vmessCipherExpanded = it }) {
                        OutlinedTextField(
                            value = vmessSecurity, onValueChange = {}, readOnly = true,
                            label = { Text("Шифр VMess") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(vmessCipherExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = vmessCipherExpanded, onDismissRequest = { vmessCipherExpanded = false }) {
                            listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero").forEach { c ->
                                DropdownMenuItem(text = { Text(c) }, onClick = { vmessSecurity = c; vmessCipherExpanded = false })
                            }
                        }
                    }
                }

                // Per-profile advanced overrides (#22 / #23)
                HorizontalDivider()
                ProfileToggleRow(
                    title = "Мультиплексирование (mux)",
                    subtitle = "Одно соединение для нескольких потоков. Игнорируется неподдерживаемыми протоколами.",
                    checked = muxEnabled,
                    onChange = { muxEnabled = it },
                )
                ProfileToggleRow(
                    title = "Фрагментация TLS (anti-DPI)",
                    subtitle = "Разделение TLS-рукопожатия. Размеры задаются в разделе маршрутизации.",
                    checked = fragmentEnabled,
                    onChange = { fragmentEnabled = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave((initial ?: VpnProfile()).copy(
                        name            = name.ifBlank { "Без названия" },
                        protocol        = protocol,
                        address         = address.trim(),
                        port            = port.toIntOrNull() ?: 443,
                        uuid            = uuid.trim(),
                        transport       = transport,
                        path            = path.ifBlank { "/" },
                        host            = host.trim(),
                        transportMode   = transportMode.trim(),
                        transportExtraJson = transportExtraJson.ifBlank { "{}" },
                        transportHeader = transportHeader.trim().ifBlank { "none" },
                        transportSeed   = transportSeed.trim(),
                        transportSecurity = transportSecurity.trim().ifBlank { "none" },
                        transportKey    = transportKey.trim(),
                        security        = security,
                        sni             = sni.trim(),
                        fingerprint     = fingerprint.trim(),
                        alpn            = alpn.trim(),
                        publicKey       = publicKey.trim(),
                        shortId         = shortId.trim(),
                        spiderX         = spiderX.trim(),
                        flow            = flow.trim(),
                        allowInsecure   = allowInsecure,
                        vmessSecurity   = vmessSecurity,
                        ssMethod        = ssMethod.trim(),
                        ssPassword      = ssPassword.trim(),
                        hystPassword    = hystPwd.trim(),
                        wgPrivateKey    = wgPrivKey.trim(),
                        wgPeerPublicKey = wgPubKey.trim(),
                        wgPreSharedKey  = wgPsk.trim(),
                        wgEndpoint      = wgEndpoint.trim(),
                        wgDns           = wgDns.trim(),
                        wgMtu           = wgMtu.toIntOrNull() ?: 1280,
                        wgReserved      = wgReserved.trim(),
                        muxEnabled      = muxEnabled,
                        fragmentEnabled = fragmentEnabled,
                    ))
                },
                enabled = address.isNotBlank(),
            ) { Text(if (isEdit) "Сохранить" else "Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ProfileToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

private enum class ProfileSortMode(val label: String) {
    DEFAULT("Обычный порядок"),
    NAME("Название (А–Я)"),
    LATENCY("Задержка (сначала быстрые)"),
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000      -> "только что"
        diff < 3_600_000   -> "${diff / 60_000} мин назад"
        diff < 86_400_000  -> "${diff / 3_600_000} ч назад"
        else               -> "${diff / 86_400_000} дн. назад"
    }
}
