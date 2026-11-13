package com.mybotai.bacboanalyzer.observe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.mybotai.bacboanalyzer.R
import com.mybotai.bacboanalyzer.data.RoundStore
import com.mybotai.bacboanalyzer.game.BacBoRules
import com.mybotai.bacboanalyzer.game.CommitResult
import com.mybotai.bacboanalyzer.game.GameEvent
import com.mybotai.bacboanalyzer.game.GameState
import com.mybotai.bacboanalyzer.game.GameStateMachine
import com.mybotai.bacboanalyzer.game.InterpretationEngine
import com.mybotai.bacboanalyzer.game.MatchMemory
import com.mybotai.bacboanalyzer.game.ObservationConfig
import com.mybotai.bacboanalyzer.game.SessionFileStore
import com.mybotai.bacboanalyzer.models.Outcome
import com.mybotai.bacboanalyzer.models.Round
import com.mybotai.bacboanalyzer.stats.StatsEngine
import com.mybotai.bacboanalyzer.vision.DiceImageAnalyzer
import com.mybotai.bacboanalyzer.vision.DiceRead
import com.mybotai.bacboanalyzer.vision.Frame
import com.mybotai.bacboanalyzer.vision.FrameVerdict
import com.mybotai.bacboanalyzer.vision.SmartCaptureGate

/**
 * Continuous observation service (BAC BO AI ANALYZER 2.0).
 *
 * Uses MediaProjection with EXPLICIT user consent per session (the Android
 * system dialog). Frames are captured through an ImageReader, downscaled to
 * [ObservationConfig.ANALYSIS_WIDTH] and processed on a background thread
 * with an event-driven gate — idle screens cost almost zero CPU.
 *
 * The pipeline: gate -> state machine -> DiceImageAnalyzer -> BacBoRules
 * validation -> MatchMemory (duplicate guard, persistence) -> global history
 * -> InterpretationEngine -> ObservationBus (floating panel + analyzer UI).
 *
 * Ambiguous reads are never committed automatically: the bus publishes the
 * candidate and the user confirms AZUL/VERMELHO/EMPATE in the overlay.
 *
 * The service cannot survive process death (a MediaProjection grant is
 * single-use), so it is intentionally START_NOT_STICKY.
 */
