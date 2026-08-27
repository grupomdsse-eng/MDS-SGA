package com.grupomds.sga.ocr

import com.grupomds.sga.data.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryNoteParserTest {
    private val sampleText = """
        METATRAFIC.SLU
        ALBARAN
        300712
        ALCALA AUTOCASION SL
        DIRECCION DE ENVIO
        Fecha: 19/08/2026 Nº Cliente: 274 Cif: B91255679
        ARTÍCULO DESCRIPCIÓN LOTE CANTIDAD PRECIO UNIDAD SUBTOTAL DTO. TOTAL
        Pedido: 3-000550
        MTP11301N Matricula Acrilica (52x11) 4,00
    """.trimIndent()

    @Test
    fun parsesProvidedDeliveryNoteWithKnownProduct() {
        val products = listOf(
            ProductEntity(
                reference = "MTP11301N",
                ean = "8430000000001",
                description = "Matricula Acrilica (52x11)",
                stock = 100
            )
        )

        val result = DeliveryNoteParser.parse(sampleText, products)

        assertEquals("300712", result.number)
        assertEquals("ALCALA AUTOCASION SL", result.customer)
        assertEquals(1, result.lines.size)
        assertEquals("MTP11301N", result.lines.single().reference)
        assertEquals("Matricula Acrilica (52x11)", result.lines.single().description)
        assertEquals(4, result.lines.single().quantity)
        assertTrue(result.lines.single().matchedProduct)
    }

    @Test
    fun parsesProvidedDeliveryNoteWithoutProductMaster() {
        val result = DeliveryNoteParser.parse(sampleText, emptyList())

        assertEquals("300712", result.number)
        assertEquals("MTP11301N", result.lines.single().reference)
        assertEquals(4, result.lines.single().quantity)
        assertFalse(result.lines.single().matchedProduct)
    }

    @Test
    fun dimensionsAreNotMistakenForQuantity() {
        val text = """
            ALBARAN 999001
            ARTÍCULO DESCRIPCIÓN CANTIDAD
            ABC12345 Placa 52x11 reforzada 7,00
        """.trimIndent()

        val result = DeliveryNoteParser.parse(text, emptyList())
        assertEquals(7, result.lines.single().quantity)
    }

    @Test
    fun spatialParserUsesArticleAndQuantityColumns() {
        val products = listOf(
            ProductEntity(
                reference = "MTP11301N",
                ean = "8430000000001",
                description = "Matricula Acrilica (52x11)",
                stock = 100
            )
        )
        val tokens = listOf(
            OcrToken("ARTÍCULO", 70, 100, 150, 125),
            OcrToken("DESCRIPCIÓN", 200, 100, 330, 125),
            OcrToken("CANTIDAD", 560, 100, 650, 125),
            OcrToken("MTP11301N", 70, 155, 160, 180),
            OcrToken("Matricula", 205, 155, 280, 180),
            OcrToken("Acrilica", 285, 155, 345, 180),
            OcrToken("4,00", 580, 155, 625, 180)
        )

        val result = DeliveryNoteParser.parse(
            rawText = "ALBARAN\n300712\nMTP11301N\n4,00",
            products = products,
            tokens = tokens
        )

        assertEquals("MTP11301N", result.lines.single().reference)
        assertEquals(4, result.lines.single().quantity)
        assertTrue(result.lines.single().matchedProduct)
    }


    @Test
    fun spatialNoteNumberUsesOnlyNumberDirectlyBelowAlbaran() {
        val tokens = listOf(
            OcrToken("ALBARÁN", 620, 80, 760, 115),
            OcrToken("300712", 630, 125, 745, 160),
            OcrToken("274", 350, 455, 390, 480),
            OcrToken("B91255679", 450, 455, 560, 480),
            OcrToken("3-000550", 260, 525, 350, 550)
        )

        val result = DeliveryNoteParser.parse(
            rawText = "ALBARÁN\n274\nB91255679\n300712",
            products = emptyList(),
            tokens = tokens
        )

        assertEquals("300712", result.number)
    }

    @Test
    fun spatialReferencesAreTakenOnlyFromArticleColumn() {
        val products = listOf(
            ProductEntity(
                reference = "MTP11301N",
                ean = "8430000000001",
                description = "Matricula Acrilica (52x11)",
                stock = 100
            )
        )
        val tokens = listOf(
            OcrToken("ARTÍCULO", 50, 500, 145, 525),
            OcrToken("DESCRIPCIÓN", 205, 500, 340, 525),
            OcrToken("CANTIDAD", 480, 500, 575, 525),
            OcrToken("PRECIO", 610, 500, 680, 525),
            // Distractor outside the ARTÍCULO column.
            OcrToken("ZZZ99999", 300, 545, 390, 570),
            OcrToken("MTP11301N", 50, 590, 155, 615),
            OcrToken("Matricula", 205, 590, 285, 615),
            OcrToken("Acrilica", 290, 590, 350, 615),
            OcrToken("4,00", 500, 590, 545, 615)
        )

        val result = DeliveryNoteParser.parse(
            rawText = "ARTÍCULO DESCRIPCIÓN CANTIDAD\nZZZ99999\nMTP11301N Matricula Acrilica 4,00",
            products = products,
            tokens = tokens
        )

        assertEquals(1, result.lines.size)
        assertEquals("MTP11301N", result.lines.single().reference)
        assertEquals(4, result.lines.single().quantity)
    }

}
