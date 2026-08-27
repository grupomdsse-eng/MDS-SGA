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

    fun parse(
        rawText: String,
        products: List<ProductEntity>,
        tokens: List<OcrToken> = emptyList()
    ): ParsedDeliveryNote {
        val cleanLines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val normalizedLines = cleanLines.map(::normalizeText)
        val noteNumber = extractNoteNumber(cleanLines, normalizedLines)
        val customer = extractCustomer(cleanLines, normalizedLines, noteNumber)

        val parsed = linkedMapOf<String, ParsedLine>()

        // 1) Spatial parsing: uses the actual Artículo/Cantidad columns when ML Kit gave positions.
        if (tokens.isNotEmpty()) {
            parseSpatial(tokens, products).forEach { line ->
                parsed[line.reference] = merge(parsed[line.reference], line)
            }
        }

        // 2) Known product master: very reliable even when OCR line breaks are imperfect.
        val canonicalDocument = canonical(rawText)
        products.forEach { product ->
            val ref = product.reference.trim().uppercase()
            val refCanonical = canonical(ref)
            if (refCanonical.length < 4) return@forEach

            val sourceLine = cleanLines.firstOrNull { canonical(it).contains(refCanonical) }
            val documentContainsReference = canonicalDocument.contains(refCanonical)
            if (sourceLine != null || documentContainsReference) {
                val quantity = sourceLine?.let { extractQuantityAfterReference(it, ref) }
                    ?: findQuantityNearReference(cleanLines, ref)
                    ?: parsed[ref]?.quantity
                    ?: 1
                val detectedDescription = sourceLine?.let { extractDescription(it, ref) }.orEmpty()
                val description = product.description.takeIf { it.isNotBlank() }
                    ?: detectedDescription.takeIf { it.isNotBlank() }
                    ?: "Producto $ref"
                val line = ParsedLine(
                    reference = ref,
                    description = description,
                    quantity = quantity.coerceAtLeast(1),
                    matchedProduct = true
                )
                parsed[ref] = merge(parsed[ref], line)
            }
        }

        // 3) Generic fallback for references not yet present in the product master.
        if (parsed.isEmpty()) {
            genericLines(cleanLines, normalizedLines).forEach { line ->
                parsed[line.reference] = merge(parsed[line.reference], line)
            }
        }

        return ParsedDeliveryNote(
            number = noteNumber,
            customer = customer,
            lines = parsed.values.toList(),
            rawText = rawText
        )
    }

    private fun parseSpatial(tokens: List<OcrToken>, products: List<ProductEntity>): List<ParsedLine> {
        val meaningful = tokens.filter { it.text.isNotBlank() }
        if (meaningful.isEmpty()) return emptyList()

        val articleHeader = meaningful.firstOrNull { normalizeText(it.text) in setOf("ARTICULO", "ARTICULOS") }
            ?: return emptyList()
        val quantityHeader = meaningful.firstOrNull {
            val normalized = normalizeText(it.text)
            normalized == "CANTIDAD" || normalized == "CANT."
        } ?: return emptyList()
        val descriptionHeader = meaningful.firstOrNull { normalizeText(it.text).startsWith("DESCRIPC") }

        val headerY = max(articleHeader.centerY, quantityHeader.centerY)
        val pageWidth = meaningful.maxOfOrNull { it.right }?.coerceAtLeast(1) ?: 1
        val medianHeight = meaningful.map { it.height }.sorted().let { heights -> heights[heights.size / 2] }
        val tolerance = max(12f, medianHeight * 0.9f)

        val rows = groupRows(
            meaningful.filter { it.centerY > headerY + medianHeight * 0.4f },
            tolerance
        )

        val knownByCanonical = products.associateBy { canonical(it.reference) }
        val xRef = articleHeader.centerX
        val xQty = quantityHeader.centerX
        val xDesc = descriptionHeader?.centerX ?: (xRef + xQty) / 2f
        val refColumnRadius = pageWidth * 0.16f
        val qtyColumnRadius = pageWidth * 0.12f

        val results = mutableListOf<ParsedLine>()
        for (row in rows) {
            if (row.any { normalizeText(it.text).startsWith("PEDIDO") }) continue

            val refCandidates = row.filter { abs(it.centerX - xRef) <= refColumnRadius }
            val candidateText = refCandidates.joinToString("") { it.text }.trim()
            val directTokenRef = refCandidates
                .map { it.text.trim().uppercase() }
                .firstOrNull(::looksLikeReference)
            val joinedRef = candidateText.uppercase().takeIf(::looksLikeReference)
            var reference = directTokenRef ?: joinedRef

            if (reference == null && products.isNotEmpty()) {
                reference = refCandidates
                    .asSequence()
                    .map { canonical(it.text) }
                    .flatMap { observed ->
                        knownByCanonical.keys.asSequence().map { known -> Triple(observed, known, editDistanceAtMostOne(observed, known)) }
                    }
                    .firstOrNull { (_, known, close) -> known.length >= 7 && close }
                    ?.second
                    ?.let { known -> knownByCanonical[known]?.reference }
            }

            val resolvedReference = reference?.takeIf { it.isNotBlank() } ?: continue
            val normalizedReference = resolvedReference.trim().uppercase()

            val qtyToken = row
                .filter { abs(it.centerX - xQty) <= qtyColumnRadius }
                .mapNotNull { token -> parseQuantityToken(token.text)?.let { token to it } }
                .minByOrNull { (token, _) -> abs(token.centerX - xQty) }
            val quantity = qtyToken?.second ?: continue

            val knownProduct = products.firstOrNull { canonical(it.reference) == canonical(normalizedReference) }
            val description = knownProduct?.description?.takeIf { it.isNotBlank() }
                ?: row.filter { it.centerX > xDesc - pageWidth * 0.12f && it.centerX < xQty - pageWidth * 0.05f }
                    .sortedBy { it.left }
                    .joinToString(" ") { it.text }
                    .trim()
                    .ifBlank { "Producto $normalizedReference" }

            results += ParsedLine(
                reference = knownProduct?.reference ?: normalizedReference,
                description = description,
                quantity = quantity,
                matchedProduct = knownProduct != null
            )
        }
        return results
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
        val headerIndex = normalized.indexOfFirst { it.contains("ARTICULO") && it.contains("CANTIDAD") }
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

    private fun extractNoteNumber(lines: List<String>, normalized: List<String>): String {
        val keyIndex = normalized.indexOfFirst { it.contains("ALBARAN") }
        if (keyIndex >= 0) {
            numberRegex.find(lines[keyIndex])?.value?.let { return it }
            for (index in keyIndex + 1..minOf(keyIndex + 4, lines.lastIndex)) {
                val candidate = numberRegex.find(lines[index])?.value
                if (candidate != null && candidate.length >= 4) return candidate
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
        val refIndex = canonical(line).indexOf(canonical(reference))
        val textToSearch = if (refIndex >= 0) {
            // Use the original line, but remove the reference text so its digits cannot be mistaken for quantity.
            line.replace(reference, " ", ignoreCase = true)
        } else {
            line
        }

        val matches = quantityRegex.findAll(textToSearch).toList()
        if (matches.isEmpty()) return null

        for (match in matches) {
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
        val cleaned = value.trim().replace(',', '.')
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
