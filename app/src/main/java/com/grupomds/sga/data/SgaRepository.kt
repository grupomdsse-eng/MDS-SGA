package com.grupomds.sga.data

import androidx.room.withTransaction
import com.grupomds.sga.ocr.ParsedDeliveryNote
import java.math.BigDecimal
import java.text.Normalizer
import kotlinx.coroutines.flow.Flow

sealed class ScanResult {
    data class Accepted(val message: String) : ScanResult()
    data class Rejected(val message: String) : ScanResult()
}

sealed class ProductScanPreview {
    data class Match(val candidate: ProductScanCandidate) : ProductScanPreview()
    data class Rejected(val message: String) : ProductScanPreview()
}

sealed class FinalizeResult {
    data object Success : FinalizeResult()
    data class Error(val message: String) : FinalizeResult()
}

data class CsvImportResult(
    val imported: Int,
    val updated: Int,
    val errors: List<String>
)

class SgaRepository(private val db: SgaDatabase) {
    private val products = db.productDao()
    private val deliveries = db.deliveryDao()
    private val movements = db.stockMovementDao()

    fun observeProducts(): Flow<List<ProductEntity>> = products.observeAll()
    fun observeHistory(): Flow<List<DeliveryNoteEntity>> = deliveries.observeHistory()

    suspend fun allProducts(): List<ProductEntity> = products.getAll()

    suspend fun upsertProduct(input: ProductEntity) = db.withTransaction {
        val reference = normalizeReference(input.reference)
        require(reference.isNotBlank()) { "La referencia no puede estar vacía" }
        require(input.description.trim().isNotBlank()) { "La descripción no puede estar vacía" }
        require(input.stock >= 0) { "El stock no puede ser negativo" }

        val ean = normalizeBarcode(input.ean.orEmpty()).takeIf { it.isNotBlank() }
        if (ean != null) {
            val owner = products.byEan(ean)
            require(owner == null || owner.reference == reference) {
                "El código de barras $ean ya pertenece a ${owner?.reference}"
            }
        }

        val existing = products.byReference(reference)
        val now = System.currentTimeMillis()
        val normalized = input.copy(
            reference = reference,
            ean = ean,
            description = input.description.trim(),
            location = input.location.trim(),
            stock = input.stock.coerceAtLeast(0),
            updatedAt = now,
            sheetStock = existing?.sheetStock ?: input.sheetStock
        )

        if (existing == null) {
            products.insert(normalized)
            if (normalized.stock != 0) {
                movements.insert(
                    StockMovementEntity(
                        productReference = reference,
                        delta = normalized.stock,
                        stockAfter = normalized.stock,
                        reason = "Alta de producto"
                    )
                )
            }
        } else {
            products.update(normalized)
            val delta = normalized.stock - existing.stock
            if (delta != 0) {
                movements.insert(
                    StockMovementEntity(
                        productReference = reference,
                        delta = delta,
                        stockAfter = normalized.stock,
                        reason = "Edición manual de stock"
                    )
                )
            }
        }
    }

    suspend fun adjustStock(referenceRaw: String, delta: Int): Boolean = db.withTransaction {
        val reference = normalizeReference(referenceRaw)
        val product = products.byReference(reference) ?: return@withTransaction false
        val newStock = product.stock + delta
        if (newStock < 0) return@withTransaction false
        val changed = products.setStock(reference, newStock)
        if (changed > 0 && delta != 0) {
            movements.insert(
                StockMovementEntity(
                    productReference = reference,
                    delta = delta,
                    stockAfter = newStock,
                    reason = "Ajuste manual"
                )
            )
        }
        changed > 0
    }

