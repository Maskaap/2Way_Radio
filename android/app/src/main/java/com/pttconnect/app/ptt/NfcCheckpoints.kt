package com.pttconnect.app.ptt

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.VibrationEffect
import android.os.Vibrator
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.friendlyError
import com.pttconnect.app.net.LocationReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ScanResult(
    val ok: Boolean,
    val known: Boolean,
    val tagUid: String,
    val tagName: String?,
    val site: String?,
    val canRegister: Boolean,   // admin scanned an unknown tag
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

enum class NfcStatus { UNAVAILABLE, OFF, READY }

/**
 * NFC patrol checkpoints. While the app is open the phone reads any tag it touches (reader mode);
 * tapping a tag while the app is closed opens it and records the scan too.
 * Each scan is stored with time + GPS. Admins can register an unknown tag straight from the phone.
 */
class NfcCheckpoints(
    private val context: Context,
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val location: LocationReporter,
) {
    private val _last = MutableStateFlow<ScanResult?>(null)
    val last: StateFlow<ScanResult?> = _last

    fun status(): NfcStatus = statusOf(context)

    companion object {
        fun statusOf(context: Context): NfcStatus {
            val a = NfcAdapter.getDefaultAdapter(context) ?: return NfcStatus.UNAVAILABLE
            return if (a.isEnabled) NfcStatus.READY else NfcStatus.OFF
        }
    }

    /** Call from Activity.onResume / onPause. */
    fun enable(activity: Activity) {
        val a = NfcAdapter.getDefaultAdapter(activity) ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        runCatching { a.enableReaderMode(activity, { tag: Tag -> onTagId(tag.id) }, flags, null) }
    }

    fun disable(activity: Activity) { runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) } }

    fun onTagId(id: ByteArray?) {
        if (id == null || id.isEmpty()) return
        val uid = id.joinToString("") { "%02X".format(it) }
        if (backend.session.value == null) { show(ScanResult(false, false, uid, null, null, false, "Sign in first to record checkpoints")); return }
        scope.launch {
            val loc = location.bestLocation()
            try {
                val r = backend.recordTagScan(uid, loc?.latitude, loc?.longitude)
                val known = r.optBoolean("known")
                val name = r.optString("tag_name").takeIf { it.isNotBlank() && it != "null" }
                val site = r.optString("site").takeIf { it.isNotBlank() && it != "null" }
                val admin = r.optBoolean("is_admin")
                feedback(known)
                show(ScanResult(true, known, uid, name, site, canRegister = !known && admin,
                    text = when {
                        known -> "✓ Checked in: $name${site?.let { " ($it)" } ?: ""}"
                        admin -> "New tag $uid – register it as a checkpoint?"
                        else -> "Unregistered tag $uid (scan saved – ask your admin to register it)"
                    }))
            } catch (e: Exception) {
                feedback(false)
                show(ScanResult(false, false, uid, null, null, false, "Scan NOT saved: ${friendlyError(e)}"))
            }
        }
    }

    fun register(uid: String, name: String, site: String?, everyMinutes: Int?) = scope.launch {
        val loc = location.bestLocation()
        try {
            backend.registerTag(uid, name, site, loc?.latitude, loc?.longitude, everyMinutes)
            backend.recordTagScan(uid, loc?.latitude, loc?.longitude)
            feedback(true)
            show(ScanResult(true, true, uid, name, site, false, "✓ Checkpoint \"$name\" registered${if (loc != null) " at this location" else ""}"))
        } catch (e: Exception) {
            show(ScanResult(false, false, uid, null, null, false, "Could not register: ${friendlyError(e)}"))
        }
    }

    fun dismiss() { _last.value = null }

    private fun show(r: ScanResult) { _last.value = r }

    private fun feedback(good: Boolean) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tg.startTone(if (good) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 250)
            scope.launch { kotlinx.coroutines.delay(400); tg.release() }
        }
        runCatching {
            context.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(if (good) longArrayOf(0, 80, 60, 80) else longArrayOf(0, 400), -1))
        }
    }
}
