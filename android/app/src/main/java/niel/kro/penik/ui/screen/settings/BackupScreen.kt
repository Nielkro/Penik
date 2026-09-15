package niel.kro.penik.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.data.crypto.SafetyNumber
import niel.kro.penik.ui.theme.LocalAppColors
import niel.kro.penik.ui.viewmodel.SettingsViewModel

private enum class ExportStep {
    SELECT_METHOD,
    ENTER_PASSWORD,
    SHOW_MNEMONIC,
    VERIFY_MNEMONIC
}

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

    var exportStep by remember { mutableStateOf(ExportStep.SELECT_METHOD) }
    var exportCustomPassword by remember { mutableStateOf("") }
    var exportPasswordVisible by remember { mutableStateOf(false) }
    var exportMnemonicPhrase by remember { mutableStateOf("") }
    var exportMnemonicWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizIdx1 by remember { mutableIntStateOf(0) }
    var quizIdx2 by remember { mutableIntStateOf(1) }
    var quizOptions1 by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizOptions2 by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizSelected1 by remember { mutableStateOf<String?>(null) }
    var quizSelected2 by remember { mutableStateOf<String?>(null) }

    var finalExportPassphrase by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var importPasswordVisible by remember { mutableStateOf(false) }
    var cloudPassphrase by remember { mutableStateOf("") }
    var cloudPasswordVisible by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var isBackupLoading by remember { mutableStateOf(false) }

    fun startMnemonicExport() {
        val phrase = viewModel.generateMnemonicPhrase(12)
        exportMnemonicPhrase = phrase
        val words = phrase.split(" ").filter { it.isNotBlank() }
        exportMnemonicWords = words
        finalExportPassphrase = phrase

        val i1 = (0..5).random()
        val i2 = (6..11).random()
        quizIdx1 = i1
        quizIdx2 = i2

        val allWords = SafetyNumber.RUSSIAN_WORDS.toList()
        val decoys1 = (allWords - words[i1]).shuffled().take(3)
        quizOptions1 = (decoys1 + words[i1]).shuffled()

        val decoys2 = (allWords - words[i2]).shuffled().take(3)
        quizOptions2 = (decoys2 + words[i2]).shuffled()

        quizSelected1 = null
        quizSelected2 = null
        exportStep = ExportStep.SHOW_MNEMONIC
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && finalExportPassphrase.isNotBlank()) {
            isBackupLoading = true
            viewModel.exportHistoryToFile(finalExportPassphrase, uri, context) { res ->
                isBackupLoading = false
                showExportDialog = false
                exportStep = ExportStep.SELECT_METHOD
                finalExportPassphrase = ""
                exportCustomPassword = ""
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
                    .clickable {
                        exportStep = ExportStep.SELECT_METHOD
                        exportCustomPassword = ""
                        showExportDialog = true
                    }
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
        }
    }

    // --- Export History Dialog (Wizard) ---
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportDialog = false
                exportStep = ExportStep.SELECT_METHOD
                exportCustomPassword = ""
                finalExportPassphrase = ""
            },
            title = {
                Text(
                    text = when (exportStep) {
                        ExportStep.SELECT_METHOD -> "Экспорт всей истории"
                        ExportStep.ENTER_PASSWORD -> "Свой пароль"
                        ExportStep.SHOW_MNEMONIC -> "12 слов (Мнемоника)"
                        ExportStep.VERIFY_MNEMONIC -> "Проверка мнемоники"
                    },
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (exportStep) {
                        ExportStep.SELECT_METHOD -> {
                            Text(
                                "История чатов, сообщений, групп и ключи шифрования будут сохранены в зашифрованный файл .penikbackup (AES-256-GCM / PBKDF2 600,000).",
                                color = colors.textMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Card 1: Custom Password
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                        .background(colors.background)
                                        .clickable {
                                            exportCustomPassword = ""
                                            exportStep = ExportStep.ENTER_PASSWORD
                                        }
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("🔑", fontSize = 26.sp)
                                    Column {
                                        Text("Свой пароль", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("Личный пароль", color = colors.textMuted, fontSize = 11.sp)
                                    }
                                }

                                // Card 2: 12 Words
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                        .background(colors.background)
                                        .clickable {
                                            startMnemonicExport()
                                        }
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("🎲", fontSize = 26.sp)
                                    Column {
                                        Text("12 слов", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("Seed-фраза", color = colors.textMuted, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        ExportStep.ENTER_PASSWORD -> {
                            Text(
                                "Придумайте надёжный пароль для расшифровки файла бэкапа (не менее 6 символов).",
                                color = colors.textMuted,
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = exportCustomPassword,
                                onValueChange = { exportCustomPassword = it },
                                label = { Text("Пароль для файла") },
                                singleLine = true,
                                visualTransformation = if (exportPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { exportPasswordVisible = !exportPasswordVisible }) {
                                        Text(if (exportPasswordVisible) "🙈" else "👁️", fontSize = 16.sp)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        ExportStep.SHOW_MNEMONIC -> {
                            Text(
                                "Запишите эти 12 слов в точном порядке и сохраните в надёжном месте. Они понадобятся для восстановления:",
                                color = colors.textMuted,
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                exportMnemonicWords.chunked(3).forEachIndexed { rowIdx, chunk ->
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
                                                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "$index. $word",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = colors.textPrimary,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("Penik Mnemonic", exportMnemonicPhrase)
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "12 слов скопированы в буфер!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("📋 Скопировать фразу", fontSize = 13.sp)
                            }
                        }

                        ExportStep.VERIFY_MNEMONIC -> {
                            Text(
                                "Подтвердите, что вы записали фразу. Выберите указанные слова из предложенных вариантов:",
                                color = colors.textMuted,
                                fontSize = 13.sp,
                                lineHeight = 17.sp
                            )

                            // Quiz 1
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Слово #${quizIdx1 + 1}:",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    quizOptions1.forEach { word ->
                                        val isSelected = (quizSelected1 == word)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) colors.accent else colors.background)
                                                .border(1.dp, if (isSelected) colors.accent else colors.border, RoundedCornerShape(8.dp))
                                                .clickable { quizSelected1 = word }
                                                .padding(vertical = 8.dp, horizontal = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = word,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isSelected) colors.panel else colors.textPrimary,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            // Quiz 2
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Слово #${quizIdx2 + 1}:",
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    quizOptions2.forEach { word ->
                                        val isSelected = (quizSelected2 == word)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSelected) colors.accent else colors.background)
                                                .border(1.dp, if (isSelected) colors.accent else colors.border, RoundedCornerShape(8.dp))
                                                .clickable { quizSelected2 = word }
                                                .padding(vertical = 8.dp, horizontal = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = word,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isSelected) colors.panel else colors.textPrimary,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (exportStep) {
                    ExportStep.SELECT_METHOD -> {
                        // Handled via cards
                    }
                    ExportStep.ENTER_PASSWORD -> {
                        Button(
                            onClick = {
                                if (exportCustomPassword.length < 6) {
                                    Toast.makeText(context, "Пароль должен быть не менее 6 символов", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                finalExportPassphrase = exportCustomPassword
                                createDocLauncher.launch("penik_backup_${System.currentTimeMillis()}.penikbackup")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Сохранить в файл")
                        }
                    }
                    ExportStep.SHOW_MNEMONIC -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { exportStep = ExportStep.VERIFY_MNEMONIC }
                            ) {
                                Text("Проверить", fontSize = 13.sp)
                            }
                            Button(
                                onClick = {
                                    finalExportPassphrase = exportMnemonicPhrase
                                    createDocLauncher.launch("penik_backup_${System.currentTimeMillis()}.penikbackup")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                            ) {
                                Text("Сохранить", fontSize = 13.sp)
                            }
                        }
                    }
                    ExportStep.VERIFY_MNEMONIC -> {
                        val isCorrect1 = (quizSelected1 == exportMnemonicWords.getOrNull(quizIdx1))
                        val isCorrect2 = (quizSelected2 == exportMnemonicWords.getOrNull(quizIdx2))
                        val isAllCorrect = isCorrect1 && isCorrect2

                        Button(
                            enabled = isAllCorrect,
                            onClick = {
                                finalExportPassphrase = exportMnemonicPhrase
                                createDocLauncher.launch("penik_backup_${System.currentTimeMillis()}.penikbackup")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Готово, сохранить")
                        }
                    }
                }
            },
            dismissButton = {
                when (exportStep) {
                    ExportStep.SELECT_METHOD -> {
                        TextButton(onClick = { showExportDialog = false }) {
                            Text("Отмена", color = colors.textMuted)
                        }
                    }
                    ExportStep.ENTER_PASSWORD -> {
                        TextButton(onClick = { exportStep = ExportStep.SELECT_METHOD }) {
                            Text("Назад", color = colors.textMuted)
                        }
                    }
                    ExportStep.SHOW_MNEMONIC -> {
                        TextButton(onClick = { exportStep = ExportStep.SELECT_METHOD }) {
                            Text("Назад", color = colors.textMuted)
                        }
                    }
                    ExportStep.VERIFY_MNEMONIC -> {
                        TextButton(onClick = { exportStep = ExportStep.SHOW_MNEMONIC }) {
                            Text("Назад к фразе", color = colors.textMuted)
                        }
                    }
                }
            },
            containerColor = colors.panel
        )
    }

    // --- Import History Dialog ---
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
                        "Введите пароль или мнемоническую фразу (12 слов), которая использовалась при создании резервной копии.",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        label = { Text("Пароль или 12 слов") },
                        singleLine = true,
                        visualTransformation = if (importPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { importPasswordVisible = !importPasswordVisible }) {
                                Text(if (importPasswordVisible) "🙈" else "👁️", fontSize = 16.sp)
                            }
                        },
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

    // --- Cloud Backup Dialog ---
    if (showCloudBackupDialog) {
        AlertDialog(
            onDismissRequest = {
                showCloudBackupDialog = false
                cloudPassphrase = ""
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
                        value = cloudPassphrase,
                        onValueChange = { cloudPassphrase = it },
                        label = { Text("Пароль или мнемоника") },
                        singleLine = true,
                        visualTransformation = if (cloudPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { cloudPasswordVisible = !cloudPasswordVisible }) {
                                Text(if (cloudPasswordVisible) "🙈" else "👁️", fontSize = 16.sp)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            if (cloudPassphrase.isBlank()) {
                                Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            isBackupLoading = true
                            viewModel.uploadKeyBackup(cloudPassphrase) { res ->
                                isBackupLoading = false
                                res.fold(
                                    onSuccess = {
                                        Toast.makeText(context, "Резервная копия ключей создана на сервере!", Toast.LENGTH_SHORT).show()
                                        showCloudBackupDialog = false
                                        cloudPassphrase = ""
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
                            if (cloudPassphrase.isBlank()) {
                                Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            isBackupLoading = true
                            viewModel.restoreKeyBackup(cloudPassphrase) { res ->
                                isBackupLoading = false
                                res.fold(
                                    onSuccess = {
                                        Toast.makeText(context, "Ключи E2EE успешно восстановлены!", Toast.LENGTH_SHORT).show()
                                        showCloudBackupDialog = false
                                        cloudPassphrase = ""
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
                    cloudPassphrase = ""
                }) {
                    Text("Отмена", color = colors.textMuted)
                }
            },
            containerColor = colors.panel
        )
    }
}
