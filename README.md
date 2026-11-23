# PDF Scanner for Android

Premium, advanced PDF scanner built for **low-end to flagship** — works like magic on Tecno / Infinix / Samsung A-series (2–3 GB RAM) and on high-end phones alike.

**Amharic-first OCR** (offline + online hybrid), **Kotlin Native + XML Views (no Compose)** for maximum speed, **Android 8+ (API 26)**.

> See [docs/STRATEGY.md](docs/STRATEGY.md) for full strategy and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for code structure.

## Why This Stack?

| Choice | Reason |
|---|---|
| **Kotlin Native** | Fastest camera/OpenCV/ML Kit access, smallest APK. What Adobe Scan & Microsoft Lens use. |
| **XML Views + ViewBinding (no Compose)** | Fastest cold start (<800 ms), lowest memory, best for custom camera overlay on 2 GB RAM. |
| **CameraX + OpenCV 4.9.0** | Live edge detection, perspective warp, Magic/B&W/Lighten filters. Analysis at 640×480, capture with EXIF rotation. |
| **Hybrid OCR: Tesseract 5 (amh+eng) offline + ML Kit online** | ML Kit cannot read Ethiopic script. Tesseract reads Amharic offline; ML Kit boosts English. Dual-language for mixed ET documents. |
| **PdfDocument + Noto Sans Ethiopic** | Lightweight searchable PDFs, no heavy iText. Ethiopic font layer so Amharic text is searchable/copyable. |
| **Room + FileProvider** | Offline-first storage, title/OCR search, native Android share sheet. |
| **DeviceTier adaptation** | Auto quality by RAM/cores: Low (1280px, Q70) → High (2000px, Q85). |

## Features (V1 — Full Scanner MVP)

- [x] Phase 0 scaffold: Gradle, Hilt, Room, CameraX, CI, bilingual UI (EN + አማርኛ)
- [x] Live edge overlay + auto-capture (stable quad + Laplacian sharpness, torch, gallery import)
- [x] File-based multi-page `ScanSession` (OOM-safe on 2 GB RAM), rotate & delete pages
- [x] Perspective warp + non-destructive filters: Original / Magic (CLAHE + sharpen) / Lighten / B&W (Ethiopic block-51 threshold)
- [x] On-demand Amharic+English OCR pack downloader + Hybrid OCR (Tesseract 5 `amh+eng` + ML Kit Latin)
- [x] Searchable A4 `PdfDocument` generator + multi-page `PdfRenderer` viewer + share (`FileProvider`) + search in Home grid
- [ ] Phase 2–5 polish: manual corner drag cropper, drag-to-reorder pages, Noto Sans Ethiopic font bundling, custom FTS4 Ethiopic tokenizer, folders/tags

## Build

Requires **Android Studio (Hedgehog+) + JDK 17** or Gradle 8.7+.

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

CI (GitHub Actions) builds `app-debug.apk` and runs unit tests on every push — see `.github/workflows/android.yml`.

## Project Structure

```
app/src/main/java/com/pdfscanner/app/
  core/camera    CameraManager (CameraX Preview + RGBA Analysis + ImageCapture + Torch)
  core/vision    OpenCvHelper (lazy load, Canny quad detect, perspective warp, 4 filters, sharpness)
  core/ocr       OcrPackDownloader, TesseractOcrEngine (amh+eng), MlKitOcrEngine, HybridOcrEngine
  core/pdf       PdfGenerator (A4 + invisible OCR layer), PdfViewer (PdfRenderer)
  core/db        AppDatabase, DocumentEntity, DocumentDao
  core/common    DeviceTier, BitmapUtils, DispatcherModule, ScanSession
  ui/home        HomeFragment, HomeViewModel, DocumentAdapter (grid + thumbnails + live search)
  ui/scanner     ScannerFragment, ScannerViewModel, EdgeOverlayView (auto-capture)
  ui/editor      EditorFragment, EditorViewModel, PageAdapter (filters, rotate, delete, PDF/OCR)
  ui/viewer      ViewerFragment (multi-page PDF viewer, OCR text, share, delete)
app/src/main/res/  layouts (Views), values/ (EN), values-am/ (አማርኛ), navigation/
docs/  STRATEGY.md, ARCHITECTURE.md
```

## License

Private — all rights reserved.
