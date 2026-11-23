package com.pdfscanner.app.core.ocr

import com.pdfscanner.app.core.db.DocumentEntity
import com.pdfscanner.app.core.vision.OpenCvHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrResultTest {

    @Test
    fun ethiopicUnicodeRangeRecognized() {
        val sample = "አዲስ አበባ ኢትዮጵያ 2026"
        val hasEthiopic = sample.any { it.code in 0x1200..0x137F }
        assertTrue(hasEthiopic)
    }

    @Test
    fun documentEntityDefaultsAreValid() {
        val doc = DocumentEntity(
            title = "Fayda ID",
            pdfPath = "/tmp/fayda.pdf",
            pageCount = 1,
            ocrText = "ኢትዮጵያ"
        )
        assertEquals("Inbox", doc.folder)
        assertEquals(OpenCvHelper.Filter.entries.size, 4)
    }
}
