package com.pttconnect.app.ptt

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.pttconnect.app.data.Alert
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.friendlyError
import com.pttconnect.app.net.LocationReporter
import com.pttconnect.app.voice.VoiceEvent
import com.pttconnect.app.voice.VoiceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Emergency / panic button.
 *  - raise(): records the alert with the phone's position, posts in the channel, alerts everyone in it
 *    (alarm + red banner) and lets the caller talk over anyone (emergency floor override).
 *  - Alerts come from the database (open_emergencies), so a fake alert can't be injected over the data channel.
 *  - Admins see every alert live on the admin page and acknowledge / resolve it there.
 */
class EmergencyManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val engine: PttEngine,
    private val location: LocationReporter,
    voice: VoiceLink,
) {
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    /** Open emergencies in my channel, plus my own. */
    val alerts: StateFlow<List<Alert>> = _alerts
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val silenced = mutableSetOf<Long>()
    private var alarmJob: Job? = null
    private val myId get() = backend.session.value?.userId

    init {
        scope.launch { engine.state.map { it.channel?.id to it.conn }.distinctUntilChanged().collect { refresh() } }
        scope.launch { voice.events.collect { e -> if (e is VoiceEvent.Signal && e.text == "alert") refresh() } }
        // While anything is open, keep checking (resolution by admin, new alerts if a hint was missed)
        scope.launch { while (isActive) { delay(if (_alerts.value.isEmpty()) 60_000 else 15_000); refresh() } }
    }

    fun raise() = scope.launch {
        if (_sending.value) return@launch
        _sending.value = true; _message.value = null
        try {
            val loc = location.bestLocation()
            backend.raiseEmergency(engine.state.value.channel?.id, loc?.latitude, loc?.longitude)
            vibrate(longArrayOf(0, 400, 150, 400, 150, 400))
            engine.signal("alert"); engine.signal("sync")
            _message.value = if (loc == null) "Emergency sent (no GPS position available)" else "Emergency sent with your location"
            refresh()
        } catch (e: Exception) {
            _message.value = "Emergency NOT sent: ${friendlyError(e)}"
        } finally { _sending.value = false }
    }

    fun cancelMine(a: Alert) = scope.launch {
        runCatching { backend.updateAlert(a.id, "cancelled") }
            .onSuccess { engine.signal("alert"); engine.signal("sync"); refresh() }
            .onFailure { _message.value = friendlyError(it) }
    }

    fun silence(a: Alert) { silenced += a.id; updateAlarm() }

    fun isMine(a: Alert) = a.userId != null && a.userId == myId

    fun refresh() = scope.launch {
        val list = runCatching { backend.openEmergencies(engine.state.value.channel?.id) }
            .getOrElse { Log.w("Emergency", "refresh", it); return@launch }
        _alerts.value = list
        updateAlarm()
    }

    /** Loud repeating alarm for other people's emergencies until silenced or resolved (max 2 minutes each). */
    private fun updateAlarm() {
        val ringing = _alerts.value.filter { !isMine(it) && it.id !in silenced && minutesSince(it) < 2 }
        if (ringing.isEmpty()) { alarmJob?.cancel(); alarmJob = null; return }
        if (alarmJob?.isActive == true) return
        alarmJob = scope.launch {
            val tg = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
            try {
                while (isActive) {
                    tg?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1200)
                    vibrate(longArrayOf(0, 600, 300, 600))
                    delay(2_000)
                    if (_alerts.value.none { !isMine(it) && it.id !in silenced && minutesSince(it) < 2 }) break
                }
            } finally { tg?.release() }
        }
    }

    private fun minutesSince(a: Alert) = com.pttconnect.app.data.minutesAgo(a.createdAt)

    private fun vibrate(pattern: LongArray) = runCatching {
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
