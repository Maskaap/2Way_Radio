package com.pttconnect.app.voice

import android.content.Context
import android.util.Log
import com.pttconnect.app.data.AudioOutput
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

sealed interface VoiceEvent {
    data object Connected : VoiceEvent
    data object Reconnecting : VoiceEvent
    data object Reconnected : VoiceEvent
    data class Disconnected(val reason: String) : VoiceEvent
    /** A remote user's microphone track became audible (= they are transmitting). */
    data class RemoteTalkStart(val userId: String, val name: String) : VoiceEvent
    data class RemoteTalkEnd(val userId: String) : VoiceEvent
    data class Participants(val names: List<String>) : VoiceEvent
    /** Fast "I'm keying up" hint sent over the data channel before audio arrives. */
    data class Signal(val userId: String, val name: String, val text: String) : VoiceEvent
}

/**
 * Thin wrapper around a LiveKit Room = one PTT channel.
 *
 * Microphone policy: the mic track exists ONLY while transmitting. On release the track is
 * unpublished and stopped, so Android's recording indicator goes off and no audio is captured
 * while idle. Re-publishing on the next press costs ~100–300 ms, covered by the talk-permit tone.
 */
class VoiceLink(private val appContext: Context, private val scope: CoroutineScope) {
    private var room: Room? = null
    private var eventsJob: Job? = null
    private var userDisconnect = false

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceEvent> = _events

    val isConnected get() = room != null

    suspend fun connect(url: String, token: String, output: AudioOutput) {
        disconnect()
        userDisconnect = false
        val r = LiveKit.create(appContext, RoomOptions(adaptiveStream = false, dynacast = false))
        applyOutput(r, output)
        room = r
        eventsJob = scope.launch { r.events.collect { handle(r, it) } }
        r.connect(url, token)
        _events.emit(VoiceEvent.Connected)
        emitParticipants(r)
        // Someone may already be talking when we join.
        r.remoteParticipants.values.forEach { p ->
            if (p.getTrackPublication(Track.Source.MICROPHONE)?.track != null) {
                _events.emit(VoiceEvent.RemoteTalkStart(p.identity?.value ?: "", p.displayName()))
            }
        }
    }

    fun setOutput(output: AudioOutput) { room?.let { applyOutput(it, output) } }

    private fun applyOutput(r: Room, output: AudioOutput) {
        r.audioSwitchHandler?.preferredDeviceList = when (output) {
            AudioOutput.SPEAKER -> listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java, AudioDevice.Earpiece::class.java)
            AudioOutput.EARPIECE -> listOf(AudioDevice.BluetoothHeadset::class.java, AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java, AudioDevice.Speakerphone::class.java)
        }
    }

    /** Opens + publishes the microphone. Returns false if it failed (permission, no publish right). */
    suspend fun startMic(): Boolean {
        val lp = room?.localParticipant ?: return false
        return try { lp.setMicrophoneEnabled(true) } catch (e: Exception) { Log.w(TAG, "startMic", e); false }
    }

    /** Unpublishes and STOPS the microphone track (mic fully closed). Safe to call repeatedly. */
    fun stopMic() {
        val lp = room?.localParticipant ?: return
        try {
            lp.getTrackPublication(Track.Source.MICROPHONE)?.track?.let { lp.unpublishTrack(it, stopOnUnpublish = true) }
        } catch (e: Exception) { Log.w(TAG, "stopMic", e) }
    }

    fun signal(text: String) {
        val lp = room?.localParticipant ?: return
        scope.launch { runCatching { lp.publishData(text.toByteArray(), DataPublishReliability.RELIABLE, topic = "ptt") } }
    }

    fun disconnect() {
        userDisconnect = true
        eventsJob?.cancel(); eventsJob = null
        room?.let { r -> stopMicOf(r); r.disconnect(); r.release() }
        room = null
    }

    private fun stopMicOf(r: Room) = runCatching {
        r.localParticipant.getTrackPublication(Track.Source.MICROPHONE)?.track?.let { r.localParticipant.unpublishTrack(it) }
    }

    private suspend fun handle(r: Room, e: RoomEvent) {
        when (e) {
            is RoomEvent.TrackSubscribed -> if (e.track.kind == Track.Kind.AUDIO)
                _events.emit(VoiceEvent.RemoteTalkStart(e.participant.identity?.value ?: "", e.participant.displayName()))
            is RoomEvent.TrackUnsubscribed -> if (e.track.kind == Track.Kind.AUDIO)
                _events.emit(VoiceEvent.RemoteTalkEnd(e.participant.identity?.value ?: ""))
            is RoomEvent.ParticipantConnected -> emitParticipants(r)
            is RoomEvent.ParticipantDisconnected -> {
                _events.emit(VoiceEvent.RemoteTalkEnd(e.participant.identity?.value ?: ""))
                emitParticipants(r)
            }
            is RoomEvent.DataReceived -> if (e.topic == "ptt" && e.participant != null)
                _events.emit(VoiceEvent.Signal(e.participant!!.identity?.value ?: "", e.participant!!.displayName(), String(e.data)))
            is RoomEvent.Reconnecting -> _events.emit(VoiceEvent.Reconnecting)
            is RoomEvent.Reconnected -> _events.emit(VoiceEvent.Reconnected)
            is RoomEvent.Disconnected -> if (!userDisconnect && room === r) {
                room = null
                _events.emit(VoiceEvent.Disconnected(e.reason.name))
            }
            else -> Unit
        }
    }

    private suspend fun emitParticipants(r: Room) {
        _events.emit(VoiceEvent.Participants(r.remoteParticipants.values.map { it.displayName() }.sorted()))
    }

    private fun RemoteParticipant.displayName() = name?.takeIf { it.isNotBlank() } ?: identity?.value?.take(8) ?: "?"

    companion object { private const val TAG = "VoiceLink" }
}
