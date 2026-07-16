package niel.kro.penik.ui.screen.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import niel.kro.penik.ui.components.InitialsAvatar
import niel.kro.penik.ui.theme.Background
import niel.kro.penik.ui.theme.Danger
import niel.kro.penik.ui.theme.TextMuted
import niel.kro.penik.ui.theme.TextPrimary
import niel.kro.penik.ui.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val displayName = viewModel.name.ifBlank { viewModel.nickname }
    val nickname = viewModel.nickname

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        InitialsAvatar(name = displayName, size = 80.dp)

        Spacer(modifier = Modifier.height(16.dp))

        if (displayName.isNotBlank()) {
            Text(
                text = displayName,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (nickname.isNotBlank()) {
            Text(
                text = "@$nickname",
                color = TextMuted,
                fontSize = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "ID: ${viewModel.userId}",
            color = TextMuted,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { viewModel.logout {} },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Danger.copy(alpha = 0.15f)
            )
        ) {
            Text(
                text = "Выйти",
                color = Danger,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
