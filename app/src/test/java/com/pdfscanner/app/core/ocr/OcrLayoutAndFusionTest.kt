package com.pdfscanner.app.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLayoutAndFusionTest {

    @Test
    fun tableExtractorFixesColumnScrambledOrderToRowByRow() {
        // Words intentionally supplied column-1-first, then column-2
        val scrambled = listOf(
            OcrWord("Name", OcrBox(20, 20, 90, 45), 0.9f),
            OcrWord("Abebe", OcrBox(20, 70, 95, 95), 0.9f),
            OcrWord("Kebede", OcrBox(20, 120, 105, 145), 0.9f),
            OcrWord("Total", OcrBox(300, 20, 360, 45), 0.9f),
            OcrWord("150.00", OcrBox(300, 70, 380, 95), 0.9f),
            OcrWord("240.50", OcrBox(300, 120, 380, 145), 0.9f)
        )

        val result = TableExtractor.analyze(scrambled)
        assertEquals(1, result.tables.size)
        val rows = result.tables[0].rows
        assertEquals(3, rows.size)
        assertEquals(listOf("Name", "Total"), rows[0])
        assertEquals(listOf("Abebe", "150.00"), rows[1])
        assertEquals(listOf("Kebede", "240.50"), rows[2])
        assertEquals("Name,Total\nAbebe,150.00\nKebede,240.50", result.csvText)
    }

    @Test
    fun borderedGridExtractsAmharicAndEnglishCellsAccurately() {
        val grid = TableExtractor.GridLines(
            rowYs = listOf(0, 50, 100),
            colXs = listOf(0, 200, 400)
        )
        val words = listOf(
            OcrWord("ስም", OcrBox(20, 15, 70, 38), 0.88f, isEthiopic = true),
            OcrWord("Score", OcrBox(220, 15, 290, 38), 0.92f, isEthiopic = false),
            OcrWord("አበበ", OcrBox(20, 65, 85, 88), 0.90f, isEthiopic = true),
            OcrWord("98.5%", OcrBox(220, 65, 295, 88), 0.94f, isEthiopic = false)
        )

        val result = TableExtractor.analyze(words, grid)
        assertEquals(1, result.tables.size)
        assertEquals(listOf("ስም", "Score"), result.tables[0].rows[0])
        assertEquals(listOf("አበበ", "98.5%"), result.tables[0].rows[1])
    }

    @Test
    fun textFusionStopsNumbersAndSuperscriptsFromBecomingAmharic() {
        val tessWords = listOf(
            // Real Amharic word
            OcrWord("ኢትዮጵያ", OcrBox(20, 50, 130, 82), 0.88f, isEthiopic = true),
            // Tesseract misread "2026" as Ethiopic numeral / Fidel noise with lower conf
            OcrWord("፪0፪6", OcrBox(150, 50, 220, 82), 0.52f, isEthiopic = true),
            // Tiny superscript speck misread as Fidel "ሀ"
            OcrWord("ሀ", OcrBox(225, 48, 232, 55), 0.45f, isEthiopic = true)
        )
        val mlKitWords = listOf(
            // ML Kit garbles Amharic into Latin
            OcrWord("htf9h", OcrBox(20, 50, 130, 82), 0.90f, isEthiopic = false),
            // ML Kit reads "2026" cleanly
            OcrWord("2026", OcrBox(150, 50, 220, 82), 0.92f, isEthiopic = false)
        )

        val fused = TextFusion.fuse(tessWords, mlKitWords)
        val texts = fused.sortedBy { it.box.left }.map { it.text }
        assertTrue(texts.contains("ኢትዮጵያ"))
        assertTrue(texts.contains("2026"))
        assertFalse(texts.contains("፪0፪6"))
        assertFalse(texts.contains("ሀ"))
    }
}
