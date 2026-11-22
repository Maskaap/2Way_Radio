package com.pttconnect.app.ptt

import android.view.KeyEvent
import com.pttconnect.app.data.Settings
import com.pttconnect.app.data.VolumeUpMode

/**
 * Maps physical key events (delivered to the foreground Activity) to PTT actions.
 *
 * Phase 1 scope – honest limits:
 *  - Works while PTT Connect is the visible app (screen on). Android does not deliver volume-key
 *    events to normal apps when they are in the background or the screen is off.
 *  - Any key that Android delivers as a KeyEvent can be assigned via "Learn key" (e.g. the
 *    side PTT key on many rugged phones, camera key, headset button).
 *  - The POWER key cannot be used: Android reserves it for the system and does not deliver it
 *    to third-party apps. We don't pretend otherwise.
 */
class KeyRouter(private val settings: Settings, private val engine: PttEngine) {

    /** When set, the next key press is captured for assignment instead of acting. */
    @Volatile var learner: ((Int) -> Unit)? = null

    /** @return true if the event was consumed. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        learner?.let { l ->
            if (e.action == KeyEvent.ACTION_UP && code !in UNASSIGNABLE) { learner = null; l(code) }
            return code !in UNASSIGNABLE
        }
        val p = settings.prefs.value
        if (code in p.pttKeyCodes) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) engine.pttDown(PttSource.HARDWARE_KEY)
                KeyEvent.ACTION_UP -> engine.pttUp(PttSource.HARDWARE_KEY)
            }
            return true // swallow repeats too, so the system volume doesn't change
        }
        if (code == KeyEvent.KEYCODE_VOLUME_UP && p.volumeUpMode == VolumeUpMode.NEXT_CHANNEL) {
            if (e.action == KeyEvent.ACTION_UP) engine.nextChannel()
            return true
        }
        return false
    }

    companion object {
        val UNASSIGNABLE = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH)

        fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
            .lowercase().replaceFirstChar { it.uppercase() }
    }
}
