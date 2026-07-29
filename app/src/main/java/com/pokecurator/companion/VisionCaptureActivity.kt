package com.pokecurator.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * Tiny translucent permission trampoline for Android's one-time screen-capture consent.
 * The existing transfer-helper overlay never uses this activity.
 */
class VisionCaptureActivity : Activity() {

    private val projectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            toast("Grant Display over other apps before starting Grid Assist.")
            finish()
            return
        }

        @Suppress("DEPRECATION")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Android, retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode == RESULT_OK && data != null) {
            VisionOverlayService.start(this, resultCode, data)
            toast("Grid Assist started — open your Pokémon collection.")
            moveTaskToBack(true)
        } else {
            toast("Screen capture permission is required for OCR Grid Assist.")
        }
        finish()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_CAPTURE = 701
    }
}
