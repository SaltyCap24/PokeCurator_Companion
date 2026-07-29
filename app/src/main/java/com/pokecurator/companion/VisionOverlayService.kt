package com.pokecurator.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental OCR grid assistant. This is intentionally separate from OverlayService.
 * Phase one proves safe screen capture + OCR and never displays a transfer decision.
 */
class VisionOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var statusView: TextView? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizing = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Grid Assist is starting"))
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addStatusOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_START -> startProjection(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        projection?.stop()
        recognizer.close()
        statusView?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: run {
            setStatus("Grid Assist: capture permission missing")
            stopSelf()
            return
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader -> processLatestFrame(reader, width, height) }, null)
        virtualDisplay = projection?.createVirtualDisplay(
            "PokeCuratorGridAssist",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        setStatus("Grid Assist: looking for collection text…")
    }

    private fun processLatestFrame(reader: ImageReader, width: Int, height: Int) {
        val image = reader.acquireLatestImage() ?: return
        if (!recognizing.compareAndSet(false, true)) {
            image.close()
            return
        }

        val plane = image.planes.first()
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bitmapWidth = width + rowPadding / plane.pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        image.close()

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        if (cropped !== bitmap) bitmap.recycle()

        recognizer.process(InputImage.fromBitmap(cropped, 0))
            .addOnSuccessListener { result ->
                val cpCount = result.textBlocks
                    .flatMap { it.lines }
                    .count { it.text.trim().startsWith("CP", ignoreCase = true) }
                val state = if (cpCount > 0) {
                    "Grid Assist OCR: $cpCount visible CP labels • matching not enabled yet"
                } else {
                    "Grid Assist: open the Pokémon collection grid"
                }
                setStatus(state)
            }
            .addOnFailureListener { setStatus("Grid Assist OCR error — no recommendations shown") }
            .addOnCompleteListener {
                cropped.recycle()
                recognizing.set(false)
            }
    }

    private fun addStatusOverlay() {
        statusView = TextView(this).apply {
            text = "Grid Assist: waiting for capture permission"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC20242B.toInt())
            setPadding(20, 12, 20, 12)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 70
        }
        windowManager.addView(statusView, params)
    }

    private fun setStatus(message: String) {
        statusView?.post { statusView?.text = message }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PokéCurator Grid Assist")
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PokéCurator Grid Assist",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ACTION_START = "com.pokecurator.companion.vision.START"
        private const val ACTION_STOP = "com.pokecurator.companion.vision.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "pokecurator_grid_assist"
        private const val NOTIFICATION_ID = 202

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, VisionOverlayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VisionOverlayService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