    suspend fun createDeliveryNote(draft: ParsedDeliveryNote): Long = db.withTransaction {
        val noteNumber = draft.number.trim()
        require(noteNumber.isNotBlank()) { "El número de albarán está vacío" }
        require(deliveries.getNoteByNumber(noteNumber) == null) { "El albarán $noteNumber ya está registrado" }

        val normalizedLines = draft.lines
            .mapNotNull { line ->
                val ref = normalizeReference(line.reference)
                if (ref.isBlank() || line.quantity <= 0) null else line.copy(reference = ref)
            }
            .groupBy { it.reference }
            .map { (reference, sameReference) ->
                val first = sameReference.first()
                first.copy(
                    reference = reference,
                    description = sameReference.firstNotNullOfOrNull { line ->
                        line.description.trim().takeIf { it.isNotBlank() }
                    } ?: "Producto $reference",
                    quantity = sameReference.sumOf { it.quantity }
                )
            }

        require(normalizedLines.isNotEmpty()) { "El albarán no contiene líneas válidas" }

        val missing = normalizedLines.mapNotNull { parsed ->
            val product = products.byReference(parsed.reference)
            when {
                product == null -> "${parsed.reference}: no existe en Google Sheets/inventario"
                product.ean.isNullOrBlank() -> "${parsed.reference}: no tiene EAN en Google Sheets"
                else -> null
            }
        }
        require(missing.isEmpty()) {
            "No se puede iniciar el picking hasta relacionar CÓDIGO y EAN:\n${missing.joinToString("\n")}"
        }

        val noteId = deliveries.insertNote(
            DeliveryNoteEntity(
                number = noteNumber,
                customer = draft.customer.trim(),
                rawOcrText = draft.rawText
            )
        )

        val entities = normalizedLines.map { parsed ->
            val existing = products.byReference(parsed.reference)
                ?: error("Producto ${parsed.reference} no encontrado")
            DeliveryLineEntity(
                noteId = noteId,
                productReference = parsed.reference,
                description = existing.description.ifBlank { parsed.description.ifBlank { "Producto ${parsed.reference}" } },
                expectedQty = parsed.quantity.coerceAtLeast(1)
            )
        }
        deliveries.insertLines(entities)
        noteId
    }

    suspend fun pickingSnapshot(noteId: Long): PickingSnapshot? {
        val note = deliveries.getNote(noteId) ?: return null
        val lines = deliveries.getLines(noteId)
        val productMap = linkedMapOf<String, ProductEntity>()
        for (line in lines) {
            products.byReference(line.productReference)?.let { productMap[line.productReference] = it }
        }
        return PickingSnapshot(
            note = note,
            lines = lines,
            scanLogs = deliveries.getLogs(noteId),
            transportLabels = deliveries.getTransportLabels(noteId),
            productsByReference = productMap
        )
    }

    suspend fun previewProductScan(noteId: Long, rawBarcode: String): ProductScanPreview = db.withTransaction {
        val barcode = normalizeBarcode(rawBarcode)
        if (barcode.isBlank()) return@withTransaction ProductScanPreview.Rejected("Código de barras vacío")

        val note = deliveries.getNote(noteId)
            ?: return@withTransaction ProductScanPreview.Rejected("No se encuentra el albarán")
        if (note.status == DeliveryNoteEntity.STATUS_COMPLETED) {
            return@withTransaction ProductScanPreview.Rejected("El albarán ya está finalizado")
        }

        val product = products.byEan(barcode)
        if (product == null) {
            val referenceCoincidence = products.byReference(normalizeReference(rawBarcode))
            val message = if (referenceCoincidence != null) {
                "Se ha leído la referencia ${referenceCoincidence.reference}, pero el picking debe hacerse con su EAN ${referenceCoincidence.ean ?: "no configurado"}."
            } else {
                "El EAN $barcode no existe en el inventario sincronizado con Google Sheets."
            }
            deliveries.insertLog(
                ScanLogEntity(noteId = noteId, barcode = barcode, accepted = false, message = message)
            )
            return@withTransaction ProductScanPreview.Rejected(message)
        }

        val line = deliveries.getLineForProduct(noteId, product.reference)
        if (line == null) {
            val message = "${product.reference} (EAN $barcode) no pertenece al albarán ${note.number}"
            deliveries.insertLog(
                ScanLogEntity(
                    noteId = noteId,
                    barcode = barcode,
                    productReference = product.reference,
                    accepted = false,
                    message = message
                )
            )
            return@withTransaction ProductScanPreview.Rejected(message)
        }

        val remaining = line.expectedQty - line.pickedQty
        if (remaining <= 0) {
            val message = "${product.reference} ya está completo (${line.expectedQty}/${line.expectedQty})"
            deliveries.insertLog(
                ScanLogEntity(
                    noteId = noteId,
                    barcode = barcode,
                    productReference = product.reference,
                    accepted = false,
                    message = message
                )
            )
            return@withTransaction ProductScanPreview.Rejected(message)
        }

        ProductScanPreview.Match(
            ProductScanCandidate(
                barcode = barcode,
                reference = product.reference,
                description = product.description,
                ean = product.ean.orEmpty(),
                expectedQty = line.expectedQty,
                pickedQty = line.pickedQty,
                remainingQty = remaining
            )
        )
    }

