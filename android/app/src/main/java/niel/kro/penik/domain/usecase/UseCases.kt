package niel.kro.penik.domain.usecase

import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import niel.kro.penik.data.repository.AuthRepository
import niel.kro.penik.data.repository.ChatRepository
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
            webSocketManager.connect("penik.dev.slavchat.ru", 443, it.token)
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
            webSocketManager.connect("penik.dev.slavchat.ru", 443, it.token)
        }
    }
}

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository,
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(toUserId: Long, text: String, chatName: String = "") {
        messageRepository.sendMessage(toUserId, text)
        chatRepository.updateLastMessage(toUserId, text, System.currentTimeMillis(), name = chatName)
    }
}

class LoadMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    operator fun invoke(chatUserId: Long) = messageRepository.getMessagesForChat(chatUserId)
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
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(event: WebSocketEvent) {
        when (event) {
            is WebSocketEvent.MsgRecv -> {
                val isIncoming = messageRepository.handleMsgRecv(event)
                chatRepository.updateLastMessage(event.chatUserId, event.text, event.ts)
                if (isIncoming) {
                    chatRepository.incrementUnread(event.chatUserId)
                }
            }
            is WebSocketEvent.MsgAck -> {
                messageRepository.handleMsgAck(event)
            }
            is WebSocketEvent.MsgDelivered -> {
                messageRepository.handleMsgDelivered(event)
            }
            is WebSocketEvent.OfflineBatch -> {
                messageRepository.handleOfflineBatch(event)
                event.msgs.forEach { msg ->
                    chatRepository.updateLastMessage(msg.chatUserId, msg.text, msg.ts)
                    chatRepository.incrementUnread(msg.chatUserId)
                }
            }
            is WebSocketEvent.ChatPurge -> {
                messageRepository.deleteChatMessages(event.peerId)
                chatRepository.deleteChat(event.peerId)
            }
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
