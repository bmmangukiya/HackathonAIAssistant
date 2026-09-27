package com.hackathon.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hackathon.assistant.ui.JarvisLogo
import com.hackathon.assistant.ui.Orbitron

/** Jarvis setup: grants permissions and starts the always-on listener. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = (
            listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS) +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        // Only on a fresh start: after rotation the permission dialog (or its result) is already on its way.
        if (savedInstanceState == null) {
            if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else startJarvis()
        }

        setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF0F1015)).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                JarvisLogo(Modifier.size(120.dp), energy = 0.3f, orbits = false)
                Spacer(Modifier.height(20.dp))
                Text(
                    "J.A.R.V.I.S", color = Color.White, fontSize = 30.sp, letterSpacing = 5.sp,
                    fontFamily = Orbitron, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text("Say “Jarvis” from any screen.", color = Color(0xFFB7BAC7), fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startJarvis() }

    /** The mic service must be started while this activity is visible (Android 14+). */
    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) JarvisService.start(this)
        else Toast.makeText(this, "Jarvis needs the microphone permission to listen.", Toast.LENGTH_LONG).show()
    }
}
