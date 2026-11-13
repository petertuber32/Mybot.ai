package com.mybotai.bacboanalyzer.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.game.GameState
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.observe.ObservationBus
import com.mybotai.bacboanalyzer.observe.ObservationConsentActivity
import com.mybotai.bacboanalyzer.observe.ObservationService
import com.mybotai.bacboanalyzer.stats.Analysis
import com.mybotai.bacboanalyzer.stats.Quality
import com.mybotai.bacboanalyzer.stats.StatsEngine
import com.mybotai.bacboanalyzer.ui.MainActivity
import kotlin.math.abs

/**
 * Floating window (bubble + compact panel) rendered with
 * WindowManager TYPE_APPLICATION_OVERLAY, shown only after the user
 * grants SYSTEM_ALERT_WINDOW in Android settings. The panel can be
 * dragged, resized (S/M/L), minimized back to a bubble and closed.
 *
 * 2.0: the panel also controls the continuous observation — OBSERVAÇÃO
 * ON/OFF, manual screenshot, table state, recognized dice, last rounds,
 * current interpretation, Bac Bo prior estimates, confidence level,
 * insufficient-data warnings and confirmation buttons for ambiguous reads.
 */
class FloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var expanded = false
    private var sizeIndex = 1
    private var posX = 48
    private var posY = 240
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dataListener = { renderIfExpanded() }
    private val busListener: (com.mybotai.bacboanalyzer.observe.ObservationUiState) -> Unit = {
        mainHandler.post { renderIfExpanded() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RoundStore.init(applicationContext)
        RoundStore.addListener(dataListener)
        ObservationBus.subscribe(busListener)
        createChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        if (bubbleView == null && panelView == null) showBubble()
        return START_STICKY
    }

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        mgr.createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: foreground service type must match the manifest
            // declaration ("specialUse" — the correct type for overlay tools;
            // "systemOverlay" does not exist in the Android SDK).
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
        }

    private fun showBubble() {
        removeViews()
        expanded = false
        val v = LayoutInflater.from(this).inflate(R.layout.view_overlay_bubble, null)
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)

        var downX = 0f
        var downY = 0f
        var moved = false
        v.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = posX + dx.toInt()
                    params.y = posY + dy.toInt()
                    safeUpdate(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    posX = params.x
                    posY = params.y
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
        bubbleView = v
        safeAdd(v, params)
    }

    private fun togglePanel() {
        if (expanded) showBubble() else showPanel()
    }

    private fun showPanel() {
        removeViews()
        expanded = true
        val v = LayoutInflater.from(this).inflate(R.layout.view_overlay_panel, null)
        val params = overlayParams(dp(PANEL_WIDTHS[sizeIndex]), WindowManager.LayoutParams.WRAP_CONTENT)

        var downX = 0f
        var downY = 0f
        v.findViewById<LinearLayout>(R.id.panelHeader)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = posX + (event.rawX - downX).toInt()
                    params.y = posY + (event.rawY - downY).toInt()
                    safeUpdate(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    posX = params.x
                    posY = params.y
                    true
                }
                else -> false
            }
        }

        v.findViewById<Button>(R.id.btnPanelOpen)?.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        v.findViewById<Button>(R.id.btnPanelMinimize)?.setOnClickListener { showBubble() }
        v.findViewById<Button>(R.id.btnPanelClose)?.setOnClickListener { stopSelf() }
        v.findViewById<Button>(R.id.btnPanelSize)?.setOnClickListener {
            sizeIndex = (sizeIndex + 1) % PANEL_WIDTHS.size
            params.width = dp(PANEL_WIDTHS[sizeIndex])
            safeUpdate(v, params)
        }

        v.findViewById<Button>(R.id.btnObsToggle)?.setOnClickListener { toggleObservation() }
        v.findViewById<Button>(R.id.btnObsShot)?.setOnClickListener { requestScreenshot() }
        v.findViewById<Button>(R.id.btnConfirmBlue)?.setOnClickListener { confirm(Outcome.BLUE) }
        v.findViewById<Button>(R.id.btnConfirmRed)?.setOnClickListener { confirm(Outcome.RED) }
        v.findViewById<Button>(R.id.btnConfirmTie)?.setOnClickListener { confirm(Outcome.TIE) }

        panelView = v
        if (!safeAdd(v, params)) return
        renderPanel()
    }

    private fun toggleObservation() {
        if (ObservationBus.current.observing) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ObservationService::class.java).setAction(ObservationService.ACTION_STOP)
            )
        } else {
            startConsent()
        }
    }

    private fun requestScreenshot() {
        if (ObservationBus.current.observing) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ObservationService::class.java).setAction(ObservationService.ACTION_CAPTURE_ONCE)
            )
        } else {
            startConsent()
        }
    }

    private fun startConsent() {
        try {
            // The SYSTEM_ALERT_WINDOW grant allows background activity starts;
            // the consent dialog is the explicit Android permission flow.
            startActivity(
                Intent(this, ObservationConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Cannot happen with the overlay permission granted; be safe anyway.
        }
    }

    private fun confirm(outcome: Outcome) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ObservationService::class.java)
                .setAction(ObservationService.ACTION_CONFIRM)
                .putExtra(ObservationService.EXTRA_OUTCOME, outcome)
        )
    }

    private fun renderIfExpanded() {
        if (expanded) mainHandler.post { renderPanel() }
    }

    private fun renderPanel() {
        val v = panelView ?: return
        val bus = ObservationBus.current
        val rounds = RoundStore.getAll()

        // --- Observation status ---
        v.findViewById<Button>(R.id.btnObsToggle)?.setText(if (bus.observing) R.string.obs_on else R.string.obs_off)

        val stateView = v.findViewById<TextView>(R.id.txtTableState)
        if (bus.observing) {
            stateView?.text = getString(R.string.obs_state_fmt, stateLabel(bus.state))
        } else {
            stateView?.setText(R.string.obs_state_idle_fmt)
        }

        val read = bus.lastRead
        val lastReadView = v.findViewById<TextView>(R.id.txtLastRead)
        if (read != null && read.knownCount == 4) {
            val derived = when {
                (read.blueSum ?: 0) > (read.redSum ?: 0) -> StatsEngine.label(Outcome.BLUE)
                (read.redSum ?: 0) > (read.blueSum ?: 0) -> StatsEngine.label(Outcome.RED)
                else -> StatsEngine.label(Outcome.TIE)
            }
            lastReadView?.text = getString(
                R.string.obs_last_read_fmt,
                read.b1 ?: 0, read.b2 ?: 0, read.r1 ?: 0, read.r2 ?: 0, derived
            )
        } else {
            lastReadView?.setText(R.string.obs_no_data)
        }

        v.findViewById<LinearLayout>(R.id.confirmRow)?.visibility =
            if (bus.awaitingConfirmation) View.VISIBLE else View.GONE

        v.findViewById<TextView>(R.id.txtObsInterp)?.text =
            bus.interpretationSummary ?: getString(R.string.obs_no_data)

        val msgView = v.findViewById<TextView>(R.id.txtObsMsg)
        val parts = mutableListOf<String>()
        bus.readConfidence?.let { parts.add(getString(R.string.obs_confidence_fmt, StatsEngine.pct(it))) }
        bus.message?.let { parts.add(it) }
        if (parts.isEmpty()) {
            msgView?.visibility = View.GONE
        } else {
            msgView?.text = parts.joinToString(" · ")
            msgView?.visibility = View.VISIBLE
        }

        // --- Last rounds dots ---
        val dotsRow = v.findViewById<LinearLayout>(R.id.dotsRow)
        dotsRow?.removeAllViews()
        if (rounds.isEmpty()) {
            val t = TextView(this)
            t.text = getString(R.string.panel_empty)
            t.setTextColor(0xFF94A3B8.toInt())
            dotsRow?.addView(t)
        } else {
            for (r in rounds.takeLast(10)) {
                val dot = View(this)
                dot.setBackgroundResource(
                    when (r.outcome) {
                        Outcome.BLUE -> R.drawable.dot_blue
                        Outcome.RED -> R.drawable.dot_red
                        Outcome.TIE -> R.drawable.dot_tie
                    }
                )
                val lp = LinearLayout.LayoutParams(dp(14), dp(14))
                lp.marginEnd = dp(4)
                dotsRow?.addView(dot, lp)
            }
        }

        // --- Estimates: prefer the Bac Bo prior posterior from the
        //     interpretation engine; fall back to the plain stats engine. ---
        val analysis = StatsEngine.analyze(rounds)
        val blueP = bus.estimateFor(Outcome.BLUE)?.p ?: analysis.global.first { it.outcome == Outcome.BLUE }.p
        val redP = bus.estimateFor(Outcome.RED)?.p ?: analysis.global.first { it.outcome == Outcome.RED }.p
        val tieP = bus.estimateFor(Outcome.TIE)?.p ?: analysis.global.first { it.outcome == Outcome.TIE }.p
        setProb(v, R.id.barBlue, R.id.txtBluePct, blueP)
        setProb(v, R.id.barRed, R.id.txtRedPct, redP)
        setProb(v, R.id.barTie, R.id.txtTiePct, tieP)

        v.findViewById<TextView>(R.id.txtPanelQuality)?.text =
            getString(R.string.panel_quality_fmt, analysis.n, qualityLabel(analysis.quality))
    }

    private fun setProb(v: View, barId: Int, txtId: Int, p: Double) {
        v.findViewById<ProgressBar>(barId)?.progress = (p * 100).toInt().coerceIn(0, 100)
        v.findViewById<TextView>(txtId)?.text = StatsEngine.pct(p)
    }

    private fun stateLabel(s: GameState): String = getString(
        when (s) {
            GameState.WAITING -> R.string.state_waiting
            GameState.ROLLING -> R.string.state_rolling
            GameState.READING -> R.string.state_reading
            GameState.VALIDATING -> R.string.state_validating
            GameState.SETTLED -> R.string.state_settled
            GameState.ANALYZING -> R.string.state_analyzing
            GameState.READY -> R.string.state_ready
        }
    )

    private fun qualityLabel(q: Quality): String = getString(
        when (q) {
            Quality.INSUFFICIENT -> R.string.quality_insufficient
            Quality.LOW -> R.string.quality_low
            Quality.MEDIUM -> R.string.quality_medium
            Quality.GOOD -> R.string.quality_good
        }
    )

    private fun safeAdd(v: View, params: WindowManager.LayoutParams): Boolean = try {
        windowManager?.addView(v, params)
        true
    } catch (e: Exception) {
        stopSelf()
        false
    }

    private fun safeUpdate(v: View, params: WindowManager.LayoutParams) = try {
        windowManager?.updateViewLayout(v, params)
    } catch (e: Exception) {
        null
    }

    private fun removeViews() {
        bubbleView?.let { try { windowManager?.removeView(it) } catch (e: Exception) { } }
        bubbleView = null
        panelView?.let { try { windowManager?.removeView(it) } catch (e: Exception) { } }
        panelView = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        RoundStore.removeListener(dataListener)
        ObservationBus.unsubscribe(busListener)
        removeViews()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "floating_channel"
        const val NOTIF_ID = 42
        val PANEL_WIDTHS = intArrayOf(240, 300, 360)
    }
}
