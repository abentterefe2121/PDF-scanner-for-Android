# PDF Scanner for Android — Strategy & Architecture

**Status:** Discussion Phase — Decisions Locked (2026-11-23)
**Market:** Ethiopia / Africa — low-end Tecno, Infinix, Samsung A-series (2–3 GB RAM, Android 8–12)
**Goal:** Premium, advanced scanner that feels like magic on low-end and flagship alike.

---

## 1. Decisions Locked

| Decision | Choice | Why |
|---|---|---|
| **Min Android** | API 26 (Android 8.0) | Covers 95%+ of low-end devices in ET/Africa. Scoped Storage, CameraX, ML Kit all available. |
| **MVP Scope** | Full Scanner: Capture + Crop + Filters + PDF + OCR | Complete scan-to-searchable-PDF flow, not a demo. |
| **UI Toolkit** | **No Compose** — XML Views + ViewBinding + Material 3 | Fastest cold start, lowest memory, best for custom camera overlay on 2 GB RAM. |
| **Language** | **Kotlin Native** (confirmed) | Fast to write, interoperates with OpenCV (C++), CameraX, ML Kit, Tesseract (C). Java would be slower to develop, same runtime. |
| **OCR Priority** | **Amharic-first, advanced** (not simple) | ML Kit alone cannot read Ethiopic script. Needs dedicated pipeline. |
| **Engine** | **Hybrid Offline + Online** (confirmed): Tesseract amh+eng offline (primary) + ML Kit online/Latin boost | Works without internet (offline-first for ET) and boosts accuracy when internet is available. No Play Services dependency for core Amharic OCR. |

---

## 2. No Compose — Why Views Is Faster For This App

You are right to reject Compose for a *very fast* scanner. Here's the honest trade-off:

### Compose vs Views on low-end (2 GB RAM, 4 cores)

| Factor | Jetpack Compose | XML Views + ViewBinding |
|---|---|---|
| **Cold start** | Compose runtime + compiler overhead, ~150–300 ms extra on low-end | Direct inflation, fastest possible |
| **Memory** | Compose UI tree + recomposition state held in memory | Views are lighter, predictable GC |
| **Camera overlay** | Custom `Canvas` / `AndroidView` bridge needed for edge quad | Native `Custom View` — draw quad, animate, haptic — zero bridge |
| **APK size** | +1–2 MB Compose runtime | Smaller, R8 shrinks unused Material widgets |
| **Frame drops** | Recomposition can jank on 4-core Tecno during camera preview | `invalidate()` only redraws overlay, preview untouched |
| **Maturity for scanner** | Great for modern apps, but scanner needs imperative camera code anyway | CameraX samples, OpenCV tutorials are all Views-based |
| **Downside** | Modern, declarative, less boilerplate, easier dark theme | More XML boilerplate, need careful `ConstraintLayout` to avoid overdraw |

**Verdict:** For a scanner where 80% of the work is camera + image processing (not fancy UI), Views wins on speed, memory, and APK size. Compose would not make the *scanning* faster — it would make the *shell* heavier.

**Our Views speed rules:**
- `ViewBinding` only (no DataBinding — slower, more reflection).
- `ConstraintLayout` flat hierarchies (max 2–3 levels deep), no nested `LinearLayout` weights.
- Lazy-load OpenCV: `System.loadLibrary("opencv_java4")` **only** when Scanner opens, never in `Application.onCreate`.
- Splash → Home in <800 ms: no DB, no OCR init, no font load at startup.
- Vector drawables, WebP thumbnails via Coil, `android:hardwareAccelerated="true"` but avoid overdraw.
- Baseline Profiles + R8 full mode to shrink and speed up.

> If we later need a marketing website or iOS, we can add Compose/KMP then. For V1 speed, Views is the premium choice.

---

## 3. Amharic-First Advanced OCR — The Hard Part

This is where most "simple" scanners fail in Ethiopia, and where we win.

