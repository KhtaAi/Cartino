package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.util.IranianBankHelper
import com.example.util.ParsedCardData
import com.example.util.TextPreprocessor
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/**
 * Frame Consolidation Engine: Maintains a sliding buffer of recent frame detections
 * to eliminate flickering and select the most frequent (majority vote) detected values.
 */
class FrameConsensusStabilizer(private val bufferSize: Int = 12) {
    private val cardHistory = mutableListOf<ParsedCardData>()

    fun addFrame(parsed: ParsedCardData): ParsedCardData {
        if (cardHistory.size >= bufferSize) {
            cardHistory.removeAt(0)
        }
        cardHistory.add(parsed)

        // Majority voting for each field
        val bestCardNumber = getMostFrequent(cardHistory.mapNotNull { it.cardNumber })
        val bestIban = getMostFrequent(cardHistory.mapNotNull { it.iban })
        val bestCvv2 = getMostFrequent(cardHistory.mapNotNull { it.cvv2 })
        val bestYear = getMostFrequent(cardHistory.mapNotNull { it.expiryYear })
        val bestMonth = getMostFrequent(cardHistory.mapNotNull { it.expiryMonth })

        return ParsedCardData(
            cardNumber = bestCardNumber ?: parsed.cardNumber,
            iban = bestIban ?: parsed.iban,
            expiryYear = bestYear ?: parsed.expiryYear,
            expiryMonth = bestMonth ?: parsed.expiryMonth,
            cvv2 = bestCvv2 ?: parsed.cvv2,
            rawText = parsed.rawText
        )
    }

    fun getStabilityCount(cardNumber: String?): Int {
        if (cardNumber == null) return 0
        return cardHistory.count { it.cardNumber == cardNumber }
    }

    fun clear() {
        cardHistory.clear()
    }

    private fun getMostFrequent(list: List<String>): String? {
        if (list.isEmpty()) return null
        return list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraOcrScreen(
    onCardScanned: (ParsedCardData) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (cameraPermissionState.status.isGranted) {
        CameraPreviewContent(
            onCardScanned = onCardScanned,
            onBackClick = onBackClick,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "دسترسی به دوربین الزامی است",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "جهت اسکن هوشمند متون روی کارت بانکی، به مجوز استفاده از دوربین نیاز است.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("اعطای دسترسی دوربین")
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewContent(
    onCardScanned: (ParsedCardData) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var scannedResult by remember { mutableStateOf<ParsedCardData?>(null) }
    var detectedBankName by remember { mutableStateOf<String?>(null) }
    var isScanLocked by remember { mutableStateOf(false) }
    var isFlashOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<Camera?>(null) }

    val stabilizer = remember { FrameConsensusStabilizer(bufferSize = 10) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            textRecognizer.close()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Live Camera Preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isScanLocked) {
                            processImageProxy(imageProxy, textRecognizer) { rawParsed ->
                                val stabilized = stabilizer.addFrame(rawParsed)
                                if (stabilized.cardNumber != null) {
                                    scannedResult = stabilized
                                    detectedBankName = IranianBankHelper.getBankByCardNumber(stabilized.cardNumber).name
                                    
                                    // Auto-lock frame if card number is stable across at least 4 frames
                                    val count = stabilizer.getStabilityCount(stabilized.cardNumber)
                                    if (count >= 4 && !isScanLocked) {
                                        isScanLocked = true
                                    }
                                }
                            }
                        } else {
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        val boundCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                        cameraControl = boundCamera
                    } catch (e: Exception) {
                        Log.e("CameraOcrScreen", "Error binding camera", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay & Framing Box
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar with Flash Control
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                Text(
                    text = if (isScanLocked) "اسکن قفل شد" else "اسکن هوشمند کارت (OCR)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                // Flash Toggle Button
                IconButton(
                    onClick = {
                        isFlashOn = !isFlashOn
                        cameraControl?.cameraControl?.enableTorch(isFlashOn)
                    },
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash Toggle",
                        tint = if (isFlashOn) Color.Yellow else Color.White
                    )
                }
            }

            // Card Frame Guide
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .border(
                        width = 2.dp,
                        color = if (isScanLocked) Color(0xFF10B981) else if (scannedResult?.cardNumber != null) Color(0xFF06B6D4) else Color.White.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .background(
                        if (isScanLocked) Color(0x3310B981) else if (scannedResult?.cardNumber != null) Color(0x1106B6D4) else Color(0x11000000)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (scannedResult?.cardNumber == null) {
                    Text(
                        text = "کارت را متناسب با کادر نگه دارید",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (isScanLocked) Icons.Default.Lock else Icons.Default.Check,
                            contentDescription = "Card Found",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isScanLocked) "تصویر کارت ${detectedBankName ?: ""} قفل شد!" else "شناسایی کارت ${detectedBankName ?: ""}...",
                            color = Color(0xFF10B981),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        if (!isScanLocked) {
                            Text(
                                text = "در حال تثبیت اطلاعات...",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // Bottom Results Sheet
            scannedResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "اطلاعات استخراج شده کارت:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            if (isScanLocked) {
                                OutlinedButton(
                                    onClick = {
                                        isScanLocked = false
                                        stabilizer.clear()
                                        scannedResult = null
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("اسکن مجدد", fontSize = 12.sp)
                                }
                            } else {
                                Button(
                                    onClick = { isScanLocked = true },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("قفل تصویر", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        result.cardNumber?.let {
                            Text("شماره کارت: ${IranianBankHelper.formatCardNumberDisplay(it)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        result.iban?.let {
                            Text("شماره شبا: ${IranianBankHelper.formatIbanDisplay(it)}", fontSize = 13.sp)
                        } ?: Text("شماره شبا: شناسایی نشد", fontSize = 12.sp, color = Color.Gray)

                        if (result.expiryYear != null && result.expiryMonth != null) {
                            Text("تاریخ انقضا: ${result.expiryYear}/${result.expiryMonth}", fontSize = 13.sp)
                        } else {
                            Text("تاریخ انقضا: شناسایی نشد", fontSize = 12.sp, color = Color.Gray)
                        }

                        result.cvv2?.let {
                            Text("CVV2: $it", fontSize = 13.sp)
                        } ?: Text("CVV2: شناسایی نشد", fontSize = 12.sp, color = Color.Gray)

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { onCardScanned(result) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CreditCard, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تایید و انتقال به فرم کارت", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } ?: run {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "کارت را ثابت جلوی دوربین نگه دارید",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { onCardScanned(ParsedCardData()) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("ورود دستی اطلاعات کارت")
                    }
                }
            }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
private fun processImageProxy(
    imageProxy: ImageProxy,
    textRecognizer: com.google.mlkit.vision.text.TextRecognizer,
    onResult: (ParsedCardData) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text
                val parsed = TextPreprocessor.extractCardDataFromText(rawText)
                if (parsed.cardNumber != null) {
                    onResult(parsed)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

