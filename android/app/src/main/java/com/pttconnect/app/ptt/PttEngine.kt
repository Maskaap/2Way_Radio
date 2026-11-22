package com.pttconnect.app.ptt

import android.util.Log
import com.pttconnect.app.audio.Tones
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.BackendException
import com.pttconnect.app.data.Channel
import com.pttconnect.app.data.Settings
import com.pttconnect.app.data.friendlyError
import com.pttconnect.app.net.NetType
import com.pttconnect.app.net.NetworkMonitor
import com.pttconnect.app.voice.VoiceEvent
import com.pttconnect.app.voice.VoiceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

enum class ConnState { IDLE, CONNECTING, CONNECTED, RECONNECTING, ERROR }
enum class TxState { IDLE, REQUESTING, TRANSMITTING, BUSY, DENIED }
enum class PttSource { SCREEN, HARDWARE_KEY, HEADSET }

data class PttUiState(
    val conn: ConnState = ConnState.IDLE,
    val network: NetType = NetType.NONE,
    val channel: Channel? = null,
    val canTalk: Boolean = true,
    val tx: TxState = TxState.IDLE,
    val micOpen: Boolean = false,
    val txEndsAtMs: Long? = null,
    val remoteSpeaker: String? = null,
    val participants: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * The walkie-talkie brain. Every PTT source (screen button, volume key, later Bluetooth)
 * calls [pttDown]/[pttUp]. Rules:
 *  - Half duplex: the floor is granted atomically by Supabase (request_floor). Busy → busy tone.
 *  - The mic is opened only after a grant, and fully closed on release.
 *  - Stuck-button / lost-network safety: keep-alive every 1.5 s; if a keep-alive fails, the server
 *    lease (5 s) or the channel's max talk time runs out → transmission is cut locally too.
 *  - Several sources can be held at once; transmission ends when the last one is released.
 */
class PttEngine(
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val voice: VoiceLink,
    private val settings: Settings,
    private val tones: Tones,
    network: NetworkMonitor,
) {
    private val _state = MutableStateFlow(PttUiState(network = network.type.value))
    val state: StateFlow<PttUiState> = _state

    /** One-shot UI effects (haptics etc.). */
    private val _effects = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val effects: SharedFlow<String> = _effects

    private val held = mutableSetOf<PttSource>()
    private var txJob: Job? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var channels: List<Channel> = emptyList()
    private val remoteTalkers = linkedMapOf<String, String>() // userId -> name

    private val prefs get() = settings.prefs.value

    init {
        scope.launch { voice.events.collect { onVoiceEvent(it) } }
        scope.launch {
            var last = network.type.value
            network.type.collect { t ->
                _state.update { it.copy(network = t) }
                // Came back online after being offline and LiveKit gave up → reconnect now.
                if (last == NetType.NONE && t != NetType.NONE && _state.value.channel != null &&
                    (_state.value.conn == ConnState.ERROR || !voice.isConnected)) reconnect(immediate = true)
                last = t
            }
        }
        scope.launch { settings.prefs.collect { voice.setOutput(it.audioOutput) } }
    }

    // ------------------------------------------------------------------ channels
    suspend fun loadChannels(): List<Channel> = backend.myChannels().also { channels = it }

    fun enterChannel(ch: Channel) {
        connectJob?.cancel()
        connectJob = scope.launch {
            forceStopTx("channel_switch")
            val previous = _state.value.channel
            remoteTalkers.clear()
            _state.update { it.copy(channel = ch, conn = ConnState.CONNECTING, remoteSpeaker = null, participants = emptyList(), message = null) }
            if (previous != null && previous.id != ch.id) runCatching { backend.exitChannel(previous.id) }
            connectWithRetry(ch)
        }
    }

    /** Volume-Up "next channel" mode. Only member channels, skips direct calls. */
    fun nextChannel(step: Int = 1) {
        val list = channels.filter { it.isMember && it.kind != "direct" }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == _state.value.channel?.id }
        enterChannel(list[((if (idx < 0) 0 else idx + step) % list.size + list.size) % list.size])
    }

    /** Call after a channel was deleted or you left it: disconnects if it's the current one. */
    fun channelGone(channelId: String) {
        channels = channels.filter { it.id != channelId }
        if (_state.value.channel?.id == channelId) {
            leaveChannel()
            if (settings.lastChannelId == channelId) settings.lastChannelId = null
        }
    }

    fun leaveChannel() {
        connectJob?.cancel(); heartbeatJob?.cancel()
        val ch = _state.value.channel
        scope.launch {
            forceStopTx("leave")
            voice.disconnect()
            if (ch != null) runCatching { backend.exitChannel(ch.id) }
        }
        remoteTalkers.clear()
        _state.update { PttUiState(network = it.network) }
    }

    private suspend fun connectWithRetry(ch: Channel) {
        var backoff = 1_000L
        while (scope.isActive) {
            try {
                val (blocked, reason) = backend.registerDevice()
                if (blocked) throw BackendException("device_blocked", reason ?: "device_blocked")
                val info = backend.enterChannel(ch.id)
                val canTalk = info.optBoolean("can_talk", true)
                val (url, token) = backend.liveKitToken(ch.id)
                voice.connect(url, token, prefs.audioOutput)
                settings.lastChannelId = ch.id
                _state.update { it.copy(conn = ConnState.CONNECTED, canTalk = canTalk, message = null) }
                startHeartbeat(ch.id)
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "connect failed", e)
                val fatal = (e as? BackendException)?.code in setOf("device_blocked", "account_disabled", "not_a_member", "session_expired", "not_signed_in", "subscription_required", "plan_limit_members", "channel_not_found")
                _state.update { it.copy(conn = ConnState.ERROR, message = friendlyError(e)) }
                if (fatal) return
                delay(backoff)
                backoff = min(backoff * 2, 15_000L)
                _state.update { it.copy(conn = ConnState.RECONNECTING) }
            }
        }
    }

    private fun reconnect(immediate: Boolean = false) {
        val ch = _state.value.channel ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            if (!immediate) delay(500)
            _state.update { it.copy(conn = ConnState.RECONNECTING) }
            connectWithRetry(ch)
        }
    }

    private fun startHeartbeat(channelId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                runCatching { backend.heartbeat(channelId) }
                delay(30_000)
            }
        }
    }

    // ------------------------------------------------------------------ PTT
    fun pttDown(source: PttSource) {
        val first = held.isEmpty()
        held += source
        if (!first) return
        val s = _state.value
        val ch = s.channel
        when {
            ch == null || s.conn != ConnState.CONNECTED -> { tones.error(prefs); flash("Not connected"); return }
            !s.canTalk -> { tones.error(prefs); _state.update { it.copy(tx = TxState.DENIED, message = "Listen-only in this channel") }; return }
            remoteTalkers.isNotEmpty() -> { tones.busy(prefs); _state.update { it.copy(tx = TxState.BUSY) }; return }
        }
        txJob?.cancel()
        txJob = scope.launch { transmit(ch!!) }
    }

    fun pttUp(source: PttSource) {
        held -= source
        if (held.isNotEmpty()) return
        when (_state.value.tx) {
            TxState.BUSY, TxState.DENIED -> _state.update { it.copy(tx = TxState.IDLE) }
            TxState.REQUESTING, TxState.TRANSMITTING -> scope.launch { stopTx(cutByServer = false) }
            TxState.IDLE -> Unit
        }
    }

    /** Called when the app loses focus etc. – a key-up may never arrive. */
    fun releaseAll() {
        if (held.isEmpty()) return
        held.clear()
        pttUp(PttSource.SCREEN)
    }

    private suspend fun transmit(ch: Channel) {
        _state.update { it.copy(tx = TxState.REQUESTING) }
        val floor = try { backend.requestFloor(ch.id) } catch (e: Exception) {
            tones.error(prefs); _state.update { it.copy(tx = TxState.IDLE, message = friendlyError(e)) }; return
        }
        if (!floor.granted) {
            tones.busy(prefs)
            _state.update { it.copy(tx = if (held.isEmpty()) TxState.IDLE else if (floor.reason == "listen_only") TxState.DENIED else TxState.BUSY,
                remoteSpeaker = floor.holderName ?: it.remoteSpeaker) }
            return
        }
        if (held.isEmpty()) { runCatching { backend.releaseFloor(ch.id) }; _state.update { it.copy(tx = TxState.IDLE) }; return }

        voice.signal("tx_start")
        val micOk = voice.startMic()
        tones.start(prefs)
        if (!micOk) {
            runCatching { backend.releaseFloor(ch.id) }
            tones.error(prefs)
            _state.update { it.copy(tx = TxState.IDLE, message = "Microphone unavailable – check permission") }
            return
        }
        val endsAt = System.currentTimeMillis() + floor.maxMs
        _state.update { it.copy(tx = TxState.TRANSMITTING, micOpen = true, txEndsAtMs = endsAt, message = null) }
        if (prefs.vibrateOnGrant) _effects.tryEmit("granted")

        if (held.isEmpty()) { stopTx(cutByServer = false); return }

        // Keep-alive / watchdog loop
        var failures = 0
        while (scope.isActive && _state.value.tx == TxState.TRANSMITTING) {
            delay(1_500)
            if (System.currentTimeMillis() >= endsAt) { stopTx(cutByServer = true, why = "Maximum talk time reached"); return }
            val ok = try { backend.keepaliveFloor(ch.id).also { failures = 0 } } catch (e: Exception) { failures++; true }
            if (!ok) { stopTx(cutByServer = true, why = "Transmission ended by server"); return }
            if (failures >= 3) { stopTx(cutByServer = true, why = "Connection lost while talking"); return }
        }
    }

    private suspend fun stopTx(cutByServer: Boolean, why: String? = null) = withContext(NonCancellable) {
        val wasTx = _state.value.tx == TxState.TRANSMITTING
        // Cancelling is safe even when called from inside txJob: this block is NonCancellable.
        txJob?.cancel()
        voice.stopMic()
        _state.update { it.copy(tx = TxState.IDLE, micOpen = false, txEndsAtMs = null, message = why) }
        if (wasTx) {
            voice.signal("tx_end")
            if (cutByServer) tones.error(prefs) else tones.end(prefs)
        }
        _state.value.channel?.let { ch -> scope.launch { runCatching { backend.releaseFloor(ch.id) } } }
        if (cutByServer) held.clear()
    }

    private suspend fun forceStopTx(reason: String) {
        if (_state.value.tx == TxState.REQUESTING || _state.value.tx == TxState.TRANSMITTING) stopTx(cutByServer = false, why = null)
        held.clear()
    }

    private fun flash(msg: String) = _state.update { it.copy(message = msg) }

    // ------------------------------------------------------------------ voice events
    private suspend fun onVoiceEvent(e: VoiceEvent) {
        when (e) {
            is VoiceEvent.RemoteTalkStart, is VoiceEvent.Signal -> {
                val (id, name) = when (e) {
                    is VoiceEvent.RemoteTalkStart -> e.userId to e.name
                    is VoiceEvent.Signal -> if (e.text == "tx_start") e.userId to e.name else { remoteTalkers.remove(e.userId); updateSpeaker(); return }
                    else -> return
                }
                val wasQuiet = remoteTalkers.isEmpty()
                remoteTalkers[id] = name
                updateSpeaker()
                if (wasQuiet && _state.value.tx != TxState.TRANSMITTING) tones.incoming(prefs)
            }
            is VoiceEvent.RemoteTalkEnd -> { remoteTalkers.remove(e.userId); updateSpeaker() }
            is VoiceEvent.Participants -> _state.update { it.copy(participants = e.names) }
            VoiceEvent.Connected, VoiceEvent.Reconnected -> _state.update { it.copy(conn = ConnState.CONNECTED, message = null) }
            VoiceEvent.Reconnecting -> _state.update { it.copy(conn = ConnState.RECONNECTING) }
            is VoiceEvent.Disconnected -> {
                forceStopTx("disconnected")
                remoteTalkers.clear(); updateSpeaker()
                if (_state.value.channel != null) reconnect()
            }
        }
    }

    private fun updateSpeaker() {
        val speaker = remoteTalkers.values.lastOrNull()
        _state.update { it.copy(remoteSpeaker = speaker, tx = if (speaker == null && it.tx == TxState.BUSY && held.isEmpty()) TxState.IDLE else it.tx) }
    }

    companion object { private const val TAG = "PttEngine" }
}
