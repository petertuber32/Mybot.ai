package com.mybotai.bacboanalyzer.observe

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mybotai.bacboanalyzer.R

/**
 * Transparent activity whose ONLY job is to show the Android system
 * MediaProjection consent dialog ("Start recording?"). The projection
 * grant is single-use: the user explicitly authorizes every observation
 * session. Nothing is captured without this consent, and no capture
 * protection of any platform is bypassed (MediaProjection marks the
 * captured content itself).
 */
class ObservationConsentActivity : AppCompatActivity() {

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ObservationService::class.java)
                        .setAction(ObservationService.ACTION_START)
                        .putExtra(ObservationService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(ObservationService.EXTRA_DATA, data)
                )
            } else {
                ObservationBus.post {
                    it.copy(message = getString(R.string.obs_permission_denied))
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            consentLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            ObservationBus.post {
                it.copy(message = getString(R.string.obs_error_consent))
            }
            finish()
        }
    }
}