### Why ML Kit alone is NOT enough
- ML Kit Text Recognition supports Latin, Chinese, Japanese, Korean, Devanagari, etc.
- **Ethiopic script (U+1200–U+137F, fidel characters like ሀ ለ ሐ መ ሠ ረ ሰ ሸ ቀ በ ተ ኀ ነ አ ከ ወ ዐ ዘ የ ደ ጀ ገ ጠ ጨ ጰ ጸ ፀ ፈ ፐ) is not supported.**
- On Amharic documents, ML Kit returns empty or garbled Latin guesses.
- Google Cloud Vision *does* support Amharic, but needs internet + API cost — not offline-first for Ethiopia.

### Advanced Amharic Pipeline (Our Plan)

```
Capture (CameraX 2MP)
  → Downscale to max 1600px (save RAM)
  → OpenCV Preprocess: deskew, denoise, adaptive threshold, CLAHE
  → Edge Detect (Canny + contours) → Perspective Warp (flatten page)
  → Enhance: Magic / B&W / Lighten (shadow removal via background division)
  → OCR: Tesseract 5 LSTM with `amh + eng` dual language
       ├─ amh.traineddata (Ethiopic) — primary for Amharic text
       └─ eng.traineddata — for mixed Amharic-English forms (very common in ET)
  → Post-process: Unicode normalize (NFC), fix common fidel confusions
  → Searchable PDF: embed invisible text layer + Noto Sans Ethiopic font
  → Room FTS index for instant search inside PDFs (Amharic-aware tokenizer)
```

**Key advanced details most scanners miss:**
1. **Dual-language OCR:** Ethiopian documents are almost always Amharic + English mixed (government, school, business). Single-language OCR fails. We run `amh+eng` and merge by bounding box.
2. **Preprocessing tuned for Ethiopic:** Fidel characters have dense strokes — aggressive binarization destroys them. We use adaptive threshold with larger block size + morphology tuned for Ethiopic, tested on real scans.
3. **Searchable PDF font:** If we embed Amharic text without an Ethiopic font, the PDF text layer is invisible/broken on other devices. We embed **Noto Sans Ethiopic** (open source) subset.
4. **Download-on-demand language packs:** `amh.traineddata` (~4–6 MB) + `eng` (~4 MB) are **not** in APK. First launch prompts download (Wi-Fi preferred), stored in app storage. APK stays <15 MB.
5. **Phase 2 AI upgrade:** Fine-tune a TFLite / EasyOCR / TrOCR model on printed Ethiopic text for 95%+ accuracy on low-quality scans. Tesseract is V1; AI is V2 premium.
6. **Amharic UI:** App UI in **Amharic + English** bilingual (`values-am`, `values-en`), because our users think in Amharic. Scanner labels, filters, share text — all localized.

**Fallback:** ML Kit for pure English/Latin documents (faster, more accurate for Latin), Tesseract for Amharic. We auto-detect script by trying both and picking higher confidence.

---

## 4. Speed Architecture for Low-End (API 26, 2 GB RAM)

"Works like magic" on Tecno Spark / Infinix Hot is not luck — it's engineering:

| Layer | Magic Trick |
|---|---|
| **Camera** | CameraX `ImageAnalysis` at **640×480** for live edge detection (not full res). Full 2 MP only on shutter tap. `STRATEGY_KEEP_ONLY_LATEST` — never queue frames. |
| **Processing** | Work at max **1600 px** long edge. OpenCV `Mat.release()` immediately, Bitmap pooling, `Dispatchers.Default` coroutines, never block UI. |
| **Device tiers** | Auto-detect RAM/cores: **Low (≤3 GB):** 1280 px, JPEG Q70, sequential OCR. **Mid:** 1600 px, Q80. **High:** 2000 px, Q85, parallel. |
| **PDF** | Framework `PdfDocument` (no heavy iText/PdfBox), adaptive JPEG compression target <500 KB/page, embed OCR text layer manually. |
| **Storage** | Room (WAL) + FTS4 for search, Scoped Storage / MediaStore, EncryptedFile for premium privacy. WorkManager for background PDF/OCR. |
| **Startup** | Lazy OpenCV, lazy OCR, lazy DB. No heavy work in `Application`. Baseline Profiles. |
| **Battery/Thermal** | Pause preview when backgrounded, throttle if device hot, release camera on pause. |
| **APK** | App Bundle, R8 full mode, vector drawables, WebP, split language packs. Target <15 MB. |

