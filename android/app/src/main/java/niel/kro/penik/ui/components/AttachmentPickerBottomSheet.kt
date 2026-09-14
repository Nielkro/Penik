package niel.kro.penik.ui.components

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import niel.kro.penik.ui.theme.LocalAppColors

data class LocalGalleryMedia(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val isVideo: Boolean,
    val durationText: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerBottomSheet(
    onDismiss: () -> Unit,
    onPickPhotoOrVideo: () -> Unit,
    onPickDocument: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickAudio: () -> Unit,
    onSendRecentMedias: (List<Uri>) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(hasGalleryPermission(context)) }
    val recentMedias = remember { mutableStateListOf<LocalGalleryMedia>() }
    var isLoadingMedia by remember { mutableStateOf(false) }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.any { it }
        hasPermission = granted
        if (granted) {
            scope.launch {
                isLoadingMedia = true
                val list = fetchRecentGalleryMedia(context)
                recentMedias.clear()
                recentMedias.addAll(list)
                isLoadingMedia = false
            }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isLoadingMedia = true
            val list = fetchRecentGalleryMedia(context)
            recentMedias.clear()
            recentMedias.addAll(list)
            isLoadingMedia = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LocalAppColors.current.panel,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp, top = 2.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Прикрепить вложение",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = LocalAppColors.current.textPrimary
                )

                AnimatedVisibility(
                    visible = selectedUris.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    Button(
                        onClick = {
                            val toSend = selectedUris.toList()
                            onDismiss()
                            onSendRecentMedias(toSend)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAppColors.current.accent
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Отправить (${selectedUris.size})",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Telegram-style Gallery Thumbnail Strip
            if (hasPermission) {
                if (isLoadingMedia) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = LocalAppColors.current.accent,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                } else if (recentMedias.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // First item: Live Camera Tile
                        item {
                            CameraTile(onClick = {
                                onDismiss()
                                onTakePhoto()
                            })
                        }

                        // Recent Media items
                        items(recentMedias, key = { it.id }) { item ->
                            val isSelected = selectedUris.contains(item.uri)
                            val selectedIndex = if (isSelected) selectedUris.indexOf(item.uri) + 1 else 0

                            MediaThumbnailTile(
                                item = item,
                                isSelected = isSelected,
                                selectedNumber = selectedIndex,
                                onToggleSelect = {
                                    if (isSelected) {
                                        selectedUris.remove(item.uri)
                                    } else {
                                        selectedUris.add(item.uri)
                                    }
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }
            } else {
                // Permission Request Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LocalAppColors.current.panelSecondary.copy(alpha = 0.7f))
                        .clickable {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                            } else {
                                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            permissionLauncher.launch(perms)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(LocalAppColors.current.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = LocalAppColors.current.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Быстрый выбор из галереи",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LocalAppColors.current.textPrimary
                        )
                        Text(
                            text = "Разрешить доступ для мгновенного превью фото",
                            fontSize = 12.sp,
                            color = LocalAppColors.current.textMuted
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Action rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttachmentItemRow(
                    icon = Icons.Default.PhotoLibrary,
                    iconTint = Color(0xFF4A89DC),
                    iconBgColor = Color(0xFF4A89DC).copy(alpha = 0.16f),
                    title = "Галерея",
                    subtitle = "Открыть системную галерею для выбора фото/видео",
                    onClick = {
                        onDismiss()
                        onPickPhotoOrVideo()
                    }
                )

                AttachmentItemRow(
                    icon = Icons.AutoMirrored.Filled.InsertDriveFile,
                    iconTint = Color(0xFFF6BB42),
                    iconBgColor = Color(0xFFF6BB42).copy(alpha = 0.16f),
                    title = "Файл или документ",
                    subtitle = "Без сжатия, оригинальное качество с метаданными",
                    onClick = {
                        onDismiss()
                        onPickDocument()
                    }
                )

                AttachmentItemRow(
                    icon = Icons.Default.PhotoCamera,
                    iconTint = Color(0xFF37BC9B),
                    iconBgColor = Color(0xFF37BC9B).copy(alpha = 0.16f),
                    title = "Камера",
                    subtitle = "Сделать снимок в полном качестве",
                    onClick = {
                        onDismiss()
                        onTakePhoto()
                    }
                )

                AttachmentItemRow(
                    icon = Icons.Default.Audiotrack,
                    iconTint = Color(0xFF967ADC),
                    iconBgColor = Color(0xFF967ADC).copy(alpha = 0.16f),
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
}

@Composable
private fun CameraTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(width = 86.dp, height = 110.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppColors.current.inputBg)
            .border(1.dp, LocalAppColors.current.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(LocalAppColors.current.accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoCamera,
                contentDescription = "Камера",
                tint = LocalAppColors.current.accent,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Камера",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = LocalAppColors.current.textPrimary
        )
    }
}

@Composable
private fun MediaThumbnailTile(
    item: LocalGalleryMedia,
    isSelected: Boolean,
    selectedNumber: Int,
    onToggleSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 86.dp, height = 110.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.5.dp else 0.5.dp,
                color = if (isSelected) LocalAppColors.current.accent else LocalAppColors.current.border,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggleSelect)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Dim overlay if selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        }

        // Top right selection checkbox/badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) LocalAppColors.current.accent else Color.Black.copy(alpha = 0.45f)
                )
                .border(
                    width = 1.5.dp,
                    color = Color.White,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Text(
                    text = if (selectedNumber > 0) "$selectedNumber" else "✓",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom video duration badge
        if (item.isVideo && item.durationText != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = item.durationText,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun AttachmentItemRow(
    icon: ImageVector,
    iconTint: Color,
    iconBgColor: Color,
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
            .padding(vertical = 11.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
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

private fun hasGalleryPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private suspend fun fetchRecentGalleryMedia(context: Context, limit: Int = 40): List<LocalGalleryMedia> = withContext(Dispatchers.IO) {
    val items = mutableListOf<LocalGalleryMedia>()
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.DURATION,
        MediaStore.Files.FileColumns.DATE_ADDED
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
    val selectionArgs = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
    )
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
    val queryUri = MediaStore.Files.getContentUri("external")

    try {
        context.contentResolver.query(
            queryUri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val mediaTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val durationColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DURATION)

            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val id = cursor.getLong(idColumn)
                val mediaType = cursor.getInt(mediaTypeColumn)
                val mime = cursor.getString(mimeColumn) ?: "image/jpeg"
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val durationMs = if (durationColumn != -1 && !cursor.isNull(durationColumn)) cursor.getLong(durationColumn) else 0L

                val durationText = if (isVideo && durationMs > 0) {
                    val s = (durationMs / 1000) % 60
                    val m = (durationMs / 1000) / 60
                    String.format(java.util.Locale.US, "%d:%02d", m, s)
                } else null

                val contentUri = if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }

                items.add(LocalGalleryMedia(id, contentUri, mime, isVideo, durationText))
                count++
            }
        }
    } catch (_: Exception) {}
    items
}
