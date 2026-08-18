package niel.kro.penik.ui.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import niel.kro.penik.MainActivity
import niel.kro.penik.R
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.repository.SecureTokenStorage
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatDao: ChatDao,
    private val groupDao: GroupDao,
    private val secureTokenStorage: SecureTokenStorage
) {

    companion object {
        const val CHANNEL_ID_MESSAGES = "penik_messages"
        const val GROUP_KEY_MESSAGES = "niel.kro.penik.MESSAGES"
        const val SUMMARY_NOTIFICATION_ID = 999999

        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_CHAT_USER_ID = "chatUserId"
        const val EXTRA_CHAT_NAME = "chatName"
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"

        @Volatile
        var activeChatKey: String? = null

        fun setActiveChat(key: String?) {
            activeChatKey = key
        }

        fun clearActiveChat(key: String?) {
            if (activeChatKey == key) {
                activeChatKey = null
            }
        }
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory message thread history for rich MessagingStyle notifications
    private data class ThreadMessage(val text: CharSequence, val timestamp: Long, val senderPerson: Person)
    private val directThreads = ConcurrentHashMap<Long, MutableList<ThreadMessage>>()
    private val groupThreads = ConcurrentHashMap<Long, MutableList<ThreadMessage>>()

    init {
        createNotificationChannels()
    }

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих личных и групповых сообщениях"
                enableLights(true)
                lightColor = 0xFF409CFF.toInt()
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun cancelChatNotification(chatUserId: Long) {
        directThreads.remove(chatUserId)
        notificationManager.cancel(chatUserId.toInt())
    }

    fun cancelGroupNotification(groupId: Long) {
        groupThreads.remove(groupId)
        notificationManager.cancel((100000 + groupId).toInt())
    }

    suspend fun showDirectMessageNotification(
        chatUserId: Long,
        rawText: String,
        timestamp: Long
    ) {
        // Do not notify if the user is actively viewing this direct chat
        if (activeChatKey == "direct_$chatUserId") return

        val myUserId = secureTokenStorage.getUserId() ?: 0L
        val chatEntity = chatDao.getChat(chatUserId)
        val chatName = chatEntity?.name?.ifBlank { "Пользователь #$chatUserId" } ?: "Пользователь #$chatUserId"

        val previewText = formatMessagePreview(rawText)
        val senderPerson = createPerson(chatName, chatUserId)
        val myPerson = Person.Builder().setName("Вы").setKey(myUserId.toString()).build()

        val messagesList = directThreads.computeIfAbsent(chatUserId) { mutableListOf() }
        synchronized(messagesList) {
            messagesList.add(ThreadMessage(previewText, timestamp, senderPerson))
            if (messagesList.size > 15) {
                messagesList.removeAt(0)
            }
        }

        val messagingStyle = NotificationCompat.MessagingStyle(myPerson)
        synchronized(messagesList) {
            for (msg in messagesList) {
                messagingStyle.addMessage(msg.text, msg.timestamp, msg.senderPerson)
            }
        }

        // Tap action: open MainActivity and navigate to ChatRoom
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CHAT_USER_ID, chatUserId)
            putExtra(EXTRA_CHAT_NAME, chatName)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            chatUserId.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Direct reply action
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Ответить")
            .build()
        val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
            putExtra(EXTRA_CHAT_USER_ID, chatUserId)
            putExtra(EXTRA_CHAT_NAME, chatName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            chatUserId.toInt(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Ответить",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // Mark as read action
        val readIntent = Intent(context, MarkAsReadReceiver::class.java).apply {
            putExtra(EXTRA_CHAT_USER_ID, chatUserId)
        }
        val readPendingIntent = PendingIntent.getBroadcast(
            context,
            (chatUserId + 500000).toInt(),
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val readAction = NotificationCompat.Action.Builder(
            0,
            "Прочитано",
            readPendingIntent
        ).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .addAction(readAction)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_MESSAGES)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            notificationManager.notify(chatUserId.toInt(), notification)
            showSummaryNotification()
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted yet
        }
    }

    suspend fun showGroupMessageNotification(
        groupId: Long,
        senderUserId: Long,
        rawText: String,
        timestamp: Long
    ) {
        // Do not notify if the user is actively viewing this group
        if (activeChatKey == "group_$groupId") return

        val myUserId = secureTokenStorage.getUserId() ?: 0L
        if (senderUserId == myUserId) return

        val groupEntity = groupDao.getGroup(groupId)
        val groupName = groupEntity?.name?.ifBlank { "Группа #$groupId" } ?: "Группа #$groupId"
        val members = groupDao.getMembers(groupId)
        val senderMember = members.find { it.userId == senderUserId }
        val senderName = senderMember?.name?.ifBlank { "Участник #$senderUserId" } ?: "Участник #$senderUserId"

        val previewText = formatMessagePreview(rawText)
        val senderPerson = createPerson(senderName, senderUserId)
        val myPerson = Person.Builder().setName("Вы").setKey(myUserId.toString()).build()

        val messagesList = groupThreads.computeIfAbsent(groupId) { mutableListOf() }
        synchronized(messagesList) {
            messagesList.add(ThreadMessage(previewText, timestamp, senderPerson))
            if (messagesList.size > 15) {
                messagesList.removeAt(0)
            }
        }

        val messagingStyle = NotificationCompat.MessagingStyle(myPerson)
            .setConversationTitle(groupName)
            .setGroupConversation(true)

        synchronized(messagesList) {
            for (msg in messagesList) {
                messagingStyle.addMessage(msg.text, msg.timestamp, msg.senderPerson)
            }
        }

        // Tap action: open MainActivity and navigate to GroupChat
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_GROUP_NAME, groupName)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            (100000 + groupId).toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(messagingStyle)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_MESSAGES)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        try {
            notificationManager.notify((100000 + groupId).toInt(), notification)
            showSummaryNotification()
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted yet
        }
    }

    private fun showSummaryNotification() {
        val summaryIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val summaryPendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            summaryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText("Новые сообщения"))
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setContentIntent(summaryPendingIntent)
            .build()

        try {
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted yet
        }
    }

    fun onReplySent(chatUserId: Long, replyText: String) {
        val myUserId = secureTokenStorage.getUserId() ?: 0L
        val myPerson = Person.Builder().setName("Вы").setKey(myUserId.toString()).build()
        val messagesList = directThreads.computeIfAbsent(chatUserId) { mutableListOf() }
        synchronized(messagesList) {
            messagesList.add(ThreadMessage(replyText, System.currentTimeMillis(), myPerson))
        }
    }

    private fun createPerson(name: String, id: Long): Person {
        val avatarBitmap = createInitialsAvatar(name, id)
        val icon = IconCompat.createWithBitmap(avatarBitmap)
        return Person.Builder()
            .setName(name)
            .setKey(id.toString())
            .setIcon(icon)
            .build()
    }

    private fun createInitialsAvatar(name: String, id: Long): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val hue = ((id * 137) % 360).toFloat()
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.8f))

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), bgPaint)

        val initial = name.trim().take(1).uppercase().ifBlank { "#" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = 44f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, size / 2f, yPos, textPaint)

        return bitmap
    }

    private fun formatMessagePreview(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            try {
                val element = json.parseToJsonElement(trimmed)
                val obj = element.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                if (type == "fwd") {
                    val from = obj["from"]?.jsonPrimitive?.content ?: "собеседника"
                    val inner = obj["text"]?.jsonPrimitive?.content ?: ""
                    return "↪ Переслано от $from: ${formatMessagePreview(inner)}"
                }
                if (type == "file" || obj.containsKey("file")) {
                    val fileObj = obj["file"]?.jsonObject ?: obj
                    val mime = fileObj["mime"]?.jsonPrimitive?.content ?: ""
                    val name = fileObj["name"]?.jsonPrimitive?.content ?: ""
                    val text = obj["text"]?.jsonPrimitive?.content?.trim() ?: ""
                    val isImage = mime.startsWith("image/") || name.matches(Regex("(?i).*\\.(png|jpe?g|gif|webp|bmp|svg)$"))
                    val isVideo = mime.startsWith("video/") || name.matches(Regex("(?i).*\\.(mp4|mov|webm|mkv|avi)$"))
                    val isAudio = mime.startsWith("audio/") || name.matches(Regex("(?i).*\\.(mp3|ogg|wav|m4a|aac|flac)$"))

                    return when {
                        text.isNotEmpty() -> when {
                            isImage -> "📷 $text"
                            isVideo -> "🎬 $text"
                            isAudio -> "🎵 $text"
                            else -> "📎 $text"
                        }
                        isImage -> "📷 Фото"
                        isVideo -> "🎬 Видео"
                        isAudio -> "🎵 Аудио"
                        name.isNotEmpty() -> "📎 $name"
                        else -> "📎 Файл"
                    }
                }
                val text = obj["text"]?.jsonPrimitive?.content
                if (text != null) return formatMessagePreview(text)
            } catch (e: Exception) {
                // Not JSON or parse error, fallback
            }
        }
        return raw.replace(Regex("\\s+"), " ")
    }
}
