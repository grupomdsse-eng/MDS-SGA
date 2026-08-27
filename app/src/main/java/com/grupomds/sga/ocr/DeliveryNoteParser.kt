package com.grupomds.sga.ocr

import com.grupomds.sga.data.ProductEntity
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A light-weight OCR element so the parser can be unit-tested without Android/ML Kit classes. */
data class OcrToken(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Int get() = max(1, right - left)
    val height: Int get() = max(1, bottom - top)
}

data class ParsedLine(
    val reference: String,
    val description: String,
    val quantity: Int,
    val matchedProduct: Boolean
)

data class ParsedDeliveryNote(
    val number: String,
    val customer: String = "",
    val lines: List<ParsedLine>,
    val rawText: String
)

object DeliveryNoteParser {
    private val referenceRegex = Regex("\\b(?=[A-Z0-9._/-]{4,}\\b)(?=[A-Z0-9._/-]*[A-Z])(?=[A-Z0-9._/-]*\\d)[A-Z0-9][A-Z0-9._/-]{3,}\\b")
    private val numberRegex = Regex("(?<!\\d)\\d{4,10}(?!\\d)")
    private val quantityRegex = Regex("(?<![A-Za-z0-9])(\\d{1,6})(?:[,.](\\d{1,3}))?(?![A-Za-z0-9])")

    private data class SpatialTableResult(
        val articleHeaderFound: Boolean,
        val lines: List<ParsedLine>
    )

    fun parse(
        rawText: String,
        products: List<ProductEntity>,
        tokens: List<OcrToken> = emptyList()
    ): ParsedDeliveryNote {
        val cleanLines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val normalizedLines = cleanLines.map(::normalizeText)

        // Regla principal: el número válido es el que está VISUALMENTE debajo de ALBARÁN.
        // Solo usamos el texto lineal como respaldo cuando ML Kit no entrega coordenadas útiles.
        val noteNumber = extractNoteNumberSpatial(tokens)
            ?: extractNoteNumberFromLines(cleanLines, normalizedLines)

        val customer = extractCustomer(cleanLines, normalizedLines, noteNumber)
        val parsed = linkedMapOf<String, ParsedLine>()

        // Regla principal de artículos: las referencias se leen exclusivamente de la columna
        // situada debajo de ARTÍCULO. No buscamos referencias por toda la hoja cuando hemos
        // localizado esa cabecera, ya que eso puede confundir CIF, pedido, pie de página, etc.
        val spatial = if (tokens.isNotEmpty()) parseSpatialTable(tokens, products) else SpatialTableResult(false, emptyList())
        spatial.lines.forEach { line ->
            parsed[line.reference] = merge(parsed[line.reference], line)
        }

        if (!spatial.articleHeaderFound) {
            // Respaldo para fotos en las que OCR no haya reconocido la palabra ARTÍCULO.
            // Primero cruzamos con el maestro de productos y solo después usamos un parser genérico.
            val canonicalDocument = canonical(rawText)
            products.forEach { product ->
                val ref = product.reference.trim().uppercase()
                val refCanonical = canonical(ref)
                if (refCanonical.length < 4) return@forEach

                val sourceLine = cleanLines.firstOrNull { canonical(it).contains(refCanonical) }
                if (sourceLine != null || canonicalDocument.contains(refCanonical)) {
                    val quantity = sourceLine?.let { extractQuantityAfterReference(it, ref) }
                        ?: findQuantityNearReference(cleanLines, ref)
                        ?: 1
                    val detectedDescription = sourceLine?.let { extractDescription(it, ref) }.orEmpty()
                    val description = product.description.takeIf { it.isNotBlank() }
                        ?: detectedDescription.takeIf { it.isNotBlank() }
                        ?: "Producto $ref"
                    parsed[ref] = merge(
                        parsed[ref],
                        ParsedLine(
                            reference = ref,
                            description = description,
                            quantity = quantity.coerceAtLeast(1),
                            matchedProduct = true
                        )
                    )
                }
            }

            if (parsed.isEmpty()) {
                genericLines(cleanLines, normalizedLines).forEach { line ->
                    parsed[line.reference] = merge(parsed[line.reference], line)
                }
            }
        }

        return ParsedDeliveryNote(
            number = noteNumber,
            customer = customer,
            lines = parsed.values.toList(),
            rawText = rawText
        )
    }