class ObservationService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: android.media.ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var mainHandler: Handler = Handler(Looper.getMainLooper())
    private var prevFrame: Frame? = null
    private var captureOnce = false

    private val gate = SmartCaptureGate(
        motionThreshold = 0.012,
        stableThreshold = 0.004,
        minIntervalMs = ObservationConfig.MIN_FRAME_INTERVAL_MS,
        stableFramesNeeded = ObservationConfig.STABLE_FRAMES_NEEDED
    )
    private val machine = GameStateMachine()
    private lateinit var memory: MatchMemory
    private val analyzer = DiceImageAnalyzer()
    private var idleRunnable: Runnable? = null

    /** All state-machine interactions are serialized onto one monitor:
     *  events arrive from the main thread (user actions) and from the
     *  observation handler thread (frames). */
    private val machineLock = Any()

    private fun machineEvent(e: GameEvent) = synchronized(machineLock) { machine.onEvent(e) }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            teardown()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RoundStore.init(applicationContext)
        memory = MatchMemory(SessionFileStore(applicationContext.filesDir))
        createChannel()
        machine.addListener { state -> onStateChanged(state) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                startForegroundNotification()
                if (projection == null && data != null) {
                    setupProjection(resultCode, data)
                } else if (projection == null) {
                    ObservationBus.post { it.copy(message = getString(R.string.obs_error_no_projection)) }
                    stopSelf()
                }
            }
            ACTION_CAPTURE_ONCE -> {
                if (projection == null) {
                    startConsent()
                } else {
                    captureOnce = true
                    ObservationBus.post { it.copy(message = getString(R.string.obs_capturing)) }
                }
            }
            ACTION_STOP -> teardown()
            ACTION_CONFIRM -> {
                val outcome = intent.getSerializableExtra(EXTRA_OUTCOME) as? Outcome
                if (outcome != null) {
                    val h = handler
                    if (h != null) h.post { machineEvent(GameEvent.UserConfirmed(outcome)) }
                }
            }
            ACTION_REJECT -> {
                val h = handler
                if (h != null) h.post { machineEvent(GameEvent.UserRejected) }
            }
            else -> {
                // Restarted by the system without consent data: cannot observe.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------
    // Projection setup / teardown
    // ---------------------------------------------------------------------

    private fun setupProjection(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(resultCode, data)
            proj.registerCallback(projectionCallback, mainHandler)
            projection = proj

            handlerThread = HandlerThread("bacbo-observation").also { it.start() }
            handler = Handler(handlerThread!!.looper)

            val (w, h, dpi) = screenSize()
            reader = android.media.ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ r ->
                val img = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (img != null) handleImage(img)
            }, handler)

            virtualDisplay = proj.createVirtualDisplay(
                "bacbo-obs",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                handler
            )

            ObservationBus.post {
                it.copy(
                    observing = true,
                    state = machine.state,
                    message = getString(R.string.obs_started)
                )
            }
        } catch (e: Exception) {
            ObservationBus.post {
                it.copy(observing = false, message = getString(R.string.obs_error_start, e.message ?: ""))
            }
            teardown()
        }
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        var w: Int
        var h: Int
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        w = metrics.widthPixels
        h = metrics.heightPixels
        val dpi = metrics.densityDpi
        // Cap capture resolution to keep memory low on entry-level devices.
        val cap = 1080
        if (h > cap) {
            val scale = cap.toDouble() / h
            w = (w * scale).toInt()
            h = cap
        }
        return Triple(w.coerceAtLeast(1), h.coerceAtLeast(1), dpi)
    }

    private fun startConsent() {
        try {
            val i = Intent(this, ObservationConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            ObservationBus.post {
                it.copy(message = getString(R.string.obs_error_consent))
            }
        }
    }

    private fun teardown() {
        // Stop frame processing first so no state-machine event races teardown.
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        cancelIdle()
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        reader = null
        projection = null
        prevFrame = null
        synchronized(machineLock) { machine.reset() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        ObservationBus.reset()
        stopSelf()
    }

    // ---------------------------------------------------------------------
    // Frame pipeline (runs on the observation handler thread)
    // ---------------------------------------------------------------------

    private fun handleImage(img: android.media.Image) {
        try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = img.width
            val h = img.height

            // Downscale straight into the analysis frame (ARGB ints).
            val targetW = minOf(ObservationConfig.ANALYSIS_WIDTH, w)
            val scale = w.toDouble() / targetW
            val targetH = maxOf(1, (h / scale).toInt())
            val pixels = IntArray(targetW * targetH)
            for (ty in 0 until targetH) {
                val sy = minOf(h - 1, (ty * scale).toInt())
                val rowBase = sy * rowStride
                for (tx in 0 until targetW) {
                    val sx = minOf(w - 1, (tx * scale).toInt())
                    val off = rowBase + sx * pixelStride
                    val r = buffer.get(off).toInt() and 0xFF
                    val g = buffer.get(off + 1).toInt() and 0xFF
                    val b = buffer.get(off + 2).toInt() and 0xFF
                    pixels[ty * targetW + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            img.close()
            processFrame(Frame(pixels, targetW, targetH))
        } catch (e: Exception) {
            runCatching { img.close() }
        }
    }

    private fun processFrame(frame: Frame) {
        val now = SystemClock.elapsedRealtime()
        val prev = prevFrame
        prevFrame = frame

        if (prev != null) {
            val ratio = com.mybotai.bacboanalyzer.vision.FrameChangeDetector.changeRatio(prev, frame)
            when (gate.evaluate(ratio, now)) {
                FrameVerdict.MOTION -> {
                    machineEvent(GameEvent.MotionDetected)
                    scheduleIdle()
                }
                FrameVerdict.STABLE -> {
                    machineEvent(GameEvent.FrameStable)
                    maybeAnalyze(frame, now)
                    scheduleIdle()
                }
                FrameVerdict.IDLE -> return
            }
        } else {
            machineEvent(GameEvent.FrameStable)
            maybeAnalyze(frame, now)
            scheduleIdle()
        }
    }

    private fun maybeAnalyze(frame: Frame, now: Long) {
        val shouldAnalyze = machine.state == GameState.READING || captureOnce
        if (!shouldAnalyze) return
        captureOnce = false
        val read = analyzer.analyze(frame).copy(timestamp = now)
        ObservationBus.post { it.copy(lastRead = read, readConfidence = read.confidence) }
        machineEvent(if (read.knownCount == 4) GameEvent.DiceRead(read) else GameEvent.ReadFailed)
    }

    // ---------------------------------------------------------------------
    // State machine integration
    // ---------------------------------------------------------------------

    private fun onStateChanged(state: GameState) {
        when (state) {
            GameState.SETTLED -> commitPending()
            else -> Unit
        }
        val awaiting = machine.awaitingConfirmation
        ObservationBus.post {
            it.copy(state = state, awaitingConfirmation = awaiting)
        }
    }

    private fun commitPending() {
        val read = machine.pendingRead ?: run {
            machineEvent(GameEvent.RoundCommitted)
            return
        }
        val result = memory.commit(
            read = read,
            confirmedOutcome = machine.confirmedOutcome,
            now = System.currentTimeMillis(),
            motionSinceLastCommit = machine.motionSinceLastCommit
        )
        when (result) {
            is CommitResult.New -> {
                RoundStore.add(
                    Round(
                        timestamp = result.round.recognizedAt,
                        outcome = result.round.outcome,
                        source = result.round.source,
                        note = "B ${result.round.b1}+${result.round.b2} · V ${result.round.r1}+${result.round.r2}"
                    )
                )
                ObservationBus.post { it.copy(lastRound = result.round) }
                machineEvent(GameEvent.RoundCommitted)
                runAnalysis()
            }
            is CommitResult.Duplicate -> {
                machineEvent(GameEvent.RoundCommitted)
                runAnalysis()
            }
            is CommitResult.Ambiguous -> {
                ObservationBus.post { it.copy(message = result.reason) }
                synchronized(machineLock) { machine.reset() }
            }
        }
    }

    private fun runAnalysis() {
        val rounds = RoundStore.getAll()
        val interpretation = InterpretationEngine.interpret(rounds, memory.rounds())
        val warning = if (interpretation.insufficient) getString(R.string.obs_insufficient) else null
        ObservationBus.post {
            it.copy(
                interpretationSummary = interpretation.summary,
                estimates = interpretation.estimates,
                message = warning
            )
        }
        machineEvent(GameEvent.AnalysisDone)
    }

    // ---------------------------------------------------------------------
    // Idle timer
    // ---------------------------------------------------------------------

    private fun scheduleIdle() {
        cancelIdle()
        val r = Runnable {
            val inFlow = machine.state != GameState.WAITING && machine.state != GameState.READY
            machineEvent(GameEvent.IdleTimeout)
            if (!inFlow && machine.state == GameState.WAITING) {
                ObservationBus.post { it.copy(message = getString(R.string.obs_waiting_motion)) }
            }
        }
        idleRunnable = r
        handler?.postDelayed(r, ObservationConfig.IDLE_TIMEOUT_MS)
    }

    private fun cancelIdle() {
        idleRunnable?.let { handler?.removeCallbacks(it) }
        idleRunnable = null
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private fun createChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                OBS_CHANNEL_ID,
                getString(R.string.obs_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun startForegroundNotification() {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ObservationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, OBS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_analytics)
            .setContentTitle(getString(R.string.obs_notif_title))
            .setContentText(getString(R.string.obs_notif_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.obs_notif_stop), stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                OBS_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(OBS_NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mybotai.bacboanalyzer.obs.START"
        const val ACTION_STOP = "com.mybotai.bacboanalyzer.obs.STOP"
        const val ACTION_CAPTURE_ONCE = "com.mybotai.bacboanalyzer.obs.CAPTURE_ONCE"
        const val ACTION_CONFIRM = "com.mybotai.bacboanalyzer.obs.CONFIRM"
        const val ACTION_REJECT = "com.mybotai.bacboanalyzer.obs.REJECT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_OUTCOME = "outcome"
        const val OBS_CHANNEL_ID = "observation_channel"
        const val OBS_NOTIF_ID = 43
    }
}
