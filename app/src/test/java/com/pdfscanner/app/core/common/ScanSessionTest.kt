package com.pdfscanner.app.core.common

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ScanSessionTest {

    @Before
    fun setUp() {
        ScanSession.clear()
    }

    @Test
    fun addMoveRemoveMaintainsOrder() {
        ScanSession.addPage(ScanSession.PageEntry("r1", "p1"))
        ScanSession.addPage(ScanSession.PageEntry("r2", "p2"))
        ScanSession.addPage(ScanSession.PageEntry("r3", "p3"))

        assertEquals(listOf("p1", "p2", "p3"), ScanSession.paths)

        ScanSession.move(0, 2)
        assertEquals(listOf("p2", "p3", "p1"), ScanSession.paths)

        ScanSession.removeAt(1)
        assertEquals(listOf("p2", "p1"), ScanSession.paths)
    }

    @Test
    fun updateCropAndProcessedUpdatesSession() {
        ScanSession.addPage(ScanSession.PageEntry("r1", "p1"))
        val quad = arrayOf(PointF(0f, 0f), PointF(10f, 0f), PointF(10f, 10f), PointF(0f, 10f))
        ScanSession.updateCrop(0, quad, 100, 100, "p1_cropped")
        assertEquals("p1_cropped", ScanSession.paths[0])
        assertEquals(100, ScanSession.pages[0].quadWidth)

        ScanSession.updateProcessed(0, "p1_rot", "r1_rot", clearQuad = true)
        assertEquals("p1_rot", ScanSession.paths[0])
        assertEquals("r1_rot", ScanSession.pages[0].rawPath)
        assertNull(ScanSession.pages[0].quad)
    }
}