    /**
     * Detecta el número justo debajo de la cabecera ALBARÁN usando las coordenadas del OCR.
     * Se prioriza proximidad vertical y alineación horizontal con la propia palabra ALBARÁN.
     */
    private fun extractNoteNumberSpatial(tokens: List<OcrToken>): String? {
        val meaningful = tokens.filter { it.text.isNotBlank() }
        if (meaningful.isEmpty()) return null

        val header = findHeaderToken(meaningful, listOf("ALBARAN")) ?: return null
        val pageWidth = meaningful.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val pageHeight = meaningful.maxOfOrNull { it.bottom }?.coerceAtLeast(1) ?: 1
        val maxVerticalDistance = max(pageHeight * 0.13f, header.height * 8f)
        val maxHorizontalDistance = max(pageWidth * 0.14f, header.width * 1.6f)

        val direct = meaningful
            .asSequence()
            .filter { it.centerY > header.centerY }
            .mapNotNull { token ->
                val number = numberRegex.find(token.text)?.value ?: return@mapNotNull null
                val dy = token.centerY - header.centerY
                val dx = abs(token.centerX - header.centerX)
                if (dy <= 0f || dy > maxVerticalDistance || dx > maxHorizontalDistance) return@mapNotNull null
                Triple(number, dy, dx)
            }
            .minByOrNull { (_, dy, dx) -> dy + (dx * 0.65f) }
            ?.first
        if (direct != null) return direct

        // Respaldo para un número que OCR haya partido en varios elementos (p. ej. "300" + "712").
        val rowTolerance = max(10f, header.height * 0.8f)
        return groupRows(meaningful.filter { it.centerY > header.centerY }, rowTolerance)
            .asSequence()
            .mapNotNull { row ->
                val aligned = row.filter { abs(it.centerX - header.centerX) <= maxHorizontalDistance }
                    .sortedBy { it.left }
                if (aligned.isEmpty()) return@mapNotNull null
                val joinedDigits = aligned.joinToString("") { token -> token.text.filter(Char::isDigit) }
                val number = numberRegex.find(joinedDigits)?.value ?: return@mapNotNull null
                val rowCenterX = aligned.map { it.centerX }.average().toFloat()
                val rowCenterY = aligned.map { it.centerY }.average().toFloat()
                val dy = rowCenterY - header.centerY
                val dx = abs(rowCenterX - header.centerX)
                if (dy <= 0f || dy > maxVerticalDistance) return@mapNotNull null
                Triple(number, dy, dx)
            }
            .minByOrNull { (_, dy, dx) -> dy + (dx * 0.65f) }
            ?.first
    }

    private fun parseSpatialTable(tokens: List<OcrToken>, products: List<ProductEntity>): SpatialTableResult {
        val meaningful = tokens.filter { it.text.isNotBlank() }
        if (meaningful.isEmpty()) return SpatialTableResult(false, emptyList())

        val articleHeader = findHeaderToken(meaningful, listOf("ARTICULO", "ARTICULOS"))
            ?: return SpatialTableResult(false, emptyList())

        val quantityHeader = findHeaderToken(meaningful, listOf("CANTIDAD", "CANT", "CANT."))
        if (quantityHeader == null) {
            // ARTÍCULO sí se ha encontrado: no buscamos referencias fuera de su columna.
            return SpatialTableResult(true, emptyList())
        }

        val descriptionHeader = findHeaderToken(meaningful, listOf("DESCRIPCION", "DESCRIPC"))
        val priceHeader = findHeaderToken(meaningful, listOf("PRECIO", "PRECIOUNIDAD", "P.UNIDAD"))
        val pageWidth = meaningful.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val medianHeight = meaningful.map { it.height }.sorted().let { heights -> heights[heights.size / 2] }
        val tolerance = max(11f, medianHeight * 0.9f)
        val headerBottom = max(articleHeader.bottom, quantityHeader.bottom)

        val rows = groupRows(
            meaningful.filter { it.centerY > headerBottom + medianHeight * 0.15f },
            tolerance
        )

        val xArticle = articleHeader.centerX
        val xDescription = descriptionHeader?.centerX
        val xQuantity = quantityHeader.centerX
        val xPrice = priceHeader?.centerX

        // Límites geométricos de las columnas. Así una referencia solo puede salir de ARTÍCULO.
        val articleLeft = max(0f, articleHeader.left - pageWidth * 0.035f)
        val articleRight = when {
            xDescription != null && xDescription > xArticle -> (xArticle + xDescription) / 2f
            else -> articleHeader.right + pageWidth * 0.10f
        }
        val quantityLeft = when {
            xDescription != null && xDescription < xQuantity -> (xDescription + xQuantity) / 2f
            else -> xQuantity - pageWidth * 0.08f
        }
        val quantityRight = when {
            xPrice != null && xPrice > xQuantity -> (xQuantity + xPrice) / 2f
            else -> xQuantity + pageWidth * 0.08f
        }

        val knownByCanonical = products.associateBy { canonical(it.reference) }
        val results = mutableListOf<ParsedLine>()

        for (row in rows) {
            if (row.any { normalizeText(it.text).startsWith("PEDIDO") }) continue

            val articleTokens = row
                // Usamos solape con la columna, no solo el centro del token. Así una referencia
                // larga sigue entrando aunque visualmente invada parte del espacio de descripción.
                .filter { it.right >= articleLeft && it.left <= articleRight }
                .sortedBy { it.left }
            if (articleTokens.isEmpty()) continue

            val reference = resolveReferenceFromArticleColumn(articleTokens, knownByCanonical) ?: continue
            val knownProduct = knownByCanonical[canonical(reference)]

            val rowCenter = row.map { it.centerY }.average().toFloat()
            val quantity = findQuantityForRow(
                allTokens = meaningful,
                row = row,
                rowCenter = rowCenter,
                quantityLeft = quantityLeft,
                quantityRight = quantityRight,
                tolerance = tolerance
            ) ?: continue

            val description = knownProduct?.description?.takeIf { it.isNotBlank() }
                ?: extractDescriptionFromSpatialRow(
                    row = row,
                    descriptionHeader = descriptionHeader,
                    articleRight = articleRight,
                    quantityLeft = quantityLeft
                ).ifBlank { "Producto $reference" }

            results += ParsedLine(
                reference = knownProduct?.reference ?: reference.trim().uppercase(),
                description = description,
                quantity = quantity,
                matchedProduct = knownProduct != null
            )
        }

        return SpatialTableResult(true, results)
    }

