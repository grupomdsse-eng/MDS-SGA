package com.grupomds.sga.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.grupomds.sga.AppCrashReporter
import com.grupomds.sga.SgaApplication
import com.grupomds.sga.data.CsvImportResult
import com.grupomds.sga.data.DeliveryNoteEntity
import com.grupomds.sga.data.FinalizeResult
import com.grupomds.sga.data.GoogleSheetStockSource
import com.grupomds.sga.data.PickingSnapshot
import com.grupomds.sga.data.ProductEntity
import com.grupomds.sga.data.ProductScanCandidate
import com.grupomds.sga.data.ProductScanPreview
import com.grupomds.sga.data.ScanResult
import com.grupomds.sga.ocr.DeliveryNoteParser
import com.grupomds.sga.ocr.OcrToken
import com.grupomds.sga.ocr.ParsedDeliveryNote
import com.grupomds.sga.ocr.ParsedLine
import com.grupomds.sga.ocr.SafeOcrImageDecoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

class SgaViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SgaApplication).repository
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrTaskInFlight = AtomicBoolean(false)
    private val textRecognizerCloseRequested = AtomicBoolean(false)
    private val textRecognizerClosed = AtomicBoolean(false)

    /**
     * Última barrera para que una excepción de una operación asíncrona no termine el proceso.
     * Las operaciones importantes mantienen además sus mensajes específicos.
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
        if (error !is CancellationException) {
            AppCrashReporter.recordHandled(application, "ViewModel", error)
            _ocrBusy.value = false
            _scanBusy.value = false
            _syncBusy.value = false
            _operationMessage.value = "Se ha controlado un error interno: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    val products: StateFlow<List<ProductEntity>> = repo.observeProducts()
        .catch { error ->
            AppCrashReporter.recordHandled(application, "Flujo inventario", error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<DeliveryNoteEntity>> = repo.observeHistory()
        .catch { error ->
            AppCrashReporter.recordHandled(application, "Flujo historial", error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _draft = MutableStateFlow<ParsedDeliveryNote?>(null)
    val draft = _draft.asStateFlow()

    private val _ocrBusy = MutableStateFlow(false)
    val ocrBusy = _ocrBusy.asStateFlow()

    private val _ocrError = MutableStateFlow<String?>(null)
    val ocrError = _ocrError.asStateFlow()

    private val _picking = MutableStateFlow<PickingSnapshot?>(null)
    val picking = _picking.asStateFlow()

    private val _scanMessage = MutableStateFlow<Pair<Boolean, String>?>(null)
    val scanMessage = _scanMessage.asStateFlow()

    private val _scanBusy = MutableStateFlow(false)
    val scanBusy = _scanBusy.asStateFlow()
    private val scanMutex = Mutex()
    private val syncMutex = Mutex()

    private val _pendingProductScan = MutableStateFlow<ProductScanCandidate?>(null)
    val pendingProductScan = _pendingProductScan.asStateFlow()

    private val _syncBusy = MutableStateFlow(false)
    val syncBusy = _syncBusy.asStateFlow()

    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus = _syncStatus.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()

    init {
        AppCrashReporter.consumePreviousFatalNotice(application)?.let { notice ->
            _operationMessage.value = notice
        }
        // Sincronización en segundo plano. Conserva las salidas locales del SGA mediante sheetStock.
        syncStockFromGoogleSheet(showMessage = false)
    }

    private fun closeTextRecognizerWhenSafe() {
        if (!ocrTaskInFlight.get() && textRecognizerClosed.compareAndSet(false, true)) {
            runCatching { textRecognizer.close() }
        }
    }

    override fun onCleared() {
        textRecognizerCloseRequested.set(true)
        closeTextRecognizerWhenSafe()
        super.onCleared()
    }

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    fun clearScanMessage() {
        _scanMessage.value = null
    }

    fun clearOcrError() {
        _ocrError.value = null
    }

    fun clearPendingProductScan() {
        _pendingProductScan.value = null
    }

    fun syncStockFromGoogleSheet(showMessage: Boolean = true) {
        if (_syncBusy.value) return
        // Se marca antes de lanzar la corrutina para que dos pantallas que entren casi a la vez
        // no programen dos descargas consecutivas del mismo Google Sheet.
        _syncBusy.value = true
        viewModelScope.launch(coroutineExceptionHandler) {
            performGoogleSheetSync(showMessage = showMessage)
        }
    }

    /**
     * Serializa todas las sincronizaciones. Así, si la sincronización automática de arranque
     * sigue en curso y el operario intenta crear un albarán, la validación espera a que termine
     * en lugar de consultar una base de datos todavía incompleta.
     */
    private suspend fun performGoogleSheetSync(showMessage: Boolean): Result<CsvImportResult> {
        return syncMutex.withLock {
            _syncBusy.value = true
            _syncStatus.value = "Sincronizando Google Sheets…"
            try {
                val csv = withTimeout(18_000L) { GoogleSheetStockSource.downloadCsv() }
                val result = repo.importCsv(csv)
                val message = buildSyncMessage(result)
                _syncStatus.value = message
                if (showMessage) _operationMessage.value = message
                Result.success(result)
            } catch (_: TimeoutCancellationException) {
                val message = "Google Sheets no ha respondido a tiempo. Revisa la conexión y vuelve a sincronizar."
                _syncStatus.value = "Sin sincronizar: $message"
                if (showMessage) _operationMessage.value = message
                Result.failure(IllegalStateException(message))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Google Sheets", error)
                val message = error.message ?: "No se pudo sincronizar Google Sheets"
                _syncStatus.value = "Sin sincronizar: $message"
                if (showMessage) _operationMessage.value = message
                Result.failure(error)
            } finally {
                _syncBusy.value = false
            }
        }
    }

    private fun buildSyncMessage(result: CsvImportResult): String = buildString {
        append("Google Sheets: ${result.referencesRead} referencias leídas")
        append(" · ${result.imported} nuevas · ${result.updated} actualizadas")
        if (result.errors.isNotEmpty()) {
            append(". Avisos: ")
            append(result.errors.take(3).joinToString(" | "))
            if (result.errors.size > 3) append(" | …")
        }
    }

    fun scanDeliveryNote(uri: Uri, onDone: () -> Unit) {
        if (!ocrTaskInFlight.compareAndSet(false, true)) {
            _ocrError.value = "Todavía se está liberando una lectura OCR anterior. Espera unos segundos y vuelve a intentarlo."
            return
        }
        _ocrBusy.value = true
        _ocrError.value = null

        viewModelScope.launch(coroutineExceptionHandler) {
            var mlTaskStarted = false
            var bitmapForOcr: android.graphics.Bitmap? = null
            val timedOut = AtomicBoolean(false)
            try {
                val app = getApplication<Application>()

                // Evita cargar una fotografía de 12-50 MP completa en RAM.
                val decodedImage = withContext(Dispatchers.IO) {
                    SafeOcrImageDecoder.decode(app, uri)
                }
                val bitmap = decodedImage.bitmap
                bitmapForOcr = bitmap
                val image = try {
                    InputImage.fromBitmap(bitmap, decodedImage.rotationDegrees)
                } catch (error: Throwable) {
                    runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                    bitmapForOcr = null
                    throw error
                }

                val result = withTimeout(25_000L) {
                    suspendCancellableCoroutine { continuation ->
                        try {
                            val task = textRecognizer.process(image)
                            mlTaskStarted = true
                            task
                                .addOnSuccessListener { text ->
                                    if (continuation.isActive) continuation.resume(text)
                                }
                                .addOnFailureListener { error ->
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                }
                                .addOnCompleteListener {
                                    // ML Kit ya no necesita el bitmap, incluso si la tarea falló o
                                    // el timeout dejó de esperar su resultado.
                                    runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                                    bitmapForOcr = null
                                    ocrTaskInFlight.set(false)
                                    if (textRecognizerCloseRequested.get()) closeTextRecognizerWhenSafe()
                                    if (timedOut.get()) _ocrBusy.value = false
                                }
                        } catch (error: Throwable) {
                            // Si ML Kit falla antes incluso de crear la Task no habrá listener de
                            // finalización que libere el bitmap, por lo que lo hacemos aquí.
                            runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                            bitmapForOcr = null
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }

                val productsSnapshot = repo.allProducts()
                val parsed = withContext(Dispatchers.Default) {
                    val tokens = result.textBlocks.flatMap { block ->
                        block.lines.flatMap { line ->
                            line.elements.mapNotNull { element ->
                                element.boundingBox?.let { box ->
                                    OcrToken(
                                        text = element.text,
                                        left = box.left,
                                        top = box.top,
                                        right = box.right,
                                        bottom = box.bottom
                                    )
                                }
                            }
                        }
                    }

                    DeliveryNoteParser.parse(
                        rawText = result.text.take(120_000),
                        products = productsSnapshot,
                        tokens = tokens
                    )
                }

                _draft.value = parsed
                runCatching(onDone).onFailure { error ->
                    AppCrashReporter.recordHandled(app, "Navegación OCR", error)
                    _ocrError.value = "El albarán se ha leído, pero no se pudo abrir la revisión. Vuelve a intentarlo."
                }
            } catch (_: TimeoutCancellationException) {
                timedOut.set(true)
                if (!ocrTaskInFlight.get()) _ocrBusy.value = false
                _ocrError.value = "La lectura ha tardado demasiado. La app esperará a que ML Kit libere la imagen antes de permitir otra foto."
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: OutOfMemoryError) {
                // Evitamos intentar construir/loguear una traza grande cuando el dispositivo ya
                // está bajo presión de memoria. El decoder reintenta reducido antes de llegar aquí.
                _ocrError.value = "El dispositivo se ha quedado sin memoria al leer la foto. Vuelve a hacerla más cerca del albarán y reintenta."
                runCatching { System.gc() }
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "OCR", error)
                _ocrError.value = error.message ?: "No se pudo leer el albarán"
            } finally {
                if (!mlTaskStarted) {
                    bitmapForOcr?.let { bitmap ->
                        runCatching { if (!bitmap.isRecycled) bitmap.recycle() }
                    }
                    bitmapForOcr = null
                    ocrTaskInFlight.set(false)
                    _ocrBusy.value = false
                } else if (!timedOut.get()) {
                    _ocrBusy.value = false
                }
                try {
                    withContext(Dispatchers.IO) {
                        val directory = java.io.File(getApplication<Application>().cacheDir, "delivery_notes")
                        directory.listFiles()
                            ?.filter { it.isFile && it.name.startsWith("albaran_") }
                            ?.sortedByDescending { it.lastModified() }
                            ?.drop(2)
                            ?.forEach { it.delete() }
                    }
                } catch (_: Throwable) {
                    // La limpieza de caché nunca debe afectar al flujo de almacén.
                }
            }
        }
    }

    fun updateDraftNumber(value: String) {
        _draft.value = _draft.value?.copy(number = value.filter { it.isLetterOrDigit() || it == '-' || it == '/' })
    }

    fun updateDraftCustomer(value: String) {
        _draft.value = _draft.value?.copy(customer = value)
    }

    fun updateDraftLine(
        index: Int,
        reference: String? = null,
        description: String? = null,
        quantity: Int? = null
    ) {
        val current = _draft.value ?: return
        val lines = current.lines.toMutableList()
        if (index !in lines.indices) return
        val old = lines[index]
        lines[index] = old.copy(
            reference = reference ?: old.reference,
            description = description ?: old.description,
            quantity = quantity?.coerceAtLeast(1) ?: old.quantity
        )
        _draft.value = current.copy(lines = lines)
    }

    fun addDraftLine() {
        val current = _draft.value ?: ParsedDeliveryNote("", "", emptyList(), "")
        _draft.value = current.copy(
            lines = current.lines + ParsedLine(
                reference = "",
                description = "",
                quantity = 1,
                matchedProduct = false
            )
        )
    }

    fun removeDraftLine(index: Int) {
        val current = _draft.value ?: return
        _draft.value = current.copy(lines = current.lines.filterIndexed { lineIndex, _ -> lineIndex != index })
    }

    fun createNote(onCreated: (Long) -> Unit) {
        val current = _draft.value ?: return
        viewModelScope.launch(coroutineExceptionHandler) {
            // Siempre refresca el maestro justo antes de validar las referencias. Esto es
            // especialmente importante cuando se acaba de añadir un código nuevo a Sheets.
            _operationMessage.value = "Actualizando referencias desde Google Sheets…"
            val syncAttempt = performGoogleSheetSync(showMessage = false)

            try {
                val id = repo.createDeliveryNote(current)
                _draft.value = null
                _operationMessage.value = null
                loadPicking(id)
                runCatching { onCreated(id) }.onFailure { error ->
                    AppCrashReporter.recordHandled(getApplication(), "Navegación crear albarán", error)
                    _operationMessage.value = "Albarán creado, pero no se pudo abrir el picking. Puedes recuperarlo desde Historial."
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Crear albarán", error)
                val base = error.message ?: "No se pudo crear el albarán"
                _operationMessage.value = if (syncAttempt.isFailure) {
                    "$base\n\nAdemás, no se pudo actualizar Google Sheets: ${syncAttempt.exceptionOrNull()?.message ?: "error de sincronización"}."
                } else {
                    base
                }
            }
        }
    }

    fun loadPicking(noteId: Long) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                _picking.value = repo.pickingSnapshot(noteId)
                if (_picking.value == null) {
                    _operationMessage.value = "No se encuentra el albarán solicitado"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Cargar picking", error)
                _operationMessage.value = error.message ?: "No se pudo cargar el picking"
            }
        }
    }

    /**
     * Primera fase del picking: valida el EAN y abre el diálogo para indicar cuántas unidades
     * se van a picar. No modifica cantidades hasta que el operario confirma.
     */
    fun submitBarcode(noteId: Long, barcode: String) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isBlank() || _pendingProductScan.value != null) return

        viewModelScope.launch(coroutineExceptionHandler) {
            if (!scanMutex.tryLock()) return@launch
            _scanBusy.value = true
            try {
                _scanMessage.value = null
                when (val result = repo.previewProductScan(noteId, cleanBarcode)) {
                    is ProductScanPreview.Match -> _pendingProductScan.value = result.candidate
                    is ProductScanPreview.Rejected -> {
                        _scanMessage.value = false to result.message
                        _picking.value = repo.pickingSnapshot(noteId)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Validar EAN", error)
                _scanMessage.value = false to (error.message ?: "No se pudo validar el EAN")
            } finally {
                _scanBusy.value = false
                scanMutex.unlock()
            }
        }
    }

    fun confirmProductScan(noteId: Long, quantity: Int) {
        val candidate = _pendingProductScan.value ?: return
        if (quantity !in 1..candidate.remainingQty) {
            _scanMessage.value = false to "Indica una cantidad entre 1 y ${candidate.remainingQty}"
            return
        }

        viewModelScope.launch(coroutineExceptionHandler) {
            if (!scanMutex.tryLock()) return@launch
            _scanBusy.value = true
            try {
                when (val result = repo.recordScan(noteId, candidate.barcode, quantity)) {
                    is ScanResult.Accepted -> _scanMessage.value = true to result.message
                    is ScanResult.Rejected -> _scanMessage.value = false to result.message
                }
                _pendingProductScan.value = null
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Registrar picking", error)
                _scanMessage.value = false to (error.message ?: "No se pudo registrar la cantidad")
            } finally {
                _scanBusy.value = false
                scanMutex.unlock()
            }
        }
    }

    fun submitTransportLabel(noteId: Long, barcode: String) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isBlank()) return
        viewModelScope.launch(coroutineExceptionHandler) {
            if (!scanMutex.tryLock()) return@launch
            _scanBusy.value = true
            try {
                when (val result = repo.recordTransportLabel(noteId, cleanBarcode)) {
                    is ScanResult.Accepted -> _scanMessage.value = true to result.message
                    is ScanResult.Rejected -> _scanMessage.value = false to result.message
                }
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Etiqueta transporte", error)
                _scanMessage.value = false to (error.message ?: "No se pudo registrar la etiqueta de transporte")
            } finally {
                _scanBusy.value = false
                scanMutex.unlock()
            }
        }
    }

    fun removeTransportLabel(noteId: Long, labelId: Long) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                val removed = repo.removeTransportLabel(noteId, labelId)
                _scanMessage.value = if (removed) {
                    true to "Etiqueta eliminada"
                } else {
                    false to "No se pudo eliminar la etiqueta"
                }
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Eliminar etiqueta", error)
                _scanMessage.value = false to (error.message ?: "No se pudo eliminar la etiqueta")
            }
        }
    }

    fun finalize(noteId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                when (val result = repo.finalize(noteId)) {
                    FinalizeResult.Success -> {
                        _picking.value = repo.pickingSnapshot(noteId)
                        _operationMessage.value = "Albarán finalizado. Salida, stock y etiquetas de transporte registrados."
                        runCatching(onSuccess).onFailure { error ->
                            AppCrashReporter.recordHandled(getApplication(), "Navegación finalizar", error)
                        }
                    }
                    is FinalizeResult.Error -> _operationMessage.value = result.message
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Finalizar albarán", error)
                _operationMessage.value = error.message ?: "No se pudo finalizar el albarán"
            }
        }
    }

    fun saveProduct(product: ProductEntity) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                repo.upsertProduct(product)
                _operationMessage.value = "Producto guardado"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Guardar producto", error)
                _operationMessage.value = error.message ?: "No se pudo guardar el producto"
            }
        }
    }

    fun adjustStock(reference: String, delta: Int) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                val ok = repo.adjustStock(reference, delta)
                _operationMessage.value = if (ok) "Stock actualizado" else "No se puede dejar el stock en negativo"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Ajustar stock", error)
                _operationMessage.value = error.message ?: "No se pudo actualizar el stock"
            }
        }
    }

    fun reportUiError(message: String, error: Throwable? = null) {
        error?.let { AppCrashReporter.recordHandled(getApplication(), "Interfaz", it) }
        _operationMessage.value = message
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                val app = getApplication<Application>()
                val text = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val output = StringBuilder(128_000)
                        val buffer = CharArray(8_192)
                        val maxChars = 4_000_000
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            if (output.length + read > maxChars) {
                                error("El CSV seleccionado es demasiado grande para importarlo de forma segura")
                            }
                            output.append(buffer, 0, read)
                        }
                        output.toString()
                    } ?: error("No se pudo abrir el CSV seleccionado")
                }
                val result = repo.importCsv(text)
                _operationMessage.value = buildSyncMessage(result)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Importar CSV", error)
                _operationMessage.value = error.message ?: "No se pudo importar el archivo"
            }
        }
    }

    fun importCsv(text: String) {
        viewModelScope.launch(coroutineExceptionHandler) {
            try {
                val result: CsvImportResult = repo.importCsv(text)
                _operationMessage.value = buildSyncMessage(result)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                AppCrashReporter.recordHandled(getApplication(), "Importar CSV texto", error)
                _operationMessage.value = error.message ?: "No se pudo importar el archivo"
            }
        }
    }
}
