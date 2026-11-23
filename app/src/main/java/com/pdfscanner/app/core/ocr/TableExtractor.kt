package com.pdfscanner.app.core.ocr

/**
 * Reconstructs true reading order (row-by-row, left-to-right) and extracts
 * structured 2D tables from OCR word bounding boxes + optional OpenCV grid lines.
 *
 * Fixes the column-scrambled OCR problem where PSM_AUTO reads column 1 top-to-bottom
 * and then column 2 top-to-bottom.
 */
object TableExtractor {

    data class LayoutResult(
        val orderedText: String,
        val tables: List<OcrTable>,
        val csvText: String
    )

    data class GridLines(
        val rowYs: List<Int>, // sorted horizontal grid boundaries
        val colXs: List<Int>  // sorted vertical grid boundaries
    ) {
        val isValidTable: Boolean get() = rowYs.size >= 3 && colXs.size >= 3
    }

    fun analyze(words: List<OcrWord>, grid: GridLines? = null): LayoutResult {
        if (words.isEmpty()) return LayoutResult("", emptyList(), "")

        // 1. If OpenCV detected a physical bordered grid (>=2 rows x >=2 cols), extract exact cells!
        if (grid != null && grid.isValidTable) {
            val gridTable = extractFromGrid(words, grid)
            val nonEmptyRows = gridTable.rows.filter { row -> row.any { it.isNotBlank() } }
            if (nonEmptyRows.size >= 2) {
                val cleanTable = OcrTable(nonEmptyRows)
                val outsideWords = words.filter { w ->
                    val cx = w.box.centerX
                    val cy = w.box.centerY
                    cy < grid.rowYs.first() || cy > grid.rowYs.last() ||
                        cx < grid.colXs.first() || cx > grid.colXs.last()
                }
                val headerText = reconstructRowsText(outsideWords.filter { it.box.centerY < grid.rowYs.first() })
                val footerText = reconstructRowsText(outsideWords.filter { it.box.centerY > grid.rowYs.last() })
                val fullText = listOf(headerText, cleanTable.toTsv(), footerText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                return LayoutResult(fullText, listOf(cleanTable), cleanTable.toCsv())
            }
        }

        // 2. Group all words into visual horizontal rows (row-by-row order!)
        val rows = clusterIntoRows(words)
        val borderlessTable = detectBorderlessTable(rows)

        return if (borderlessTable != null) {
            LayoutResult(
                orderedText = borderlessTable.toTsv(),
                tables = listOf(borderlessTable),
                csvText = borderlessTable.toCsv()
            )
        } else {
            val text = rows.joinToString("\n") { row ->
                row.sortedBy { it.box.left }.joinToString(" ") { it.text }
            }
            LayoutResult(orderedText = text, tables = emptyList(), csvText = "")
        }
    }

    private fun extractFromGrid(words: List<OcrWord>, grid: GridLines): OcrTable {
        val numRows = grid.rowYs.size - 1
        val numCols = grid.colXs.size - 1
        val matrix = Array(numRows) { Array(numCols) { mutableListOf<OcrWord>() } }

        for (w in words) {
            val cx = w.box.centerX.toInt()
            val cy = w.box.centerY.toInt()
            val r = findBucket(grid.rowYs, cy)
            val c = findBucket(grid.colXs, cx)
            if (r in 0 until numRows && c in 0 until numCols) {
                matrix[r][c].add(w)
            }
        }

        val tableRows = matrix.map { rowCells ->
            rowCells.map { cellWords ->
                reconstructCellText(cellWords)
            }
        }
        return OcrTable(tableRows)
    }

    private fun reconstructCellText(cellWords: List<OcrWord>): String {
        if (cellWords.isEmpty()) return ""
        val rows = clusterIntoRows(cellWords)
        return rows.joinToString(" ") { line ->
            line.sortedBy { it.box.left }.joinToString(" ") { it.text }
        }.trim()
    }

    private fun findBucket(boundaries: List<Int>, value: Int): Int {
        for (i in 0 until boundaries.size - 1) {
            if (value >= boundaries[i] && value <= boundaries[i + 1]) return i
        }
        return -1
    }

    fun clusterIntoRows(words: List<OcrWord>): List<List<OcrWord>> {
        if (words.isEmpty()) return emptyList()
        val heights = words.map { it.box.height }.filter { it > 0 }.sorted()
        val medianH = if (heights.isNotEmpty()) heights[heights.size / 2] else 20
        val yTolerance = maxOf(medianH * 0.55f, 8f)

        val sorted = words.sortedBy { it.box.centerY }
        val rows = mutableListOf<MutableList<OcrWord>>()
        val rowCenters = mutableListOf<Float>()

        for (w in sorted) {
            val cy = w.box.centerY
            var matchedIdx = -1
            var bestDist = Float.MAX_VALUE
            for (i in rows.indices) {
                val dist = kotlin.math.abs(cy - rowCenters[i])
                if (dist <= yTolerance && dist < bestDist) {
                    bestDist = dist
                    matchedIdx = i
                }
            }
            if (matchedIdx >= 0) {
                rows[matchedIdx].add(w)
                rowCenters[matchedIdx] = rows[matchedIdx].map { it.box.centerY }.average().toFloat()
            } else {
                rows.add(mutableListOf(w))
                rowCenters.add(cy)
            }
        }

        return rows.map { it.sortedBy { w -> w.box.left } }
    }

    private fun reconstructRowsText(words: List<OcrWord>): String {
        if (words.isEmpty()) return ""
        return clusterIntoRows(words).joinToString("\n") { row ->
            row.joinToString(" ") { it.text }
        }
    }

    /**
     * Detects borderless tables (e.g., invoices, receipts, grade sheets) when >=3 rows
     * have large horizontal column gaps and consistent column X-anchors.
     */
    private fun detectBorderlessTable(rows: List<List<OcrWord>>): OcrTable? {
        if (rows.size < 3) return null
        val allWords = rows.flatten()
        if (allWords.size < 4) return null

        val avgCharW = allWords.map {
            it.box.width.toFloat() / maxOf(it.text.length, 1)
        }.sorted().let { it[it.size / 2] }.coerceAtLeast(6f)
        val colGapThreshold = maxOf(avgCharW * 2.3f, 24f)

        // Split each row into visual segments where horizontal gap > colGapThreshold
        data class Segment(val text: String, val left: Int, val right: Int)
        val rowSegments = rows.map { row ->
            val segs = mutableListOf<Segment>()
            var curWords = mutableListOf(row[0])
            for (i in 1 until row.size) {
                val prev = row[i - 1]
                val curr = row[i]
                val gap = curr.box.left - prev.box.right
                if (gap > colGapThreshold) {
                    segs.add(
                        Segment(
                            curWords.joinToString(" ") { it.text },
                            curWords.first().box.left,
                            curWords.last().box.right
                        )
                    )
                    curWords = mutableListOf(curr)
                } else {
                    curWords.add(curr)
                }
            }
            segs.add(
                Segment(
                    curWords.joinToString(" ") { it.text },
                    curWords.first().box.left,
                    curWords.last().box.right
                )
            )
            segs
        }

        val multiColRows = rowSegments.count { it.size >= 2 }
        if (multiColRows < 3 || multiColRows * 2 < rows.size) return null

        // Cluster segment left X-positions into consistent table columns
        val anchorTolerance = maxOf(avgCharW * 3.5f, 45f)
        val colCenters = mutableListOf<Float>()
        for (seg in rowSegments.flatten().sortedBy { it.left }) {
            val match = colCenters.indexOfFirst { kotlin.math.abs(it - seg.left) <= anchorTolerance }
            if (match == -1) {
                colCenters.add(seg.left.toFloat())
            }
        }
        colCenters.sort()
        if (colCenters.size < 2 || colCenters.size > 12) return null

        val tableRows = rowSegments.map { segs ->
            val rowArr = Array(colCenters.size) { "" }
            for (seg in segs) {
                var bestCol = 0
                var bestDist = Float.MAX_VALUE
                for (c in colCenters.indices) {
                    val d = kotlin.math.abs(seg.left - colCenters[c])
                    if (d < bestDist) {
                        bestDist = d
                        bestCol = c
                    }
                }
                rowArr[bestCol] = if (rowArr[bestCol].isEmpty()) seg.text else "${rowArr[bestCol]} ${seg.text}"
            }
            rowArr.toList()
        }
        return OcrTable(tableRows)
    }
}
