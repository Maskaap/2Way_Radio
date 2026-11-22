package com.pttconnect.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pttconnect.app.PttApp
import com.pttconnect.app.R
import com.pttconnect.app.ptt.ConnState
import com.pttconnect.app.ptt.TxState
import com.pttconnect.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service (type = microphone) that keeps the channel connected and audible while
 * the screen is off or another app is open. It must be started while the app is visible
 * (Android 14+ rule for microphone services) – we start it when a channel is entered.
 * It does NOT keep the microphone open; the mic is only active while transmitting.
 */
class PttService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "PTT connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while PTT Connect is listening to a channel"
            setShowBadge(false)
        })
        // Location type only if the user granted location (Android 14 refuses it otherwise).
        val graph = (application as PttApp).graph
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                (if (graph.location.hasPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build("Connecting…", null), type)

        // Partial wake lock keeps the CPU alive for audio decoding while the screen is off.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PTTConnect:channel").apply { setReferenceCounted(false); acquire() }

        val engine = (application as PttApp).graph.engine
        scope.launch {
            engine.state.map { s ->
                val title = s.channel?.name ?: "No channel"
                val text = when {
                    s.tx == TxState.TRANSMITTING -> "You are talking"
                    s.remoteSpeaker != null -> "${s.remoteSpeaker} is talking"
                    s.conn == ConnState.CONNECTED -> "Listening · ${s.participants.size + 1} online"
                    s.conn == ConnState.ERROR -> s.message ?: "Connection problem"
                    else -> "Connecting…"
                }
                title to text
            }.distinctUntilChanged().collect { (t, x) -> nm.notify(NOTIF_ID, build(t, x)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun build(title: String, text: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val CHANNEL = "ptt_connection"
        private const val NOTIF_ID = 42

        fun start(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, PttService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, PttService::class.java)) }
    }
}
