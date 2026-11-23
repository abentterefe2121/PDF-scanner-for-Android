package com.pdfscanner.app.core.ocr

import java.text.Normalizer

/**
 * Script-aware word-level fusion between Tesseract (Amharic-capable) and ML Kit (Latin/Numbers).
 *
 * Solves mixed Amharic + English + number/superscript corruption:
 * - Suppresses tiny noise/superscript specks (< 35% of median word height) that Tesseract
 *   hallucinates as stray Ethiopic fidel (e.g. ሀ, ፟, ፡).
 * - When ML Kit detects clean ASCII letters/digits/dates/punctuation overlapping a word box
 *   where Tesseract has low confidence, mixed Ethiopic+ASCII noise, or rare Ethiopic numerals,
 *   keeps ML Kit's clean English/number token.
 * - Preserves true Amharic (Ethiopic U+1200..U+137F) words and normalizes Unicode NFC.
 */
object TextFusion {

    private val CLEAN_ASCII_REGEX = Regex("^[A-Za-z0-9.,:/%+\\-()$#@&'\"_=*]+$")
    private val PURE_NUMERIC_CODE_REGEX = Regex("^[0-9.,:/%+\\-()$#]+$")
    private val ETHIOPIC_NUMERAL_RANGE = 0x1369..0x137C

    fun containsEthiopic(text: String): Boolean =
        text.any { it.code in 0x1200..0x137F }

    fun cleanToken(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace("\u135F", "") // strip stray Ethiopic combining gemination noise
            .trim()

    fun fuse(
        tessWords: List<OcrWord>,
        mlKitWords: List<OcrWord>
    ): List<OcrWord> {
        if (tessWords.isEmpty()) return filterNoise(mlKitWords)
        if (mlKitWords.isEmpty()) return filterNoise(tessWords)

        val cleanTess = filterNoise(tessWords)
        val cleanMl = filterNoise(mlKitWords)
        val result = ArrayList<OcrWord>(cleanTess.size + cleanMl.size)
        val usedMlIndices = HashSet<Int>()

        for (tw in cleanTess) {
            val tText = cleanToken(tw.text)
            if (tText.isEmpty()) continue

            // Find best overlapping ML Kit word(s)
            val overlappingMl = cleanMl.withIndex().filter { (_, mw) ->
                tw.box.overlapRatioOfSmaller(mw.box) >= 0.38f || tw.box.iou(mw.box) >= 0.25f
            }

            if (overlappingMl.isEmpty()) {
                // Reject isolated 1-char low-confidence Ethiopic specks
                if (!(tText.length == 1 && containsEthiopic(tText) && tw.confidence < 0.62f)) {
                    result.add(tw.copy(text = tText, isEthiopic = containsEthiopic(tText)))
                }
                continue
            }

            overlappingMl.forEach { usedMlIndices.add(it.index) }
            val mergedMlText = overlappingMl
                .sortedBy { it.value.box.left }
                .joinToString(" ") { cleanToken(it.value.text) }
                .trim()
            val mlConf = overlappingMl.map { it.value.confidence }.average().toFloat()

            val chosen = pickBestWord(
                tessWord = tw.copy(text = tText, isEthiopic = containsEthiopic(tText)),
                mlText = mergedMlText,
                mlConf = mlConf,
                mlBox = unionBoxes(overlappingMl.map { it.value.box })
            )
            if (chosen != null) result.add(chosen)
        }

        // Add any ML Kit words in regions Tesseract missed completely (e.g. table cell numbers)
        for ((idx, mw) in cleanMl.withIndex()) {
            if (!usedMlIndices.contains(idx)) {
                val mText = cleanToken(mw.text)
                if (mText.isNotEmpty()) {
                    result.add(mw.copy(text = mText, isEthiopic = false))
                }
            }
        }

        return result
    }

    private fun pickBestWord(
        tessWord: OcrWord,
        mlText: String,
        mlConf: Float,
        mlBox: OcrBox
    ): OcrWord? {
        val tText = tessWord.text
        if (mlText.isEmpty()) return tessWord

        val mlIsCleanAscii = CLEAN_ASCII_REGEX.matches(mlText)
        val mlIsNumericCode = PURE_NUMERIC_CODE_REGEX.matches(mlText)
        val tHasEthiopic = containsEthiopic(tText)
        val tHasAscii = tText.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        val tHasRareEthiopicNumeral = tText.any { it.code in ETHIOPIC_NUMERAL_RANGE }

        // 1. If ML Kit found a clean number/code/date/percent (e.g. "2026", "12.5%", "0911-2345")
        //    and Tesseract turned it into rare Ethiopic numerals, mixed garbage, or low-conf Fidel -> ML Kit wins!
        if (mlIsNumericCode && (!tHasEthiopic || tHasRareEthiopicNumeral || tHasAscii || tessWord.confidence < 0.78f || tText.length <= 3)) {
            return OcrWord(mlText, mlBox, maxOf(mlConf, 0.85f), isEthiopic = false)
        }

        // 2. If Tesseract produced mixed Ethiopic + ASCII inside a single word (classic superscript/mixed corruption),
        //    and ML Kit reads clean ASCII -> ML Kit wins!
        if (tHasEthiopic && tHasAscii && mlIsCleanAscii) {
            return OcrWord(mlText, mlBox, maxOf(mlConf, 0.82f), isEthiopic = false)
        }

        // 3. If Tesseract output is pure ASCII (no Ethiopic), ML Kit is generally cleaner for Latin/English.
        if (!tHasEthiopic && mlIsCleanAscii) {
            return OcrWord(mlText, mlBox, maxOf(mlConf, tessWord.confidence), isEthiopic = false)
        }

        // 4. If Tesseract has low confidence (<0.68) and ML Kit read a multi-char Latin word cleanly -> ML Kit wins!
        if (tHasEthiopic && tessWord.confidence < 0.68f && mlIsCleanAscii && mlText.length >= 2) {
            return OcrWord(mlText, mlBox, maxOf(mlConf, 0.80f), isEthiopic = false)
        }

        // 5. Otherwise keep Tesseract's true Amharic word!
        return tessWord
    }

    private fun filterNoise(words: List<OcrWord>): List<OcrWord> {
        if (words.isEmpty()) return emptyList()
        val heights = words.map { it.box.height }.filter { it > 0 }.sorted()
        val medianH = if (heights.isNotEmpty()) heights[heights.size / 2] else 20
        val minHeight = maxOf((medianH * 0.38f).toInt(), 6)

        return words.filter { w ->
            val t = cleanToken(w.text)
            if (t.isEmpty()) return@filter false
            // Drop tiny superscript/speck boxes unless they are valid multi-char numbers/words
            if (w.box.width < 4 || (w.box.height < minHeight && t.length <= 2)) return@filter false
            if (w.confidence in 0.01f..0.28f) return@filter false
            true
        }
    }

    private fun unionBoxes(boxes: List<OcrBox>): OcrBox {
        return OcrBox(
            left = boxes.minOf { it.left },
            top = boxes.minOf { it.top },
            right = boxes.maxOf { it.right },
            bottom = boxes.maxOf { it.bottom }
        )
    }
}
