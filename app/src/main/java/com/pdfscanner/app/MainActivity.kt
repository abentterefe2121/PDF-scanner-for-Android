package com.pdfscanner.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.pdfscanner.app.core.common.BitmapUtils
import com.pdfscanner.app.core.common.DeviceTier
import com.pdfscanner.app.core.common.ScanSession
import com.pdfscanner.app.core.common.SettingsPrefs
import com.pdfscanner.app.core.db.DocumentDao
import com.pdfscanner.app.core.db.DocumentEntity
import com.pdfscanner.app.core.pdf.PdfViewer
import com.pdfscanner.app.core.vision.OpenCvHelper
import com.pdfscanner.app.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Single-Activity host (Views + Navigation Fragment).
 * Supports receiving images & PDFs via Android Share / Open With intents.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject lateinit var openCv: OpenCvHelper
    @Inject lateinit var deviceTier: DeviceTier
    @Inject lateinit var dao: DocumentDao
    @Inject lateinit var prefs: SettingsPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController
        setupActionBarWithNavController(navController)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val type = intent.type ?: ""

        when {
            (action == Intent.ACTION_SEND || action == Intent.ACTION_VIEW) && type == "application/pdf" -> {
                val uri = getParcelableUri(intent) ?: intent.data ?: return
                importSharedPdf(uri)
            }
            action == Intent.ACTION_SEND && type.startsWith("image/") -> {
                val uri = getParcelableUri(intent) ?: return
                importSharedImages(listOf(uri))
            }
            action == Intent.ACTION_SEND_MULTIPLE && type.startsWith("image/") -> {
                val uris = getParcelableUriList(intent)
                if (uris.isNotEmpty()) importSharedImages(uris)
            }
        }
    }

    private fun importSharedImages(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.Default) {
            val filter = prefs.defaultFilter.first()
            val dir = File(cacheDir, "scans")
            var added = 0
            for (uri in uris.take(20)) {
                val bmp = BitmapUtils.decodeSampledUri(this@MainActivity, uri, deviceTier.maxProcessingEdge)
                    ?: continue
                val bw = bmp.width
                val bh = bmp.height
                val quad = openCv.detectDocumentQuad(bmp)
                val proc = openCv.processPage(bmp, filter, quad, bw, bh)
                val rawPath = ScanSession.saveBitmap(dir, "raw", bmp, 92)
                val procPath = ScanSession.saveBitmap(dir, "page", proc, deviceTier.jpegQuality)
                BitmapUtils.safeRecycle(bmp)
                BitmapUtils.safeRecycle(proc)
                if (rawPath != null && procPath != null) {
                    ScanSession.addPage(
                        ScanSession.PageEntry(
                            rawPath = rawPath,
                            processedPath = procPath,
                            quad = quad,
                            quadWidth = bw,
                            quadHeight = bh
                        )
                    )
                    added++
                }
            }
            if (added > 0) {
                withContext(Dispatchers.Main) {
                    navController.navigate(R.id.editorFragment)
                }
            }
        }
    }

    private fun importSharedPdf(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val pdfDir = File(filesDir, "pdfs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dest = File(pdfDir, "imported_$stamp.pdf")
            val ok = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                dest.exists() && dest.length() > 0
            } catch (_: Exception) {
                false
            }
            if (!ok) return@launch
            val pages = maxOf(PdfViewer.pageCount(dest), 1)
            val id = dao.insert(
                DocumentEntity(
                    title = "${getString(R.string.imported_pdf)} $stamp",
                    pdfPath = dest.absolutePath,
                    pageCount = pages
                )
            )
            withContext(Dispatchers.Main) {
                val args = Bundle().apply { putLong("documentId", id) }
                navController.navigate(R.id.viewerFragment, args)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableUriList(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
