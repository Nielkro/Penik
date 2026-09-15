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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

private enum class WizardStep {
    SELECT_METHOD,
    ENTER_PASSWORD,
    SHOW_MNEMONIC,
    VERIFY_MNEMONIC
}

@Composable
private fun BackupPassphraseWizardDialog(
    title: String,
    description: String,
    confirmActionLabel: String,
    onDismiss: () -> Unit,
    onConfirmed: (passphrase: String) -> Unit,
    generateMnemonic: (count: Int) -> String
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current

    var step by remember { mutableStateOf(WizardStep.SELECT_METHOD) }
    var customPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var mnemonicPhrase by remember { mutableStateOf("") }
    var mnemonicWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizIdx1 by remember { mutableIntStateOf(0) }
    var quizIdx2 by remember { mutableIntStateOf(1) }
    var quizOptions1 by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizOptions2 by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizSelected1 by remember { mutableStateOf<String?>(null) }
    var quizSelected2 by remember { mutableStateOf<String?>(null) }

    fun initMnemonic() {
        val phrase = generateMnemonic(12)
        mnemonicPhrase = phrase
        val words = phrase.split(" ").filter { it.isNotBlank() }
        mnemonicWords = words

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
        step = WizardStep.SHOW_MNEMONIC
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (step) {
                    WizardStep.SELECT_METHOD -> title
                    WizardStep.ENTER_PASSWORD -> "Свой пароль"
                    WizardStep.SHOW_MNEMONIC -> "12 слов (Мнемоника)"
                    WizardStep.VERIFY_MNEMONIC -> "Проверка мнемоники"
                },
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (step) {
                    WizardStep.SELECT_METHOD -> {
                        Text(
                            text = description,
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
                                        customPassword = ""
                                        step = WizardStep.ENTER_PASSWORD
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
                                        initMnemonic()
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

                    WizardStep.ENTER_PASSWORD -> {
                        Text(
                            text = "Придумайте надёжный пароль (минимум 6 символов) для шифрования.",
                            color = colors.textMuted,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = customPassword,
                            onValueChange = { customPassword = it },
                            label = { Text("Пароль") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "🙈" else "👁️", fontSize = 16.sp)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    WizardStep.SHOW_MNEMONIC -> {
                        Text(
                            text = "Запишите эти 12 слов в точном порядке и сохраните в надёжном месте. Они понадобятся для восстановления:",
                            color = colors.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            mnemonicWords.chunked(3).forEachIndexed { rowIdx, chunk ->
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
                                val clip = ClipData.newPlainText("Penik Mnemonic", mnemonicPhrase)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "12 слов скопированы в буфер!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("📋 Скопировать фразу", fontSize = 13.sp)
                        }
                    }

                    WizardStep.VERIFY_MNEMONIC -> {
                        Text(
                            text = "Подтвердите сохранность фразы. Выберите указанные слова из предложенных вариантов:",
                            color = colors.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 17.sp
                        )

                        // Quiz 1
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Слово #${quizIdx1 + 1}:",
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
                                text = "Слово #${quizIdx2 + 1}:",
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
            when (step) {
                WizardStep.SELECT_METHOD -> {}
                WizardStep.ENTER_PASSWORD -> {
                    Button(
                        onClick = {
                            if (customPassword.length < 6) {
                                Toast.makeText(context, "Пароль должен быть не менее 6 символов", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            onConfirmed(customPassword)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text(confirmActionLabel)
                    }
                }
                WizardStep.SHOW_MNEMONIC -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { step = WizardStep.VERIFY_MNEMONIC }
                        ) {
                            Text("Проверить", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { onConfirmed(mnemonicPhrase) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text(confirmActionLabel, fontSize = 13.sp)
                        }
                    }
                }
                WizardStep.VERIFY_MNEMONIC -> {
                    val isCorrect1 = (quizSelected1 == mnemonicWords.getOrNull(quizIdx1))
                    val isCorrect2 = (quizSelected2 == mnemonicWords.getOrNull(quizIdx2))
                    val isAllCorrect = isCorrect1 && isCorrect2

                    Button(
                        enabled = isAllCorrect,
                        onClick = { onConfirmed(mnemonicPhrase) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                    ) {
                        Text("Готово, $confirmActionLabel")
                    }
                }
            }
        },
        dismissButton = {
            when (step) {
                WizardStep.SELECT_METHOD -> {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = colors.textMuted)
                    }
                }
                WizardStep.ENTER_PASSWORD, WizardStep.SHOW_MNEMONIC -> {
                    TextButton(onClick = { step = WizardStep.SELECT_METHOD }) {
                        Text("Назад", color = colors.textMuted)
                    }
                }
                WizardStep.VERIFY_MNEMONIC -> {
                    TextButton(onClick = { step = WizardStep.SHOW_MNEMONIC }) {
                        Text("Назад к фразе", color = colors.textMuted)
                    }
                }
            }
        },
        containerColor = colors.panel
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current

    var showCloudBackupWizard by remember { mutableStateOf(false) }
    var showCloudRestoreDialog by remember { mutableStateOf(false) }
    var showExportWizard by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    var pendingExportPassphrase by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }
    var importPasswordVisible by remember { mutableStateOf(false) }
    var cloudRestorePassphrase by remember { mutableStateOf("") }
    var cloudRestorePasswordVisible by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var isBackupLoading by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && pendingExportPassphrase.isNotBlank()) {
            isBackupLoading = true
            viewModel.exportHistoryToFile(pendingExportPassphrase, uri, context) { res ->
                isBackupLoading = false
                showExportWizard = false
                pendingExportPassphrase = ""
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

            // Create Cloud Key Backup row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable { showCloudBackupWizard = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("☁️ Резервная копия ключей в облаке", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Зашифровать ключи паролем или 12 словами и сохранить на сервере",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
                Text("›", color = colors.textMuted, fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Restore Cloud Key Backup row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.panel)
                    .clickable { showCloudRestoreDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🔄 Восстановить ключи из облака", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = "Восстановить ключи E2EE по паролю или 12 словам",
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
                        pendingExportPassphrase = ""
                        showExportWizard = true
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

    // --- Export History Wizard Dialog ---
    if (showExportWizard) {
        BackupPassphraseWizardDialog(
            title = "Экспорт всей истории",
            description = "История чатов, сообщений, групп и ключи шифрования будут сохранены в зашифрованный файл .penikbackup (AES-256-GCM / PBKDF2 600,000).",
            confirmActionLabel = "Сохранить",
            onDismiss = { showExportWizard = false },
            onConfirmed = { passphrase ->
                pendingExportPassphrase = passphrase
                createDocLauncher.launch("penik_backup_${System.currentTimeMillis()}.penikbackup")
            },
            generateMnemonic = { viewModel.generateMnemonicPhrase(it) }
        )
    }

    // --- Cloud Backup Wizard Dialog ---
    if (showCloudBackupWizard) {
        BackupPassphraseWizardDialog(
            title = "Резервная копия ключей в облаке",
            description = "Зашифруйте ваши ключи E2EE и эпохи групп паролем или мнемонической фразой (12 слов) для безопасного хранения на сервере.",
            confirmActionLabel = "Создать",
            onDismiss = { showCloudBackupWizard = false },
            onConfirmed = { passphrase ->
                isBackupLoading = true
                viewModel.uploadKeyBackup(passphrase) { res ->
                    isBackupLoading = false
                    showCloudBackupWizard = false
                    res.fold(
                        onSuccess = {
                            Toast.makeText(context, "Резервная копия ключей успешно создана на сервере!", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { e ->
                            Toast.makeText(context, "Ошибка создания копии: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
            generateMnemonic = { viewModel.generateMnemonicPhrase(it) }
        )
    }

    // --- Cloud Restore Dialog ---
    if (showCloudRestoreDialog) {
        AlertDialog(
            onDismissRequest = {
                showCloudRestoreDialog = false
                cloudRestorePassphrase = ""
            },
            title = {
                Text("Восстановление ключей из облака", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Введите пароль или мнемоническую фразу (12 слов), которая использовалась при создании резервной копии ключей на сервере.",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = cloudRestorePassphrase,
                        onValueChange = { cloudRestorePassphrase = it },
                        label = { Text("Пароль или 12 слов") },
                        singleLine = true,
                        visualTransformation = if (cloudRestorePasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { cloudRestorePasswordVisible = !cloudRestorePasswordVisible }) {
                                Text(if (cloudRestorePasswordVisible) "🙈" else "👁️", fontSize = 16.sp)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cloudRestorePassphrase.isBlank()) {
                            Toast.makeText(context, "Введите пароль или мнемонику", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isBackupLoading = true
                        viewModel.restoreKeyBackup(cloudRestorePassphrase) { res ->
                            isBackupLoading = false
                            showCloudRestoreDialog = false
                            cloudRestorePassphrase = ""
                            res.fold(
                                onSuccess = {
                                    Toast.makeText(context, "Ключи шифрования успешно восстановлены!", Toast.LENGTH_SHORT).show()
                                },
                                onFailure = { e ->
                                    Toast.makeText(context, "Ошибка восстановления: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Восстановить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCloudRestoreDialog = false
                    cloudRestorePassphrase = ""
                }) {
                    Text("Отмена", color = colors.textMuted)
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
}