    private fun resolveReferenceFromArticleColumn(
        articleTokens: List<OcrToken>,
        knownByCanonical: Map<String, ProductEntity>
    ): String? {
        val direct = articleTokens
            .asSequence()
            .map { it.text.trim().uppercase() }
            .firstOrNull(::looksLikeReference)
        if (direct != null) {
            val known = knownByCanonical[canonical(direct)]
            if (known != null) return known.reference
            return direct
        }

        val joined = articleTokens.joinToString("") { it.text }.trim().uppercase()
        if (looksLikeReference(joined)) {
            val known = knownByCanonical[canonical(joined)]
            if (known != null) return known.reference
            return joined
        }

        if (knownByCanonical.isEmpty()) return null
        val observedCandidates = buildList {
            articleTokens.forEach { token ->
                canonical(token.text).takeIf { it.length >= 4 }?.let(::add)
            }
            canonical(joined).takeIf { it.length >= 4 }?.let(::add)
        }.distinct()

        for (observed in observedCandidates) {
            val exact = knownByCanonical[observed]
            if (exact != null) return exact.reference
            val near = knownByCanonical.entries.firstOrNull { (known, _) ->
                known.length >= 6 && editDistanceAtMostOne(observed, known)
            }
            if (near != null) return near.value.reference
        }
        return null
    }

    private fun findQuantityForRow(
        allTokens: List<OcrToken>,
        row: List<OcrToken>,
        rowCenter: Float,
        quantityLeft: Float,
        quantityRight: Float,
        tolerance: Float
    ): Int? {
        val sameRow = row
            .filter { it.centerX in quantityLeft..quantityRight }
            .mapNotNull { token -> parseQuantityToken(token.text)?.let { token to it } }
            .minByOrNull { (token, _) -> abs(token.centerY - rowCenter) }
            ?.second
        if (sameRow != null) return sameRow

        // ML Kit puede colocar el 4,00 en una línea OCR distinta aunque visualmente esté a la misma altura.
        return allTokens
            .asSequence()
            .filter { it.centerX in quantityLeft..quantityRight }
            .filter { abs(it.centerY - rowCenter) <= tolerance * 1.55f }
            .mapNotNull { token -> parseQuantityToken(token.text)?.let { token to it } }
            .minByOrNull { (token, _) -> abs(token.centerY - rowCenter) }
            ?.second
    }

    private fun extractDescriptionFromSpatialRow(
        row: List<OcrToken>,
        descriptionHeader: OcrToken?,
        articleRight: Float,
        quantityLeft: Float
    ): String {
        val left = descriptionHeader?.left?.toFloat()?.minus(descriptionHeader.width * 0.6f)
            ?: articleRight
        return row
            .filter { it.centerX > left && it.centerX < quantityLeft }
            .sortedBy { it.left }
            .joinToString(" ") { it.text }
            .trim()
    }

