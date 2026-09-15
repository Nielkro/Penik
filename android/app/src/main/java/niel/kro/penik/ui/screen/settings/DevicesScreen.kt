package niel.kro.penik.ui.screen.settings

import niel.kro.penik.ui.theme.LocalAppColors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.viewmodel.DevicesViewModel

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit = {},
    onPairingScanner: () -> Unit = {},
    viewModel: DevicesViewModel = hiltViewModel()
) {
    val colors = LocalAppColors.current
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Мои устройства", fontWeight = FontWeight.Bold, fontSize = 22.sp) },
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
                .padding(16.dp)
        ) {
            Button(
                onClick = onPairingScanner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent
                )
            ) {
                Text(
                    text = "Синхронизация истории (сканировать QR)",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                uiState.loading && uiState.devices.isEmpty() -> {
                    CircularProgressIndicator(color = colors.accent)
                }
                uiState.error != null && uiState.devices.isEmpty() -> {
                    Text(uiState.error ?: "", color = colors.danger, fontSize = 14.sp)
                }
                else -> {
                    LazyColumn {
                        items(uiState.devices) { device ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                val title = device.deviceName.ifBlank { device.platform.ifBlank { "Устройство" } }
                                val subtitle = if (device.deviceName.isNotBlank() && device.platform.isNotBlank() && device.deviceName != device.platform) {
                                    device.platform
                                } else null

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = title,
                                        color = colors.textPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (device.isCurrent) {
                                        Text(
                                            text = "  · это устройство",
                                            color = colors.accent,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Normal
                                        )
                                    }
                                }
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        color = colors.textMuted,
                                        fontSize = 13.sp
                                    )
                                }
                                Text(
                                    text = if (device.location.isNotBlank()) "📍 ${device.location}" else "📍 Местоположение неизвестно",
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                                val statusText = when {
                                    device.isOnline -> "в сети"
                                    device.hasSession -> {
                                        val formatted = niel.kro.penik.ui.util.formatPresence(false, device.lastSeen)
                                        if (formatted.isNotBlank()) formatted else "не в сети"
                                    }
                                    else -> "нет активной сессии"
                                }
                                val statusColor = if (device.isOnline) colors.accent else colors.textMuted
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
