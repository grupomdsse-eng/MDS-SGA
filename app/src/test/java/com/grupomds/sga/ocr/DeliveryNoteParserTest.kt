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
}