    suspend fun recordScan(noteId: Long, rawBarcode: String, quantity: Int): ScanResult = db.withTransaction {
        val barcode = normalizeBarcode(rawBarcode)
        if (barcode.isBlank()) return@withTransaction ScanResult.Rejected("Código vacío")
        if (quantity <= 0) return@withTransaction ScanResult.Rejected("La cantidad debe ser mayor que 0")

        val note = deliveries.getNote(noteId)
            ?: return@withTransaction ScanResult.Rejected("No se encuentra el albarán")
        if (note.status == DeliveryNoteEntity.STATUS_COMPLETED) {
            return@withTransaction ScanResult.Rejected("El albarán ya está finalizado")
        }

        val product = products.byEan(barcode)
            ?: return@withTransaction ScanResult.Rejected("El EAN $barcode no existe en Google Sheets/inventario")
        val line = deliveries.getLineForProduct(noteId, product.reference)
            ?: return@withTransaction ScanResult.Rejected("${product.reference} no pertenece al albarán ${note.number}")

        val remaining = line.expectedQty - line.pickedQty
        if (remaining <= 0) {
            return@withTransaction ScanResult.Rejected("${product.reference} ya está completo")
        }
        if (quantity > remaining) {
            return@withTransaction ScanResult.Rejected(
                "Solo quedan $remaining unidades de ${product.reference} por picar"
            )
        }

        val changed = deliveries.incrementPickedBy(line.id, quantity)
        if (changed == 0) {
            return@withTransaction ScanResult.Rejected("No se pudo registrar la cantidad. Actualiza el albarán y vuelve a intentarlo.")
        }

        val newCount = line.pickedQty + quantity
        val message = "${product.reference}: +$quantity · $newCount/${line.expectedQty}"
        deliveries.insertLog(
            ScanLogEntity(
                noteId = noteId,
                barcode = barcode,
                productReference = product.reference,
                accepted = true,
                message = message
            )
        )
        ScanResult.Accepted(message)
    }

    suspend fun recordTransportLabel(noteId: Long, rawBarcode: String): ScanResult = db.withTransaction {
        val barcode = normalizeTransportBarcode(rawBarcode)
        if (barcode.isBlank()) return@withTransaction ScanResult.Rejected("Etiqueta de transporte vacía")

        val note = deliveries.getNote(noteId)
            ?: return@withTransaction ScanResult.Rejected("Albarán no encontrado")
        if (note.status == DeliveryNoteEntity.STATUS_COMPLETED) {
            return@withTransaction ScanResult.Rejected("El albarán ya está finalizado")
        }

        val lines = deliveries.getLines(noteId)
        val pending = lines.any { it.pickedQty != it.expectedQty }
        if (pending) {
            return@withTransaction ScanResult.Rejected("Primero completa el picking de todos los productos")
        }

        val inserted = deliveries.insertTransportLabel(
            TransportLabelEntity(noteId = noteId, barcode = barcode)
        )
        if (inserted == -1L) {
            return@withTransaction ScanResult.Rejected("La etiqueta $barcode ya está registrada en este albarán")
        }

        ScanResult.Accepted("Etiqueta de transporte registrada: $barcode")
    }

    suspend fun removeTransportLabel(noteId: Long, labelId: Long): Boolean = db.withTransaction {
        val note = deliveries.getNote(noteId) ?: return@withTransaction false
        if (note.status == DeliveryNoteEntity.STATUS_COMPLETED) return@withTransaction false
        deliveries.deleteTransportLabel(noteId, labelId) > 0
    }

