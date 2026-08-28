package com.grupomds.sga.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SgaRepositoryNormalizationTest {
    @Test
    fun normalizesBarcodeWithoutLosingLeadingZeros() {
        assertEquals("08430000000001", SgaRepository.normalizeBarcode(" 08430000000001 "))
    }

    @Test
    fun normalizesExcelScientificNotation() {
        assertEquals("8430000000000", SgaRepository.normalizeBarcode("8,43E+12"))
    }

    @Test
    fun doesNotExpandAbsurdScientificExponent() {
        assertEquals("1E999", SgaRepository.normalizeBarcode("1E+999"))
    }

    @Test
    fun capsPathologicalBarcodeLength() {
        val normalized = SgaRepository.normalizeBarcode("9".repeat(10_000))
        assertEquals(128, normalized.length)
    }

    @Test
    fun normalizesReferenceForMatching() {
        assertEquals("MTP11301N", SgaRepository.normalizeReference(" mtp11301n "))
    }

    @Test
    fun normalizesPrmt1vmpEvenWithInvisibleSheetCharacters() {
        assertEquals("PRMT1VMP", SgaRepository.normalizeReference("\uFEFF PRMT1VMP\u00A0"))
        assertEquals("PRMT1VMP", SgaRepository.normalizeReference("PRMT\u200B1VMP"))
        assertEquals("PRMT1VMP", SgaRepository.normalizeReference("'PRMT1VMP'"))
    }

    @Test
    fun normalizesTransportBarcodeWithoutDestroyingTrackingSeparators() {
        assertEquals("1z-abc/123.45", SgaRepository.normalizeTransportBarcode(" 1z-abc/123.45 "))
    }
}
