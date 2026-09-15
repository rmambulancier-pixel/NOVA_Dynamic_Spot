package com.rmambulancier.nova

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaController
import android.media.session.MediaSessionManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.*
import android.widget.*
import kotlin.math.roundToInt

class NovaNotificationListenerService : NotificationListenerService() {
    private lateinit var wm: WindowManager
    private var spot: NovaSpotView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var mediaManager: MediaSessionManager? = null
    private var mediaController: MediaController? = null
    private var batteryReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Settings.canDrawOverlays(this)) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            showBattery()
            setupMedia()
            batteryReceiver = registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        if (level >= 0) showSpot("🔋 $level%")
                    }
                },
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }
    }

    private fun setupMedia() {
        mediaManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, javaClass)
        try {
            mediaController = mediaManager?.getActiveSessions(component)?.firstOrNull()
            mediaController?.registerCallback(object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                    updateMedia(metadata)
                }
                override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) {
                    updateMedia(mediaController?.metadata)
                }
            })
            updateMedia(mediaController?.metadata)
        } catch (_: SecurityException) { }
    }

    private fun updateMedia(metadata: android.media.MediaMetadata?) {
        val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        if (!title.isNullOrBlank()) showSpot("♪ $title${if (!artist.isNullOrBlank()) " • $artist" else ""}", sticky = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)) } catch (_: Exception) { "Notification" }
        showSpot("● $label${if (title.isNotBlank()) ": $title" else ""}${if (text.isNotBlank()) " — $text" else ""}")
    }

    private fun showBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (level >= 0) showSpot("🔋 $level%")
    }

    private fun showSpot(text: String, sticky: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) return
        handler.post {
            val view = spot ?: NovaSpotView(this).also {
                spot = it
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(8)
                }
                wm.addView(it, lp)
            }
            view.setText(text)
            view.animateIn()
            if (!sticky) {
                handler.removeCallbacksAndMessages(view)
                handler.postDelayed({ view.animateOut() }, 4500)
            }
        }
    }

    override fun onDestroy() {
        batteryReceiver?.let { unregisterReceiver(it) }
        mediaController?.unregisterCallback(object : MediaController.Callback() {})
        spot?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private class NovaSpotView(context: Context) : FrameLayout(context) {
        private val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            setPadding(dp(16), dp(8), dp(16), dp(8))
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        init {
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 23, 26))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(70, 75, 80))
            }
            elevation = dp(10).toFloat()
            addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        fun setText(value: String) { text.text = value }
        fun animateIn() {
            alpha = 0f
            scaleX = .82f
            scaleY = .82f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        }
        fun animateOut() {
            animate().alpha(0f).scaleX(.82f).scaleY(.82f).setDuration(180).withEndAction {
                visibility = GONE
            }.start()
        }
        private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    }
}
