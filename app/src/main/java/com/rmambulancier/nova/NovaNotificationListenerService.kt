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
import android.media.PlaybackState
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
import kotlin.math.abs
import kotlin.math.roundToInt

class NovaNotificationListenerService : NotificationListenerService() {
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }
    private var spot: NovaSpotView? = null
    private var mediaManager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var hideRunnable: Runnable? = null
    private val queue = ArrayDeque<String>()
    private var draining = false
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateMedia()
            if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) handler.postDelayed(this, 750)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        registerBattery()
        setupMedia()
        showBattery()
    }

    private fun setupMedia() {
        mediaManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, NovaNotificationListenerService::class.java)
        sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> selectController(list) }
        runCatching {
            mediaManager?.addOnActiveSessionsChangedListener(sessionsListener!!, component)
            selectController(mediaManager?.getActiveSessions(component).orEmpty())
        }
    }

    private fun selectController(list: List<MediaController>) {
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken == controller?.sessionToken) {
            updateMedia()
            return
        }
        controllerCallback?.let { cb -> runCatching { controller?.unregisterCallback(cb) } }
        controller = next
        controllerCallback = next?.let {
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    updateMedia()
                    handler.removeCallbacks(progressRunnable)
                    if (state?.state == PlaybackState.STATE_PLAYING) handler.post(progressRunnable)
                }
            }
        }
        controllerCallback?.let { next.registerCallback(it) }
        handler.removeCallbacks(progressRunnable)
        if (next?.playbackState?.state == PlaybackState.STATE_PLAYING) handler.post(progressRunnable)
        updateMedia()
    }

    private fun updateMedia() {
        if (!prefs.getBoolean("media", true)) return
        val c = controller ?: return
        val m = c.metadata ?: return
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: 0L
        val state = c.playbackState
        val position = state?.position ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        handler.post {
            ensureSpot()
            spot?.setMedia(title, artist, duration, position, playing, art)
            spot?.showAnimated()
            hideRunnable?.let(handler::removeCallbacks)
            hideRunnable = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!prefs.getBoolean("notifications", true) || sbn == null || sbn.packageName == packageName) return
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault("Notification")
        val msg = buildString {
            append("● ").append(app)
            if (title.isNotBlank()) append(": ").append(title)
            if (body.isNotBlank()) append(" — ").append(body)
        }
        enqueue(msg)
    }

    private fun registerBattery() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!prefs.getBoolean("battery", true)) return
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: return
                if (level < 0) return
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                when {
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL -> enqueue("⚡ $level%")
                    level <= 15 -> enqueue("🔋 Batterie faible • $level%")
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun showBattery() {
        if (!prefs.getBoolean("battery", true)) return
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        if (level >= 0) enqueue("🔋 $level%")
    }

    private fun enqueue(text: String) {
        handler.post {
            if (queue.size >= 5) queue.removeFirst()
            queue.addLast(text)
            drain()
        }
    }

    private fun drain() {
        if (draining || queue.isEmpty()) return
        draining = true
        val text = queue.removeFirst()
        ensureSpot()
        spot?.setMessage(text)
        spot?.showAnimated()
        hideRunnable?.let(handler::removeCallbacks)
        hideRunnable = Runnable {
            spot?.hideAnimated()
            draining = false
            handler.postDelayed({ drain() }, 120)
        }
        handler.postDelayed(hideRunnable!!, 3200)
    }

    private fun ensureSpot() {
        if (spot != null) return
        val view = NovaSpotView(this) { action -> performMediaAction(action) }
        spot = view
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
        runCatching { wm.addView(view, p) }.onFailure { spot = null }
    }

    private fun performMediaAction(action: NovaSpotView.Action) {
        val c = controller ?: return
        runCatching {
            when (action) {
                NovaSpotView.Action.TOGGLE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
                NovaSpotView.Action.NEXT -> c.transportControls.skipToNext()
                NovaSpotView.Action.PREVIOUS -> c.transportControls.skipToPrevious()
            }
        }
        handler.postDelayed({ updateMedia() }, 100)
    }

    override fun onDestroy() {
        hideRunnable?.let(handler::removeCallbacks)
        handler.removeCallbacks(progressRunnable)
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        sessionsListener?.let { l -> runCatching { mediaManager?.removeOnActiveSessionsChangedListener(l) } }
        controllerCallback?.let { cb -> runCatching { controller?.unregisterCallback(cb) } }
        spot?.let { runCatching { wm.removeView(it) } }
        spot = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        fun isAccessGranted(context: Context): Boolean = runCatching {
            val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            raw.split(":").mapNotNull { ComponentName.unflattenFromString(it) }
                .contains(ComponentName(context, NovaNotificationListenerService::class.java))
        }.getOrDefault(false)
    }

    private class NovaSpotView(context: Context, private val action: (Action) -> Unit) : View(context) {
        enum class Action { TOGGLE, NEXT, PREVIOUS }
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(16, 18, 21) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 74, 80); style = Paint.Style.STROKE; strokeWidth = dp(1f) }
        private var media = false
        private var message = ""
        private var title = ""
        private var artist = ""
        private var duration = 0L
        private var position = 0L
        private var playing = false
        private var art: Bitmap? = null
        private var downX = 0f
        private var downTime = 0L

        init { alpha = 0f; scaleX = .92f; scaleY = .92f; isClickable = true }

        fun setMessage(value: String) { media = false; message = value; requestLayout(); invalidate() }
        fun setMedia(t: String, a: String, d: Long, p: Long, play: Boolean, bitmap: Bitmap?) {
            media = true; title = t; artist = a; duration = d; position = p; playing = play; art = bitmap; requestLayout(); invalidate()
        }
        fun showAnimated() { visibility = VISIBLE; animate().cancel(); animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start() }
        fun hideAnimated() { animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(130).withEndAction { visibility = GONE }.start() }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(if (media) dp(276f).roundToInt() else dp(250f).roundToInt(), if (media) dp(60f).roundToInt() else dp(42f).roundToInt())
        }

        override fun onDraw(c: Canvas) {
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            c.drawRoundRect(r, dp(30f), dp(30f), bg)
            c.drawRoundRect(r, dp(30f), dp(30f), border)
            if (media) drawMedia(c) else drawMessage(c)
        }

        private fun drawMessage(c: Canvas) {
            paint.color = Color.WHITE; paint.textSize = dp(13f); paint.typeface = Typeface.DEFAULT
            c.drawText(message.take(48), dp(16f), dp(27f), paint)
        }

        private fun drawMedia(c: Canvas) {
            val size = dp(44f); val left = dp(8f)
            art?.let { c.drawBitmap(it, null, RectF(left, dp(8f), left + size, dp(52f)), paint) } ?: run {
                paint.color = Color.rgb(44, 47, 52); c.drawRoundRect(RectF(left, dp(8f), left + size, dp(52f)), dp(12f), dp(12f), paint)
            }
            paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = dp(12.5f)
            c.drawText(title.take(25), dp(60f), dp(24f), paint)
            paint.color = Color.LTGRAY; paint.typeface = Typeface.DEFAULT; paint.textSize = dp(10.5f)
            c.drawText(artist.take(26), dp(60f), dp(39f), paint)
            paint.color = Color.WHITE; paint.textSize = dp(18f)
            c.drawText(if (playing) "Ⅱ" else "▶", width - dp(48f), dp(31f), paint)
            if (duration > 0) {
                val p = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                paint.color = Color.rgb(92, 95, 100)
                c.drawRoundRect(RectF(dp(60f), dp(49f), width - dp(58f), dp(52f)), dp(2f), dp(2f), paint)
                paint.color = Color.WHITE
                c.drawRoundRect(RectF(dp(60f), dp(49f), dp(60f) + (width - dp(118f)) * p, dp(52f)), dp(2f), dp(2f), paint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downTime = System.currentTimeMillis(); return true }
                MotionEvent.ACTION_UP -> {
                    if (!media) return true
                    val dx = e.x - downX; val elapsed = System.currentTimeMillis() - downTime
                    if (elapsed < 500 && abs(dx) > dp(45f)) action(if (dx > 0) Action.NEXT else Action.PREVIOUS)
                    else if (elapsed < 500) action(Action.TOGGLE)
                    return true
                }
            }
            return true
        }

        private fun dp(v: Float): Float = v * density
    }
}
