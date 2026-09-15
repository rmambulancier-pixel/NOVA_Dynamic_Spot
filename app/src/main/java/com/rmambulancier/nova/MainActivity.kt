package com.rmambulancier.nova

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("nova", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NovaApp() }
    }

    override fun onResume() {
        super.onResume()
        setContent { NovaApp() }
    }

    private fun notificationAccessEnabled(): Boolean =
        NovaNotificationListenerService.isAccessGranted(this)

    private fun openOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openNotifications() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    @androidx.compose.runtime.Composable
    private fun NovaApp() {
        var overlay by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var notifications by remember { mutableStateOf(notificationAccessEnabled()) }
        var media by remember { mutableStateOf(prefs.getBoolean("media", true)) }
        var notification by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
        var battery by remember { mutableStateOf(prefs.getBoolean("battery", true)) }

        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("NOVA", style = MaterialTheme.typography.displaySmall)
                Text("Dynamic Spot 2.0 • Android 17", style = MaterialTheme.typography.titleMedium)
                Text("Local, privé, sans publicité, sans compte et sans serveur.")

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("État", style = MaterialTheme.typography.titleLarge)
                        Text(if (overlay) "✓ Affichage flottant actif" else "○ Affichage flottant à autoriser")
                        Text(if (notifications) "✓ Accès notifications actif" else "○ Accès notifications à autoriser")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { openOverlay(); overlay = Settings.canDrawOverlays(this@MainActivity) }) { Text("Affichage") }
                            OutlinedButton(onClick = { openNotifications(); notifications = notificationAccessEnabled() }) { Text("Notifications") }
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Modules", style = MaterialTheme.typography.titleLarge)
                        NovaSwitch("🎵 Musique + contrôles", media) {
                            media = it; prefs.edit().putBoolean("media", it).apply()
                        }
                        NovaSwitch("🔔 Notifications", notification) {
                            notification = it; prefs.edit().putBoolean("notifications", it).apply()
                        }
                        NovaSwitch("🔋 Batterie + charge", battery) {
                            battery = it; prefs.edit().putBoolean("battery", it).apply()
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nouveautés V2", style = MaterialTheme.typography.titleLarge)
                        Text("• Dynamic Spot adaptative et plus compacte")
                        Text("• Pochette, titre, artiste et progression média")
                        Text("• Tap = lecture/pause • glissement = précédent/suivant")
                        Text("• Gestion propre des changements de lecteur média")
                        Text("• File d'événements pour éviter le spam des notifications")
                        Text("• Batterie, charge et batterie faible")
                        Text("• Réglages locaux persistants")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Après activation des deux autorisations, lance une musique pour tester NOVA.")
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun NovaSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
