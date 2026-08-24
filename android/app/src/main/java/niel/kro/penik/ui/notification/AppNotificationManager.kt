package niel.kro.penik.ui.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import niel.kro.penik.MainActivity
import niel.kro.penik.R
import niel.kro.penik.data.local.dao.ChatDao
import niel.kro.penik.data.local.dao.GroupDao
import niel.kro.penik.data.network.api.ApiConfig
import niel.kro.penik.data.repository.SecureTokenStorage
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        const val CHANNEL_ID_CALLS = "penik_calls"
        const val GROUP_KEY_MESSAGES = "niel.kro.penik.MESSAGES"
        const val SUMMARY_NOTIFICATION_ID = 999999
        const val INCOMING_CALL_NOTIFICATION_ID = 900001

        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_CHAT_USER_ID = "chatUserId"
        const val EXTRA_CHAT_NAME = "chatName"
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
        const val EXTRA_LAST_MSG_SERVER_ID = "lastMsgServerId"
        const val EXTRA_CALL_ACTION = "callAction"

        @Volatile
        var isAppInForeground: Boolean = false

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
    data class ThreadMessage(
        val text: CharSequence,
        val timestamp: Long,
        val senderPerson: Person,
        val imageUri: Uri? = null,
        val imageMimeType: String? = null
    )

    private val directThreads = ConcurrentHashMap<Long, MutableList<ThreadMessage>>()
    private val groupThreads = ConcurrentHashMap<Long, MutableList<ThreadMessage>>()
    private val lastAlertTimestamps = ConcurrentHashMap<String, Long>()
    // Last incoming message server ID per chatUserId — used for reply-to
    private val lastIncomingMsgIds = ConcurrentHashMap<Long, Long>()

    private fun shouldAlert(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAlertTimestamps[key] ?: 0L
        val should = (now - last) > 2500L
        if (should) {
            lastAlertTimestamps[key] = now
        }
        return should
    }

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

            val callChannel = NotificationChannel(
                CHANNEL_ID_CALLS,
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих звонках"
                // Ringtone and vibration are driven by CallManager while the app process is alive
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm?.createNotificationChannel(callChannel)
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

    fun cancelAllNotifications() {
        directThreads.clear()
        groupThreads.clear()
        notificationManager.cancelAll()
    }

    fun showIncomingCallNotification(peerUserId: Long, peerName: String, isVideo: Boolean) {
        val avatarBitmap = createInitialsAvatar(peerName, peerUserId)
        val title = if (isVideo) "Входящий видеозвонок" else "Входящий звонок"

        val answerIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CALL_ACTION, CallActionReceiver.ACTION_ANSWER)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            INCOMING_CALL_NOTIFICATION_ID,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            putExtra(EXTRA_CALL_ACTION, CallActionReceiver.ACTION_DECLINE)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            INCOMING_CALL_NOTIFICATION_ID + 1,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val senderIcon = IconCompat.createWithBitmap(avatarBitmap)
        val person = Person.Builder()
            .setName(peerName)
            .setIcon(senderIcon)
            .setKey(peerUserId.toString())
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    answerPendingIntent
                )
            )
            .setFullScreenIntent(answerPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(30_000)
            .build()

        try {
            notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS or USE_FULL_SCREEN_INTENT permission not granted yet
        }
    }

    fun cancelIncomingCallNotification() {
        notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }

    suspend fun showDirectMessageNotification(
        chatUserId: Long,
        rawText: String,
        timestamp: Long,
        msgServerId: Long = 0L,
        overrideSenderName: String? = null,
        customAvatarBitmap: Bitmap? = null,
        customImageBitmap: Bitmap? = null
    ) {
        // Do not notify if the user is actively viewing this direct chat
        if (activeChatKey == "direct_$chatUserId") return

        val myUserId = secureTokenStorage.getUserId() ?: 0L
        val chatEntity = chatDao.getChat(chatUserId)
        val chatName = overrideSenderName ?: chatEntity?.name?.ifBlank { "Пользователь #$chatUserId" } ?: "Пользователь #$chatUserId"

        val previewText = formatMessagePreview(rawText)
        val avatarUrl = ApiConfig.getUserAvatarUrl(chatUserId)
        val senderAvatarBitmap = resolveAvatarBitmap(chatName, chatUserId, customAvatarBitmap, avatarUrl)
        val senderIcon = IconCompat.createWithBitmap(senderAvatarBitmap)
        val senderPerson = Person.Builder()
            .setName(chatName)
            .setKey(chatUserId.toString())
            .setIcon(senderIcon)
            .setImportant(true)
            .build()
        val myPerson = Person.Builder().setName("Вы").setKey(myUserId.toString()).build()

        val (imageUri, imageMime) = if (customImageBitmap != null) {
            val uri = saveBitmapToCache(customImageBitmap, "notif_custom_${System.currentTimeMillis()}.jpg")
            Pair(uri, "image/jpeg")
        } else {
            extractAttachmentImageUri(rawText)
        }

        val messagesList = directThreads.computeIfAbsent(chatUserId) { mutableListOf() }
        synchronized(messagesList) {
            messagesList.add(ThreadMessage(previewText, timestamp, senderPerson, imageUri, imageMime))
            if (messagesList.size > 15) {
                messagesList.removeAt(0)
            }
        }
        // Track last incoming message server ID for reply-to
        if (msgServerId > 0L) {
            lastIncomingMsgIds[chatUserId] = msgServerId
        }

        val messagingStyle = NotificationCompat.MessagingStyle(myPerson)
            .setConversationTitle(null)
            .setGroupConversation(false)

        synchronized(messagesList) {
            for (msg in messagesList) {
                val messageObj = NotificationCompat.MessagingStyle.Message(msg.text, msg.timestamp, msg.senderPerson)
                if (msg.imageUri != null && msg.imageMimeType != null) {
                    messageObj.setData(msg.imageMimeType, msg.imageUri)
                }
                messagingStyle.addMessage(messageObj)
            }
        }

        // Tap action: open MainActivity and navigate to ChatRoom
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
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

        // Register Dynamic Shortcut for Android 11+ Conversation Notification styling
        val shortcutId = "conversation_direct_$chatUserId"
        try {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(chatName)
                .setLongLabel(chatName)
                .setPerson(senderPerson)
                .setIcon(senderIcon)
                .setIntent(contentIntent)
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (e: Exception) {
            // Ignore shortcut failures on older Android versions
        }

        // Direct reply action
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Ответить")
            .build()
        val lastMsgId = lastIncomingMsgIds[chatUserId] ?: msgServerId
        val replyIntent = Intent(context, DirectReplyReceiver::class.java).apply {
            putExtra(EXTRA_CHAT_USER_ID, chatUserId)
            putExtra(EXTRA_CHAT_NAME, chatName)
            if (lastMsgId > 0L) {
                putExtra(EXTRA_LAST_MSG_SERVER_ID, lastMsgId.toString())
            }
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
            "Пометить прочитанным",
            readPendingIntent
        ).build()

        val alertKey = "direct_$chatUserId"
        val isAlertable = shouldAlert(alertKey)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(senderAvatarBitmap)
            .setStyle(messagingStyle)
            .setShortcutId(shortcutId)
            .addPerson(senderPerson)
            .setContentIntent(contentPendingIntent)
            .addAction(replyAction)
            .addAction(readAction)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(!isAlertable)
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
        timestamp: Long,
        overrideGroupName: String? = null,
        overrideSenderName: String? = null,
        customAvatarBitmap: Bitmap? = null,
        customImageBitmap: Bitmap? = null
    ) {
        // Do not notify if the user is actively viewing this group
        if (activeChatKey == "group_$groupId") return

        val myUserId = secureTokenStorage.getUserId() ?: 0L
        if (senderUserId == myUserId) return

        val groupEntity = groupDao.getGroup(groupId)
        val groupName = overrideGroupName ?: groupEntity?.name?.ifBlank { "Группа #$groupId" } ?: "Группа #$groupId"
        val members = groupDao.getMembers(groupId)
        val senderMember = members.find { it.userId == senderUserId }
        val senderName = overrideSenderName ?: senderMember?.name?.ifBlank { "Участник #$senderUserId" } ?: "Участник #$senderUserId"

        val previewText = formatMessagePreview(rawText)
        val avatarUrl = ApiConfig.getUserAvatarUrl(senderUserId)
        val senderAvatarBitmap = resolveAvatarBitmap(senderName, senderUserId, customAvatarBitmap, avatarUrl)
        val senderIcon = IconCompat.createWithBitmap(senderAvatarBitmap)
        val senderPerson = Person.Builder()
            .setName(senderName)
            .setKey(senderUserId.toString())
            .setIcon(senderIcon)
            .setImportant(true)
            .build()
        val myPerson = Person.Builder().setName("Вы").setKey(myUserId.toString()).build()

        val (imageUri, imageMime) = if (customImageBitmap != null) {
            val uri = saveBitmapToCache(customImageBitmap, "notif_custom_grp_${System.currentTimeMillis()}.jpg")
            Pair(uri, "image/jpeg")
        } else {
            extractAttachmentImageUri(rawText)
        }

        val messagesList = groupThreads.computeIfAbsent(groupId) { mutableListOf() }
        synchronized(messagesList) {
            messagesList.add(ThreadMessage(previewText, timestamp, senderPerson, imageUri, imageMime))
            if (messagesList.size > 15) {
                messagesList.removeAt(0)
            }
        }

        val messagingStyle = NotificationCompat.MessagingStyle(myPerson)
            .setConversationTitle(groupName)
            .setGroupConversation(true)

        synchronized(messagesList) {
            for (msg in messagesList) {
                val messageObj = NotificationCompat.MessagingStyle.Message(msg.text, msg.timestamp, msg.senderPerson)
                if (msg.imageUri != null && msg.imageMimeType != null) {
                    messageObj.setData(msg.imageMimeType, msg.imageUri)
                }
                messagingStyle.addMessage(messageObj)
            }
        }

        // Tap action: open MainActivity and navigate to GroupChat
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
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

        // Register Dynamic Shortcut for Android 11+ Conversation Group Notification styling
        val shortcutId = "conversation_group_$groupId"
        try {
            val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(groupName)
                .setLongLabel(groupName)
                .setPerson(senderPerson)
                .setIcon(senderIcon)
                .setIntent(contentIntent)
                .setLongLived(true)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (e: Exception) {
            // Ignore shortcut failures on older Android versions
        }

        val alertKey = "group_$groupId"
        val isAlertable = shouldAlert(alertKey)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(senderAvatarBitmap)
            .setStyle(messagingStyle)
            .setShortcutId(shortcutId)
            .addPerson(senderPerson)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(!isAlertable)
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
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText("Новые сообщения"))
            .setGroup(GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
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

    private suspend fun resolveAvatarBitmap(
        name: String,
        id: Long,
        customAvatarBitmap: Bitmap? = null,
        avatarUrl: String? = null
    ): Bitmap {
        if (customAvatarBitmap != null) {
            return createCircleBitmap(customAvatarBitmap)
        }

        if (avatarUrl != null) {
            val fetchedBitmap = fetchAvatarBitmap(avatarUrl)
            if (fetchedBitmap != null) {
                return createCircleBitmap(fetchedBitmap)
            }
        }

        return createInitialsAvatar(name, id)
    }

    private suspend fun fetchAvatarBitmap(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(urlStr)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                doInput = true
            }
            connection.connect()
            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } else {
                null
            }
        }.getOrNull()
    }

    fun createCircleBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        paint.shader = shader
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        return output
    }

    fun createInitialsAvatar(name: String, id: Long): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val hue = ((id * 137) % 360).toFloat()
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), bgPaint)

        val initial = name.trim().take(1).uppercase().ifBlank { "#" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = 86f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val yPos = (size / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(initial, size / 2f, yPos, textPaint)

        return bitmap
    }

    private fun extractAttachmentImageUri(rawText: String): Pair<Uri?, String?> {
        val trimmed = rawText.trim()
        if (!trimmed.startsWith("{")) return Pair(null, null)
        try {
            val root = json.parseToJsonElement(trimmed).jsonObject
            val file = if (root["type"]?.jsonPrimitive?.content == "file") root["file"]?.jsonObject else null
            val thumbBase64 = file?.get("thumb")?.jsonPrimitive?.content
            val mime = file?.get("mime")?.jsonPrimitive?.content ?: "image/jpeg"
            if (!thumbBase64.isNullOrBlank() && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                val cleanBase64 = if (thumbBase64.contains(",")) thumbBase64.substringAfter(",") else thumbBase64
                val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                val attachmentsDir = File(context.cacheDir, "attachments").apply { mkdirs() }
                val fileOut = File(attachmentsDir, "notif_${System.currentTimeMillis()}_${Math.abs(cleanBase64.hashCode())}.jpg")
                fileOut.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fileOut)
                return Pair(uri, "image/jpeg")
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return Pair(null, null)
    }

    fun saveBitmapToCache(bitmap: Bitmap, filename: String): Uri {
        val attachmentsDir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val file = File(attachmentsDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
