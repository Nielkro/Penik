package niel.kro.penik.ui.screen.profile

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.AvatarCropDialog
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.theme.LocalAppColors
import niel.kro.penik.ui.viewmodel.ProfileViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit = {},
    onPairingScanner: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val displayName = viewModel.name.ifBlank { viewModel.nickname }
    val nickname = viewModel.nickname

    var showAvatarOptions by remember { mutableStateOf(false) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingCropUri = uri
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            pendingCropUri = tempCameraUri
        }
    }

    LaunchedEffect(uiState.error, uiState.successMsg) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
        uiState.successMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }

    // Avatar Crop & Edit Dialog
    pendingCropUri?.let { uri ->
        AvatarCropDialog(
            imageUri = uri,
            onDismiss = { pendingCropUri = null },
            onCropDone = { bytes ->
                pendingCropUri = null
                viewModel.uploadAvatar(bytes)
            }
        )
    }

    // Bottom Sheet for Photo Selection
    if (showAvatarOptions) {
        ModalBottomSheet(
            onDismissRequest = { showAvatarOptions = false },
            containerColor = LocalAppColors.current.panel,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp, top = 8.dp)
            ) {
                Text(
                    text = "Фотография профиля",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAvatarOptions = false
                            try {
                                val avatarsDir = File(context.cacheDir, "avatars").apply { mkdirs() }
                                val tempFile = File(avatarsDir, "camera_${System.currentTimeMillis()}.jpg")
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    tempFile
                                )
                                tempCameraUri = uri
                                cameraLauncher.launch(uri)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Не удалось открыть камеру", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = null,
                        tint = LocalAppColors.current.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Сделать снимок",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocalAppColors.current.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showAvatarOptions = false
                            imagePickerLauncher.launch("image/*")
                        }
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        tint = LocalAppColors.current.accent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Выбрать из галереи",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = LocalAppColors.current.textPrimary
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onBack != null) {
            TopAppBar(
                title = { Text("Профиль", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = LocalAppColors.current.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LocalAppColors.current.background,
                    titleContentColor = LocalAppColors.current.textPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(if (onBack != null) 16.dp else 40.dp))

        // Avatar with Camera icon badge at bottom-right
        Box(
            modifier = Modifier.size(104.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .clickable { showAvatarOptions = true },
                contentAlignment = Alignment.Center
            ) {
                UserAvatar(
                    userId = viewModel.userId,
                    name = displayName,
                    size = 96.dp,
                    avatarKey = uiState.avatarUpdateKey
                )

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LocalAppColors.current.background.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = LocalAppColors.current.accent,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Camera Icon Badge in Bottom-Right
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(LocalAppColors.current.accent)
                    .clickable { showAvatarOptions = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Сменить аватар",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (displayName.isNotBlank()) {
            Text(
                text = displayName,
                color = LocalAppColors.current.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (nickname.isNotBlank()) {
            Text(
                text = "@$nickname",
                color = LocalAppColors.current.textMuted,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onPairingScanner,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Подключить устройство", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { viewModel.logout(onLogout) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = LocalAppColors.current.danger.copy(alpha = 0.15f)
            )
        ) {
            Text(
                text = "Выйти",
                color = LocalAppColors.current.danger,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
