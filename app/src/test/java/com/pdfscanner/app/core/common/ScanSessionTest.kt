package com.pdfscanner.app.core.common

import org.junit.Assert.assertEquals
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
}
