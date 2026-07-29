package com.pokecurator.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun String.normalizedSpeciesKey(): String =
    lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

/**
 * Experimental OCR grid assistant. This is intentionally separate from OverlayService.
 * Phase one proves safe screen capture + OCR and never displays a transfer decision.
 */
class VisionOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var debugOverlay: GridDebugOverlayView? = null
    private var statusView: TextView? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizing = AtomicBoolean(false)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var recommendationIndex = RecommendationIndex.empty("Grid Assist: loading cleanup plan...")
    private var lastFrameStartedAt = 0L
    private var lastStatusMessage: String? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            projection = null
            setStatus("Grid Assist: screen capture stopped")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Grid Assist is starting"))
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addDebugOverlay()
        addStatusOverlay()
        refreshPlan()
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
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        scope.coroutineContext[Job]?.cancel()
        recognizer.close()
        statusView?.let { runCatching { windowManager.removeView(it) } }
        debugOverlay?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    private fun refreshPlan() {
        val url = Prefs.planUrl(this) ?: run {
            recommendationIndex = RecommendationIndex.empty("Grid Assist: sync URL missing")
            setStatus("Grid Assist: sync URL missing")
            return
        }
        scope.launch {
            when (val result = Api.fetchPlan(url)) {
                is Api.Result.Ok -> {
                    recommendationIndex = RecommendationIndex.fromPlan(result.plan)
                    setStatus("Grid Assist: plan loaded (${recommendationIndex.matchableCount} matchable Pokemon)")
                }
                is Api.Result.Error -> {
                    recommendationIndex = RecommendationIndex.empty("Grid Assist: plan load failed")
                    setStatus("Grid Assist: plan load failed - ${result.message}")
                }
            }
        }
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
        projection?.registerCallback(projectionCallback, null)
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
        setStatus("Grid Assist: looking for collection text...")
    }

    private fun processLatestFrame(reader: ImageReader, width: Int, height: Int) {
        val image = reader.acquireLatestImage() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameStartedAt < OCR_INTERVAL_MS) {
            image.close()
            return
        }
        if (!recognizing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameStartedAt = now

        val plane = image.planes.first()
        val rowPadding = plane.rowStride - plane.pixelStride * width
        val bitmapWidth = width + rowPadding / plane.pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        image.close()

        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        if (cropped !== bitmap) bitmap.recycle()

        recognizer.process(InputImage.fromBitmap(cropped, 0))
            .addOnSuccessListener { result -> handleOcrResult(result, width, height) }
            .addOnFailureListener { setStatus("Grid Assist OCR error - no recommendations shown") }
            .addOnCompleteListener {
                cropped.recycle()
                recognizing.set(false)
            }
    }

    private fun handleOcrResult(result: Text, width: Int, height: Int) {
        val allLines = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val normalized = line.text.trim()
                if (normalized.isBlank()) null else OcrLine(normalized, box)
            }
            .sortedWith(compareBy<OcrLine> { it.centerY }.thenBy { it.centerX })
        val allElements = result.textBlocks
            .flatMap { it.lines }
            .flatMap { it.elements }
            .mapNotNull { element ->
                val box = element.boundingBox ?: return@mapNotNull null
                val normalized = element.text.trim()
                if (normalized.isBlank()) null else OcrLine(normalized, box)
            }
            .sortedWith(compareBy<OcrLine> { it.centerY }.thenBy { it.centerX })
        val cpLines = allLines.filter { it.text.startsWith("CP", ignoreCase = true) }

        val rows = clusterRows(cpLines, height)
        val positionedTiles = cpLines.map { line ->
            val column = ((line.centerX * GRID_COLUMNS) / width).toInt().coerceIn(0, GRID_COLUMNS - 1)
            val row = rows.indexOfFirst { abs(it - line.centerY) <= rowClusterThreshold(height) }
                .takeIf { it >= 0 } ?: 0
            PositionedTile(row, column, line)
        }.sortedWith(compareBy<PositionedTile> { it.row }.thenBy { it.column })

        val tiles = positionedTiles.mapIndexed { index, positioned ->
            val cell = estimateCellBounds(positioned.column, positioned.row, rows, width, height)
            val tileElements = allElements
                .filter { cellContainsTightly(cell, it.centerX, it.centerY) }
                .filterNot { it.text.startsWith("CP", ignoreCase = true) && centerDistance(it, positioned.cpLine) < 60f }
                .sortedWith(compareBy<OcrLine> { it.centerY }.thenBy { it.centerX })
            buildTileDebug(index, positioned.cpLine, tileElements, cell)
        }

        debugOverlay?.post { debugOverlay?.setTiles(tiles) }
        val state = if (cpLines.isNotEmpty()) {
            val visibleIcons = tiles.mapNotNull { it.actionBadge }
            val summary = visibleIcons.groupingBy { it }.eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(" ") { "${it.key}:${it.value}" }
                .ifBlank { "none" }
            "Grid: ${visibleIcons.size}/${tiles.size} ($summary)"
        } else {
            recommendationIndex.statusMessage
        }
        setStatus(state)
        statusView?.post { statusView?.visibility = if (cpLines.isNotEmpty()) View.GONE else View.VISIBLE }
    }

    private fun buildTileDebug(index: Int, cpLine: OcrLine, tileElements: List<OcrLine>, cell: RectF): TileDebug {
        val reading = extractTileReading(cpLine, tileElements)
        val match = recommendationIndex.match(reading)
        val species = reading.species ?: "?"
        val cp = reading.cp?.let { "CP$it" } ?: cpLine.text.cleanForBadgeLine()
        val iv = reading.ivPercent?.let { " ${it}%" }.orEmpty()
        return TileDebug(
            bounds = cell,
            anchor = RectF(cpLine.bounds),
            actionBadge = match.badge,
            labelLines = listOf("${index + 1}:${match.badge} $species $cp$iv".cleanForBadgeLine()),
        )
    }

    private fun extractTileReading(cpLine: OcrLine, tileElements: List<OcrLine>): TileReading {
        val cp = cpLine.text.filter { it.isDigit() }.toIntOrNull()
        val speciesPercent = speciesPercentCandidates(tileElements)
            .maxWithOrNull(compareBy<SpeciesPercent> { it.species.length }.thenBy { it.ivPercent })
        return TileReading(
            species = speciesPercent?.species,
            cp = cp,
            ivPercent = speciesPercent?.ivPercent,
        )
    }

    private fun speciesPercentCandidates(tileElements: List<OcrLine>): List<SpeciesPercent> {
        val candidates = ArrayList<SpeciesPercent>()
        tileElements.mapNotNullTo(candidates) { parseSpeciesPercent(it.text) }

        val rows = clusterTextRows(tileElements)
        rows.forEach { row ->
            val parts = row.sortedBy { it.centerX }.map { it.text.cleanForBadgePart() }
            val joinedTight = parts.joinToString("")
            val joinedSpaced = parts.joinToString(" ")
            parseSpeciesPercent(joinedTight)?.let { candidates.add(it) }
            parseSpeciesPercent(joinedSpaced)?.let { candidates.add(it) }

            for (start in parts.indices) {
                for (end in start + 1..min(parts.lastIndex, start + 3)) {
                    parseSpeciesPercent(parts.subList(start, end + 1).joinToString(""))?.let { candidates.add(it) }
                    parseSpeciesPercent(parts.subList(start, end + 1).joinToString(" "))?.let { candidates.add(it) }
                }
            }
        }
        return candidates.distinct()
    }

    private fun clusterTextRows(elements: List<OcrLine>): List<List<OcrLine>> {
        val rows = ArrayList<MutableList<OcrLine>>()
        elements.sortedBy { it.centerY }.forEach { element ->
            val row = rows.firstOrNull { existing ->
                existing.any { abs(it.centerY - element.centerY) <= max(16f, element.bounds.height() * 0.9f) }
            }
            if (row == null) rows.add(arrayListOf(element)) else row.add(element)
        }
        return rows
    }

    private fun parseSpeciesPercent(text: String): SpeciesPercent? {
        val compact = text.cleanForBadgePart()
            .replace("@", "")
            .replace(Regex("[^A-Za-z0-9. '-]"), "")
        val match = Regex("^([A-Za-z][A-Za-z. '-]{1,22})(100|[1-9]?\\d)$").find(compact) ?: return null
        val species = match.groupValues[1].trim()
        val ivPercent = match.groupValues[2].toIntOrNull() ?: return null
        if (species.equals("CP", ignoreCase = true)) return null
        return SpeciesPercent(species, ivPercent)
    }

    private fun String.cleanForBadgePart(): String = replace(Regex("\\s+"), " ").trim()

    private fun String.cleanForBadgeLine(): String = cleanForBadgePart().take(MAX_TILE_DEBUG_CHARS)

    private fun centerDistance(a: OcrLine, b: OcrLine): Float =
        abs(a.centerX - b.centerX) + abs(a.centerY - b.centerY)

    private fun cellContainsTightly(cell: RectF, x: Float, y: Float): Boolean {
        val tightened = RectF(cell).apply {
            inset(cell.width() * 0.04f, -cell.height() * 0.04f)
        }
        return tightened.contains(x, y)
    }

    private fun clusterRows(lines: List<OcrLine>, height: Int): List<Float> {
        val threshold = rowClusterThreshold(height)
        return lines.fold(emptyList<Float>()) { rows, line ->
            val matchingIndex = rows.indexOfFirst { abs(it - line.centerY) <= threshold }
            if (matchingIndex == -1) {
                rows + line.centerY
            } else {
                rows.toMutableList().also { it[matchingIndex] = (it[matchingIndex] + line.centerY) / 2f }
            }
        }.sorted()
    }

    private fun rowClusterThreshold(height: Int): Float = max(56f, height * 0.045f)

    private fun estimateCellBounds(
        column: Int,
        row: Int,
        rows: List<Float>,
        width: Int,
        height: Int,
    ): RectF {
        val cellWidth = width / GRID_COLUMNS.toFloat()
        val centerX = cellWidth * column + cellWidth / 2f
        val rowGap = if (rows.size > 1) {
            rows.zipWithNext { a, b -> b - a }.average().toFloat().coerceIn(160f, height * 0.28f)
        } else {
            height * 0.22f
        }
        val centerY = rows.getOrNull(row) ?: (height * 0.28f)
        val nextCenterY = rows.getOrNull(row + 1)
        val top = (centerY - rowGap * 0.16f).coerceAtLeast(0f)
        val bottom = if (nextCenterY != null) {
            (nextCenterY - rowGap * 0.12f).coerceAtMost(height.toFloat())
        } else {
            (centerY + rowGap * 0.84f).coerceAtMost(height.toFloat())
        }
        return RectF(
            max(0f, centerX - cellWidth * 0.45f),
            top,
            min(width.toFloat(), centerX + cellWidth * 0.45f),
            bottom,
        )
    }

    private fun addDebugOverlay() {
        debugOverlay = GridDebugOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(debugOverlay, params)
    }

    private fun addStatusOverlay() {
        statusView = TextView(this).apply {
            text = "Grid Assist: waiting for capture permission"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC20242B.toInt())
            setPadding(14, 8, 14, 8)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
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

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun setStatus(message: String) {
        if (message == lastStatusMessage) return
        lastStatusMessage = message
        statusView?.post { statusView?.text = message }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PokeCurator Grid Assist")
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PokeCurator Grid Assist",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private data class OcrLine(val text: String, val bounds: Rect) {
        val centerX: Float get() = bounds.exactCenterX()
        val centerY: Float get() = bounds.exactCenterY()
    }

    private data class PositionedTile(
        val row: Int,
        val column: Int,
        val cpLine: OcrLine,
    )

    private data class SpeciesPercent(
        val species: String,
        val ivPercent: Int,
    )

    private data class TileReading(
        val species: String?,
        val cp: Int?,
        val ivPercent: Int?,
    )

    private data class IdentityKey(
        val species: String,
        val cp: Int,
        val ivPercent: Int,
    )

    private data class CpIvKey(
        val cp: Int,
        val ivPercent: Int,
    )

    private enum class RecommendationAction(val badge: String) {
        Keep("K"),
        Trade("T"),
        Transfer("X"),
    }

    private data class RecommendationCandidate(
        val action: RecommendationAction,
    )

    private data class RecommendationMatch(val badge: String)

    private data class RecommendationIndex(
        val candidates: Map<IdentityKey, List<RecommendationCandidate>>,
        val cpIvCandidates: Map<CpIvKey, List<RecommendationCandidate>>,
        val statusMessage: String,
    ) {
        val matchableCount: Int = candidates.values.sumOf { it.size }

        fun match(reading: TileReading): RecommendationMatch {
            val cp = reading.cp ?: return RecommendationMatch("?")
            val ivPercent = reading.ivPercent ?: return RecommendationMatch("?")
            val species = reading.species
            if (species != null) {
                val matches = candidates[IdentityKey(species.normalizedSpeciesKey(), cp, ivPercent)].orEmpty()
                if (matches.isEmpty()) return RecommendationMatch(RecommendationAction.Keep.badge)
                return matches.toRecommendationMatch()
            }

            val fallbackMatches = cpIvCandidates[CpIvKey(cp, ivPercent)].orEmpty()
            return fallbackMatches.toRecommendationMatch(requireMatch = true)
        }

        private fun List<RecommendationCandidate>.toRecommendationMatch(requireMatch: Boolean = false): RecommendationMatch {
            if (isEmpty()) return RecommendationMatch(if (requireMatch) "?" else RecommendationAction.Keep.badge)
            val actions = map { it.action }.distinct()
            return if (actions.size == 1) RecommendationMatch(actions.first().badge) else RecommendationMatch("AMB")
        }

        companion object {
            fun empty(message: String): RecommendationIndex = RecommendationIndex(emptyMap(), emptyMap(), message)

            fun fromPlan(plan: Plan): RecommendationIndex {
                val out = LinkedHashMap<IdentityKey, MutableList<RecommendationCandidate>>()
                val cpIvOut = LinkedHashMap<CpIvKey, MutableList<RecommendationCandidate>>()
                plan.steps.forEach { step ->
                    addSpecimens(out, cpIvOut, step.species, step.promote, RecommendationAction.Keep)
                    addSpecimens(out, cpIvOut, step.species, step.review, RecommendationAction.Keep)
                    addSpecimens(out, cpIvOut, step.species, step.trade, RecommendationAction.Trade)
                    addSpecimens(out, cpIvOut, step.species, step.transfer, RecommendationAction.Transfer)
                }
                return RecommendationIndex(out, cpIvOut, "Grid Assist: open the Pokemon collection grid")
            }

            private fun addSpecimens(
                out: MutableMap<IdentityKey, MutableList<RecommendationCandidate>>,
                cpIvOut: MutableMap<CpIvKey, MutableList<RecommendationCandidate>>,
                species: String,
                specimens: List<Specimen>,
                action: RecommendationAction,
            ) {
                specimens.forEach { specimen ->
                    val cp = specimen.cp ?: return@forEach
                    val ivPercent = specimen.iv?.toInt() ?: return@forEach
                    val candidate = RecommendationCandidate(action)
                    val key = IdentityKey(species.normalizedSpeciesKey(), cp, ivPercent)
                    out.getOrPut(key) { ArrayList() }.add(candidate)
                    cpIvOut.getOrPut(CpIvKey(cp, ivPercent)) { ArrayList() }.add(candidate)
                }
            }
        }
    }

    private data class TileDebug(
        val bounds: RectF,
        val anchor: RectF,
        val actionBadge: String?,
        val labelLines: List<String>,
    )

    private class GridDebugOverlayView(context: Context) : View(context) {
        private val tiles = mutableListOf<TileDebug>()
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            color = Color.WHITE
        }
        private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 44f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        fun setTiles(nextTiles: List<TileDebug>) {
            tiles.clear()
            tiles.addAll(nextTiles)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            tiles.forEach { tile ->
                val badge = tile.actionBadge ?: return@forEach
                val marker = markerFor(badge) ?: return@forEach
                val radius = (canvas.width / 24f).coerceIn(38f, 58f)
                val cx = (tile.anchor.right + radius + 18f)
                    .coerceIn(radius + 8f, canvas.width - radius - 8f)
                val cy = tile.anchor.centerY()
                    .coerceIn(radius + 8f, canvas.height - radius - 8f)
                markerPaint.color = Color.WHITE
                canvas.drawCircle(cx, cy, radius + 7f, markerPaint)
                markerPaint.color = marker.color
                canvas.drawCircle(cx, cy, radius, markerPaint)
                val textY = cy - (markerTextPaint.descent() + markerTextPaint.ascent()) / 2f
                canvas.drawText(marker.symbol, cx, textY, markerTextPaint)
            }
        }

        private fun markerFor(badge: String): Marker? = when (badge) {
            "K" -> Marker("✓", Color.argb(235, 26, 145, 72))
            "X" -> Marker("X", Color.argb(235, 210, 42, 42))
            "T" -> Marker("T", Color.argb(235, 235, 130, 24))
            "AMB", "?" -> Marker("?", Color.argb(215, 85, 90, 100))
            else -> null
        }

        private data class Marker(val symbol: String, val color: Int)
    }

    companion object {
        private const val ACTION_START = "com.pokecurator.companion.vision.START"
        private const val ACTION_STOP = "com.pokecurator.companion.vision.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL_ID = "pokecurator_grid_assist"
        private const val NOTIFICATION_ID = 202
        private const val GRID_COLUMNS = 3
        private const val OCR_INTERVAL_MS = 1_000L
        private const val MAX_TILE_DEBUG_CHARS = 28

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
