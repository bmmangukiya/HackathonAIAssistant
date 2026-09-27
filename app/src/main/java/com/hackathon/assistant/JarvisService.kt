package com.hackathon.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps "Jarvis" listening from any screen. Android only lets a background app use the mic
 * from a microphone foreground service, and that service must be started while the app is
 * visible (see [MainActivity]).
 */
class JarvisService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis is listening")
            .setContentText("Say “Jarvis” from any screen. Everything runs on this phone.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching { startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onFailure { Log.e(TAG, "could not start mic foreground service", it); stopSelf(); return START_NOT_STICKY }
        (application as AssistantApp).startJarvis()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as AssistantApp).stopJarvis()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Jarvis"
        private const val CHANNEL = "jarvis"

        fun start(context: Context) = context.startForegroundService(Intent(context, JarvisService::class.java))
    }
}
