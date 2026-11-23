# PDF Scanner for Android

Premium, fast PDF scanner built for **low-end to flagship** — works smoothly on Tecno / Infinix / Samsung A-series (2–3 GB RAM) and high-end phones alike.

**Amharic-first OCR** (offline + online hybrid), **Kotlin Native + XML Views (no Compose)** for fast cold start and low memory, **Android 8+ (API 26)** targeting **Android 16 (SDK 36)**.

> See [docs/STRATEGY.md](docs/STRATEGY.md) for full strategy and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for code structure.

## Why This Stack?

| Choice | Reason |
|---|---|
| **Kotlin Native** | Fast camera/OpenCV/ML Kit access, small APK. |
| **XML Views + ViewBinding (no Compose)** | Fast cold start (<800 ms), predictable memory, zero-bridge custom `Canvas` camera overlay. |
| **CameraX 1.6.1 + OpenCV 4.14.0** | Live edge detection, perspective warp, Magic Color (YCrCb CLAHE), Lighten, and Ethiopic-tuned B&W filters. |
| **Hybrid OCR: Tesseract 5 (amh+eng) offline + ML Kit online** | ML Kit cannot read Ethiopic script. Tesseract reads Amharic offline; ML Kit boosts Latin text. |
| **PdfDocument + Noto Sans Ethiopic** | Lightweight searchable A4 PDFs with bundled `NotoSansEthiopic-Regular.ttf` for Amharic & English text layers. |
| **Room + FTS4 + FileProvider** | Offline-first storage, hybrid FTS4 + Amharic-aware search, folders, native Android share sheet. |
| **DeviceTier adaptation** | Auto quality by RAM/cores: Low (1280px, Q70) → High (2000px, Q85), with sub-sampled decode to prevent OOM. |

## Features (V1 Complete)

- [x] Phase 0 setup: Gradle `9.9.2`, AGP `9.3.2`, Kotlin `2.4.21`, Hilt `2.60.1`, Room `2.8.3`, CI, bilingual UI (EN + አማርኛ), Light & Dark (`values-night`) Material 3 themes
- [x] Live edge overlay (aspect-fill `FILL_CENTER` mapped) + haptic auto-capture, batch scan mode, torch indicator, sub-sampled gallery import
- [x] File-based multi-page `ScanSession` (OOM-safe), interactive 4-corner perspective cropper, drag-to-reorder pages, rotate & delete
- [x] Non-destructive filters: Original / Magic Color / Lighten / B&W (Ethiopic block-51 threshold)
- [x] On-demand Amharic+English OCR pack downloader + Hybrid OCR (Tesseract 5 `amh+eng` + ML Kit Latin)
- [x] Searchable A4 `PdfDocument` generator with bundled `NotoSansEthiopic-Regular.ttf`, multi-page `PdfRenderer` viewer, copy OCR text, share (`FileProvider`)
- [x] Home grid with cached PDF thumbnails, folders (`Inbox`, `IDs`, `Receipts`, `School`), rename, duplicate, delete, hybrid FTS4 + Amharic search
- [x] Settings screen: default scan filter, auto-capture toggle, live EN/አማርኛ language switch, offline OCR status/download, `DeviceTier` profile

## Build

Requires **JDK 17** and **Android SDK (compileSdk 37, targetSdk 36)** — wrapper included (`./gradlew` on Linux/macOS, `.\gradlew.bat` on Windows).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

CI (GitHub Actions) builds `app-debug.apk` and runs unit tests on every push — see `.github/workflows/android.yml`.

## Project Structure

```
app/src/main/java/com/pdfscanner/app/
  core/camera    CameraManager (CameraX Preview + RGBA Analysis + ImageCapture + Torch)
  core/vision    OpenCvHelper (lazy load, quad detect, perspective warp, 4 filters, sharpness)
  core/ocr       OcrPackDownloader, TesseractOcrEngine (amh+eng), MlKitOcrEngine, HybridOcrEngine
  core/pdf       PdfGenerator (A4 + NotoSansEthiopic OCR layer), PdfViewer (PdfRenderer)
  core/db        AppDatabase (v2), DocumentEntity, DocumentFtsEntity, DocumentDao
  core/common    DeviceTier, BitmapUtils, DispatcherModule, ScanSession, SettingsPrefs
  ui/home        HomeFragment, HomeViewModel, DocumentAdapter (grid + LRU thumbnails + search + folders)
  ui/scanner     ScannerFragment, ScannerViewModel, EdgeOverlayView (auto-capture + batch mode)
  ui/editor      EditorFragment, EditorViewModel, PageAdapter, CropFragment, CropOverlayView
  ui/viewer      ViewerFragment (multi-page PDF viewer, copy OCR, rename, folder move, share, delete)
  ui/settings    SettingsFragment (DataStore prefs, EN/AM language switch, offline OCR packs, DeviceTier)
app/src/main/res/  layouts (Views), values/ (EN), values-am/ (አማርኛ), values-night/, navigation/
app/src/main/assets/fonts/  NotoSansEthiopic-Regular.ttf
docs/  STRATEGY.md, ARCHITECTURE.md
```

## License

Private — all rights reserved.
