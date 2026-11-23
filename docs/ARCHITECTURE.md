# Architecture — PDF Scanner (Kotlin Native, Views)

## Package Structure (single-module Phase 0, split later)

```
com.pdfscanner.app
├── PdfScannerApp.kt          # @HiltAndroidApp, light init
├── MainActivity.kt           # Single-Activity + NavHost (Views)
├── core/
│   ├── camera/CameraManager.kt      # CameraX: Preview + Analysis(640x480) + Capture
│   ├── vision/OpenCvHelper.kt       # OpenCV lazy-load, edge/warp/enhance (Phase 1-2)
│   ├── ocr/
│   │   ├── OcrEngine.kt             # Interface + OcrResult
│   │   ├── TesseractOcrEngine.kt    # Offline amh+eng (primary, Amharic)
│   │   ├── MlKitOcrEngine.kt        # Online/Latin boost (ML Kit)
│   │   └── HybridOcrEngine.kt       # Offline-first, online boost, pick best
│   ├── pdf/PdfGenerator.kt          # PdfDocument, searchable, Ethiopic font
│   ├── db/AppDatabase.kt            # Room: DocumentEntity + DAO + Hilt module
│   └── common/
│       ├── DeviceTier.kt            # RAM/cores -> quality (LOW/MID/HIGH)
│       ├── BitmapUtils.kt           # Downscale early, safe recycle
│       └── DispatcherModule.kt      # IO/Default/Main dispatchers
└── ui/
    ├── home/HomeFragment + ViewModel
    ├── scanner/ScannerFragment + ViewModel + EdgeOverlayView (custom View)
    ├── editor/ (Phase 2)
    └── viewer/ (Phase 3)
```

## Data Flow

```
CameraX Preview (640x480) ──► EdgeOverlayView (live quad)
        │
        ▼ Shutter / Auto-capture (stable + sharp)
Capture 2MP ──► OpenCvHelper.processPage (warp + Magic/B&W/Lighten, max 1280-2000px by tier)
        │
        ▼ HybridOcrEngine (Tesseract amh+eng offline → ML Kit online boost)
OCR text (NFC normalized, Ethiopic-aware)
        │
        ▼ PdfGenerator (A4, JPEG Q70-85 by tier, invisible text layer, Noto Ethiopic)
PDF file ──► Room (DocumentEntity) ──► Home grid + Search + Share
```

## Speed Rules (Low-End Magic)

- OpenCV loaded only in ScannerFragment, never in Application.
- Analysis at 640x480, KEEP_ONLY_LATEST, single executor.
- Process at DeviceTier.maxProcessingEdge (1280 low / 1600 mid / 2000 high).
- All CPU work on Dispatchers.Default, DB/files on IO, UI on Main.
- R8 full mode, App Bundle, vector drawables, WebP, split tessdata download.

## Future Module Split (after V1)

When the app grows, split into `:core:camera`, `:core:vision`, `:core:ocr`,
`:core:pdf`, `:core:db`, `:feature:scanner` etc. Packages already mirror this.
