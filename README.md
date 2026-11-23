# PDF Scanner for Android

Premium, advanced PDF scanner built for **low-end to flagship** — works like magic on Tecno / Infinix / Samsung A-series (2–3 GB RAM) and on high-end phones alike.

**Amharic-first OCR** (offline + online hybrid), **Kotlin Native + XML Views (no Compose)** for maximum speed, **Android 8+ (API 26)**.

> See [docs/STRATEGY.md](docs/STRATEGY.md) for full strategy and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for code structure.

## Why This Stack?

| Choice | Reason |
|---|---|
| **Kotlin Native** | Fastest camera/OpenCV/ML Kit access, smallest APK. What Adobe Scan & Microsoft Lens use. |
| **XML Views + ViewBinding (no Compose)** | Fastest cold start (<800 ms), lowest memory, best for custom camera overlay on 2 GB RAM. |
| **CameraX + OpenCV** | Live edge detection, perspective warp, Magic/B&W/Lighten filters. Analysis at 640×480, capture at 2 MP. |
| **Hybrid OCR: Tesseract (amh+eng) offline + ML Kit online** | ML Kit cannot read Ethiopic script. Tesseract reads Amharic offline; ML Kit boosts English. Dual-language for mixed ET documents. |
| **PdfDocument + Noto Sans Ethiopic** | Lightweight searchable PDFs, no heavy iText. Ethiopic font so Amharic text layer actually renders. |
| **Room + WorkManager** | Offline-first storage, Amharic-aware search, background PDF/OCR. |
| **DeviceTier adaptation** | Auto quality by RAM/cores: Low (1280px, Q70) → High (2000px, Q85). |

## Features (V1 — Full Scanner MVP)

- [x] Phase 0 scaffold: Gradle, Hilt, Room, CameraX, CI, bilingual UI (EN + አማርኛ)
- [ ] Live edge overlay + auto-capture (stable quad + sharpness)
- [ ] Capture + gallery import, multi-page, reorder/rotate
- [ ] Crop (perspective warp) + filters: Original / Magic / Lighten / B&W
- [ ] Amharic + English OCR, searchable PDF, copy text
- [ ] PDF viewer, share (WhatsApp/Telegram/Drive), folders/tags, search
- [ ] Dark/light theme, offline-first, no account required

## Build

Requires **Android Studio (Hedgehog+) + JDK 17**.

```bash
# Generate Gradle wrapper (once, if gradle-wrapper.jar is missing)
# Android Studio will do this automatically on first open.
# Or: gradle wrapper --gradle-version 8.7

chmod +x gradlew
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

CI (GitHub Actions) builds the debug APK on every push — see `.github/workflows/android.yml`.

## Project Structure

```
app/src/main/java/com/pdfscanner/app/
  core/camera, core/vision, core/ocr, core/pdf, core/db, core/common
  ui/home, ui/scanner (EdgeOverlayView), ui/editor, ui/viewer
app/src/main/res/  layouts (Views), values/ (EN), values-am/ (አማርኛ), navigation/
docs/  STRATEGY.md, ARCHITECTURE.md
```

## Roadmap

Phase 0 Setup → Phase 1 Scanner Core → Phase 2 Editor → Phase 3 OCR+PDF → Phase 4 Organize → Phase 5 Polish & Play Store beta. ~7–8 weeks to beta. See STRATEGY.md §7.

## License

Private — all rights reserved.
