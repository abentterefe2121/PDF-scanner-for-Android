package com.pdfscanner.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application — intentionally LIGHT for fast cold start (<800ms on low-end).
 * No OpenCV, OCR, or DB init here. Everything is lazy-loaded when the
 * Scanner screen opens. See docs/STRATEGY.md §4.
 */
@HiltAndroidApp
class PdfScannerApp : Application()
