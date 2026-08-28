package com.grupomds.sga.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SgaViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SgaApplication).repository

    val products: StateFlow<List<ProductEntity>> = repo.observeProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<DeliveryNoteEntity>> = repo.observeHistory()
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
        // Sincronización en segundo plano. Conserva las salidas locales del SGA mediante sheetStock.
        syncStockFromGoogleSheet(showMessage = false)
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
        viewModelScope.launch {
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
            } catch (error: Throwable) {
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
        if (_ocrBusy.value) return
        _ocrBusy.value = true
        _ocrError.value = null

        viewModelScope.launch {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val app = getApplication<Application>()

                val image = withContext(Dispatchers.IO) {
                    InputImage.fromFilePath(app, uri)
                }

                val result = withTimeout(25_000L) {
                    suspendCancellableCoroutine { continuation ->
                        recognizer.process(image)
                            .addOnSuccessListener { text ->
                                if (continuation.isActive) continuation.resume(text)
                            }
                            .addOnFailureListener { error ->
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
                        rawText = result.text,
                        products = productsSnapshot,
                        tokens = tokens
                    )
                }

                _draft.value = parsed
                onDone()
            } catch (_: TimeoutCancellationException) {
                _ocrError.value = "La lectura ha tardado demasiado. Haz otra foto con el albarán centrado y bien enfocado."
            } catch (error: Throwable) {
                _ocrError.value = error.message ?: "No se pudo leer el albarán"
            } finally {
                runCatching { recognizer.close() }
                _ocrBusy.value = false
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
        viewModelScope.launch {
            // Siempre refresca el maestro justo antes de validar las referencias. Esto es
            // especialmente importante cuando se acaba de añadir un código nuevo a Sheets.
            _operationMessage.value = "Actualizando referencias desde Google Sheets…"
            val syncAttempt = performGoogleSheetSync(showMessage = false)

            try {
                val id = repo.createDeliveryNote(current)
                _draft.value = null
                _operationMessage.value = null
                loadPicking(id)
                onCreated(id)
            } catch (error: Throwable) {
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
        viewModelScope.launch {
            _picking.value = repo.pickingSnapshot(noteId)
        }
    }

    /**
     * Primera fase del picking: valida el EAN y abre el diálogo para indicar cuántas unidades
     * se van a picar. No modifica cantidades hasta que el operario confirma.
     */
    fun submitBarcode(noteId: Long, barcode: String) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isBlank() || _pendingProductScan.value != null) return

        viewModelScope.launch {
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
            } catch (error: Throwable) {
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

        viewModelScope.launch {
            if (!scanMutex.tryLock()) return@launch
            _scanBusy.value = true
            try {
                when (val result = repo.recordScan(noteId, candidate.barcode, quantity)) {
                    is ScanResult.Accepted -> _scanMessage.value = true to result.message
                    is ScanResult.Rejected -> _scanMessage.value = false to result.message
                }
                _pendingProductScan.value = null
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (error: Throwable) {
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
        viewModelScope.launch {
            if (!scanMutex.tryLock()) return@launch
            _scanBusy.value = true
            try {
                when (val result = repo.recordTransportLabel(noteId, cleanBarcode)) {
                    is ScanResult.Accepted -> _scanMessage.value = true to result.message
                    is ScanResult.Rejected -> _scanMessage.value = false to result.message
                }
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (error: Throwable) {
                _scanMessage.value = false to (error.message ?: "No se pudo registrar la etiqueta de transporte")
            } finally {
                _scanBusy.value = false
                scanMutex.unlock()
            }
        }
    }

    fun removeTransportLabel(noteId: Long, labelId: Long) {
        viewModelScope.launch {
            try {
                val removed = repo.removeTransportLabel(noteId, labelId)
                _scanMessage.value = if (removed) {
                    true to "Etiqueta eliminada"
                } else {
                    false to "No se pudo eliminar la etiqueta"
                }
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (error: Throwable) {
                _scanMessage.value = false to (error.message ?: "No se pudo eliminar la etiqueta")
            }
        }
    }

    fun finalize(noteId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch {
            when (val result = repo.finalize(noteId)) {
                FinalizeResult.Success -> {
                    _picking.value = repo.pickingSnapshot(noteId)
                    _operationMessage.value = "Albarán finalizado. Salida, stock y etiquetas de transporte registrados."
                    onSuccess()
                }
                is FinalizeResult.Error -> _operationMessage.value = result.message
            }
        }
    }

    fun saveProduct(product: ProductEntity) {
        viewModelScope.launch {
            try {
                repo.upsertProduct(product)
                _operationMessage.value = "Producto guardado"
            } catch (error: Throwable) {
                _operationMessage.value = error.message ?: "No se pudo guardar el producto"
            }
        }
    }

    fun adjustStock(reference: String, delta: Int) {
        viewModelScope.launch {
            val ok = repo.adjustStock(reference, delta)
            _operationMessage.value = if (ok) "Stock actualizado" else "No se puede dejar el stock en negativo"
        }
    }

    fun importCsv(text: String) {
        viewModelScope.launch {
            try {
                val result: CsvImportResult = repo.importCsv(text)
                _operationMessage.value = buildSyncMessage(result)
            } catch (error: Throwable) {
                _operationMessage.value = error.message ?: "No se pudo importar el archivo"
            }
        }
    }
}