**Target metrics (Galaxy A04 / Tecno Spark 10):**
- Cold start: <800 ms
- Camera open → preview: <600 ms
- Shutter → enhanced page: <1.2 s
- 5-page PDF generation: <4 s (background)
- OCR 1 page (Amharic): <2 s

---

## 5. Full Scanner MVP Scope (V1)

**Must-have:**
- [ ] CameraX scanner with live edge overlay + auto-capture (stable quad + sharpness check)
- [ ] Manual capture + gallery import
- [ ] Crop (perspective warp) + rotate + reorder pages
- [ ] Filters: Original / Magic / Lighten / B&W
- [ ] Multi-page document, thumbnail strip
- [ ] Amharic + English OCR (Tesseract), searchable PDF, copy text
- [ ] PDF generation + share (Android Sharesheet, WhatsApp, Telegram, Drive)
- [ ] Home: recent docs grid, search (Amharic-aware), folders/tags
- [ ] PDF viewer (PdfRenderer), rename, delete, duplicate
- [ ] Bilingual UI (Amharic + English), dark/light theme
- [ ] Offline-first, no account required

**V2 Premium (after launch):**
- AI enhancement (TFLite), signature, PDF/A, encryption, cloud sync, batch OCR, widgets

---

## 6. Final Stack

```
Language:      Kotlin (Coroutines + Flow)
UI:            XML Views + ViewBinding + Material 3 + ConstraintLayout
Camera:        CameraX (ImageAnalysis + ImageCapture)
Vision:        OpenCV 4.x (C++ via JNI) — edge, warp, enhance
OCR:           Tesseract 5 (amh+eng) primary, ML Kit Text (Latin) fallback
PDF:           Android PdfDocument + custom JPEG + Noto Sans Ethiopic
DB:            Room + FTS4, DataStore, WorkManager
DI:            Hilt (lightweight, lazy)
Image:         Coil (thumbnails), Bitmap pooling
Architecture:  Clean + MVVM, multi-module (:app, :core:camera, :core:vision, :core:ocr, :core:pdf, :core:db)
Min SDK:       26 (Android 8.0) | Target: 34/35 | Build: Gradle Kotlin DSL, AGP 8.x
Testing:       JUnit, Turbine, Espresso, Firebase Test Lab (Tecno/Infinix devices)
CI:            GitHub Actions (build, test, APK bundle)
```

---

## 7. Roadmap

| Phase | Duration | Deliverable |
|---|---|---|
| **0 — Setup** | 3–4 days | Gradle, Hilt, Room, CI, design system, bilingual strings, device-tier helper |
| **1 — Scanner Core** | 2 weeks | CameraX + live edge overlay + auto-capture + capture/import |
| **2 — Editor** | 1.5 weeks | Crop/warp, rotate, filters (Magic/B&W/Lighten), multi-page |
| **3 — OCR + PDF** | 2 weeks | Tesseract amh+eng, searchable PDF, share, viewer |
| **4 — Organize** | 1 week | Home grid, search, folders, rename/delete |
| **5 — Polish** | 1 week | Speed tuning on Tecno/Infinix, Play Store, beta testers in Jimma/Addis |

Total V1: ~7–8 weeks to Play Store beta.

---

## 8. Open Questions for You

1. Confirm: **Kotlin + XML Views (no Compose)** — agree?
2. Confirm: **Tesseract amh+eng on-device** as V1 OCR (offline, free) — agree, or do you want Cloud Vision (internet, paid)?
3. Next step: scaffold the project (Phase 0) now, or refine this doc first?