    private fun findHeaderToken(tokens: List<OcrToken>, targets: List<String>): OcrToken? {
        val targetSet = targets.map(::canonical).filter { it.isNotBlank() }.toSet()
        tokens.firstOrNull { token ->
            val value = canonical(token.text)
            value in targetSet || targetSet.any { target ->
                value.length >= 4 && target.length >= 4 && editDistanceAtMostOne(value, target)
            }
        }?.let { return it }

        // Respaldo si OCR separa una cabecera en dos elementos consecutivos.
        val rows = groupRows(tokens, max(10f, tokens.map { it.height }.sorted()[tokens.size / 2] * 0.8f))
        for (row in rows) {
            val sorted = row.sortedBy { it.left }
            for (index in sorted.indices) {
                for (count in 2..minOf(3, sorted.size - index)) {
                    val chunk = sorted.subList(index, index + count)
                    val joined = canonical(chunk.joinToString("") { it.text })
                    if (joined in targetSet) {
                        return OcrToken(
                            text = chunk.joinToString(" ") { it.text },
                            left = chunk.minOf { it.left },
                            top = chunk.minOf { it.top },
                            right = chunk.maxOf { it.right },
                            bottom = chunk.maxOf { it.bottom }
                        )
                    }
                }
            }
        }
        return null
    }

    private fun groupRows(tokens: List<OcrToken>, tolerance: Float): List<List<OcrToken>> {
        if (tokens.isEmpty()) return emptyList()
        val sorted = tokens.sortedWith(compareBy<OcrToken> { it.centerY }.thenBy { it.left })
        val rows = mutableListOf<MutableList<OcrToken>>()
        val rowCenters = mutableListOf<Float>()

        for (token in sorted) {
            val rowIndex = rowCenters.indices.minByOrNull { abs(rowCenters[it] - token.centerY) }
            if (rowIndex != null && abs(rowCenters[rowIndex] - token.centerY) <= tolerance) {
                rows[rowIndex] += token
                rowCenters[rowIndex] = rows[rowIndex].map { it.centerY }.average().toFloat()
            } else {
                rows += mutableListOf(token)
                rowCenters += token.centerY
            }
        }
        return rows.map { it.sortedBy(OcrToken::left) }
    }

    private fun genericLines(lines: List<String>, normalized: List<String>): List<ParsedLine> {
        val headerIndex = normalized.indexOfFirst { it.contains("ARTICULO") }
        val candidates = if (headerIndex >= 0) lines.drop(headerIndex + 1).take(30) else lines
        val results = mutableListOf<ParsedLine>()

        for (line in candidates) {
            val upper = normalizeText(line)
            if (upper.startsWith("PEDIDO") || upper.contains("FORMA DE PAGO") || upper.contains("TOTAL BULTOS")) continue
            val match = referenceRegex.find(upper) ?: continue
            val reference = match.value.uppercase()
            if (!looksLikeReference(reference)) continue
            val quantity = extractQuantityAfterReference(line, reference) ?: continue
            results += ParsedLine(
                reference = reference,
                description = extractDescription(line, reference),
                quantity = quantity,
                matchedProduct = false
            )
        }
        return results
    }

