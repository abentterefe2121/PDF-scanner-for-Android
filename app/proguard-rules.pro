# Keep OpenCV native bindings
-keep class org.opencv.** { *; }
# Keep Tesseract & Leptonica JNI
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
# Keep Room entities/DAOs
-keep class androidx.room.** { *; }
-keep class com.pdfscanner.app.core.db.** { *; }
# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class com.pdfscanner.app.PdfScannerApp { *; }
