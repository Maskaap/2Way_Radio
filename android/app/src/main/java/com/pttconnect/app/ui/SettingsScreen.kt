package com.pttconnect.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pttconnect.app.BuildConfig
import com.pttconnect.app.data.AudioOutput
import com.pttconnect.app.data.Settings
import com.pttconnect.app.data.VolumeUpMode
import com.pttconnect.app.ptt.KeyRouter

@Composable
fun SettingsScreen(settings: Settings, keys: KeyRouter, onBack: () -> Unit, onSignOut: () -> Unit) {
    val p by settings.prefs.collectAsState()
    var learning by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { keys.learner = null } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Pal.text) }
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        }

        Section("PTT tones")
        Toggle("Talk-permit tone (start)", p.startToneEnabled) { v -> settings.update { it.copy(startToneEnabled = v) } }
        Toggle("End tone (roger beep)", p.endToneEnabled) { v -> settings.update { it.copy(endToneEnabled = v) } }
        Text("Tone length: ${p.toneMs} ms", color = Pal.text)
        Slider(p.toneMs.toFloat(), { v -> settings.update { it.copy(toneMs = v.toInt()) } }, valueRange = 40f..500f)
        Text("Tone volume: ${p.toneVolume}%", color = Pal.text)
        Slider(p.toneVolume.toFloat(), { v -> settings.update { it.copy(toneVolume = v.toInt()) } }, valueRange = 0f..100f)
        Toggle("Vibrate when you get the channel", p.vibrateOnGrant) { v -> settings.update { it.copy(vibrateOnGrant = v) } }

        Section("Audio output")
        Choice("Loudspeaker (default)", p.audioOutput == AudioOutput.SPEAKER) { settings.update { it.copy(audioOutput = AudioOutput.SPEAKER) } }
        Choice("Earpiece", p.audioOutput == AudioOutput.EARPIECE) { settings.update { it.copy(audioOutput = AudioOutput.EARPIECE) } }
        Note("A connected Bluetooth or wired headset is always used first.")

        Section("Hardware buttons")
        Text("Push-to-talk keys (hold to talk):", color = Pal.text)
        p.pttKeyCodes.sorted().forEach { code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("• ${KeyRouter.keyName(code)}", color = Pal.text, modifier = Modifier.weight(1f))
                if (p.pttKeyCodes.size > 1 || code != KeyEvent.KEYCODE_VOLUME_DOWN)
                    TextButton(onClick = { settings.update { it.copy(pttKeyCodes = it.pttKeyCodes - code) } }) { Text("Remove", color = Pal.red) }
            }
        }
        OutlinedButton(onClick = {
            learning = true
            keys.learner = { code -> settings.update { it.copy(pttKeyCodes = it.pttKeyCodes + code) }; learning = false }
        }) { Text(if (learning) "Press the button you want to use…" else "Learn a new PTT key") }
        if (learning) TextButton(onClick = { keys.learner = null; learning = false }) { Text("Cancel") }

        Text("Volume Up button:", color = Pal.text, modifier = Modifier.padding(top = 8.dp))
        Choice("Normal volume control", p.volumeUpMode == VolumeUpMode.VOLUME) { settings.update { it.copy(volumeUpMode = VolumeUpMode.VOLUME) } }
        Choice("Switch to next channel", p.volumeUpMode == VolumeUpMode.NEXT_CHANNEL) { settings.update { it.copy(volumeUpMode = VolumeUpMode.NEXT_CHANNEL) } }

        Note("Phase 1: hardware keys work while PTT Connect is open on screen. " +
            "The power button cannot be used – Android doesn't give it to apps. " +
            "Bluetooth PTT buttons and screen-off talk come in Phase 2.")

        Section("About")
        Note("Version ${BuildConfig.VERSION_NAME}\nServer: ${settings.apiBaseUrl}\nDevice ID: ${settings.deviceUid}")
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out", color = Pal.red) }
    }
}

@Composable private fun Section(t: String) {
    HorizontalDivider(color = Pal.line, modifier = Modifier.padding(top = 12.dp))
    Text(t.uppercase(), color = Pal.green, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}
@Composable private fun Note(t: String) = Text(t, color = Pal.muted, fontSize = 13.sp)
@Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Pal.text, modifier = Modifier.weight(1f)); Switch(value, onChange)
    }
@Composable private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label, color = Pal.text) }
