package niel.kro.penik.ui.screen.settings

import niel.kro.penik.ui.theme.LocalAppColors

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import niel.kro.penik.ui.theme.AppIconManager
import niel.kro.penik.ui.theme.AppVariant
import niel.kro.penik.ui.theme.ThemeManager

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import niel.kro.penik.BuildConfig
import niel.kro.penik.data.update.UpdateCheckResult
import niel.kro.penik.ui.components.UpdateDialog
import niel.kro.penik.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onDevices: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val isLight by ThemeManager.isLight.collectAsState()
    val currentVariant by AppIconManager.currentVariant.collectAsState()
    val currentNavStyle by niel.kro.penik.ui.theme.NavigationStyleManager.navigationStyle.collectAsState()
    val isChecking by viewModel.isChecking.collectAsState()
    val updateStatus by viewModel.updateStatus.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    var showVariantDialog by remember { mutableStateOf(false) }
    var showNavStyleDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        viewModel.manualCheckResult.collect { result ->
            when (result) {
                is UpdateCheckResult.UpToDate -> {
                    android.widget.Toast.makeText(
                        context,
                        "У вас установлена последняя версия (v${BuildConfig.VERSION_NAME})",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is UpdateCheckResult.Error -> {
                    android.widget.Toast.makeText(
                        context,
                        "Не удалось проверить обновления: ${result.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is UpdateCheckResult.Available -> Unit
            }
        }
    }

    UpdateDialog(
        status = updateStatus,
        downloadState = downloadState,
        onDismiss = { viewModel.dismissSoftUpdate() },
        onDownload = { url -> viewModel.startDownload(context, url) },
        onInstall = { file -> viewModel.installDownloadedApk(context, file) },
        onOpenBrowser = { url -> viewModel.openDownloadUrl(context, url) }
    )

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Настройки", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
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
            // Theme toggle row: tapping anywhere switches between light and dark.
            var themeRowCenter by remember { mutableStateOf(Offset.Unspecified) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .onGloballyPositioned { coordinates ->
                        val pos = coordinates.positionInWindow()
                        val sz = coordinates.size
                        themeRowCenter = Offset(
                            pos.x + sz.width - 24.dp.value,
                            pos.y + sz.height / 2f
                        )
                    }
                    .clickable { ThemeManager.toggle(origin = themeRowCenter) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Тема", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = if (isLight) "Светлая" else "Тёмная",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Icon(
                    imageVector = if (isLight) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Тема",
                    tint = colors.accent,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // App name and icon chooser row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showVariantDialog = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Имя и иконка приложения", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = currentVariant.displayName,
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(currentVariant.iconRes),
                        contentDescription = currentVariant.displayName,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Navigation style chooser row (Bottom Bar vs Telegram Drawer)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showNavStyleDialog = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Стиль навигации", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = currentNavStyle.displayName,
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Devices navigation row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onDevices() }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Мои устройства", color = colors.textPrimary, fontSize = 16.sp)
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cloud Key Backup row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showCloudBackupDialog = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Резервная копия ключей в облаке", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = "Сохранить или восстановить ключи E2EE",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Export History to File row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showExportDialog = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Экспорт истории в файл (.penikbackup)", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = "Зашифрованный файл со всеми чатами и группами",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Import History from File row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        importFilePickerLauncher.launch(arrayOf("*/*"))
                    }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Импорт истории из файла (.penikbackup)", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = "Восстановить историю переписок из локального файла",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Mnemonic seed phrase row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        mnemonicPhrase = viewModel.generateMnemonicPhrase(12)
                        showMnemonicDialog = true
                    }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Мнемоническая фраза (12 слов)", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = "Сгенерировать 12 слов для шифрования и бэкапа",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // App version and update check row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !isChecking) { viewModel.checkForUpdates(manual = true) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Версия приложения", color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        text = if (isChecking) "Проверка обновлений..." else "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent
                    )
                } else {
                    Text("Проверить", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showVariantDialog) {
        AlertDialog(
            onDismissRequest = { showVariantDialog = false },
            title = {
                Text("Имя и иконка приложения", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppVariant.entries.forEach { variant ->
                        val isSelected = variant == currentVariant
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    AppIconManager.setVariant(variant, context)
                                    showVariantDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    AppIconManager.setVariant(variant, context)
                                    showVariantDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.accent,
                                    unselectedColor = colors.textMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(variant.iconRes),
                                    contentDescription = variant.displayName,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = variant.displayName,
                                    color = colors.textPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = if (variant == AppVariant.PENIK) "По умолчанию" else "Альтернативное",
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVariantDialog = false }) {
                    Text("Закрыть", color = colors.accent)
                }
            },
            containerColor = colors.panel
        )
    }

    if (showNavStyleDialog) {
        AlertDialog(
            onDismissRequest = { showNavStyleDialog = false },
            title = {
                Text("Стиль навигации", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    niel.kro.penik.ui.theme.NavigationStyle.entries.forEach { style ->
                        val isSelected = style == currentNavStyle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    niel.kro.penik.ui.theme.NavigationStyleManager.setStyle(style)
                                    showNavStyleDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    niel.kro.penik.ui.theme.NavigationStyleManager.setStyle(style)
                                    showNavStyleDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.accent,
                                    unselectedColor = colors.textMuted
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = style.displayName,
                                    color = colors.textPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = style.description,
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showNavStyleDialog = false }) {
                    Text("Закрыть", color = colors.accent)
                }
            },
            containerColor = colors.panel
        )
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


