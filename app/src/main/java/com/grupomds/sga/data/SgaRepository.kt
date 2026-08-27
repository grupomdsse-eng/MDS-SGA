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
            updatedAt = now
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

        val noteId = deliveries.insertNote(
            DeliveryNoteEntity(
                number = noteNumber,
                customer = draft.customer.trim(),
                rawOcrText = draft.rawText
            )
        )

        val entities = normalizedLines.map { parsed ->
            val existing = products.byReference(parsed.reference)
            if (existing == null) {
                products.insert(
                    ProductEntity(
                        reference = parsed.reference,
                        description = parsed.description.ifBlank { "Producto ${parsed.reference}" },
                        stock = 0
                    )
                )
            }
            DeliveryLineEntity(
                noteId = noteId,
                productReference = parsed.reference,
                description = existing?.description?.ifBlank { parsed.description } ?: parsed.description,
                expectedQty = parsed.quantity.coerceAtLeast(1)
            )
        }
        deliveries.insertLines(entities)
        noteId
    }

    suspend fun pickingSnapshot(noteId: Long): PickingSnapshot? {
        val note = deliveries.getNote(noteId) ?: return null
        return PickingSnapshot(
            note = note,
            lines = deliveries.getLines(noteId),
            scanLogs = deliveries.getLogs(noteId)
        )
    }

    suspend fun recordScan(noteId: Long, rawBarcode: String): ScanResult = db.withTransaction {
        val barcode = normalizeBarcode(rawBarcode)
        if (barcode.isBlank()) return@withTransaction ScanResult.Rejected("Código vacío")

        val note = deliveries.getNote(noteId)
            ?: return@withTransaction ScanResult.Rejected("No se encuentra el albarán")
        if (note.status == DeliveryNoteEntity.STATUS_COMPLETED) {
            return@withTransaction ScanResult.Rejected("El albarán ya está finalizado")
        }

        var product = products.byEan(barcode)
        if (product == null) {
            product = products.byReference(normalizeReference(rawBarcode))
        }

        if (product == null) {
            val pendingLines = deliveries.getLines(noteId).filter { it.pickedQty < it.expectedQty }
            val missingEanProducts = pendingLines.mapNotNull { line ->
                products.byReference(line.productReference)?.takeIf { it.ean.isNullOrBlank() }
            }.distinctBy { it.reference }

            if (missingEanProducts.size == 1) {
                val target = missingEanProducts.single()
                val owner = products.byEan(barcode)
                if (owner == null) {
                    products.assignEan(target.reference, barcode)
                    product = target.copy(ean = barcode)
                }
            }
        }

        if (product == null) {
            val message = "El código $barcode no existe en el inventario"
            deliveries.insertLog(
                ScanLogEntity(
                    noteId = noteId,
                    barcode = barcode,
                    accepted = false,
                    message = message
                )
            )
            return@withTransaction ScanResult.Rejected(message)
        }

        val line = deliveries.getLineForProduct(noteId, product.reference)
        if (line == null) {
            val message = "${product.reference} no pertenece al albarán ${note.number}"
            deliveries.insertLog(
                ScanLogEntity(
                    noteId = noteId,
                    barcode = barcode,
                    productReference = product.reference,
                    accepted = false,
                    message = message
                )
            )
            return@withTransaction ScanResult.Rejected(message)
        }

        if (line.pickedQty >= line.expectedQty) {
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
            return@withTransaction ScanResult.Rejected(message)
        }

        val changed = deliveries.incrementPicked(line.id)
        if (changed == 0) {
            val message = "No se pudo registrar la unidad. Vuelve a escanear."
            return@withTransaction ScanResult.Rejected(message)
        }

        val newCount = line.pickedQty + 1
        val message = "${product.reference}: $newCount/${line.expectedQty}"
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
                "Stock insuficiente. Ajusta el inventario antes de cerrar:\n${stockProblems.joinToString("\n")}"
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
        val eanIndex = findColumn(header, "ean", "barcode", "codigo_barras", "codigo_de_barras", "codigobarras", "gtin")
        val descIndex = findColumn(header, "descripcion", "description", "nombre", "producto", "product")
        val stockIndex = findColumn(header, "stock", "existencias", "unidades", "cantidad", "qty")
        val locationIndex = findColumn(header, "ubicacion", "location", "pasillo", "hueco", "almacen")

        if (refIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna de referencia. Usa REFERENCIA o CÓDIGO.")
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
            if (reference.isBlank()) {
                errors += "Fila $lineNumber: referencia vacía"
                return@forEachIndexed
            }

            val ean = normalizeBarcode(valueAt(eanIndex)).takeIf { it.isNotBlank() }
            val description = valueAt(descIndex).ifBlank { "Producto $reference" }
            val location = valueAt(locationIndex)
            val existing = products.byReference(reference)
            val stock = if (stockIndex >= 0) parseStock(valueAt(stockIndex)) ?: existing?.stock ?: 0 else existing?.stock ?: 0

            if (stock < 0) {
                errors += "Fila $lineNumber ($reference): stock negativo no permitido"
                return@forEachIndexed
            }

            if (ean != null) {
                val owner = products.byEan(ean)
                if (owner != null && owner.reference != reference) {
                    errors += "Fila $lineNumber ($reference): EAN $ean ya usado por ${owner.reference}"
                    return@forEachIndexed
                }
            }

            val now = System.currentTimeMillis()
            val product = ProductEntity(
                reference = reference,
                ean = ean ?: existing?.ean,
                description = description,
                stock = stock,
                location = location.ifBlank { existing?.location.orEmpty() },
                active = existing?.active ?: true,
                updatedAt = now
            )

            if (existing == null) {
                products.insert(product)
                imported++
                if (stock != 0) {
                    movements.insert(
                        StockMovementEntity(
                            productReference = reference,
                            delta = stock,
                            stockAfter = stock,
                            reason = "Importación inicial"
                        )
                    )
                }
            } else {
                products.update(product)
                updated++
                val delta = stock - existing.stock
                if (stockIndex >= 0 && delta != 0) {
                    movements.insert(
                        StockMovementEntity(
                            productReference = reference,
                            delta = delta,
                            stockAfter = stock,
                            reason = "Actualización por importación"
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
