package cc.moon.internet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/**
 * Camera QR scanner. Full-screen preview, decodes with ZXing (already bundled for drawing QRs)
 * and returns the text — the caller feeds it into the normal import path.
 */
class ScanQrActivity : ComponentActivity() {

    private val reader = QRCodeReader()
    private val executor = Executors.newSingleThreadExecutor()

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else finishWith(null)
    }

    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        // Preview + an aiming frame on top: a bare camera gives the user nothing to point at.
        setContentView(android.widget.FrameLayout(this).apply {
            addView(previewView, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(ScannerOverlay(this@ScanQrActivity), android.widget.FrameLayout.LayoutParams(-1, -1))
        })

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse { finishWith(null); return@addListener }

            val preview = androidx.camera.core.Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyse) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { finishWith(null) }
        }, ContextCompat.getMainExecutor(this))
    }

    /** One frame → luminance plane → ZXing. Anything that fails to decode is simply the next frame. */
    private fun analyse(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            val source = PlanarYUVLuminanceSource(
                bytes, plane.rowStride, image.height,
                0, 0, image.width.coerceAtMost(plane.rowStride), image.height, false,
            )
            val result = runCatching {
                reader.decode(
                    BinaryBitmap(HybridBinarizer(source)),
                    mapOf(DecodeHintType.TRY_HARDER to true),
                )
            }.getOrNull()
            reader.reset()
            result?.text?.takeIf { it.isNotBlank() }?.let { text ->
                runOnUiThread { finishWith(text) }
            }
        } catch (_: Throwable) {
            // a frame we cannot read is not an error worth reporting
        } finally {
            image.close()
        }
    }

    private fun finishWith(text: String?) {
        setResult(if (text == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                  Intent().putExtra("text", text))
        finish()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

/** Launches [ScanQrActivity] and hands back the decoded text, or null if the user backed out. */
class ScanQrContract : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit) = Intent(context, ScanQrActivity::class.java)
    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra("text") else null
}
