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
    fun normalizesReferenceForMatching() {
        assertEquals("MTP11301N", SgaRepository.normalizeReference(" mtp11301n "))
    }

    @Test
    fun normalizesTransportBarcodeWithoutDestroyingTrackingSeparators() {
        assertEquals("1z-abc/123.45", SgaRepository.normalizeTransportBarcode(" 1z-abc/123.45 "))
    }
}
