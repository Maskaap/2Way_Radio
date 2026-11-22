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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import com.pttconnect.app.BuildConfig
import com.pttconnect.app.data.AudioOutput
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.friendlyError
import com.pttconnect.app.data.Settings
import com.pttconnect.app.data.VolumeUpMode
import com.pttconnect.app.ptt.KeyRouter
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: Settings, backend: Backend, keys: KeyRouter, onSubscription: () -> Unit, onBack: () -> Unit, onSignOut: () -> Unit) {
    val p by settings.prefs.collectAsState()
    var learning by remember { mutableStateOf(false) }
    var showPw by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) { onDispose { keys.learner = null } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Pal.text) }
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        }

        OutlinedButton(onClick = onSubscription, modifier = Modifier.fillMaxWidth()) { Text("Subscription & billing") }

        Section("PTT tones")
        Toggle("Talk-permit tone (start)", p.startToneEnabled) { v -> settings.update { it.copy(startToneEnabled = v) } }
        Toggle("End tone (roger beep)", p.endToneEnabled) { v -> settings.update { it.copy(endToneEnabled = v) } }
        Text("Tone length: ${p.toneMs} ms", color = Pal.text)
        Slider(p.toneMs.toFloat(), { v -> settings.update { it.copy(toneMs = v.toInt()) } }, valueRange = 40f..500f)
        Text("Tone volume: ${p.toneVolume}%", color = Pal.text)
        Slider(p.toneVolume.toFloat(), { v -> settings.update { it.copy(toneVolume = v.toInt()) } }, valueRange = 0f..100f)
        Toggle("Vibrate when you get the channel", p.vibrateOnGrant) { v -> settings.update { it.copy(vibrateOnGrant = v) } }

        Section("Location")
        Toggle("Share my location while connected", p.shareLocation) { v -> settings.update { it.copy(shareLocation = v) } }
        Note("Used for the dispatcher map, geo-fence alerts and to send your position with an SOS. " +
            "Only administrators can see it. Turning this off removes you from the map.")

        Section("NFC checkpoints")
        val nfcStatus = remember { com.pttconnect.app.ptt.NfcCheckpoints.statusOf(ctx) }
        Note(when (nfcStatus) {
            com.pttconnect.app.ptt.NfcStatus.READY -> "NFC is on. Hold the back of the phone against a checkpoint tag to check in."
            com.pttconnect.app.ptt.NfcStatus.OFF -> "NFC is switched off on this phone."
            com.pttconnect.app.ptt.NfcStatus.UNAVAILABLE -> "This phone has no NFC reader."
        })
        if (nfcStatus == com.pttconnect.app.ptt.NfcStatus.OFF) OutlinedButton(onClick = {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }
        }) { Text("Turn on NFC") }

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
        OutlinedButton(onClick = { showPw = true }, modifier = Modifier.fillMaxWidth()) { Text("Change password") }
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out", color = Pal.red) }
    }

    if (showPw) ChangePasswordDialog(backend) { showPw = false }
}

@Composable
private fun ChangePasswordDialog(backend: Backend, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!done) {
                    OutlinedTextField(p1, { p1 = it }, label = { Text("New password (min. 8)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(p2, { p2 = it }, label = { Text("Type it again") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
                msg?.let { Text(it, color = if (done) Pal.green else Pal.red) }
            }
        },
        confirmButton = {
            if (done) TextButton(onClick = onClose) { Text("OK") }
            else TextButton(enabled = !busy, onClick = {
                when {
                    p1.length < 8 -> msg = "Use at least 8 characters"
                    p1 != p2 -> msg = "The two passwords are not the same"
                    else -> { busy = true; scope.launch {
                        try { backend.changePassword(p1); done = true; msg = "Password changed. Use it next time you sign in." }
                        catch (e: Exception) { msg = friendlyError(e) }
                        busy = false
                    } }
                }
            }) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { if (!done) TextButton(onClick = onClose) { Text("Cancel") } },
    )
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
