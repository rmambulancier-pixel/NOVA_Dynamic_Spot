package com.nova.dynamicspot

import android.app.Notification
import android.content.*
import android.graphics.PixelFormat
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.*
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.*
import android.widget.TextView
import androidx.core.content.getSystemService

class NovaNotificationListener : NotificationListenerService() {
    private var overlay: View? = null
    private var wm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var batteryReceiver: BroadcastReceiver? = null
    private var mediaManager: MediaSessionManager? = null
    private var mediaListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        wm = getSystemService()
        mediaManager = getSystemService()
        mediaListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            val c = controllers?.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            val title = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            val artist = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            if (!title.isNullOrBlank()) show("♪ $title${if (!artist.isNullOrBlank()) " • $artist" else ""}", 4200)
        }
        runCatching { mediaManager?.addOnActiveSessionsChangedListener(mediaListener, componentName) }
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra("level", -1)
                    val scale = intent.getIntExtra("scale", 100)
                    show("🔋 ${level * 100 / scale}%", 2400)
                }
            }
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        show("NOVA prêt", 1800)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        show(if (text.isBlank()) title else "$title • $text", 3800)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    private fun show(message: String, duration: Long) {
        if (!Settings.canDrawOverlays(this)) return
        handler.post {
            val view = TextView(this).apply {
                text = message
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                setPadding(30, 13, 30, 13)
                setBackgroundResource(com.nova.dynamicspot.R.drawable.nova_pill)
                alpha = 0f
                elevation = 30f
            }
            removeOverlay()
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 22
            }
            overlay = view
            wm?.addView(view, lp)
            view.animate().alpha(1f).setDuration(180).withEndAction {
                handler.postDelayed({
                    view.animate().alpha(0f).setDuration(220).withEndAction { removeOverlay() }.start()
                }, duration)
            }.start()
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
    }

    override fun onDestroy() {
        batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
        runCatching { mediaListener?.let { mediaManager?.removeOnActiveSessionsChangedListener(it) } }
        removeOverlay()
        super.onDestroy()
    }
}
