package niel.kro.penik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import niel.kro.penik.data.network.api.ApiConfig
import niel.kro.penik.data.network.api.StickerItemResponse
import niel.kro.penik.data.network.api.StickerPackResponse
import niel.kro.penik.data.repository.StickerRepository
import niel.kro.penik.ui.theme.LocalAppColors

@Serializable
data class StickerPayload(
    val type: String = "sticker",
    val pack_id: String = "",
    val sticker_id: String = "",
    val emoji: String = "",
    val url: String = "",
    val file_name: String? = null
)

fun parseSticker(text: String): StickerPayload? = runCatching {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{")) return null
    val root = Json.parseToJsonElement(trimmed).jsonObject
    if (root["type"]?.jsonPrimitive?.content != "sticker") return null
    val packId = root["pack_id"]?.jsonPrimitive?.content ?: ""
    val stickerId = root["sticker_id"]?.jsonPrimitive?.content ?: root["id"]?.jsonPrimitive?.content ?: ""
    val emoji = root["emoji"]?.jsonPrimitive?.content ?: ""
    val url = root["url"]?.jsonPrimitive?.content ?: ""
    val fileName = root["file_name"]?.jsonPrimitive?.content
    StickerPayload(
        type = "sticker",
        pack_id = packId,
        sticker_id = stickerId,
        emoji = emoji,
        url = url,
        file_name = fileName
    )
}.getOrNull()

@Composable
fun StickerMediaView(
    url: String,
    isVideo: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String = "Стикер"
) {
    if (isVideo) {
        val context = LocalContext.current
        val exoPlayer = remember(url) {
            ExoPlayer.Builder(context).build().apply {
                val mediaItem = MediaItem.fromUri(url)
                setMediaItem(mediaItem)
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                prepare()
                playWhenReady = true
            }
        }

        DisposableEffect(exoPlayer) {
            onDispose {
                exoPlayer.release()
            }
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun StickerMessageView(
    payload: StickerPayload,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVideo = remember(payload) {
        payload.file_name?.endsWith(".webm", ignoreCase = true) == true ||
        payload.url.endsWith(".webm", ignoreCase = true)
    }
    val fullUrl = remember(payload) {
        if (payload.url.isNotBlank()) {
            ApiConfig.getFullStickerUrl(payload.url)
        } else {
            val fileName = payload.file_name ?: "${payload.sticker_id}.${if (isVideo) "webm" else "webp"}"
            ApiConfig.getStickerFileUrl(payload.pack_id, fileName)
        }
    }

    Box(
        modifier = modifier
            .size(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        StickerMediaView(
            url = fullUrl,
            isVideo = isVideo,
            contentDescription = payload.emoji.ifBlank { "Стикер" },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPickerBottomSheet(
    stickerRepository: StickerRepository,
    onDismiss: () -> Unit,
    onStickerSelect: (StickerItemResponse) -> Unit,
    onOpenPack: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recents by stickerRepository.recentStickers.collectAsState()
    var packs by remember { mutableStateOf<List<StickerPackResponse>>(emptyList()) }
    var selectedTab by remember { mutableStateOf("recent") } // "recent" or packId
    var isLoading by remember { mutableStateOf(true) }
    var showImportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val loadPacks = {
        scope.launch {
            isLoading = true
            val res = stickerRepository.getMyPacks()
            packs = res.getOrDefault(emptyList())
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadPacks()
    }

    if (showImportDialog) {
        ImportTelegramStickersDialog(
            stickerRepository = stickerRepository,
            onDismiss = { showImportDialog = false },
            onImportSuccess = { newPack ->
                showImportDialog = false
                loadPacks()
                selectedTab = newPack.id
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LocalAppColors.current.panel,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Стикеры",
                    color = LocalAppColors.current.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showImportDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = LocalAppColors.current.accent)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Импорт",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Импорт", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = LocalAppColors.current.textMuted
                        )
                    }
                }
            }

            // Grid Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalAppColors.current.accent, modifier = Modifier.size(28.dp))
                    }
                } else if (selectedTab == "recent") {
                    if (recents.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Нет недавних стикеров\nВыберите стикер из пака ниже",
                                color = LocalAppColors.current.textMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(recents, key = { "${it.packId}_${it.id}" }) { sticker ->
                                StickerGridItem(
                                    sticker = sticker,
                                    onClick = {
                                        onStickerSelect(sticker)
                                        stickerRepository.addRecentSticker(sticker)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                } else {
                    val activePack = packs.find { it.id == selectedTab }
                    var packDetail by remember(selectedTab) { mutableStateOf(activePack) }
                    var loadingDetails by remember(selectedTab) { mutableStateOf(activePack?.stickers.isNullOrEmpty()) }

                    LaunchedEffect(selectedTab) {
                        if (activePack != null && activePack.stickers.isEmpty()) {
                            val detRes = stickerRepository.getPackDetails(selectedTab)
                            if (detRes.isSuccess) {
                                packDetail = detRes.getOrNull()
                            }
                            loadingDetails = false
                        }
                    }

                    if (loadingDetails) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LocalAppColors.current.accent, modifier = Modifier.size(28.dp))
                        }
                    } else if (packDetail?.stickers.isNullOrEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("В этом паке пока нет стикеров", color = LocalAppColors.current.textMuted)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(packDetail!!.stickers, key = { "${it.packId}_${it.id}" }) { sticker ->
                                StickerGridItem(
                                    sticker = sticker,
                                    onClick = {
                                        onStickerSelect(sticker)
                                        stickerRepository.addRecentSticker(sticker)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Tabs Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalAppColors.current.panelSecondary)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Recent tab button
                val isRecentActive = selectedTab == "recent"
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isRecentActive) LocalAppColors.current.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { selectedTab = "recent" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Недавние",
                        tint = if (isRecentActive) LocalAppColors.current.accent else LocalAppColors.current.textMuted,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Installed packs tabs
                for (pack in packs) {
                    val isPackActive = selectedTab == pack.id
                    val coverUrl = remember(pack) {
                        val stickerId = pack.coverStickerId ?: pack.stickers.firstOrNull()?.id
                        if (!stickerId.isNullOrBlank()) {
                            ApiConfig.getStickerFileUrl(pack.id, "$stickerId.webp")
                        } else null
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isPackActive) LocalAppColors.current.accent.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { selectedTab = pack.id },
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverUrl != null) {
                            AsyncImage(
                                model = coverUrl,
                                contentDescription = pack.title,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Text(
                                text = pack.title.take(1),
                                color = if (isPackActive) LocalAppColors.current.accent else LocalAppColors.current.textMuted,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }
    }
}

@Composable
fun StickerGridItem(
    sticker: StickerItemResponse,
    onClick: () -> Unit
) {
    val url = remember(sticker) {
        if (!sticker.url.isNullOrBlank()) {
            ApiConfig.getFullStickerUrl(sticker.url)
        } else {
            val fileName = sticker.fileName.ifBlank { "${sticker.id}.webp" }
            ApiConfig.getStickerFileUrl(sticker.packId, fileName)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = url,
            contentDescription = sticker.emoji.ifBlank { "Стикер" },
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun StickerPackDetailDialog(
    packId: String,
    stickerRepository: StickerRepository,
    onDismiss: () -> Unit,
    onStickerSelect: ((StickerItemResponse) -> Unit)? = null
) {
    var packDetail by remember { mutableStateOf<StickerPackResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isInstalled by remember { mutableStateOf(false) }
    var isActionLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(packId) {
        isLoading = true
        val myPacks = stickerRepository.getMyPacks().getOrDefault(emptyList())
        isInstalled = myPacks.any { it.id == packId }
        val det = stickerRepository.getPackDetails(packId).getOrNull()
        packDetail = det
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.panel,
        titleContentColor = LocalAppColors.current.textPrimary,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = packDetail?.title ?: "Стикерпак",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть",
                        tint = LocalAppColors.current.textMuted
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
            ) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalAppColors.current.accent)
                    }
                } else if (packDetail == null) {
                    Text("Не удалось загрузить стикерпак", color = LocalAppColors.current.textMuted)
                } else {
                    Text(
                        text = "${packDetail!!.stickers.size} стикеров",
                        color = LocalAppColors.current.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(packDetail!!.stickers, key = { "${it.packId}_${it.id}" }) { sticker ->
                            StickerGridItem(
                                sticker = sticker,
                                onClick = {
                                    if (onStickerSelect != null) {
                                        onStickerSelect(sticker)
                                        stickerRepository.addRecentSticker(sticker)
                                        onDismiss()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (packDetail != null) {
                Button(
                    onClick = {
                        scope.launch {
                            isActionLoading = true
                            if (isInstalled) {
                                stickerRepository.uninstallPack(packId)
                                isInstalled = false
                            } else {
                                stickerRepository.installPack(packId)
                                isInstalled = true
                            }
                            isActionLoading = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isInstalled) Color(0xFFEF5350) else LocalAppColors.current.accent
                    ),
                    enabled = !isActionLoading
                ) {
                    if (isActionLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                    } else {
                        Text(if (isInstalled) "Удалить стикерпак" else "Добавить стикерпак")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = LocalAppColors.current.textMuted)
            }
        }
    )
}

@Composable
fun ImportTelegramStickersDialog(
    stickerRepository: StickerRepository,
    onDismiss: () -> Unit,
    onImportSuccess: (StickerPackResponse) -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.panel,
        title = {
            Text(
                text = "Импорт из Telegram",
                color = LocalAppColors.current.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "Введите ссылку на стикерпак в Telegram (например, https://t.me/addstickers/animals или название пака):",
                    color = LocalAppColors.current.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        errorMsg = null
                    },
                    placeholder = { Text("https://t.me/addstickers/...", color = LocalAppColors.current.textMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = LocalAppColors.current.inputBg,
                        unfocusedContainerColor = LocalAppColors.current.inputBg,
                        focusedBorderColor = LocalAppColors.current.accent,
                        unfocusedBorderColor = LocalAppColors.current.border,
                        focusedTextColor = LocalAppColors.current.textPrimary,
                        unfocusedTextColor = LocalAppColors.current.textPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                if (errorMsg != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMsg!!,
                        color = Color(0xFFEF5350),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (urlInput.isBlank()) return@Button
                    scope.launch {
                        isLoading = true
                        errorMsg = null
                        val res = stickerRepository.importTelegramPack(urlInput.trim())
                        isLoading = false
                        if (res.isSuccess && res.getOrNull() != null) {
                            onImportSuccess(res.getOrNull()!!)
                        } else {
                            errorMsg = res.exceptionOrNull()?.message ?: "Не удалось импортировать стикерпак"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.accent),
                enabled = !isLoading && urlInput.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                } else {
                    Text("Импортировать")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Отмена", color = LocalAppColors.current.textMuted)
            }
        }
    )
}
