package niel.kro.penik.domain.usecase

import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.ChatRepository
import niel.kro.penik.data.repository.GroupRepository
import niel.kro.penik.data.repository.MessageRepository
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val messageRepository: MessageRepository,
    private val webSocketManager: WebSocketManager
) {
    suspend operator fun invoke(
        nickname: String,
        password: String,
        deviceName: String
    ): Result<Unit> {
        val result = authRepository.login(nickname, password, deviceName)
        return result.map {
            messageRepository.syncHistory()
            webSocketManager.connect(niel.kro.penik.data.network.api.ApiConfig.HOST, niel.kro.penik.data.network.api.ApiConfig.PORT, it.token)
        }
    }
}

class RegisterUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager
) {
    suspend operator fun invoke(
        name: String,
        nickname: String,
        password: String,
        deviceName: String
    ): Result<Unit> {
        val result = authRepository.register(name, nickname, password, deviceName)
        return result.map {
            webSocketManager.connect(niel.kro.penik.data.network.api.ApiConfig.HOST, niel.kro.penik.data.network.api.ApiConfig.PORT, it.token)
        }
    }
}

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(toUserId: Long, text: String, chatName: String = "", replyToMsgId: String? = null) {
        messageRepository.sendMessage(toUserId, text, replyToMsgId)
        chatRepository.updateLastMessage(toUserId, text, System.currentTimeMillis(), name = chatName)
    }
}

class LoadMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    operator fun invoke(chatUserId: Long) = messageRepository.getMessagesForChat(chatUserId)
}

class DeleteMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val webSocketManager: WebSocketManager
) {
    suspend operator fun invoke(localId: String, chatUserId: Long, deleteForEveryone: Boolean) {
        messageRepository.deleteMessage(localId, chatUserId)
        if (deleteForEveryone) {
            webSocketManager.sendMsgDelete(localId, chatUserId, true)
        }
    }
}

class LoadChatsUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke() = chatRepository.getAllChats()
}

class SyncHistoryUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke() = messageRepository.syncHistory()
}

class HandleWebSocketEventUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository,
    private val groupRepository: GroupRepository,
    private val webSocketManager: WebSocketManager,
    private val tokenStorage: niel.kro.penik.data.repository.SecureTokenStorage,
    private val appNotificationManager: niel.kro.penik.ui.notification.AppNotificationManager,
    private val callManager: niel.kro.penik.domain.call.CallManager
) {
    suspend operator fun invoke(event: WebSocketEvent) {
        when (event) {
            is WebSocketEvent.MsgRecv -> {
                val isIncoming = messageRepository.handleMsgRecv(event)
                chatRepository.updateLastMessage(event.chatUserId, event.text, event.ts)
                if (isIncoming) {
                    chatRepository.incrementUnread(event.chatUserId)
                    appNotificationManager.showDirectMessageNotification(event.chatUserId, event.text, event.ts, msgServerId = event.msgId)
                }
            }
            is WebSocketEvent.MsgRecvEncrypted -> {
                val (text, isIncoming) = messageRepository.handleMsgRecvEncrypted(event)
                // Skip chat list update when the message was not meant for this device
                // (self-chat copy encrypted for another device returns empty text).
                if (text.isNotEmpty()) {
                    chatRepository.updateLastMessage(event.chatUserId, text, event.ts)
                    if (isIncoming) {
                        chatRepository.incrementUnread(event.chatUserId)
                        appNotificationManager.showDirectMessageNotification(event.chatUserId, text, event.ts, msgServerId = event.msgId)
                    }
                }
            }
            is WebSocketEvent.MsgAck -> {
                messageRepository.handleMsgAck(event)
            }
            is WebSocketEvent.MsgDelivered -> {
                messageRepository.handleMsgDelivered(event)
            }
            is WebSocketEvent.MsgRead -> {
                messageRepository.handleMsgRead(event)
            }
            is WebSocketEvent.OfflineBatch -> {
                messageRepository.handleOfflineBatch(event)
                event.msgs.forEach { msg ->
                    chatRepository.updateLastMessage(msg.chatUserId, msg.text, msg.ts)
                }
            }
            is WebSocketEvent.OfflineBatchEncrypted -> {
                val decrypted = messageRepository.handleOfflineBatchEncrypted(event)
                decrypted.forEach { msg ->
                    chatRepository.updateLastMessage(msg.chatUserId, msg.text, msg.ts)
                }
            }
            is WebSocketEvent.ChatPurge -> {
                messageRepository.deleteChatMessages(event.peerId)
                chatRepository.deleteChat(event.peerId)
                appNotificationManager.cancelChatNotification(event.peerId)
            }
            is WebSocketEvent.MsgStatusBatch -> {
                messageRepository.handleMsgStatusBatch(event)
            }
            is WebSocketEvent.PairingHistoryReady -> Unit
            is WebSocketEvent.GroupMessageRecv -> {
                val msgEntity = groupRepository.handleIncoming(
                    event.groupId, event.id, event.messageId, event.senderUserId, event.senderDeviceId,
                    event.keyVersion, event.ciphertext, event.salt, event.nonce, event.createdAt,
                    event.replyToMsgId
                )
                if (msgEntity != null && !msgEntity.sentByMe) {
                    appNotificationManager.showGroupMessageNotification(
                        groupId = event.groupId,
                        senderUserId = event.senderUserId,
                        rawText = msgEntity.text,
                        timestamp = event.createdAt
                    )
                }
            }
            is WebSocketEvent.GroupMessageAck -> {
                groupRepository.onAck(event.groupId, event.messageId, event.id)
            }
            is WebSocketEvent.GroupKeyAvailable -> {
                groupRepository.ensureGroupKey(event.groupId, event.keyVersion)
                groupRepository.syncHistory(event.groupId)
            }
            is WebSocketEvent.GroupMemberChanged -> {
                // Sync the group list first: a fresh invitation surfaces here as a
                // pending group that doesn't exist locally yet. Member refresh is
                // best-effort since a pending invitee can't list the roster.
                runCatching { groupRepository.syncGroups() }
                runCatching { groupRepository.refreshMembers(event.groupId) }
            }
            is WebSocketEvent.GroupAvatarUpdate -> {
                niel.kro.penik.data.repository.AvatarCacheBus.bumpGroup(event.groupId, event.ts)
            }
            is WebSocketEvent.UserAvatarUpdate -> {
                niel.kro.penik.data.repository.AvatarCacheBus.bumpUser(event.userId, event.ts)
            }
            is WebSocketEvent.TypingNotify -> {
                niel.kro.penik.data.repository.TypingBus.update(event.fromUserId, event.isTyping)
            }
            is WebSocketEvent.MsgDeleteNotify -> {
                messageRepository.deleteMessageByServerOrLocalId(event.msgId, event.chatId)
            }
            is WebSocketEvent.PresenceUpdate -> {
                niel.kro.penik.data.repository.PresenceBus.update(event.userId, event.online, event.lastSeen)
            }
            is WebSocketEvent.Connected -> {
                // Sync direct message history and group history/metadata on connection
                runCatching { messageRepository.syncHistory() }
                runCatching {
                    val groups = groupRepository.syncGroups()
                    for (g in groups) {
                        if (g.status == "pending") continue
                        runCatching { groupRepository.syncHistory(g.id) }
                    }
                }
            }
            is WebSocketEvent.ServerShutdown -> {
                webSocketManager.closeForServerShutdown()
            }
            is WebSocketEvent.CallIncoming -> callManager.onIncoming(event)
            is WebSocketEvent.CallAccepted -> callManager.onAccepted(event)
            is WebSocketEvent.CallReject -> callManager.onReject(event)
            is WebSocketEvent.CallEnd -> callManager.onEnd(event)
            else -> {}
        }
    }
}

class LogoutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val webSocketManager: WebSocketManager
) {
    operator fun invoke() {
        webSocketManager.disconnect()
        authRepository.logout()
    }
}
