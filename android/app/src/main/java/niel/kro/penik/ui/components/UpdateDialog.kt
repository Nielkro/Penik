package niel.kro.penik.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import niel.kro.penik.data.update.DownloadState
import niel.kro.penik.data.update.UpdateStatus
import niel.kro.penik.ui.theme.LocalAppColors
import java.io.File
import java.util.Locale

@Composable
fun UpdateDialog(
    status: UpdateStatus,
    downloadState: DownloadState,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit,
    onInstall: (File) -> Unit,
    onOpenBrowser: (String) -> Unit
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
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        UpdateProgressSection(downloadState = downloadState)
                    }
                },
                confirmButton = {
                    UpdateActionButtons(
                        apkUrl = info.apkUrl,
                        downloadState = downloadState,
                        onDownload = onDownload,
                        onInstall = onInstall,
                        onOpenBrowser = onOpenBrowser
                    )
                },
                containerColor = colors.panel
            )
        }

        is UpdateStatus.SoftUpdateAvailable -> {
            val info = status.versionInfo
            val isDownloading = downloadState is DownloadState.Downloading
            AlertDialog(
                onDismissRequest = { if (!isDownloading) onDismiss() },
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
                        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        UpdateProgressSection(downloadState = downloadState)
                    }
                },
                confirmButton = {
                    UpdateActionButtons(
                        apkUrl = info.apkUrl,
                        downloadState = downloadState,
                        onDownload = onDownload,
                        onInstall = onInstall,
                        onOpenBrowser = onOpenBrowser
                    )
                },
                dismissButton = {
                    if (!isDownloading) {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Позже",
                                color = colors.textMuted
                            )
                        }
                    }
                },
                containerColor = colors.panel
            )
        }

        is UpdateStatus.UpToDate -> Unit
    }
}

@Composable
private fun UpdateProgressSection(downloadState: DownloadState) {
    val colors = LocalAppColors.current

    when (downloadState) {
        is DownloadState.Downloading -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (downloadState.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = colors.accent,
                        trackColor = colors.border
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(downloadState.progress * 100).toInt()}%",
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${formatBytes(downloadState.bytesDownloaded)} / ${formatBytes(downloadState.totalBytes)}",
                            color = colors.textMuted,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = colors.accent,
                        trackColor = colors.border
                    )
                    Text(
                        text = "Загрузка: ${formatBytes(downloadState.bytesDownloaded)}",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        is DownloadState.ReadyToInstall -> {
            Text(
                text = "Файл обновления загружен. Нажмите «Установить», чтобы завершить обновление.",
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        is DownloadState.Error -> {
            Text(
                text = "Ошибка скачивания: ${downloadState.message}",
                color = Color(0xFFEF5350),
                fontSize = 13.sp
            )
        }

        is DownloadState.Idle -> Unit
    }
}

@Composable
private fun UpdateActionButtons(
    apkUrl: String,
    downloadState: DownloadState,
    onDownload: (String) -> Unit,
    onInstall: (File) -> Unit,
    onOpenBrowser: (String) -> Unit
) {
    val colors = LocalAppColors.current

    when (downloadState) {
        is DownloadState.Idle -> {
            TextButton(onClick = { onDownload(apkUrl) }) {
                Text(
                    text = "Обновить",
                    color = colors.accent,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        is DownloadState.Downloading -> {
            Text(
                text = "Скачивание...",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        is DownloadState.ReadyToInstall -> {
            TextButton(onClick = { onInstall(downloadState.apkFile) }) {
                Text(
                    text = "Установить",
                    color = colors.accent,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        is DownloadState.Error -> {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpenBrowser(apkUrl) }) {
                    Text(
                        text = "В браузере",
                        color = colors.textMuted
                    )
                }
                TextButton(onClick = { onDownload(apkUrl) }) {
                    Text(
                        text = "Повторить",
                        color = colors.accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f МБ", mb)
    } else {
        String.format(Locale.US, "%.0f КБ", kb)
    }
}
