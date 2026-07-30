package niel.kro.penik.ui.screen.auth

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.InitialsAvatar
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.theme.Accent
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Border
import niel.kro.penik.ui.theme.Danger
import niel.kro.penik.ui.theme.InputBg
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.AuthMode
import niel.kro.penik.ui.viewmodel.AuthViewModel

@Composable
fun AuthScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var showPassword by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var newE2eePassword by remember { mutableStateOf("") }

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bytes = context.contentResolver.openInputStream(it)?.readBytes()
            viewModel.updateAvatar(bytes)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp)
    ) {
        // Back button (top left)
        if (state.mode != AuthMode.WELCOME) {
            IconButton(
                onClick = { viewModel.goBack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp)
            ) {
                Text(
                    text = "←",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Steps Progress Bar
            val maxSteps = if (state.mode == AuthMode.REGISTER) 4 else 4
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0 until maxSteps) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (i <= state.step) Accent else Color.White.copy(alpha = 0.05f)
                            )
                    )
                }
            }
        }

        // Main wizard contents
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.mode) {
                AuthMode.WELCOME -> {
                    Text(
                        text = "Penik",
                        color = Accent,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Защищенный мессенджер с E2EE",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(48.dp))
                    Button(
                        onClick = { viewModel.setMode(AuthMode.REGISTER) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                    ) {
                        Text("Регистрация", fontSize = 16.sp, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.setMode(AuthMode.LOGIN) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .border(1.dp, Border, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                    ) {
                        Text("Войти", fontSize = 16.sp, color = TextPrimary)
                    }
                }

                AuthMode.REGISTER -> {
                    when (state.step) {
                        0 -> { // Registration: Nickname
                            Text("Выберите никнейм", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Уникальное имя для поиска в сети", fontSize = 13.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedTextField(
                                value = state.nickname,
                                onValueChange = viewModel::updateNickname,
                                label = { Text("Никнейм") },
                                placeholder = { Text("username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Продолжить",
                                isLoading = state.isLoading,
                                onClick = viewModel::submitRegisterNickname
                            )
                        }

                        1 -> { // Registration: Password
                            Text("Создайте пароль", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Используется для входа в ваш аккаунт", fontSize = 13.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedTextField(
                                value = state.password,
                                onValueChange = viewModel::updatePassword,
                                label = { Text("Пароль") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Продолжить",
                                isLoading = state.isLoading,
                                onClick = viewModel::submitRegisterPassword
                            )
                        }

                        2 -> { // Registration: E2EE Password
                            Text("Создайте e2ee-пароль", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Ключ шифрования переписок. Знаете его только вы.", fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedTextField(
                                value = state.e2eePassword,
                                onValueChange = viewModel::updateE2eePassword,
                                label = { Text("Пароль E2EE") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Text(if (showPassword) "🙈" else "👁️", color = TextMuted)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Сохранить и продолжить",
                                isLoading = state.isLoading,
                                onClick = viewModel::submitRegisterE2eePassword
                            )
                        }

                        3 -> { // Registration: Profile Name / Avatar
                            Text("Ваша аватарка и имя", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Загрузите фото и укажите отображаемое имя", fontSize = 13.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(24.dp))

                            // Clickable avatar placeholder
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.05f))
                                    .border(1.dp, Border, CircleShape)
                                    .clickable { imageLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                val avatarBytes = state.avatarBytes
                                if (avatarBytes != null) {
                                    val bitmap = remember(avatarBytes) {
                                        android.graphics.BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size)
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = "Preview",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    Text("+", fontSize = 32.sp, color = TextMuted)
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
                            OutlinedTextField(
                                value = state.name,
                                onValueChange = viewModel::updateName,
                                label = { Text("Имя") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Завершить регистрацию",
                                isLoading = state.isLoading,
                                onClick = { viewModel.submitRegisterProfile(onLoginSuccess) }
                            )
                        }
                    }
                }

                AuthMode.LOGIN -> {
                    when (state.step) {
                        0 -> { // Login: Nickname
                            Text("Введите никнейм", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Укажите ваш никнейм для входа", fontSize = 13.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedTextField(
                                value = state.nickname,
                                onValueChange = viewModel::updateNickname,
                                label = { Text("Никнейм") },
                                placeholder = { Text("@username") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Продолжить",
                                isLoading = state.isLoading,
                                onClick = viewModel::submitLoginNickname
                            )
                        }

                        1 -> { // Login: Confirm profile ("Это вы?")
                            Text("Это ваш аккаунт?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Проверьте данные перед входом", fontSize = 13.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(32.dp))

                            state.tempUserId?.let { uid ->
                                UserAvatar(userId = uid, name = state.tempName ?: "", size = 88.dp)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(state.tempName ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("@${state.nickname}", fontSize = 14.sp, color = TextMuted)

                            Spacer(modifier = Modifier.height(32.dp))
                            PrimaryButton(
                                text = "Да, это я",
                                isLoading = state.isLoading,
                                onClick = viewModel::confirmLoginProfile
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = { viewModel.goBack() }) {
                                Text("Войти в другой аккаунт", color = Accent, fontSize = 14.sp)
                            }
                        }

                        2 -> { // Login: Password
                            Text("Введите пароль", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Пароль от вашего аккаунта Penik", fontSize = 13.sp, color = TextMuted)
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedTextField(
                                value = state.password,
                                onValueChange = viewModel::updatePassword,
                                label = { Text("Пароль") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Войти",
                                isLoading = state.isLoading,
                                onClick = { viewModel.submitLoginPassword(onLoginSuccess) }
                            )
                        }

                        3 -> { // Login: E2EE Password
                            Text("Восстановление ключей", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Введите e2ee-пароль для расшифрования сообщений", fontSize = 13.sp, color = TextMuted, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(32.dp))
                            OutlinedTextField(
                                value = state.e2eePassword,
                                onValueChange = viewModel::updateE2eePassword,
                                label = { Text("Пароль E2EE") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Text(if (showPassword) "🙈" else "👁️", color = TextMuted)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = InputBg,
                                    unfocusedContainerColor = InputBg,
                                    focusedBorderColor = Accent,
                                    unfocusedBorderColor = Border,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    focusedLabelColor = Accent,
                                    unfocusedLabelColor = TextMuted
                                )
                            )
                            RenderError(state.error)
                            Spacer(modifier = Modifier.height(24.dp))
                            PrimaryButton(
                                text = "Восстановить переписку",
                                isLoading = state.isLoading,
                                onClick = { viewModel.submitLoginE2eePassword(onLoginSuccess) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = { viewModel.skipE2eeBackup(onLoginSuccess) }) {
                                Text("Войти как новое устройство (создать свои ключи)", color = Accent, fontSize = 13.sp, textAlign = TextAlign.Center)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { showResetDialog = true }) {
                                Text("Забыли e2ee-пароль? (Начать с чистого листа)", color = TextMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Начать с чистого листа?") },
            text = {
                Column {
                    Text("Внимание! Старые зашифрованные сообщения не смогут быть расшифрованы. Введите новый e2ee-пароль для шифрования будущих переписок:")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newE2eePassword,
                        onValueChange = { newE2eePassword = it },
                        label = { Text("Новый E2EE пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        viewModel.submitLoginE2eeReset(newE2eePassword, onLoginSuccess)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    Text("Сбросить ключи", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Отмена", color = TextPrimary)
                }
            },
            containerColor = Background,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }
}

@Composable
private fun RenderError(error: String?) {
    if (error != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = error,
            color = Danger,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            disabledContainerColor = Accent.copy(alpha = 0.5f)
        ),
        enabled = !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = TextPrimary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }
    }
}
