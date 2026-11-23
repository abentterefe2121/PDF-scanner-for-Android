package com.pdfscanner.app.core.common

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects device capability so the scanner can adapt quality.
 * Low-end (Tecno/Infinix 2-3GB): smaller images, lower JPEG quality, sequential OCR.
 * High-end: full resolution, parallel processing.
 * This is the "magic on low-end" switch.
 */
@Singleton
class DeviceTier @Inject constructor(
    @ApplicationContext context: Context
) {
    enum class Tier { LOW, MID, HIGH }

    private val totalRamMb: Long = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    }

    val tier: Tier = when {
        totalRamMb <= 3072 || Runtime.getRuntime().availableProcessors() <= 4 -> Tier.LOW
        totalRamMb <= 6144 -> Tier.MID
        else -> Tier.HIGH
    }

    /** Max long-edge for processing (px). Low-end works smaller to save RAM. */
    val maxProcessingEdge: Int = when (tier) {
        Tier.LOW -> 1280
        Tier.MID -> 1600
        Tier.HIGH -> 2000
    }

    /** JPEG quality for PDF pages. */
    val jpegQuality: Int = when (tier) {
        Tier.LOW -> 70
        Tier.MID -> 80
        Tier.HIGH -> 85
    }

    /** Preview analysis resolution for live edge detection. */
    val analysisWidth: Int = 640
    val analysisHeight: Int = 480

    val isLowEnd: Boolean get() = tier == Tier.LOW

    fun logInfo(): String =
        "DeviceTier=$tier RAM=${totalRamMb}MB cores=${Runtime.getRuntime().availableProcessors()} " +
            "SDK=${Build.VERSION.SDK_INT} maxEdge=$maxProcessingEdge q=$jpegQuality"
}
