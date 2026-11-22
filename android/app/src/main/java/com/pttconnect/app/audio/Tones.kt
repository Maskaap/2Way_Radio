package com.pttconnect.app.audio

import android.media.AudioManager
import android.media.ToneGenerator
import com.pttconnect.app.data.PttPrefs
import kotlinx.coroutines.delay

/**
 * Short PTT tones, played on the voice-call stream so they follow the same
 * route (speaker / earpiece / Bluetooth) as the received voice.
 */
class Tones {
    private var gen: ToneGenerator? = null
    private var genVolume = -1

    private fun generator(volume: Int): ToneGenerator? {
        if (gen == null || genVolume != volume) {
            gen?.release()
            gen = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume) }.getOrNull()
            genVolume = volume
        }
        return gen
    }

    private fun play(type: Int, ms: Int, p: PttPrefs) {
        if (p.toneVolume == 0) return
        generator(p.toneVolume)?.startTone(type, ms)
    }

    /** Talk-permit tone. Suspends for its duration so the first word isn't clipped by it. */
    suspend fun start(p: PttPrefs) { if (p.startToneEnabled) { play(ToneGenerator.TONE_PROP_BEEP, p.toneMs, p); delay(p.toneMs.toLong()) } }
    fun end(p: PttPrefs) { if (p.endToneEnabled) play(ToneGenerator.TONE_PROP_ACK, p.toneMs, p) }
    fun busy(p: PttPrefs) = play(ToneGenerator.TONE_SUP_BUSY, 700, p)
    fun error(p: PttPrefs) = play(ToneGenerator.TONE_PROP_NACK, 300, p)
    fun incoming(p: PttPrefs) { if (p.startToneEnabled) play(ToneGenerator.TONE_PROP_BEEP2, (p.toneMs * 0.7).toInt(), p) }

    fun release() { gen?.release(); gen = null }
}
