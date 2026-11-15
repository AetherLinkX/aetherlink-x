package com.palazik.vpn.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import com.palazik.vpn.data.repository.UpdateInfo

/** One global gate covers home, profiles, settings and onboarding in either theme. */
@Composable
internal fun RequiredUpdateDialog(update: UpdateInfo) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text("Необходимо обновление") },
        text = {
            Text("Вышла новая версия AetherLink X ${update.version}. Чтобы продолжить пользоваться приложением, скачайте и установите обновление. Ваши профили и настройки сохранятся при установке поверх текущей версии.")
        },
        confirmButton = {
            Button(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                }.onFailure {
                    Toast.makeText(context, "Не удалось открыть загрузку. Проверьте наличие браузера и повторите.", Toast.LENGTH_LONG).show()
                }
                // Opening the browser does not dismiss the requirement: it remains
                // visible on return until the new APK is actually installed.
            }) { Text("Скачать новую версию") }
        },
    )
}