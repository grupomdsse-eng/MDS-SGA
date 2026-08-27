package com.grupomds.sga.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Composable
fun BarcodeCamera(
    enabled: Boolean,
    onBarcode: (String) -> Unit,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(300.dp)
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(
            modifier = modifier.background(Color(0xFF161A1D), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Dar permiso a la cámara")
            }
        }
        return
    }

    Box(modifier = modifier) {
        CameraPreview(
            enabled = enabled,
            onBarcode = onBarcode,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(280.dp)
                .height(120.dp)
                .border(3.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(14.dp))
        )
        Text(
            text = if (enabled) "Coloca el código dentro del recuadro" else "Procesando / picking completo",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CameraPreview(
    enabled: Boolean,
    onBarcode: (String) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val processing = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val lockedCode = remember { AtomicReference<String?>(null) }
    val lastBarcodeSeenAt = remember { AtomicLong(0L) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val analysisRef = remember { AtomicReference<ImageAnalysis?>(null) }
    val previewRef = remember { AtomicReference<Preview?>(null) }
    val enabledState = rememberUpdatedState(enabled)
    val onBarcodeState = rememberUpdatedState(onBarcode)

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_QR_CODE
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            val analysis = analysisRef.getAndSet(null)
            val preview = previewRef.getAndSet(null)
            analysis?.clearAnalyzer()
            providerRef.getAndSet(null)?.let { provider ->
                analysis?.let { provider.unbind(it) }
                preview?.let { provider.unbind(it) }
            }
            processing.set(false)
            runCatching { scanner.close() }
            executor.shutdownNow()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { androidContext ->
            val previewView = PreviewView(androidContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val providerFuture = ProcessCameraProvider.getInstance(androidContext)
            providerFuture.addListener({
                if (disposed.get()) return@addListener

                runCatching {
                    val provider = providerFuture.get()
                    if (disposed.get()) return@runCatching

                    providerRef.set(provider)
                    val preview = Preview.Builder().build().also { cameraPreview ->
                        cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    previewRef.set(preview)
                    analysisRef.set(analysis)

                    analysis.setAnalyzer(executor) { imageProxy ->
                        if (disposed.get() || !enabledState.value || !processing.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            processing.set(false)
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                if (disposed.get()) return@addOnSuccessListener

                                val now = SystemClock.elapsedRealtime()
                                val rawValue = barcodes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotBlank) }

                                if (rawValue == null) {
                                    // El mismo artículo solo puede volver a contarse después de
                                    // retirar físicamente su código del encuadre durante un instante.
                                    if (now - lastBarcodeSeenAt.get() >= 650L) {
                                        lockedCode.set(null)
                                    }
                                } else {
                                    lastBarcodeSeenAt.set(now)
                                    val previous = lockedCode.get()
                                    if (previous == null || previous != rawValue) {
                                        lockedCode.set(rawValue)
                                        onBarcodeState.value(rawValue)
                                    }
                                }
                            }
                            .addOnCompleteListener {
                                processing.set(false)
                                imageProxy.close()
                            }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }
            }, ContextCompat.getMainExecutor(androidContext))

            previewView
        }
    )
}
