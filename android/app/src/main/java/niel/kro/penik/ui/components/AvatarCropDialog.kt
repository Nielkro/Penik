package niel.kro.penik.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import niel.kro.penik.ui.theme.LocalAppColors
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Dialog for interactive avatar photo cropping, scaling, panning, and rotating.
 */
@Composable
fun AvatarCropDialog(
    imageUri: Uri,
    onDismiss: () -> Unit,
    onCropDone: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }

    var userScale by remember { mutableFloatStateOf(1f) }
    var userOffset by remember { mutableStateOf(Offset.Zero) }
    var rotationDegrees by remember { mutableIntStateOf(0) }

    LaunchedEffect(imageUri) {
        isLoading = true
        withContext(Dispatchers.IO) {
            sourceBitmap = loadAndPrepareBitmap(context, imageUri)
        }
        isLoading = false
    }

    Dialog(
        onDismissRequest = {
            if (!isProcessing) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 16.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isProcessing
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = "Кадрирование фото",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(
                        onClick = {
                            val bitmap = sourceBitmap ?: return@IconButton
                            isProcessing = true
                            coroutineScope.launch {
                                val croppedBytes = withContext(Dispatchers.Default) {
                                    cropAvatarToByteArray(
                                        source = bitmap,
                                        userScale = userScale,
                                        userOffset = userOffset,
                                        rotationDegrees = rotationDegrees
                                    )
                                }
                                isProcessing = false
                                if (croppedBytes != null) {
                                    onCropDone(croppedBytes)
                                }
                            }
                        },
                        enabled = !isLoading && !isProcessing && sourceBitmap != null
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = LocalAppColors.current.accent,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Done,
                                contentDescription = "Done",
                                tint = LocalAppColors.current.accent
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Crop Viewport
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading || sourceBitmap == null) {
                        CircularProgressIndicator(
                            color = LocalAppColors.current.accent,
                            modifier = Modifier.size(48.dp)
                        )
                    } else {
                        val bitmap = sourceBitmap!!
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF121212))
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        userScale = (userScale * zoom).coerceIn(0.5f, 6.0f)
                                        userOffset += pan
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val viewportWidthPx = constraints.maxWidth.toFloat()
                            val viewportHeightPx = constraints.maxHeight.toFloat()
                            val cropDiameterPx = min(viewportWidthPx, viewportHeightPx) * 0.85f

                            val isRotated90 = (rotationDegrees % 180 != 0)
                            val effectiveWidth = if (isRotated90) bitmap.height.toFloat() else bitmap.width.toFloat()
                            val effectiveHeight = if (isRotated90) bitmap.width.toFloat() else bitmap.height.toFloat()

                            val baseScale = max(
                                cropDiameterPx / effectiveWidth,
                                cropDiameterPx / effectiveHeight
                            )

                            // Render transformed bitmap
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        translationX = userOffset.x
                                        translationY = userOffset.y
                                        scaleX = baseScale * userScale
                                        scaleY = baseScale * userScale
                                        rotationZ = rotationDegrees.toFloat()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Avatar preview",
                                    modifier = Modifier.size(
                                        width = (bitmap.width / LocalDensity.current.density).dp,
                                        height = (bitmap.height / LocalDensity.current.density).dp
                                    )
                                )
                            }

                            // Circular Mask Overlay
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val canvasWidth = size.width
                                val canvasHeight = size.height
                                val radius = cropDiameterPx / 2f
                                val center = Offset(canvasWidth / 2f, canvasHeight / 2f)

                                // Darkened transparent background with circular cutout
                                val path = Path().apply {
                                    addRect(Rect(0f, 0f, canvasWidth, canvasHeight))
                                    addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
                                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                                }

                                drawPath(
                                    path = path,
                                    color = Color(0xAA000000)
                                )

                                // Circle boundary line
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.8f),
                                    radius = radius,
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Editing Tools
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            rotationDegrees = (rotationDegrees + 90) % 360
                        },
                        enabled = !isLoading && !isProcessing
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.RotateRight,
                                contentDescription = "Rotate",
                                tint = Color.White
                            )
                            Text("90°", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }

                    IconButton(
                        onClick = {
                            userScale = 1f
                            userOffset = Offset.Zero
                        },
                        enabled = !isLoading && !isProcessing
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reset",
                                tint = Color.White
                            )
                            Text("Сброс", color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isProcessing
                    ) {
                        Text("Отмена", color = Color.White, fontSize = 15.sp)
                    }

                    Button(
                        onClick = {
                            val bitmap = sourceBitmap ?: return@Button
                            isProcessing = true
                            coroutineScope.launch {
                                val croppedBytes = withContext(Dispatchers.Default) {
                                    cropAvatarToByteArray(
                                        source = bitmap,
                                        userScale = userScale,
                                        userOffset = userOffset,
                                        rotationDegrees = rotationDegrees
                                    )
                                }
                                isProcessing = false
                                if (croppedBytes != null) {
                                    onCropDone(croppedBytes)
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAppColors.current.accent
                        ),
                        enabled = !isLoading && !isProcessing && sourceBitmap != null
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Готово", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads bitmap from Uri, correcting orientation and downsampling to avoid OOM.
 */
private fun loadAndPrepareBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        // 1. Read EXIF orientation
        var rotation = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                rotation = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Exception) {}

        // 2. Decode bounds to compute sample size
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        val maxDim = 2048
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        // 3. Decode bitmap with sample size
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        // 4. Apply initial EXIF rotation if needed
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated != decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Renders the scaled, translated, rotated bitmap into a clean 512x512 square ByteArray.
 */
private fun cropAvatarToByteArray(
    source: Bitmap,
    userScale: Float,
    userOffset: Offset,
    rotationDegrees: Int,
    targetSize: Int = 512
): ByteArray? {
    return try {
        val resultBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val isRotated90 = (rotationDegrees % 180 != 0)
        val effectiveWidth = if (isRotated90) source.height.toFloat() else source.width.toFloat()
        val effectiveHeight = if (isRotated90) source.width.toFloat() else source.height.toFloat()

        // Base scale fitting the crop circle
        val cropDiameterRatio = 0.85f
        val previewCropSize = 1000f * cropDiameterRatio
        val baseScale = max(
            previewCropSize / effectiveWidth,
            previewCropSize / effectiveHeight
        )
        val totalScale = (baseScale * userScale) * (targetSize.toFloat() / previewCropSize)

        val matrix = Matrix()
        // Center of source bitmap
        matrix.postTranslate(-source.width / 2f, -source.height / 2f)
        // Apply user rotation
        matrix.postRotate(rotationDegrees.toFloat())
        // Apply scaling
        matrix.postScale(totalScale, totalScale)
        // Apply user offset mapped to target coordinate space
        val offsetScale = targetSize.toFloat() / previewCropSize
        matrix.postTranslate(
            targetSize / 2f + userOffset.x * offsetScale,
            targetSize / 2f + userOffset.y * offsetScale
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        val stream = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        resultBitmap.recycle()
        stream.toByteArray()
    } catch (e: Exception) {
        null
    }
}
