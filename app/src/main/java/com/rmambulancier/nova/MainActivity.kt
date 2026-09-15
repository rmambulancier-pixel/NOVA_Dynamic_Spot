package com.rmambulancier.nova

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NovaSetupScreen(
                    overlayGranted = Settings.canDrawOverlays(this),
                    onOverlay = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    },
                    onNotifications = { startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                )
            }
        }
    }
}

@Composable
private fun NovaSetupScreen(
    overlayGranted: Boolean,
    onOverlay: () -> Unit,
    onNotifications: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("NOVA", style = MaterialTheme.typography.displaySmall)
            Text("Dynamic Spot • Android 17", style = MaterialTheme.typography.titleMedium)
            Text("Version personnelle, locale et sans publicité.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Affichage au-dessus des applications", style = MaterialTheme.typography.titleMedium)
                    Text(if (overlayGranted) "✓ Autorisation active" else "Autorisation nécessaire")
                    Button(onClick = onOverlay, Modifier.fillMaxWidth()) {
                        Text(if (overlayGranted) "Vérifier / modifier" else "Autoriser NOVA")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("2. Notifications et musique", style = MaterialTheme.typography.titleMedium)
                    Text("Active l'accès aux notifications pour permettre à NOVA de détecter les événements et médias.")
                    Button(onClick = onNotifications, Modifier.fillMaxWidth()) {
                        Text("Ouvrir l'accès aux notifications")
                    }
                }
            }

            Text(
                "Après les deux autorisations, lance une musique : la Dynamic Spot apparaîtra autour de la zone caméra.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
