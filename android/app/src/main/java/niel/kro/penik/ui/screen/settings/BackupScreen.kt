package niel.kro.penik.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.theme.LocalAppColors
import niel.kro.penik.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current

    var showCloudBackupDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showMnemonicDialog by remember { mutableStateOf(false) }
    var mnemonicPhrase by remember { mutableStateOf("") }

    var exportPassphrase by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var cloudBackupPassphrase by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var isBackupLoading by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && exportPassphrase.isNotBlank()) {
            isBackupLoading = true
            viewModel.exportHistoryToFile(exportPassphrase, uri, context) { res ->
                isBackupLoading = false
                showExportDialog = false
                exportPassphrase = ""
                res.fold(
                    onSuccess = {
                        Toast.makeText(context, "История успешно экспортирована в файл!", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    val importFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Резервное копирование", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.textPrimary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "ОБЛАЧНОЕ ХРАНИЛИЩЕ",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            // Cloud Key Backup row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable { showCloudBackupDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("☁️ Резервная копия ключей в облаке", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Сохранить или восстановить ключи E2EE и групп на сервере",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "ЛОКАЛЬНАЯ ИСТОРИЯ",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            // Export History to File row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable { showExportDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("💾 Экспорт всей истории в файл", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Зашифрованный файл (.penikbackup) со всеми чатами, группами и ключами",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Import History from File row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable {
                        importFilePickerLauncher.launch(arrayOf("*/*"))
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("📂 Импорт истории из файла", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Восстановить историю переписок из локального файла .penikbackup",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "БЕЗОПАСНОСТЬ",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textMuted,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            // Mnemonic seed phrase row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable {
                        mnemonicPhrase = viewModel.generateMnemonicPhrase(12)
                        showMnemonicDialog = true
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🔐 Мнемоническая фраза (12 слов)", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Сгенерировать 12 слов для шифрования и безопасного бэкапа",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }
        }
    }

    if (showCloudBackupDialog) {
        AlertDialog(
            onDismissRequest = {
                showCloudBackupDialog = false
                cloudBackupPassphrase = ""
            },
            title = {
                Text("Резервная копия ключей", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Зашифруйте ваши ключи E2EE паролем или мнемонической фразой для безопасного хранения на сервере.",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = cloudBackupPassphrase,
                        onValueChange = { cloudBackupPassphrase = it },
                        label = { Text("Пароль или мнемоника") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (cloudBackupPassphrase.isBlank()) {
                                Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            isBackupLoading = true
                            viewModel.uploadKeyBackup(cloudBackupPassphrase) { res ->
                                isBackupLoading = false
                                res.fold(
                                    onSuccess = {
                                        Toast.makeText(context, "Резервная копия ключей создана на сервере!", Toast.LENGTH_SHORT).show()
                                        showCloudBackupDialog = false
                                        cloudBackupPassphrase = ""
                                    },
                                    onFailure = { e ->
                                        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    ) {
                        Text("Создать", color = colors.accent)
                    }

                    TextButton(
                        onClick = {
                            if (cloudBackupPassphrase.isBlank()) {
                                Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            isBackupLoading = true
                            viewModel.restoreKeyBackup(cloudBackupPassphrase) { res ->
                                isBackupLoading = false
                                res.fold(
                                    onSuccess = {
                                        Toast.makeText(context, "Ключи E2EE успешно восстановлены!", Toast.LENGTH_SHORT).show()
                                        showCloudBackupDialog = false
                                        cloudBackupPassphrase = ""
                                    },
                                    onFailure = { e ->
                                        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    ) {
                        Text("Восстановить", color = colors.accent)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCloudBackupDialog = false
                    cloudBackupPassphrase = ""
                }) {
                    Text("Отмена", color = colors.textMuted)
                }
            },
            containerColor = colors.panel
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                exportPassphrase = ""
            },
            title = {
                Text("Экспорт всей истории", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "История чатов, сообщений, групп и ключи шифрования будут сохранены в зашифрованный файл .penikbackup (AES-256-GCM / PBKDF2 600,000).",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = exportPassphrase,
                        onValueChange = { exportPassphrase = it },
                        label = { Text("Пароль или мнемоническая фраза") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            val phrase = viewModel.generateMnemonicPhrase(12)
                            exportPassphrase = phrase
                            Toast.makeText(context, "Мнемоника сгенерирована! Обязательно сохраните её.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("🎲 Сгенерировать мнемонику (12 слов)", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (exportPassphrase.isBlank()) {
                            Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        createDocLauncher.launch("penik_backup_${System.currentTimeMillis()}.penikbackup")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Сохранить в файл")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    exportPassphrase = ""
                }) {
                    Text("Отмена", color = colors.textMuted)
                }
            },
            containerColor = colors.panel
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importPassphrase = ""
                pendingImportUri = null
            },
            title = {
                Text("Импорт истории из файла", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Введите пароль или мнемоническую фразу, которая использовалась при создании резервной копии.",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        label = { Text("Пароль или мнемоника файла") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingImportUri
                        if (uri == null || importPassphrase.isBlank()) {
                            Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isBackupLoading = true
                        viewModel.importHistoryFromFile(uri, importPassphrase, context) { res ->
                            isBackupLoading = false
                            showImportDialog = false
                            importPassphrase = ""
                            pendingImportUri = null
                            res.fold(
                                onSuccess = { summary ->
                                    Toast.makeText(
                                        context,
                                        "Импортировано: чатов ${summary.chatsCount}, сообщений ${summary.messagesCount}, групп ${summary.groupsCount}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                onFailure = { e ->
                                    Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Импортировать")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    importPassphrase = ""
                    pendingImportUri = null
                }) {
                    Text("Отмена", color = colors.textMuted)
                }
            },
            containerColor = colors.panel
        )
    }

    if (showMnemonicDialog) {
        AlertDialog(
            onDismissRequest = { showMnemonicDialog = false },
            title = {
                Text("Мнемоническая фраза", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Сохраните эти 12 слов в надёжном месте. Фразу можно использовать как мастер-пароль для резервных копий истории и ключей.",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    val words = mnemonicPhrase.split(" ")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        words.chunked(3).forEachIndexed { rowIdx, chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chunk.forEachIndexed { colIdx, word ->
                                    val index = rowIdx * 3 + colIdx + 1
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(colors.background)
                                            .padding(vertical = 8.dp, horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "$index. $word",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.textPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("Penik Mnemonic", mnemonicPhrase)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(context, "Мнемоническая фраза скопирована!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text("Скопировать фразу")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMnemonicDialog = false }) {
                    Text("Закрыть", color = colors.accent)
                }
            },
            containerColor = colors.panel
        )
    }
}