    suspend fun finalize(noteId: Long): FinalizeResult = db.withTransaction {
        val note = deliveries.getNote(noteId)
            ?: return@withTransaction FinalizeResult.Error("Albarán no encontrado")
        if (note.status == DeliveryNoteEntity.STATUS_COMPLETED) return@withTransaction FinalizeResult.Success

        val lines = deliveries.getLines(noteId)
        if (lines.isEmpty()) return@withTransaction FinalizeResult.Error("El albarán no tiene líneas")

        val incomplete = lines.filter { it.pickedQty != it.expectedQty }
        if (incomplete.isNotEmpty()) {
            val pending = incomplete.joinToString(", ") { "${it.productReference} ${it.pickedQty}/${it.expectedQty}" }
            return@withTransaction FinalizeResult.Error("Faltan unidades por picar: $pending")
        }

        val transportLabels = deliveries.getTransportLabels(noteId)
        if (transportLabels.isEmpty()) {
            return@withTransaction FinalizeResult.Error(
                "Antes de finalizar debes escanear al menos una etiqueta de transporte"
            )
        }

        val stockProblems = mutableListOf<String>()
        val currentProducts = linkedMapOf<String, ProductEntity>()
        for (line in lines) {
            val product = products.byReference(line.productReference)
            if (product == null) {
                stockProblems += "${line.productReference}: producto inexistente"
            } else {
                currentProducts[line.productReference] = product
                if (product.stock < line.expectedQty) {
                    stockProblems += "${line.productReference}: stock ${product.stock}, salida ${line.expectedQty}"
                }
            }
        }

        if (stockProblems.isNotEmpty()) {
            return@withTransaction FinalizeResult.Error(
                "Stock insuficiente. Revisa la sincronización antes de cerrar:\n${stockProblems.joinToString("\n")}"
            )
        }

        for (line in lines) {
            val product = currentProducts.getValue(line.productReference)
            val newStock = product.stock - line.expectedQty
            products.setStock(product.reference, newStock)
            movements.insert(
                StockMovementEntity(
                    productReference = product.reference,
                    delta = -line.expectedQty,
                    stockAfter = newStock,
                    reason = "Salida por albarán",
                    deliveryNoteNumber = note.number
                )
            )
        }

        deliveries.updateStatus(
            id = noteId,
            status = DeliveryNoteEntity.STATUS_COMPLETED,
            completedAt = System.currentTimeMillis()
        )
        FinalizeResult.Success
    }

