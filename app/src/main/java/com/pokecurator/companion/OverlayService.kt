package com.pokecurator.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Foreground service that floats the cleanup plan above Pokemon GO / PGSharp.
 * A draggable bubble expands into a panel showing the current species, the
 * keepers to favorite, and the two in-game searches (with copy buttons) plus
 * Prev / Skip / Done navigation. Progress is stored per-species so you can stop
 * anytime and resume.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var bubble: View? = null
    private var panel: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private var plan: Plan? = null
    private var index = 0
    private var loadError: String? = null
    private val spriteCache = HashMap<Int, Bitmap>()

    // --- lifecycle -------------------------------------------------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        showBubble()
        refreshPlan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
        removeView(bubble); bubble = null
        removeView(panel); panel = null
    }

    // --- data ------------------------------------------------------------
    private fun refreshPlan() {
        val url = Prefs.planUrl(this) ?: run {
            loadError = "No sync URL set."; render(); return
        }
        scope.launch {
            when (val r = Api.fetchPlan(url)) {
                is Api.Result.Ok -> {
                    plan = r.plan
                    loadError = null
                    index = firstUnfinishedIndex()
                    render()
                }
                is Api.Result.Error -> { loadError = r.message; render() }
            }
        }
    }

    private fun visibleSteps(): List<Step> = plan?.steps ?: emptyList()

    private fun firstUnfinishedIndex(): Int {
        val done = Prefs.done(this)
        val steps = visibleSteps()
        val i = steps.indexOfFirst { !done.contains(it.species) }
        return if (i >= 0) i else 0
    }

    // --- bubble ----------------------------------------------------------
    private fun showBubble() {
        if (bubble != null) return
        removeView(panel); panel = null
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)
        bubbleParams = baseParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 240
        }
        // Clip the icon to a circle so the bubble reads as the app icon.
        val bub = v.findViewById<View>(R.id.bubbleButton)
        bub.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        bub.clipToOutline = true
        makeDraggable(
            bub, bubbleParams,
            onClick = { showPanel() },
            onLongClick = {
                Toast.makeText(this, "Overlay closed", Toast.LENGTH_SHORT).show()
                stopSelf()
            },
        )
        wm.addView(v, bubbleParams)
        bubble = v
    }

    // --- panel -----------------------------------------------------------
    private fun showPanel() {
        removeView(bubble); bubble = null
        if (panel != null) return
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        panelParams = baseParams().apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 120
            alpha = 0.94f  // let the game show through a touch
            // Get notified of taps outside the panel so we can collapse to the
            // bubble. The touch still passes through to whatever is underneath.
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        // Tap anywhere outside the panel (e.g. on the game) shrinks it back to
        // the bubble - clears the screen instantly, no timer.
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) { showBubble(); true } else false
        }
        v.findViewById<View>(R.id.panelHeader).let { makeDraggable(it, panelParams) }
        v.findViewById<View>(R.id.minimize).setOnClickListener { showBubble() }
        v.findViewById<View>(R.id.close).setOnClickListener {
            Toast.makeText(this, "Overlay closed", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        v.findViewById<View>(R.id.refresh).setOnClickListener { refreshPlan() }
        val orderToggle = v.findViewById<TextView>(R.id.orderToggle)
        orderToggle.text = orderLabel()
        orderToggle.setOnClickListener {
            val next = if (Prefs.order(this) == "recent") "impact" else "recent"
            Prefs.setOrder(this, next)
            orderToggle.text = orderLabel()
            Toast.makeText(
                this,
                if (next == "recent") "Newest catches first" else "Most space freed first",
                Toast.LENGTH_SHORT,
            ).show()
            index = 0
            refreshPlan()
        }
        v.findViewById<View>(R.id.prev).setOnClickListener { goPrev() }
        v.findViewById<View>(R.id.skip).setOnClickListener { goNext() }
        v.findViewById<View>(R.id.done).setOnClickListener { markDone() }
        v.findViewById<View>(R.id.copySpecies).setOnClickListener {
            currentStep()?.let { copy(it.searchSpecies) }
        }
        v.findViewById<View>(R.id.copyTransfer).setOnClickListener {
            currentStep()?.let { copy(it.searchTransfer) }
        }
        wm.addView(v, panelParams)
        panel = v
        render()
    }

    private fun currentStep(): Step? = visibleSteps().getOrNull(index)

    private fun orderLabel(): String =
        if (Prefs.order(this) == "recent") "\uD83D\uDD50 Newest" else "\uD83D\uDCCA Impact"

    // Small species sprite from the same PokeAPI CDN the web app uses, keyed by
    // national dex. Cached in memory; guarded so a late download doesn't paint
    // onto a species you've already navigated past.
    private fun loadSprite(dex: Int, into: ImageView) {
        spriteCache[dex]?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val u = URL("https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/$dex.png")
                    val c = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000; readTimeout = 5000
                    }
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp != null) {
                spriteCache[dex] = bmp
                if (panel != null && currentStep()?.dex == dex) into.setImageBitmap(bmp)
            }
        }
    }

    private fun render() {
        val v = panel ?: return
        val title = v.findViewById<TextView>(R.id.title)
        val sub = v.findViewById<TextView>(R.id.subtitle)
        val counts = v.findViewById<TextView>(R.id.counts)
        val searchSpecies = v.findViewById<TextView>(R.id.searchSpecies)
        val searchTransfer = v.findViewById<TextView>(R.id.searchTransfer)
        val icon = v.findViewById<ImageView>(R.id.speciesIcon)
        val zones = v.findViewById<LinearLayout>(R.id.zones)
        val scroll = v.findViewById<ScrollView>(R.id.zonesScroll)
        zones.removeAllViews()
        icon.setImageDrawable(null)
        // Reset to natural height; capped again below once repopulated.
        scroll.layoutParams = scroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }

        if (loadError != null) {
            title.text = "Couldn't load plan"
            sub.text = loadError
            counts.text = ""
            searchSpecies.text = ""
            searchTransfer.text = ""
            return
        }
        val steps = visibleSteps()
        if (steps.isEmpty()) {
            title.text = "Nothing to clean up"
            sub.text = "Sync after your next catches and check back."
            counts.text = ""
            searchSpecies.text = ""
            searchTransfer.text = ""
            return
        }
        val done = Prefs.done(this)
        val step = steps.getOrNull(index) ?: steps.first().also { index = 0 }
        val doneCount = steps.count { done.contains(it.species) }

        title.text = step.species + (step.dex?.let { "  #${it.toString().padStart(4, '0')}" } ?: "")
        step.dex?.let { loadSprite(it, icon) }
        sub.text = "$doneCount of ${steps.size} species done \u00b7 ${plan?.favoritePct ?: 0}% favorited"
        counts.text = "Own ${step.owned}   \u00b7   keep ${step.keep}   \u00b7   transfer ${step.transferCount}"
        searchSpecies.text = step.searchSpecies
        searchTransfer.text = step.searchTransfer

        // Multi-form species (Flabebe colors, Unown letters, ...): the in-game
        // search can't tell the forms apart, so a blind select-all would grab
        // other forms too. Warn and steer the user to the listed ones only.
        val transferLabel = v.findViewById<TextView>(R.id.transferLabel)
        val transferWarn = v.findViewById<TextView>(R.id.transferWarn)
        if (step.searchAmbiguous) {
            transferLabel.text = "\u2461 Then search \u2013 transfer ONLY the ones listed above:"
            transferWarn.text =
                "\u26A0 This search also matches other forms/colors of ${step.searchSpecies}. " +
                "Don't select-all \u2013 pick just the CP/IV shown in the Transfer list, " +
                "or favorite every form's keepers first."
            transferWarn.visibility = View.VISIBLE
        } else {
            transferLabel.text = "\u2461 Then search, select all, Transfer:"
            transferWarn.visibility = View.GONE
        }

        addZone(zones, "\u2b50 Favorite these FIRST", step.promote, 0xFF1E7D34.toInt())
        addZone(zones, "\uD83D\uDD0E Review (favorited but outclassed)", step.review, 0xFFC79100.toInt())
        addZone(zones, "\uD83E\uDD1D Trade-worthy \u2013 don't transfer", step.trade, 0xFF3F7DF0.toInt())
        addZone(zones, "\uD83D\uDDD1\uFE0F Transfer", step.transfer, 0xFFB03A2E.toInt())

        // Keep the panel compact: cap the list area at ~38% of the screen so it
        // scrolls instead of stretching down over the game.
        val cap = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        scroll.post {
            val content = scroll.getChildAt(0)?.height ?: 0
            scroll.layoutParams = scroll.layoutParams.apply {
                height = if (content > cap) cap else ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    private fun addZone(parent: LinearLayout, header: String, items: List<Specimen>, color: Int) {
        if (items.isEmpty()) return
        val h = TextView(this).apply {
            text = "$header  (${items.size})"
            setTextColor(color)
            textSize = 13f
            setPadding(0, dp(8), 0, dp(2))
        }
        parent.addView(h)
        for (s in items) {
            val row = TextView(this).apply {
                text = s.line() + (if (s.why.isNotEmpty()) "   \u00b7 ${s.why}" else "")
                setTextColor(0xFFE7EBF0.toInt())
                textSize = 12f
                setPadding(dp(8), dp(1), 0, dp(1))
            }
            parent.addView(row)
        }
    }

    // --- navigation ------------------------------------------------------
    private fun markDone() {
        val step = currentStep() ?: return
        val done = Prefs.done(this)
        done.add(step.species)
        Prefs.setDone(this, done)
        goNext()
    }

    private fun goNext() {
        val steps = visibleSteps()
        if (steps.isEmpty()) return
        val done = Prefs.done(this)
        val next = (index + 1 until steps.size).firstOrNull { !done.contains(steps[it].species) }
            ?: (index + 1 until steps.size).firstOrNull()
        index = next ?: index
        render()
    }

    private fun goPrev() {
        if (index > 0) index--
        render()
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("search", text))
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    // --- window helpers --------------------------------------------------
    private fun baseParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun makeDraggable(
        handle: View,
        params: WindowManager.LayoutParams,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ) {
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false
        var longFired = false
        val longPress = Runnable {
            if (!moved) { longFired = true; onLongClick?.invoke() }
        }
        handle.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false; longFired = false
                    if (onLongClick != null) v.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX; val dy = e.rawY - touchY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                        moved = true
                        v.removeCallbacks(longPress)
                    }
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    (bubble ?: panel)?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && !longFired) onClick?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeView(v: View?) {
        if (v != null && v.isAttachedToWindow) runCatching { wm.removeView(v) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // --- foreground notification ----------------------------------------
    private fun buildNotification(): Notification {
        val channelId = "overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Cleanup overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, channelId)
            .setContentTitle("PokeCurator cleanup overlay")
            .setContentText("Floating above Pokemon GO")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val LONG_PRESS_MS = 500L
        fun start(ctx: Context) {
            val i = Intent(ctx, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }
}
