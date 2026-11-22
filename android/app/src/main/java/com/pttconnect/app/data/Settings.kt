package com.pttconnect.app.data

import android.content.Context
import android.view.KeyEvent
import com.pttconnect.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class VolumeUpMode { VOLUME, NEXT_CHANNEL }
enum class AudioOutput { SPEAKER, EARPIECE }

data class PttPrefs(
    val startToneEnabled: Boolean = true,
    val endToneEnabled: Boolean = true,
    val toneMs: Int = 120,
    val toneVolume: Int = 70,
    val volumeUpMode: VolumeUpMode = VolumeUpMode.VOLUME,
    val pttKeyCodes: Set<Int> = setOf(KeyEvent.KEYCODE_VOLUME_DOWN),
    val audioOutput: AudioOutput = AudioOutput.SPEAKER,
    val vibrateOnGrant: Boolean = true,
)

/** Local app settings (SharedPreferences, private to the app). */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("ptt_settings", Context.MODE_PRIVATE)

    /** Random per-install ID used for device registration / blocking. Not the hardware IMEI. */
    val deviceUid: String = sp.getString("device_uid", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("device_uid", it).apply()
    }

    var apiBaseUrl: String
        get() = sp.getString("api_base_url", null) ?: BuildConfig.API_BASE_URL
        set(v) = sp.edit().putString("api_base_url", v.trim().trimEnd('/')).apply()

    var lastChannelId: String?
        get() = sp.getString("last_channel", null)
        set(v) = sp.edit().putString("last_channel", v).apply()

    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<PttPrefs> = _prefs

    fun update(block: (PttPrefs) -> PttPrefs) {
        val p = block(_prefs.value).let { it.copy(toneMs = it.toneMs.coerceIn(40, 500), toneVolume = it.toneVolume.coerceIn(0, 100)) }
        sp.edit()
            .putBoolean("start_tone", p.startToneEnabled)
            .putBoolean("end_tone", p.endToneEnabled)
            .putInt("tone_ms", p.toneMs)
            .putInt("tone_vol", p.toneVolume)
            .putString("vol_up_mode", p.volumeUpMode.name)
            .putStringSet("ptt_keys", p.pttKeyCodes.map { it.toString() }.toSet())
            .putString("audio_out", p.audioOutput.name)
            .putBoolean("vibrate", p.vibrateOnGrant)
            .apply()
        _prefs.value = p
    }

    private fun load(): PttPrefs {
        val d = PttPrefs()
        return PttPrefs(
            startToneEnabled = sp.getBoolean("start_tone", d.startToneEnabled),
            endToneEnabled = sp.getBoolean("end_tone", d.endToneEnabled),
            toneMs = sp.getInt("tone_ms", d.toneMs),
            toneVolume = sp.getInt("tone_vol", d.toneVolume),
            volumeUpMode = runCatching { VolumeUpMode.valueOf(sp.getString("vol_up_mode", null)!!) }.getOrDefault(d.volumeUpMode),
            pttKeyCodes = sp.getStringSet("ptt_keys", null)?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.pttKeyCodes,
            audioOutput = runCatching { AudioOutput.valueOf(sp.getString("audio_out", null)!!) }.getOrDefault(d.audioOutput),
            vibrateOnGrant = sp.getBoolean("vibrate", d.vibrateOnGrant),
        )
    }

    // ---- auth session persistence ----
    fun saveSession(json: String?) = sp.edit().putString("session", json).apply()
    fun loadSession(): String? = sp.getString("session", null)
}