    private fun extractNoteNumberFromLines(lines: List<String>, normalized: List<String>): String {
        val keyIndex = normalized.indexOfFirst { line ->
            line == "ALBARAN" || line.startsWith("ALBARAN ") || line.startsWith("ALBARAN:")
        }
        if (keyIndex >= 0) {
            val sameLine = normalized[keyIndex].removePrefix("ALBARAN").trim(' ', ':', '#', '-')
            numberRegex.find(sameLine)?.value?.let { return it }

            // El diseño del albarán coloca el número inmediatamente debajo. Limitamos el
            // respaldo a las dos siguientes líneas para no capturar nº cliente, CIF, pedido, etc.
            var checked = 0
            var index = keyIndex + 1
            while (index <= lines.lastIndex && checked < 2) {
                val candidateLine = lines[index].trim()
                if (candidateLine.isNotBlank()) {
                    checked++
                    val candidate = numberRegex.find(candidateLine)?.value
                    if (candidate != null) return candidate
                }
                index++
            }
        }

        return Regex("ALBAR[AÁ]N\\s*[:#-]?\\s*(\\d{4,10})", RegexOption.IGNORE_CASE)
            .find(lines.joinToString(" "))
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun extractCustomer(lines: List<String>, normalized: List<String>, noteNumber: String): String {
        val albaranIndex = normalized.indexOfFirst { it.contains("ALBARAN") }
        if (albaranIndex < 0) return ""

        val numberIndex = lines.indexOfFirst { noteNumber.isNotBlank() && it.contains(noteNumber) }
        val start = if (numberIndex >= 0) numberIndex + 1 else albaranIndex + 1
        for (index in start..minOf(start + 4, lines.lastIndex)) {
            val value = lines[index].trim()
            val norm = normalizeText(value)
            if (value.isBlank()) continue
            if (norm.contains("DIRECCION") || norm.contains("METATRAFIC") || norm.startsWith("FECHA")) continue
            if (numberRegex.matches(value)) continue
            if (value.any(Char::isLetter) && value.length >= 3) return value
        }
        return ""
    }

    private fun extractQuantityAfterReference(line: String, reference: String): Int? {
        val textToSearch = if (reference.isNotBlank() && canonical(line).contains(canonical(reference))) {
            line.replace(reference, " ", ignoreCase = true)
        } else {
            line
        }

        val matches = quantityRegex.findAll(textToSearch).toList()
        if (matches.isEmpty()) return null

        // Preferimos el último valor numérico de la línea. En los albaranes la cantidad está a
        // la derecha de descripción; esto evita confundir dimensiones como 52x11 con la cantidad.
        for (match in matches.asReversed()) {
            val before = textToSearch.getOrNull(match.range.first - 1)
            val after = textToSearch.getOrNull(match.range.last + 1)
            if (before == 'x' || before == 'X' || after == 'x' || after == 'X') continue
            parseQuantityToken(match.value)?.let { return it }
        }
        return null
    }

    private fun findQuantityNearReference(lines: List<String>, reference: String): Int? {
        val index = lines.indexOfFirst { canonical(it).contains(canonical(reference)) }
        if (index < 0) return null
        for (lineIndex in index..minOf(index + 2, lines.lastIndex)) {
            val line = lines[lineIndex]
            if (lineIndex > index && normalizeText(line).startsWith("PEDIDO")) continue
            extractQuantityAfterReference(line, if (lineIndex == index) reference else "")?.let { return it }
        }
        return null
    }

    private fun parseQuantityToken(value: String): Int? {
        val match = quantityRegex.find(value.trim()) ?: return null
        val cleaned = match.value.trim().replace(',', '.')
        val number = cleaned.toDoubleOrNull() ?: return null
        if (number <= 0.0 || number > 100_000.0) return null
        return number.roundToInt().coerceAtLeast(1)
    }

    private fun extractDescription(line: String, reference: String): String {
        var cleaned = line.replace(reference, " ", ignoreCase = true)
        cleaned = cleaned.replace(Regex("(?<![A-Za-z0-9])\\d{1,6}(?:[,.]\\d{1,3})?(?![A-Za-z0-9])\\s*$"), " ")
        return cleaned.trim(' ', '-', ':').ifBlank { "Producto $reference" }
    }

    private fun looksLikeReference(value: String): Boolean {
        val compact = value.trim().uppercase()
        if (compact.length !in 4..40) return false
        if (!compact.any(Char::isLetter) || !compact.any(Char::isDigit)) return false
        if (compact.startsWith("B") && compact.length in 8..10 && compact.drop(1).all(Char::isDigit)) return false
        return compact.matches(Regex("[A-Z0-9][A-Z0-9._/-]+"))
    }

    private fun merge(old: ParsedLine?, new: ParsedLine): ParsedLine {
        if (old == null) return new
        return ParsedLine(
            reference = if (new.matchedProduct) new.reference else old.reference,
            description = when {
                new.matchedProduct && new.description.isNotBlank() -> new.description
                old.description.isNotBlank() -> old.description
                else -> new.description
            },
            quantity = max(old.quantity, new.quantity),
            matchedProduct = old.matchedProduct || new.matchedProduct
        )
    }

    private fun normalizeText(value: String): String {
        val noAccents = Normalizer.normalize(value.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .replace(Regex("[^A-Z0-9,.:/()_\\- ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun canonical(value: String): String = normalizeText(value)
        .filter { it.isLetterOrDigit() }

    /** True only for exact match or a single OCR substitution/insertion/deletion. */
    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (abs(a.length - b.length) > 1) return false
        if (a.isBlank() || b.isBlank()) return false

        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    a.length > b.length -> i++
                    b.length > a.length -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }
}
