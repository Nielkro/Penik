package niel.kro.penik.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import niel.kro.penik.data.update.UpdateStatus
import niel.kro.penik.ui.theme.LocalAppColors

@Composable
fun UpdateDialog(
    status: UpdateStatus,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    val colors = LocalAppColors.current

    when (status) {
        is UpdateStatus.ForceUpdateRequired -> {
            val info = status.versionInfo
            AlertDialog(
                onDismissRequest = { /* Non-dismissible: mandatory update */ },
                title = {
                    Text(
                        text = "Требуется обновление",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Текущая версия приложения устарела и больше не поддерживается сервером.\n\nПожалуйста, установите обновление v${info.latestAndroidVersionName}.",
                            color = colors.textPrimary,
                            fontSize = 14.sp
                        )
                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                text = "Что нового:\n${info.releaseNotes}",
                                color = colors.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onDownload(info.apkUrl) }) {
                        Text(
                            text = "Обновить",
                            color = colors.accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                containerColor = colors.panel
            )
        }

        is UpdateStatus.SoftUpdateAvailable -> {
            val info = status.versionInfo
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(
                        text = "Доступно обновление",
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Доступна новая версия Penik Messenger v${info.latestAndroidVersionName}.",
                            color = colors.textPrimary,
                            fontSize = 14.sp
                        )
                        if (info.releaseNotes.isNotBlank()) {
                            Text(
                                text = "Что нового:\n${info.releaseNotes}",
                                color = colors.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onDownload(info.apkUrl) }) {
                        Text(
                            text = "Скачать",
                            color = colors.accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Позже",
                            color = colors.textMuted
                        )
                    }
                },
                containerColor = colors.panel
            )
        }

        is UpdateStatus.UpToDate -> Unit
    }
}
