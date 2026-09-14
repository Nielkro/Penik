package niel.kro.penik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import niel.kro.penik.ui.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerBottomSheet(
    onDismiss: () -> Unit,
    onPickPhotoOrVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickAudio: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.panel,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp, top = 4.dp)
        ) {
            Text(
                text = "Прикрепить вложение",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = LocalAppColors.current.textPrimary,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
            )

            AttachmentItemRow(
                emoji = "🖼️",
                title = "Фото или видео",
                subtitle = "Быстрая отправка, очищает геометку и EXIF",
                onClick = {
                    onDismiss()
                    onPickPhotoOrVideo()
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            AttachmentItemRow(
                emoji = "📁",
                title = "Файл или документ",
                subtitle = "Без сжатия, оригинальное качество с метаданными",
                onClick = {
                    onDismiss()
                    onPickDocument()
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            AttachmentItemRow(
                emoji = "📷",
                title = "Сделать снимок",
                subtitle = "Сфотографировать с камеры прямо сейчас",
                onClick = {
                    onDismiss()
                    onTakePhoto()
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            AttachmentItemRow(
                emoji = "🎵",
                title = "Аудиозапись",
                subtitle = "Музыка и звуковые дорожки",
                onClick = {
                    onDismiss()
                    onPickAudio()
                }
            )
        }
    }
}

@Composable
private fun AttachmentItemRow(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(LocalAppColors.current.panelSecondary.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(LocalAppColors.current.inputBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                fontSize = 22.sp
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalAppColors.current.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = LocalAppColors.current.textMuted
            )
        }
    }
}
