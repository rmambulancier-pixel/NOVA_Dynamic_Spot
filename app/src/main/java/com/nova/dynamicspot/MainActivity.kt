package com.nova.dynamicspot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("NOVA Dynamic Spot", style = MaterialTheme.typography.headlineMedium)
                        Text("Version personnelle optimisée Android 17 / Pixel 10 Pro XL")
                        Text("1. Autorise l'affichage par-dessus les autres applications.")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }) { Text("Autoriser la superposition") }
                        Text("2. Active l'accès aux notifications pour que NOVA puisse détecter musique, notifications et événements.")
                        Button(onClick = { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }) { Text("Activer l'accès notifications") }
                        Text("Une fois les deux autorisations activées, NOVA affiche la pastille autour de la caméra et reste local : pas de compte, pub, abonnement ni serveur.")
                    }
                }
            }
        }
    }
}
