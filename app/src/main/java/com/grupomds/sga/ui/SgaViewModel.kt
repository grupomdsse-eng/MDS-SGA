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
import com.grupomds.sga.data.PickingSnapshot
import com.grupomds.sga.data.ProductEntity
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine

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

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()

    fun clearOperationMessage() {
        _operationMessage.value = null
    }

    fun clearScanMessage() {
        _scanMessage.value = null
    }

    fun clearOcrError() {
        _ocrError.value = null
    }

    fun scanDeliveryNote(uri: Uri, onDone: () -> Unit) {
        if (_ocrBusy.value) return

        viewModelScope.launch {
            _ocrBusy.value = true
            _ocrError.value = null
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val app = getApplication<Application>()

                // La carga de una foto grande puede tardar y no debe bloquear el hilo de interfaz.
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
            try {
                val id = repo.createDeliveryNote(current)
                _draft.value = null
                loadPicking(id)
                onCreated(id)
            } catch (error: Throwable) {
                _operationMessage.value = error.message ?: "No se pudo crear el albarán"
            }
        }
    }

    fun loadPicking(noteId: Long) {
        viewModelScope.launch {
            _picking.value = repo.pickingSnapshot(noteId)
        }
    }

    fun submitBarcode(noteId: Long, barcode: String) {
        val cleanBarcode = barcode.trim()
        if (cleanBarcode.isBlank()) return

        viewModelScope.launch {
            // Evita una cola creciente de lecturas si la cámara sigue viendo el mismo código.
            if (!scanMutex.tryLock()) return@launch
            _scanBusy.value = true
            try {
                _scanMessage.value = null
                when (val result = repo.recordScan(noteId, cleanBarcode)) {
                    is ScanResult.Accepted -> _scanMessage.value = true to result.message
                    is ScanResult.Rejected -> _scanMessage.value = false to result.message
                }
                _picking.value = repo.pickingSnapshot(noteId)
            } catch (error: Throwable) {
                _scanMessage.value = false to (error.message ?: "No se pudo registrar la lectura")
            } finally {
                _scanBusy.value = false
                scanMutex.unlock()
            }
        }
    }

    fun finalize(noteId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch {
            when (val result = repo.finalize(noteId)) {
                FinalizeResult.Success -> {
                    _picking.value = repo.pickingSnapshot(noteId)
                    _operationMessage.value = "Albarán finalizado. La salida y el stock han quedado registrados."
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
                _operationMessage.value = buildString {
                    append("Importación: ${result.imported} nuevos, ${result.updated} actualizados")
                    if (result.errors.isNotEmpty()) {
                        append(". Avisos: ")
                        append(result.errors.take(4).joinToString(" | "))
                        if (result.errors.size > 4) append(" | …")
                    }
                }
            } catch (error: Throwable) {
                _operationMessage.value = error.message ?: "No se pudo importar el archivo"
            }
        }
    }
}
