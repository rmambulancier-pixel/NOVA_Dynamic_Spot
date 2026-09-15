package com.rmambulancier.nova

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

class NovaNotificationListenerService : NotificationListenerService() {
    private lateinit var windowManager: WindowManager
    private var spot: NovaSpotView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaManager: MediaSessionManager? = null
    private var mediaController: MediaController? = null
    private var mediaCallback: MediaController.Callback? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var hideRunnable: Runnable? = null
    private val eventQueue = ArrayDeque<SpotEvent>()
    private var showingEvent = false
    private var connected = false

    private data class SpotEvent(val text: String, val kind: Kind)
    private enum class Kind { NOTIFICATION, BATTERY, CHARGING, LOW_BATTERY }

    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerBattery()
        setupMediaSessions()
        if (prefs.getBoolean("battery", true)) showBatteryNow()
    }

    private fun registerBattery() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!prefs.getBoolean("battery", true)) return
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                if (level < 0) return
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                when {
                    charging -> enqueue(SpotEvent("⚡ $level%", Kind.CHARGING))
                    level <= 15 -> enqueue(SpotEvent("🔋 Batterie faible • $level%", Kind.LOW_BATTERY))
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun setupMediaSessions() {
        mediaManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, javaClass)
        sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachBestController(controllers)
        }
        try {
            mediaManager?.addOnActiveSessionsChangedListener(sessionsListener!!, component)
            attachBestController(mediaManager?.getActiveSessions(component).orEmpty())
        } catch (_: SecurityException) {
            // Notification access has not been fully granted yet.
        }
    }

    private fun attachBestController(controllers: List<MediaController>) {
        val candidate = controllers.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        if (candidate?.sessionToken == mediaController?.sessionToken) {
            updateMedia()
            return
        }
        mediaCallback?.let { callback -> mediaController?.unregisterCallback(callback) }
        mediaController = candidate
        mediaCallback = candidate?.let {
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = updateMedia()
            }
        }
        mediaCallback?.let { candidate.registerCallback(it) }
        updateMedia()
    }

    private fun updateMedia() {
        if (!prefs.getBoolean("media", true)) return
        val controller = mediaController ?: return
        val metadata = controller.metadata ?: return
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: 0L
        val position = controller.playbackState?.position ?: 0L
        val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        showMedia(title, artist, duration, position, playing, art)
    }

    private fun showMedia(title: String, artist: String, duration: Long, position: Long, playing: Boolean, art: Bitmap?) {
        handler.post {
            ensureSpot()
            spot?.setMedia(title, artist, duration, position, playing, art)
            spot?.animateIn()
            hideRunnable?.let(handler::removeCallbacks)
            hideRunnable = null // Media remains visible while a session is active.
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName || !prefs.getBoolean("notifications", true)) return
        val notification = sbn.notification
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault("Notification")
        val message = buildString {
            append("● ").append(label)
            if (title.isNotBlank()) append(": ").append(title)
            if (text.isNotBlank()) append(" — ").append(text)
        }
        enqueue(SpotEvent(message, Kind.NOTIFICATION))
    }

    private fun showBatteryNow() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (level >= 0) enqueue(SpotEvent("🔋 $level%", Kind.BATTERY))
    }

    private fun enqueue(event: SpotEvent) {
        handler.post {
            if (eventQueue.size >= 5) eventQueue.removeFirst()
            eventQueue.addLast(event)
            drainQueue()
        }
    }

    private fun drainQueue() {
        if (showingEvent || eventQueue.isEmpty()) return
        showingEvent = true
        val event = eventQueue.removeFirst()
        ensureSpot()
        spot?.setMessage(event.text)
        spot?.animateIn()
        hideRunnable?.let(handler::removeCallbacks)
        hideRunnable = Runnable {
            spot?.animateOut()
            showingEvent = false
            handler.postDelayed({ drainQueue() }, 120)
        }
        handler.postDelayed(hideRunnable!!, if (event.kind == Kind.LOW_BATTERY) 5000L else 3200L)
    }

    private fun ensureSpot() {
        if (spot != null) return
        val view = NovaSpotView(this) { action -> handleSpotAction(action) }
        spot = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
        windowManager.addView(view, params)
    }

    private fun handleSpotAction(action: NovaSpotView.Action) {
        val controller = mediaController ?: return
        runCatching {
            when (action) {
                NovaSpotView.Action.TOGGLE -> {
                    if (controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else controller.transportControls.play()
                }
                NovaSpotView.Action.NEXT -> controller.transportControls.skipToNext()
                NovaSpotView.Action.PREVIOUS -> controller.transportControls.skipToPrevious()
            }
        }
        handler.postDelayed({ updateMedia() }, 120)
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        hideRunnable?.let(handler::removeCallbacks)
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        sessionsListener?.let { listener -> runCatching { mediaManager?.removeOnActiveSessionsChangedListener(listener) } }
        mediaCallback?.let { callback -> runCatching { mediaController?.unregisterCallback(callback) } }
        spot?.let { runCatching { windowManager.removeView(it) } }
        spot = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        fun isAccessGranted(context: Context): Boolean =
            runCatching {
                getEnabledListeners(context).contains(ComponentName(context, NovaNotificationListenerService::class.java))
            }.getOrDefault(false)

        private fun getEnabledListeners(context: Context): Set<ComponentName> {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return emptySet()
            return flat.split(":").mapNotNull { ComponentName.unflattenFromString(it) }.toSet()
        }
    }

    private class NovaSpotView(
        context: Context,
        private val actionHandler: (Action) -> Unit
    ) : View(context) {
        enum class Action { TOGGLE, NEXT, PREVIOUS }

        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 20, 23) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(72, 76, 82); style = Paint.Style.STROKE; strokeWidth = dp(1f) }
        private var message: String = ""
        private var title: String = ""
        private var artist: String = ""
        private var duration: Long = 0L
        private var position: Long = 0L
        private var playing: Boolean = false
        private var artwork: Bitmap? = null
        private var mediaMode = false
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L

        init {
            isClickable = true
            alpha = 0f
            scaleX = .9f
            scaleY = .9f
        }

        fun setMessage(value: String) {
            mediaMode = false
            message = value
            requestLayout(); invalidate()
        }

        fun setMedia(t: String, a: String, d: Long, p: Long, isPlaying: Boolean, art: Bitmap?) {
            mediaMode = true
            title = t; artist = a; duration = d; position = p; playing = isPlaying; artwork = art
            requestLayout(); invalidate()
        }

        fun animateIn() {
            visibility = VISIBLE
            animate().cancel()
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }

        fun animateOut() {
            animate().alpha(0f).scaleX(.9f).scaleY(.9f).setDuration(140).withEndAction { visibility = GONE }.start()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = if (mediaMode) dp(270f) else dp(250f)
            setMeasuredDimension(width, dp(if (mediaMode) 58f else 42f))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(r, dp(29f), dp(29f), bg)
            canvas.drawRoundRect(r, dp(29f), dp(29f), stroke)
            if (mediaMode) drawMedia(canvas) else drawMessage(canvas)
        }

        private fun drawMessage(canvas: Canvas) {
            paint.color = Color.WHITE
            paint.textSize = dp(13f)
            paint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
            canvas.drawText(message.take(48), dp(16f), dp(27f), paint)
        }

        private fun drawMedia(canvas: Canvas) {
            val artSize = dp(42f)
            val left = dp(8f)
            artwork?.let {
                val dst = RectF(left, dp(8f), left + artSize, dp(50f))
                canvas.save()
                canvas.clipRect(dst)
                canvas.drawBitmap(it, null, dst, paint)
                canvas.restore()
            } ?: run {
                paint.color = Color.rgb(45, 48, 53)
                canvas.drawRoundRect(RectF(left, dp(8f), left + artSize, dp(50f)), dp(12f), dp(12f), paint)
            }

            paint.color = Color.WHITE
            paint.textSize = dp(12.5f)
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText(title.take(25), dp(58f), dp(23f), paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.color = Color.LTGRAY
            paint.textSize = dp(10.5f)
            canvas.drawText(artist.take(27), dp(58f), dp(38f), paint)

            paint.color = Color.WHITE
            paint.textSize = dp(18f)
            val icon = if (playing) "Ⅱ" else "▶"
            canvas.drawText(icon, width - dp(48f), dp(30f), paint)

            if (duration > 0) {
                val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                paint.color = Color.rgb(105, 105, 110)
                canvas.drawRoundRect(RectF(dp(58f), dp(47f), width - dp(58f), dp(50f)), dp(2f), dp(2f), paint)
                paint.color = Color.WHITE
                canvas.drawRoundRect(RectF(dp(58f), dp(47f), dp(58f) + (width - dp(116f)) * progress, dp(50f)), dp(2f), dp(2f), paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; downTime = System.currentTimeMillis(); return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!mediaMode) return true
                    val dx = event.x - downX
                    val elapsed = System.currentTimeMillis() - downTime
                    if (elapsed < 500 && kotlin.math.abs(dx) > dp(45f)) {
                        actionHandler(if (dx > 0) Action.NEXT else Action.PREVIOUS)
                    } else if (elapsed < 500) {
                        actionHandler(Action.TOGGLE)
                    }
                    return true
                }
            }
            return true
        }

        private fun dp(value: Float): Float = value * density
    }
}
