package com.grupomds.sga.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.grupomds.sga.AppCrashReporter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cámara documental integrada.
 *
 * Guarda directamente la captura en el almacenamiento privado de la aplicación. De este modo
 * evitamos depender de una aplicación de cámara externa y de permisos temporales FileProvider,
 * que eran la causa más probable de "No se puede abrir la fotografía" en algunos dispositivos.
 */
@Composable
fun DocumentCamera(
    enabled: Boolean,
    onCaptured: (File) -> Unit,
    onError: (String, Throwable?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var bindingError by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    val disposed = remember { AtomicBoolean(false) }
    val captureRef = remember { AtomicReference<ImageCapture?>(null) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val onCapturedState = rememberUpdatedState(onCaptured)
    val onErrorState = rememberUpdatedState(onError)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) bindingError = "La cámara necesita permiso para fotografiar albaranes."
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner) {
        disposed.set(false)
        onDispose {
            disposed.set(true)
            runCatching { cameraRef.getAndSet(null)?.cameraControl?.enableTorch(false) }
            runCatching { providerRef.getAndSet(null)?.unbindAll() }
            captureRef.set(null)
        }
    }

    if (!granted) {
        Box(
            modifier = modifier
                .background(Color(0xFF101820), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Permitir cámara")
            }
        }
        return
    }

    Box(modifier = modifier.background(Color.Black, RoundedCornerShape(20.dp))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { androidContext ->
                val previewView = PreviewView(androidContext).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(androidContext)
                future.addListener({
                    if (disposed.get()) return@addListener
                    try {
                        val provider = future.get()
                        if (disposed.get()) return@addListener
                        providerRef.set(provider)
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setTargetResolution(Size(1600, 1200))
                            .build()
                        captureRef.set(imageCapture)

                        provider.unbindAll()
                        val camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                        cameraRef.set(camera)
                        bindingError = null
                    } catch (error: Throwable) {
                        AppCrashReporter.recordHandled(context, "Cámara documental", error)
                        bindingError = "No se pudo iniciar la cámara."
                        onErrorState.value("No se pudo iniciar la cámara del albarán.", error)
                    }
                }, ContextCompat.getMainExecutor(androidContext))
                previewView
            }
        )

        // Guía A4: ayuda a que el OCR reciba siempre una fotografía centrada y consistente.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(270.dp)
                .height(390.dp)
                .border(2.dp, Color.White.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
        )

        Surface(
            color = Color.Black.copy(alpha = 0.65f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Encuadra el albarán completo", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text("Mantén ALBARÁN y ARTÍCULO nítidos", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                enabled = enabled && !capturing,
                onClick = {
                    val next = !torchEnabled
                    runCatching { cameraRef.get()?.cameraControl?.enableTorch(next) }
                        .onSuccess { torchEnabled = next }
                        .onFailure { onErrorState.value("No se pudo cambiar el flash.", it) }
                }
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash",
                    tint = Color.White
                )
            }

            Button(
                enabled = enabled && !capturing && captureRef.get() != null,
                onClick = {
                    val imageCapture = captureRef.get() ?: return@Button
                    capturing = true
                    val dir = File(context.cacheDir, "delivery_notes").apply { mkdirs() }
                    runCatching {
                        dir.listFiles()
                            ?.filter { it.isFile && it.name.startsWith("albaran_") }
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(3)
                            ?.forEach { it.delete() }
                    }
                    val file = File(dir, "albaran_${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    runCatching {
                        imageCapture.takePicture(
                            options,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    if (disposed.get()) return
                                    if (!file.exists() || file.length() <= 0L) {
                                        val error = IllegalStateException("La cámara no generó un archivo válido")
                                        onErrorState.value("La fotografía no se guardó correctamente. Repite la captura.", error)
                                        return
                                    }
                                    onCapturedState.value(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    if (disposed.get()) return
                                    AppCrashReporter.recordHandled(context, "Captura albarán", exception)
                                    onErrorState.value("No se pudo guardar la fotografía. Vuelve a intentarlo.", exception)
                                }
                            }
                        )
                    }.onFailure { error ->
                        capturing = false
                        if (!disposed.get()) {
                            AppCrashReporter.recordHandled(context, "Captura albarán", error)
                            onErrorState.value("La cámara no pudo iniciar la captura. Vuelve a intentarlo.", error)
                        }
                    }
                },
                shape = CircleShape,
                modifier = Modifier.size(66.dp)
            ) {
                if (capturing) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.White)
                } else {
                    Text("FOTO", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Equilibra el botón de flash para mantener el disparador centrado.
            Box(Modifier.size(48.dp))
        }

        bindingError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(18.dp)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
