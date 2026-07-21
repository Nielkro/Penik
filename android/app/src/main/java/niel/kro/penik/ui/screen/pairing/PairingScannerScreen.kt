package niel.kro.penik.ui.screen.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import niel.kro.penik.data.network.api.ApiService
import niel.kro.penik.data.network.api.PairingClaimRequest
import niel.kro.penik.data.crypto.E2EECrypto
import niel.kro.penik.data.repository.MessageRepository
import niel.kro.penik.data.repository.SecureTokenStorage
import niel.kro.penik.data.network.websocket.WebSocketEvent
import niel.kro.penik.data.network.websocket.WebSocketManager
import javax.inject.Inject
import kotlinx.serialization.json.*

data class PairPayload(val session: String, val token: String, val ephemeralKey: String)

private fun parsePairingPayload(value: String): PairPayload? {
    // QR readers may include a leading/trailing whitespace or a line break.
    // Normalize it before splitting; otherwise Base64 decoding later reports
    // the misleading "String contains an invalid character" error.
    val normalized = value.trim().replace("\n", "").replace("\r", "")
    val parts = normalized.split(":", limit = 4)
    if (parts.size != 4 || parts[0] != "penik-pair-v1" || parts.any { it.isBlank() }) return null
    if (!parts[1].matches(Regex("[A-Za-z0-9_-]+")) ||
        !parts[2].matches(Regex("[A-Za-z0-9_-]+")) ||
        !parts[3].matches(Regex("[A-Za-z0-9_-]+"))) return null
    return PairPayload(parts[1], parts[2], parts[3])
}

@HiltViewModel
class PairingScannerViewModel @Inject constructor(private val api: ApiService, private val crypto: E2EECrypto, private val tokenStorage: SecureTokenStorage, private val messageRepository: MessageRepository, private val webSocketManager: WebSocketManager) : androidx.lifecycle.ViewModel() {
    var message by mutableStateOf<String?>(null)
        private set
    var success by mutableStateOf(false)
        private set
    var claiming by mutableStateOf(false)
        private set
    private var lastSession: String? = null

    fun claim(payload: PairPayload) {
        if (claiming || success || lastSession == payload.session) return
        lastSession = payload.session
        claiming = true
        message = null
        viewModelScope.launch {
            try {
                val kp = crypto.generateX25519KeyPair()
                val response = api.claimPairingSession(PairingClaimRequest(payload.session, payload.token, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(kp.second)))
                if (response.isSuccessful) {
                    var transfer = response.body()?.encryptedHistory.orEmpty()
                    if (transfer.isBlank()) {
                        transfer = kotlinx.coroutines.withTimeoutOrNull(300_000) {
                            while (true) {
                                val current = api.getPairingSession(payload.session).body()?.encryptedHistory.orEmpty()
                                if (current.isNotBlank()) return@withTimeoutOrNull current
                                delay(1000)
                            }
                            ""
                        }.orEmpty()
                    }
                    if (transfer.isBlank()) {
                        throw IllegalStateException("Веб не передал историю")
                    }
                    messageRepository.importPairingHistory(transfer, crypto.deriveSharedSecret(kp.first, decodeUrlBase64(payload.ephemeralKey)))
                    success = true
                    message = "Устройство успешно подключено"
                } else {
                    message = "Не удалось подключить устройство (${response.code()})"
                    // A pairing session is one-use/expired. Keep the scanner
                    // locked so the same QR is not submitted on every frame.
                    if (response.code() == 410) return@launch
                }
            } catch (e: Exception) {
                message = e.message ?: "Ошибка подключения устройства"
            } finally { claiming = false }
        }
    }
}

private fun decodeUrlBase64(value: String): ByteArray {
    val normalized = value.trim().replace('+', '-').replace('/', '_')
    return java.util.Base64.getUrlDecoder().decode(normalized)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScannerScreen(
    onBack: () -> Unit,
    viewModel: PairingScannerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    Scaffold(topBar = { TopAppBar(title = { Text("Подключить устройство") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Назад") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                viewModel.success -> ResultPanel(viewModel.message ?: "Устройство успешно подключено", onBack)
                !granted -> ResultPanel("Требуется доступ к камере для сканирования QR-кода", onBack)
                else -> {
                    Text("Наведите камеру на QR-код устройства", Modifier.padding(16.dp))
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).also { previewView ->
                            val providerFuture = ProcessCameraProvider.getInstance(ctx)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                                val scanner = BarcodeScanning.getClient()
                                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                                    val image = proxy.image
                                    if (image != null) scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                                        .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { parsePairingPayload(it.rawValue.orEmpty()) }?.let(viewModel::claim) }
                                        .addOnCompleteListener { proxy.close() }
                                    else proxy.close()
                                }
                                provider.unbindAll(); provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }, ContextCompat.getMainExecutor(ctx))
                        } }, Modifier.fillMaxWidth().weight(1f))
                    viewModel.message?.let { Text(it, Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Готово") }
    }
}
