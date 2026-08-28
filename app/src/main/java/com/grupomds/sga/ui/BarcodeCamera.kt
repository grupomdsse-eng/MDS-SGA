package com.grupomds.sga.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Size
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.grupomds.sga.AppCrashReporter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        if (!result) cameraError = "La cámara necesita permiso para escanear códigos."
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
            onError = { message -> cameraError = message },
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
            text = cameraError ?: if (enabled) {
                "Coloca el código dentro del recuadro"
            } else {
                "Procesando / esperando confirmación"
            },
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun CameraPreview(
    enabled: Boolean,
    onBarcode: (String) -> Unit,
    onError: (String?) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val processing = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val lockedCode = remember { AtomicReference<String?>(null) }
    val lastBarcodeSeenAt = remember { AtomicLong(0L) }
    val lastErrorLoggedAt = remember { AtomicLong(0L) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val analysisRef = remember { AtomicReference<ImageAnalysis?>(null) }
    val previewRef = remember { AtomicReference<Preview?>(null) }
    val enabledState = rememberUpdatedState(enabled)
    val onBarcodeState = rememberUpdatedState(onBarcode)
    val onErrorState = rememberUpdatedState(onError)

    val scanner = remember { BarcodeScanning.getClient() }
    val activeScannerTasks = remember { AtomicInteger(0) }
    val scannerCloseRequested = remember { AtomicBoolean(false) }
    val scannerClosed = remember { AtomicBoolean(false) }

    fun reportError(message: String, error: Throwable? = null) {
        if (error != null) {
            val now = SystemClock.elapsedRealtime()
            val previous = lastErrorLoggedAt.get()
            if (now - previous >= 10_000L && lastErrorLoggedAt.compareAndSet(previous, now)) {
                AppCrashReporter.recordHandled(context, "Cámara", error)
            }
        }
        ContextCompat.getMainExecutor(context).execute {
            if (!disposed.get()) onErrorState.value(message)
        }
    }

    fun closeScannerWhenSafe() {
        if (activeScannerTasks.get() == 0 && scannerClosed.compareAndSet(false, true)) {
            runCatching { scanner.close() }
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            disposed.set(true)
            scannerCloseRequested.set(true)

            val analysis = analysisRef.getAndSet(null)
            val preview = previewRef.getAndSet(null)
            analysis?.clearAnalyzer()
            providerRef.getAndSet(null)?.let { provider ->
                runCatching { analysis?.let { provider.unbind(it) } }
                runCatching { preview?.let { provider.unbind(it) } }
            }
            processing.set(false)
            closeScannerWhenSafe()
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

                try {
                    val provider = providerFuture.get()
                    if (disposed.get()) return@addListener

                    providerRef.set(provider)
                    val preview = Preview.Builder().build().also { cameraPreview ->
                        cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        // 1280x720 es más que suficiente para EAN/Code128 y evita analizar
                        // fotogramas enormes de la cámara durante horas de picking.
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    previewRef.set(preview)
                    analysisRef.set(analysis)

                    analysis.setAnalyzer(executor) { imageProxy ->
                        if (disposed.get() || !enabledState.value || !processing.compareAndSet(false, true)) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val imageClosed = AtomicBoolean(false)
                        fun finishFrame() {
                            if (imageClosed.compareAndSet(false, true)) {
                                runCatching { imageProxy.close() }
                            }
                            processing.set(false)
                        }

                        // Reservamos el scanner antes de comprobar disposed para evitar que onDispose
                        // lo cierre entre la comprobación y scanner.process().
                        activeScannerTasks.incrementAndGet()
                        var taskStarted = false
                        try {
                            if (disposed.get()) {
                                finishFrame()
                                return@setAnalyzer
                            }

                            val mediaImage = imageProxy.image
                            if (mediaImage == null) {
                                finishFrame()
                                return@setAnalyzer
                            }

                            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                            val task = scanner.process(input)
                            taskStarted = true
                            task
                                .addOnSuccessListener { barcodes ->
                                    if (disposed.get()) return@addOnSuccessListener

                                    val now = SystemClock.elapsedRealtime()
                                    val detected = barcodes
                                        .filter { !it.rawValue.isNullOrBlank() }
                                        .maxByOrNull { barcode ->
                                            val box = barcode.boundingBox
                                            if (box == null) 0L else box.width().toLong() * box.height().toLong()
                                        }
                                    val rawValue = detected?.rawValue?.trim()?.takeIf(String::isNotBlank)

                                    if (rawValue == null) {
                                        if (now - lastBarcodeSeenAt.get() >= 650L) {
                                            lockedCode.set(null)
                                        }
                                    } else {
                                        lastBarcodeSeenAt.set(now)
                                        val previous = lockedCode.get()
                                        if (previous == null || previous != rawValue) {
                                            lockedCode.set(rawValue)
                                            runCatching { onBarcodeState.value(rawValue) }
                                                .onFailure { error -> reportError("No se pudo procesar la lectura.", error) }
                                        }
                                    }
                                }
                                .addOnFailureListener { error ->
                                    reportError("La cámara no pudo analizar este fotograma. Sigue apuntando al código.", error)
                                }
                                .addOnCompleteListener {
                                    finishFrame()
                                    if (activeScannerTasks.decrementAndGet() == 0 && scannerCloseRequested.get()) {
                                        closeScannerWhenSafe()
                                    }
                                }
                        } catch (error: Throwable) {
                            reportError("Se ha recuperado un error de cámara. Puedes seguir escaneando.", error)
                            finishFrame()
                        } finally {
                            if (!taskStarted) {
                                if (activeScannerTasks.decrementAndGet() == 0 && scannerCloseRequested.get()) {
                                    closeScannerWhenSafe()
                                }
                            }
                        }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    onErrorState.value(null)
                } catch (error: Throwable) {
                    reportError("No se ha podido iniciar la cámara. Sal de esta pantalla y vuelve a entrar.", error)
                }
            }, ContextCompat.getMainExecutor(androidContext))

            previewView
        }
    )
}
