package com.rmambulancier.nova

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }
    private lateinit var accessText: TextView
    private lateinit var overlayText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompatLite.edgeToEdge(window)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        if (::accessText.isInitialized) refreshStatus()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 8, 10))
            setPadding(dp(22), dp(18), dp(22), dp(24))
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -1))

        content.addView(text("NOVA", 34f, Color.WHITE, Typeface.DEFAULT_BOLD))
        content.addView(text("Dynamic Spot 3.0", 18f, Color.LTGRAY, Typeface.DEFAULT_BOLD), marginTop(2))
        content.addView(text("Android 17 • Pixel • 100 % local", 13f, Color.GRAY, Typeface.DEFAULT), marginTop(6))

        val statusCard = card()
        accessText = text("Notification Access : …", 15f, Color.WHITE, Typeface.DEFAULT_BOLD)
        overlayText = text("Overlay : …", 15f, Color.WHITE, Typeface.DEFAULT_BOLD)
        statusCard.addView(accessText)
        statusCard.addView(overlayText, marginTop(8))
        content.addView(statusCard, marginTop(24))

        content.addView(button("Autoriser l’overlay") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, marginTop(14))
        content.addView(button("Autoriser l’accès aux notifications") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, marginTop(10))

        val features = card()
        features.addView(text("FONCTIONS", 12f, Color.GRAY, Typeface.DEFAULT_BOLD))
        addSwitch(features, "🎵 Média", "Dynamic Spot pour musique et podcasts", "media", true)
        addSwitch(features, "🔔 Notifications", "Messages et alertes courtes", "notifications", true)
        addSwitch(features, "🔋 Batterie", "Charge et batterie faible", "battery", true)
        content.addView(features, marginTop(22))

        val info = card()
        info.addView(text("Architecture V3", 15f, Color.WHITE, Typeface.DEFAULT_BOLD))
        info.addView(text("Aucune Compose • aucune librairie UI • aucun serveur • aucune pub • aucun analytics.\n\nLe cœur Dynamic Spot est basé uniquement sur les API Android natives pour maximiser la fiabilité du build et la compatibilité Android 17.", 13f, Color.LTGRAY, Typeface.DEFAULT), marginTop(8))
        content.addView(info, marginTop(14))

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun addSwitch(parent: LinearLayout, title: String, subtitle: String, key: String, default: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, Color.WHITE, Typeface.DEFAULT_BOLD))
        texts.addView(text(subtitle, 12f, Color.GRAY, Typeface.DEFAULT), marginTop(3))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        row.addView(sw)
        parent.addView(row)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        setBackgroundColor(Color.rgb(22, 24, 28))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun button(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(13), dp(12), dp(13))
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int, typeface: Typeface): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        this.typeface = typeface
    }

    private fun marginTop(px: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun refreshStatus() {
        val access = NovaNotificationListenerService.isAccessGranted(this)
        val overlay = Settings.canDrawOverlays(this)
        accessText.text = "Notification Access : ${if (access) "ACTIF" else "À autoriser"}"
        overlayText.text = "Overlay : ${if (overlay) "ACTIF" else "À autoriser"}"
    }

    private object WindowCompatLite {
        fun edgeToEdge(window: Window) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            }
        }
    }
}
