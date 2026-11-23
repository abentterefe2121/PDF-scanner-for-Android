# Keep OpenCV native bindings
-keep class org.opencv.** { *; }
# Keep Tesseract
-keep class com.googlecode.tesseract4android.** { *; }
# Keep Room entities/DAOs
-keep class androidx.room.** { *; }
-keep class com.pdfscanner.app.core.db.** { *; }
# Keep Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class com.pdfscanner.app.PdfScannerApp { *; }
