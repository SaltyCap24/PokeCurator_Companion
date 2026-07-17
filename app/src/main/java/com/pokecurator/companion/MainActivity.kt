package com.pokecurator.companion

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pokecurator.companion.databinding.ActivityMainBinding

/**
 * Setup screen: paste the PokeCurator sync URL, grant "Display over other apps",
 * then launch the floating cleanup overlay.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.syncUrl.setText(Prefs.syncUrl(this))

        b.saveUrl.setOnClickListener {
            val url = b.syncUrl.text.toString().trim()
            if (Uri.parse(url).getQueryParameter("token").isNullOrBlank()) {
                toast("That URL is missing its ?token=\u2026 part.")
            } else {
                Prefs.setSyncUrl(this, url)
                toast("Saved.")
            }
        }

        b.grantOverlay.setOnClickListener {
            if (!canDrawOverlays()) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                toast("Overlay permission already granted.")
            }
        }

        b.startOverlay.setOnClickListener {
            when {
                Prefs.planUrl(this) == null ->
                    toast("Paste and save your sync URL first.")
                !canDrawOverlays() ->
                    toast("Grant \"Display over other apps\" first.")
                else -> {
                    OverlayService.start(this)
                    toast("Overlay started \u2013 open Pokemon GO.")
                    moveTaskToBack(true)
                }
            }
        }

        b.stopOverlay.setOnClickListener {
            OverlayService.stop(this)
            toast("Overlay stopped.")
        }

        b.resetProgress.setOnClickListener {
            Prefs.clearDone(this)
            toast("Cleanup progress reset.")
        }

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /**
     * When opened via the pokecurator://overlay deep link (the "Start Transfer
     * Overlay" button on pokecurator.com), auto-start the overlay if it's ready;
     * otherwise fall through to the setup screen with a hint.
     */
    private fun handleLaunchIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "pokecurator" || data.host != "overlay") return
        when {
            Prefs.planUrl(this) == null ->
                toast("Paste and save your sync URL first.")
            !canDrawOverlays() ->
                toast("Grant \"Display over other apps\" first.")
            else -> {
                OverlayService.start(this)
                toast("Overlay started \u2013 open Pokemon GO.")
                moveTaskToBack(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.overlayStatus.text =
            if (canDrawOverlays()) "\u2705 Overlay permission granted"
            else "\u274C Overlay permission needed"
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