    /**
     * Importa/sincroniza CÓDIGO, EAN, descripción y stock desde CSV.
     * El stock leído de la hoja se guarda en sheetStock. Las salidas hechas en el SGA se
     * conservan como diferencia local, para que una sincronización no "deshaga" el picking.
     */
    suspend fun importCsv(text: String): CsvImportResult = db.withTransaction {
        val rows = text
            .removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .toList()

        if (rows.isEmpty()) return@withTransaction CsvImportResult(0, 0, listOf("El archivo está vacío"))

        val delimiter = detectDelimiter(rows.first())
        val header = parseCsvRow(rows.first(), delimiter).map(::normalizeHeader)

        val refIndex = findColumn(header, "referencia", "codigo", "articulo", "reference", "sku", "code")
        val eanIndex = findColumn(
            header,
            "ean", "ean13", "ean_13", "barcode", "codigo_barras", "codigo_de_barras", "codigobarras", "gtin", "gtin13", "codigo_ean"
        )
        val descIndex = findColumn(header, "descripcion", "description", "nombre", "producto", "product")
        val stockIndex = findColumn(header, "stock", "existencias", "unidades", "cantidad", "qty", "stock_actual", "disponible")
        val locationIndex = findColumn(header, "ubicacion", "location", "pasillo", "hueco", "almacen")

        if (refIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna de referencia. Debe llamarse CÓDIGO, REFERENCIA o ARTÍCULO.")
            )
        }
        if (eanIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna EAN/código de barras. Sin ella no se puede relacionar el picking con los artículos.")
            )
        }
        if (stockIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna STOCK/EXISTENCIAS. El stock debe proceder de Google Sheets.")
            )
        }

        var imported = 0
        var updated = 0
        val errors = mutableListOf<String>()

        rows.drop(1).forEachIndexed { index, row ->
            val lineNumber = index + 2
            val columns = parseCsvRow(row, delimiter)
            fun valueAt(columnIndex: Int): String = if (columnIndex in columns.indices) columns[columnIndex].trim() else ""

            val reference = normalizeReference(valueAt(refIndex))
            if (reference.isBlank()) return@forEachIndexed

            val ean = normalizeBarcode(valueAt(eanIndex)).takeIf { it.isNotBlank() }
            val existing = products.byReference(reference)

            if (ean == null && existing?.ean.isNullOrBlank()) {
                errors += "Fila $lineNumber ($reference): sin EAN; no podrá picarse hasta configurarlo"
            }

            if (ean != null) {
                val owner = products.byEan(ean)
                if (owner != null && owner.reference != reference) {
                    errors += "Fila $lineNumber ($reference): EAN $ean ya usado por ${owner.reference}"
                    return@forEachIndexed
                }
            }

            val incomingStockRaw = if (stockIndex >= 0) valueAt(stockIndex) else ""
            val incomingSheetStock = if (stockIndex >= 0) parseStock(incomingStockRaw) else null
            if (stockIndex >= 0 && incomingStockRaw.isNotBlank() && incomingSheetStock == null) {
                errors += "Fila $lineNumber ($reference): stock no válido '$incomingStockRaw'"
                return@forEachIndexed
            }
            if (incomingSheetStock != null && incomingSheetStock < 0) {
                errors += "Fila $lineNumber ($reference): stock negativo no permitido"
                return@forEachIndexed
            }

            val localDelta = if (existing != null && existing.sheetStock != null) {
                existing.stock - existing.sheetStock
            } else {
                0
            }
            val calculatedStock = when {
                incomingSheetStock != null -> incomingSheetStock + localDelta
                existing != null -> existing.stock
                else -> 0
            }
            val effectiveStock = calculatedStock.coerceAtLeast(0)
            if (calculatedStock < 0) {
                errors += "Fila $lineNumber ($reference): las salidas locales superan el stock de la hoja; stock SGA ajustado a 0"
            }

            val descriptionFromSheet = if (descIndex >= 0) valueAt(descIndex) else ""
            val locationFromSheet = if (locationIndex >= 0) valueAt(locationIndex) else ""
            val description = descriptionFromSheet.ifBlank { existing?.description ?: "Producto $reference" }
            val location = locationFromSheet.ifBlank { existing?.location.orEmpty() }
            val now = System.currentTimeMillis()

            val product = ProductEntity(
                reference = reference,
                ean = ean ?: existing?.ean,
                description = description,
                stock = effectiveStock,
                location = location,
                active = existing?.active ?: true,
                updatedAt = now,
                sheetStock = incomingSheetStock ?: existing?.sheetStock
            )

            if (existing == null) {
                products.insert(product)
                imported++
                if (effectiveStock != 0) {
                    movements.insert(
                        StockMovementEntity(
                            productReference = reference,
                            delta = effectiveStock,
                            stockAfter = effectiveStock,
                            reason = "Sincronización inicial Google Sheets"
                        )
                    )
                }
            } else {
                products.update(product)
                updated++
                val delta = effectiveStock - existing.stock
                if (delta != 0) {
                    movements.insert(
                        StockMovementEntity(
                            productReference = reference,
                            delta = delta,
                            stockAfter = effectiveStock,
                            reason = "Sincronización Google Sheets"
                        )
                    )
                }
            }
        }

        CsvImportResult(imported, updated, errors)
    }

    companion object {
        fun normalizeReference(value: String): String = value
            .trim()
            .uppercase()
            .replace(Regex("\\s+"), "")

        fun normalizeBarcode(value: String): String {
            var cleaned = value.trim().trim('"', '\'', ' ')
            if (cleaned.isBlank()) return ""

            if (cleaned.contains('E', ignoreCase = true)) {
                val scientificCandidate = cleaned.replace(',', '.')
                if (scientificCandidate.toBigDecimalOrNull() != null) {
                    cleaned = try {
                        BigDecimal(scientificCandidate).toPlainString()
                    } catch (_: NumberFormatException) {
                        cleaned
                    }
                }
            }

            if (cleaned.matches(Regex("\\d+\\.0+"))) {
                cleaned = cleaned.substringBefore('.')
            }

            return cleaned.filter { it.isLetterOrDigit() }.uppercase()
        }

        fun normalizeTransportBarcode(value: String): String = value
            .trim()
            .filterNot(Char::isISOControl)
            .take(500)

        private fun parseStock(value: String): Int? {
            val normalized = value.trim().replace(" ", "").replace(',', '.')
            if (normalized.isBlank()) return null
            return normalized.toDoubleOrNull()?.toInt()
        }

        private fun detectDelimiter(header: String): Char {
            val candidates = listOf(';', '\t', ',')
            return candidates.maxByOrNull { delimiter -> header.count { it == delimiter } } ?: ';'
        }

        private fun findColumn(header: List<String>, vararg names: String): Int {
            val accepted = names.toSet()
            return header.indexOfFirst { it in accepted }
        }

        private fun normalizeHeader(value: String): String {
            val noAccents = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return noAccents
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        }

        private fun parseCsvRow(row: String, delimiter: Char): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var index = 0
            while (index < row.length) {
                val char = row[index]
                when {
                    char == '"' && quoted && index + 1 < row.length && row[index + 1] == '"' -> {
                        current.append('"')
                        index++
                    }
                    char == '"' -> quoted = !quoted
                    char == delimiter && !quoted -> {
                        result += current.toString()
                        current.clear()
                    }
                    else -> current.append(char)
                }
                index++
            }
            result += current.toString()
            return result
        }
    }
}
