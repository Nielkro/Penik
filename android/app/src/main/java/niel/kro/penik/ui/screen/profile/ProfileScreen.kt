package niel.kro.penik.ui.screen.profile

import niel.kro.penik.ui.theme.LocalAppColors

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.UserAvatar
import niel.kro.penik.ui.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onLogout: () -> Unit = {},
    onPairingScanner: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val displayName = viewModel.name.ifBlank { viewModel.nickname }
    val nickname = viewModel.nickname

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = stream.readBytes()
                    viewModel.uploadAvatar(bytes)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
            }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .clickable { imagePickerLauncher.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            UserAvatar(
                userId = viewModel.userId,
                name = displayName,
                size = 96.dp,
                avatarKey = uiState.avatarUpdateKey
            )

            if (uiState.isLoading) {
                CircularProgressIndicator(
                    color = LocalAppColors.current.accent,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Нажмите на аватар для смены",
            color = LocalAppColors.current.textMuted,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

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

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "ID: ${viewModel.userId}",
            color = LocalAppColors.current.textMuted,
            fontSize = 13.sp
        )

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
