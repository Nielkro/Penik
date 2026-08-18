package niel.kro.penik.ui.screen.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import niel.kro.penik.ui.notification.AppNotificationManager
import niel.kro.penik.ui.theme.LocalAppColors

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationDebugEntryPoint {
    fun appNotificationManager(): AppNotificationManager
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDebugScreen(
    onBack: () -> Unit
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appNotificationManager = remember(context) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationDebugEntryPoint::class.java
        )
        entryPoint.appNotificationManager()
    }

    var senderName by remember { mutableStateOf("Алексей Бурунов") }
    var messageText by remember { mutableStateOf("ваппавпва") }
    var attachPhoto by remember { mutableStateOf(false) }
    var usePhotoAvatar by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Тест уведомлений", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚡ Быстрые пресеты",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            // Preset 1: Text message
            PresetCard(
                title = "💬 Обычное сообщение",
                subtitle = "Текст с аватаркой собеседника",
                onClick = {
                    scope.launch {
                        val avatar = createDemoAvatar("Алексей")
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8801L,
                            rawText = "ваппавпва",
                            timestamp = System.currentTimeMillis(),
                            overrideSenderName = "Алексей Бурунов",
                            customAvatarBitmap = avatar
                        )
                        Toast.makeText(context, "Уведомление отправлено", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Preset 2: Message with photo attachment
            PresetCard(
                title = "🖼️ Сообщение с фото",
                subtitle = "Аватарка + превью картинки справа",
                onClick = {
                    scope.launch {
                        val avatar = createDemoAvatar("Алексей")
                        val photo = createDemoQuoteImage()
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8801L,
                            rawText = "проблема цитат в интернете...",
                            timestamp = System.currentTimeMillis(),
                            overrideSenderName = "Алексей Бурунов",
                            customAvatarBitmap = avatar,
                            customImageBitmap = photo
                        )
                        Toast.makeText(context, "Фото-уведомление отправлено", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Preset 3: Video notification
            PresetCard(
                title = "🎬 Сообщение с видео",
                subtitle = "Превью видеоролика",
                onClick = {
                    scope.launch {
                        val avatar = createDemoAvatar("Ниэль")
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8802L,
                            rawText = "{\"type\":\"file\",\"file\":{\"mime\":\"video/mp4\",\"name\":\"video.mp4\"},\"text\":\"Смотри видео!\"}",
                            timestamp = System.currentTimeMillis(),
                            overrideSenderName = "Ниэль кро",
                            customAvatarBitmap = avatar
                        )
                        Toast.makeText(context, "Видео-уведомление отправлено", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Preset 4: Forwarded message
            PresetCard(
                title = "↪️ Пересланное сообщение",
                subtitle = "↪ Переслано от Ниэль кро: Привет",
                onClick = {
                    scope.launch {
                        val avatar = createDemoAvatar("Тест")
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8803L,
                            rawText = "{\"type\":\"fwd\",\"from\":\"Ниэль кро\",\"text\":\"Привет! Это пересланное сообщение\"}",
                            timestamp = System.currentTimeMillis(),
                            overrideSenderName = "Тестовый контакт",
                            customAvatarBitmap = avatar
                        )
                        Toast.makeText(context, "Уведомление отправлено", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Preset 5: Group chat message
            PresetCard(
                title = "👥 Групповой чат",
                subtitle = "Разработчики Penik • Алексей: Всем привет!",
                onClick = {
                    scope.launch {
                        val avatar = createDemoAvatar("Алексей")
                        appNotificationManager.showGroupMessageNotification(
                            groupId = 5501L,
                            senderUserId = 8801L,
                            rawText = "Всем привет! Релиз готов к тестированию 🚀",
                            timestamp = System.currentTimeMillis(),
                            overrideGroupName = "Разработчики Penik",
                            overrideSenderName = "Алексей Бурунов",
                            customAvatarBitmap = avatar
                        )
                        Toast.makeText(context, "Групповое уведомление отправлено", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Preset 6: Thread conversation (3 messages in a row)
            PresetCard(
                title = "💬 Цепочка из 3 сообщений",
                subtitle = "Проверка группировки нескольких сообщений в одном уведомлении",
                onClick = {
                    scope.launch {
                        val avatar = createDemoAvatar("Алексей")
                        val baseTime = System.currentTimeMillis()
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8801L,
                            rawText = "Привет!",
                            timestamp = baseTime - 4000,
                            overrideSenderName = "Алексей Бурунов",
                            customAvatarBitmap = avatar
                        )
                        delay(600)
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8801L,
                            rawText = "Ты тут?",
                            timestamp = baseTime - 2000,
                            overrideSenderName = "Алексей Бурунов",
                            customAvatarBitmap = avatar
                        )
                        delay(600)
                        appNotificationManager.showDirectMessageNotification(
                            chatUserId = 8801L,
                            rawText = "Смотри какую картинку нашел",
                            timestamp = baseTime,
                            overrideSenderName = "Алексей Бурунов",
                            customAvatarBitmap = avatar,
                            customImageBitmap = createDemoQuoteImage()
                        )
                        Toast.makeText(context, "Цепочка уведомлений отправлена", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "🛠️ Кастомный конструктор",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = colors.panel),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = senderName,
                        onValueChange = { senderName = it },
                        label = { Text("Имя собеседника") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border
                        )
                    )

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        label = { Text("Текст сообщения") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Прикрепить фото к сообщению", color = colors.textPrimary, fontSize = 14.sp)
                        Switch(
                            checked = attachPhoto,
                            onCheckedChange = { attachPhoto = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = colors.accent)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Использовать фото-аватарку", color = colors.textPrimary, fontSize = 14.sp)
                        Switch(
                            checked = usePhotoAvatar,
                            onCheckedChange = { usePhotoAvatar = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = colors.accent)
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                val avatar = if (usePhotoAvatar) createDemoAvatar(senderName) else null
                                val photo = if (attachPhoto) createDemoQuoteImage() else null
                                appNotificationManager.showDirectMessageNotification(
                                    chatUserId = 8899L,
                                    rawText = messageText,
                                    timestamp = System.currentTimeMillis(),
                                    overrideSenderName = senderName.ifBlank { "Собеседник" },
                                    customAvatarBitmap = avatar,
                                    customImageBitmap = photo
                                )
                                Toast.makeText(context, "Уведомление отправлено", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("🚀 Отправить уведомление", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    appNotificationManager.cancelAllNotifications()
                    Toast.makeText(context, "Все уведомления удалены", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("🗑️ Очистить все уведомления", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun PresetCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.panel),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, color = colors.textMuted, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Тест ›", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

private fun createDemoAvatar(name: String): Bitmap {
    val size = 128
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw stylish gradient circle
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val gradient = LinearGradient(
        0f, 0f, size.toFloat(), size.toFloat(),
        AndroidColor.rgb(54, 114, 246),
        AndroidColor.rgb(139, 92, 246),
        Shader.TileMode.CLAMP
    )
    paint.shader = gradient
    canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)

    // Draw face icon silhouette / smiley
    val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    // Eyes
    canvas.drawCircle(size * 0.38f, size * 0.42f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
    canvas.drawCircle(size * 0.62f, size * 0.42f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
    // Smile
    canvas.drawArc(
        RectF(size * 0.35f, size * 0.45f, size * 0.65f, size * 0.7f),
        20f, 140f, false, facePaint
    )

    return bitmap
}

private fun createDemoQuoteImage(): Bitmap {
    val width = 400
    val height = 400
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Background
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(244, 237, 219)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // Text quote
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(20, 20, 20)
        textSize = 28f
        isFakeBoldText = true
    }

    canvas.drawText("«Главная проблема", 24f, 80f, textPaint)
    canvas.drawText("цитат в интернете", 24f, 130f, textPaint)
    canvas.drawText("в том, что люди", 24f, 180f, textPaint)
    canvas.drawText("сразу верят в их", 24f, 230f, textPaint)
    canvas.drawText("подлинность.»", 24f, 280f, textPaint)

    val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(100, 100, 100)
        textSize = 22f
        isFakeBoldText = true
    }
    canvas.drawText("— В. И. Ленин", 24f, 350f, authorPaint)

    return bitmap
}
