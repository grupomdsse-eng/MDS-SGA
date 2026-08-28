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
    val errors: List<String>,
    val rowsRead: Int = 0,
    val referencesRead: Int = 0
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
     *
     * v1.3.1: el CSV se interpreta como documento RFC-4180 completo (incluidos saltos de
     * línea dentro de celdas), se toleran espacios invisibles/BOM en las referencias y una
     * fila ya no se descarta entera por un stock mal formateado. Esto evita que una referencia
     * que sí existe en Google Sheets aparezca como inexistente en el SGA.
     */
    suspend fun importCsv(text: String): CsvImportResult = db.withTransaction {
        val cleanText = text.removePrefix("\uFEFF")
        if (cleanText.isBlank()) {
            return@withTransaction CsvImportResult(0, 0, listOf("El archivo está vacío"))
        }

        val delimiter = detectDelimiter(firstCsvRecord(cleanText))
        val rows = parseCsvDocument(cleanText, delimiter)
            .filter { row -> row.any { it.isNotBlank() } }

        if (rows.isEmpty()) {
            return@withTransaction CsvImportResult(0, 0, listOf("El archivo está vacío"))
        }

        val header = rows.first().map(::normalizeHeader)
        val refIndex = findReferenceColumn(header)
        val eanIndex = findEanColumn(header)
        val descIndex = findColumn(header, "descripcion", "description", "nombre", "producto", "product")
        val stockIndex = findColumn(header, "stock", "existencias", "unidades", "cantidad", "qty", "stock_actual", "disponible")
        val locationIndex = findColumn(header, "ubicacion", "location", "pasillo", "hueco", "almacen")

        if (refIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna de referencia. Debe contener CÓDIGO, REFERENCIA, ARTÍCULO o SKU."),
                rowsRead = (rows.size - 1).coerceAtLeast(0)
            )
        }
        if (eanIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna EAN/código de barras. Sin ella no se puede relacionar el picking con los artículos."),
                rowsRead = (rows.size - 1).coerceAtLeast(0)
            )
        }
        if (stockIndex < 0) {
            return@withTransaction CsvImportResult(
                imported = 0,
                updated = 0,
                errors = listOf("No encuentro la columna STOCK/EXISTENCIAS. El stock debe proceder de Google Sheets."),
                rowsRead = (rows.size - 1).coerceAtLeast(0)
            )
        }

        val dataRows = rows.drop(1)
        fun valueAt(row: List<String>, columnIndex: Int): String =
            if (columnIndex in row.indices) row[columnIndex].trim() else ""

        // Se conoce de antemano qué referencias existen en la hoja. Así, si un EAN pertenecía
        // localmente a una referencia antigua que ya no está en Sheets, puede reasignarse sin
        // descartar el nuevo artículo.
        val sheetReferences = dataRows
            .mapNotNull { row -> normalizeReference(valueAt(row, refIndex)).takeIf(String::isNotBlank) }
            .toSet()

        var imported = 0
        var updated = 0
        val errors = mutableListOf<String>()
        var suppressedErrors = 0
        fun addImportWarning(message: String) {
            if (errors.size < 100) errors += message else suppressedErrors++
        }
        val eanSeenInSheet = mutableMapOf<String, String>()

        dataRows.forEachIndexed { index, row ->
            val lineNumber = index + 2
            val reference = normalizeReference(valueAt(row, refIndex))
            if (reference.isBlank()) return@forEachIndexed

            val existing = products.byReference(reference)
            var ean = normalizeBarcode(valueAt(row, eanIndex)).takeIf { it.isNotBlank() }

            if (ean != null) {
                val previousSheetOwner = eanSeenInSheet[ean]
                if (previousSheetOwner != null && previousSheetOwner != reference) {
                    addImportWarning("Fila $lineNumber ($reference): EAN $ean duplicado también en $previousSheetOwner. Se importa la referencia, pero hay que corregir el EAN en Google Sheets.")
                    ean = null
                } else {
                    eanSeenInSheet[ean] = reference
                }
            }

            if (ean != null) {
                val localOwner = products.byEan(ean)
                if (localOwner != null && localOwner.reference != reference) {
                    if (localOwner.reference !in sheetReferences) {
                        products.clearEan(localOwner.reference)
                        addImportWarning("Fila $lineNumber ($reference): EAN $ean reasignado desde la referencia antigua ${localOwner.reference}.")
                    } else {
                        addImportWarning("Fila $lineNumber ($reference): EAN $ean ya pertenece a ${localOwner.reference}. Se importa la referencia, pero sin cambiar ese EAN hasta corregir la hoja.")
                        ean = null
                    }
                }
            }

            if (ean == null && existing?.ean.isNullOrBlank()) {
                addImportWarning("Fila $lineNumber ($reference): sin EAN válido; la referencia sí se ha importado, pero no podrá picarse hasta tener EAN.")
            }

            val incomingStockRaw = valueAt(row, stockIndex)
            val parsedSheetStock = parseStock(incomingStockRaw)
            val incomingSheetStock = when {
                incomingStockRaw.isBlank() -> existing?.sheetStock
                parsedSheetStock == null -> {
                    addImportWarning("Fila $lineNumber ($reference): stock no válido '$incomingStockRaw'. Se mantiene el stock anterior, pero la referencia sí se importa.")
                    existing?.sheetStock
                }
                parsedSheetStock < 0 -> {
                    addImportWarning("Fila $lineNumber ($reference): stock negativo no permitido. Se mantiene el stock anterior, pero la referencia sí se importa.")
                    existing?.sheetStock
                }
                else -> parsedSheetStock
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
                addImportWarning("Fila $lineNumber ($reference): las salidas locales superan el stock de la hoja; stock SGA ajustado a 0.")
            }

            val descriptionFromSheet = if (descIndex >= 0) valueAt(row, descIndex).take(500) else ""
            val locationFromSheet = if (locationIndex >= 0) valueAt(row, locationIndex).take(120) else ""
            val description = descriptionFromSheet.ifBlank { existing?.description ?: "Producto $reference" }
            val location = locationFromSheet.ifBlank { existing?.location.orEmpty() }
            val now = System.currentTimeMillis()

            val product = ProductEntity(
                reference = reference,
                ean = ean ?: existing?.ean,
                description = description,
                stock = effectiveStock,
                location = location,
                active = true,
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

        if (suppressedErrors > 0) {
            errors += "… $suppressedErrors avisos adicionales omitidos para proteger la memoria."
        }

        CsvImportResult(
            imported = imported,
            updated = updated,
            errors = errors,
            rowsRead = dataRows.size,
            referencesRead = sheetReferences.size
        )
    }

    companion object {
        /**
         * Normalización pensada tanto para Google Sheets como para OCR/entrada manual.
         * Elimina BOM, NBSP, espacios de ancho cero, comillas y signos ajenos al código.
         */
        fun normalizeReference(value: String): String {
            val normalized = Normalizer.normalize(value.take(256), Normalizer.Form.NFKC)
                .trim()
                .trim('"', '\'', '`')
                .uppercase()
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")

            return normalized.filter { char ->
                char.isLetterOrDigit() || char == '-' || char == '_' || char == '/' || char == '.'
            }
        }

        fun normalizeBarcode(value: String): String {
            var cleaned = Normalizer.normalize(value.take(256), Normalizer.Form.NFKC)
                .trim()
                .trim('"', '\'', ' ')
            if (cleaned.isBlank()) return ""

            // Google Sheets puede exportar un EAN numérico en notación científica. Solo
            // expandimos exponentes razonables para impedir que un valor corrupto del tipo
            // 1E+1000000 provoque una asignación de memoria gigantesca.
            val scientific = cleaned.replace(',', '.')
            if (scientific.matches(Regex("[+-]?\\d{1,18}(?:\\.\\d{0,12})?[eE][+-]?\\d{1,3}"))) {
                val exponent = scientific.substringAfterLast('E', scientific.substringAfterLast('e', "0"))
                    .toIntOrNull()
                if (exponent != null && exponent in -30..30) {
                    cleaned = try {
                        BigDecimal(scientific).toPlainString().take(128)
                    } catch (_: NumberFormatException) {
                        cleaned
                    }
                }
            }

            if (cleaned.matches(Regex("\\d+\\.0+"))) {
                cleaned = cleaned.substringBefore('.')
            }

            return cleaned.filter { it.isLetterOrDigit() }.uppercase().take(128)
        }

        fun normalizeTransportBarcode(value: String): String = value
            .take(2_000)
            .trim()
            .filterNot(Char::isISOControl)
            .take(500)

        private fun parseStock(value: String): Int? {
            val normalized = value.take(128)
                .replace("\u00A0", "")
                .replace(" ", "")
                .trim()
                .replace(',', '.')
            if (normalized.isBlank()) return null
            return normalized.toDoubleOrNull()?.toInt()
        }

        private fun firstCsvRecord(text: String): String {
            val result = StringBuilder()
            var quoted = false
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '"') {
                    if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                        result.append("\"\"")
                        index += 2
                        continue
                    }
                    quoted = !quoted
                    result.append(char)
                } else if ((char == '\n' || char == '\r') && !quoted) {
                    break
                } else {
                    result.append(char)
                }
                index++
            }
            return result.toString()
        }

        private fun detectDelimiter(header: String): Char {
            val candidates = listOf(',', ';', '\t')
            return candidates.maxByOrNull { delimiter -> countDelimiterOutsideQuotes(header, delimiter) } ?: ','
        }

        private fun countDelimiterOutsideQuotes(text: String, delimiter: Char): Int {
            var count = 0
            var quoted = false
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '"') {
                    if (quoted && index + 1 < text.length && text[index + 1] == '"') {
                        index += 2
                        continue
                    }
                    quoted = !quoted
                } else if (char == delimiter && !quoted) {
                    count++
                }
                index++
            }
            return count
        }

        private fun findReferenceColumn(header: List<String>): Int {
            val exact = findColumn(header, "referencia", "codigo", "articulo", "reference", "sku", "code")
            if (exact >= 0) return exact

            return header.indexOfFirst { column ->
                when {
                    column.contains("codigo_de_barras") || column.contains("codigo_barras") || column.contains("barcode") || column.contains("ean") || column.contains("gtin") -> false
                    column.contains("referencia") -> true
                    column.contains("articulo") -> true
                    column == "sku" || column.startsWith("sku_") || column.endsWith("_sku") -> true
                    column == "codigo" || column.startsWith("codigo_producto") || column.startsWith("codigo_articulo") -> true
                    else -> false
                }
            }
        }

        private fun findEanColumn(header: List<String>): Int {
            val exact = findColumn(
                header,
                "ean", "ean13", "ean_13", "barcode", "codigo_barras", "codigo_de_barras", "codigobarras", "gtin", "gtin13", "codigo_ean"
            )
            if (exact >= 0) return exact

            return header.indexOfFirst { column ->
                column.contains("ean") || column.contains("gtin") || column.contains("barcode") ||
                    column.contains("codigo_de_barras") || column.contains("codigo_barras")
            }
        }

        private fun findColumn(header: List<String>, vararg names: String): Int {
            val accepted = names.map(::normalizeHeader).toSet()
            val exact = header.indexOfFirst { it in accepted }
            if (exact >= 0) return exact

            return header.indexOfFirst { column ->
                accepted.any { name ->
                    column.startsWith("${name}_") || column.endsWith("_$name") || column.contains("_${name}_")
                }
            }
        }

        private fun normalizeHeader(value: String): String {
            val noAccents = Normalizer.normalize(value.take(256).trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            return noAccents
                .replace("\uFEFF", "")
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
        }

        /** Parser CSV que soporta comas/punto y coma, comillas escapadas y saltos de línea dentro de celdas. */
        private fun parseCsvDocument(text: String, delimiter: Char): List<List<String>> {
            val rows = mutableListOf<MutableList<String>>()
            var row = mutableListOf<String>()
            val cell = StringBuilder()
            var quoted = false
            var index = 0

            fun finishCell() {
                row += cell.toString()
                cell.clear()
            }

            fun finishRow() {
                finishCell()
                rows += row
                row = mutableListOf()
            }

            while (index < text.length) {
                val char = text[index]
                when {
                    char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                        cell.append('"')
                        index++
                    }
                    char == '"' -> quoted = !quoted
                    char == delimiter && !quoted -> finishCell()
                    (char == '\n' || char == '\r') && !quoted -> {
                        if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                        finishRow()
                    }
                    else -> cell.append(char)
                }
                index++
            }

            if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
            return rows
        }
    }
}
