package com.mybotai.bacboanalyzer.observe

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.SystemClock
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
 *
 * Authorization-flow guarantees (anti-loop):
 *  - the system dialog is launched at most ONCE per instance, and only
 *    from a single instance at a time (process-wide single-flight guard);
 *  - if the activity is recreated while the dialog is up (rotation, OEM
 *    theme change), it does NOT launch a second dialog — the
 *    ActivityResultRegistry re-delivers the pending result instead;
 *  - a denial or any failure posts an error message to the bus and stops.
 *    The dialog is NEVER re-opened automatically — the user must tap
 *    OBSERVAÇÃO again (or the screenshot button starts nothing).
 */
class ObservationConsentActivity : AppCompatActivity() {

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            clearInFlight()
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                try {
                    // Hand the single-use grant to the service exactly once.
                    // The service owns it for this session; this activity
                    // finishes immediately and is never reused.
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, ObservationService::class.java)
                            .setAction(ObservationService.ACTION_START)
                            .putExtra(ObservationService.EXTRA_RESULT_CODE, result.resultCode)
                            .putExtra(ObservationService.EXTRA_DATA, data)
                    )
                } catch (e: Exception) {
                    // e.g. ForegroundServiceStartNotAllowedException on some
                    // OEMs: show the real error instead of re-asking consent.
                    ObservationBus.post {
                        it.copy(
                            observing = false,
                            message = getString(R.string.obs_error_service, e.message ?: "")
                        )
                    }
                }
            } else {
                // Explicit refusal — not an error loop, a user decision.
                ObservationBus.post {
                    it.copy(observing = false, message = getString(R.string.obs_permission_denied))
                }
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreation while the dialog is up: the registry holds the
            // pending request and will deliver the result. Launching here
            // again would stack a second dialog (the reported bug).
            return
        }
        if (consentInFlight()) {
            // Another consent dialog is already on screen (double tap on
            // the overlay button). Do not stack a second one.
            finish()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            markInFlight()
            consentLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            clearInFlight()
            ObservationBus.post {
                it.copy(observing = false, message = getString(R.string.obs_error_consent))
            }
            finish()
        }
    }

    companion object {
        /** Guard window: an unanswered dialog older than this is considered abandoned. */
        private const val CONSENT_TIMEOUT_MS = 120_000L

        @Volatile
        private var inFlightSince: Long = 0L

        private fun consentInFlight(): Boolean =
            inFlightSince != 0L && (SystemClock.elapsedRealtime() - inFlightSince) < CONSENT_TIMEOUT_MS

        private fun markInFlight() {
            inFlightSince = SystemClock.elapsedRealtime()
        }

        private fun clearInFlight() {
            inFlightSince = 0L
        }
    }
}
