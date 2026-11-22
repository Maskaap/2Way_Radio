package com.pttconnect.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.Channel
import com.pttconnect.app.data.RosterEntry
import com.pttconnect.app.data.friendlyError
import com.pttconnect.app.net.NetType
import com.pttconnect.app.ptt.ConnState
import com.pttconnect.app.ptt.PttEngine
import com.pttconnect.app.ptt.PttSource
import com.pttconnect.app.ptt.TxState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PttScreen(
    engine: PttEngine,
    backend: Backend,
    onChannels: () -> Unit,
    onSettings: () -> Unit,
    onEnterChannel: (Channel) -> Unit,
) {
    val s by engine.state.collectAsState()
    val ctx = LocalContext.current
    val view = LocalView.current
    var showMembers by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        engine.effects.collect { if (it == "granted") view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }

    // Countdown while transmitting
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.tx) { while (s.tx == TxState.TRANSMITTING) { now = System.currentTimeMillis(); delay(250) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // ---- status bar
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NetChip(s.network)
            Spacer(Modifier.width(8.dp))
            ConnChip(s.conn)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Icon(Icons.Filled.Bluetooth, "Bluetooth", tint = Pal.muted)
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Pal.muted) }
        }

        // ---- channel switcher
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { engine.nextChannel(-1) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.ChevronLeft, "Previous channel", tint = Pal.text, modifier = Modifier.size(40.dp))
            }
            Column(Modifier.weight(1f).clickable(onClick = onChannels), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.channel?.name ?: "No channel", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Pal.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text("tap for all channels", fontSize = 12.sp, color = Pal.muted)
            }
            IconButton(onClick = { engine.nextChannel(1) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.ChevronRight, "Next channel", tint = Pal.text, modifier = Modifier.size(40.dp))
            }
        }

        // ---- online users
        Row(
            Modifier.padding(top = 6.dp).clip(RoundedCornerShape(20.dp)).clickable { showMembers = true }
                .background(Pal.panel).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Group, null, tint = Pal.green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            val others = s.participants
            Text(
                if (others.isEmpty()) "Only you online" else "${others.size + 1} online: " + others.take(3).joinToString() + if (others.size > 3) "…" else "",
                color = Pal.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }

        // ---- speaker / transmission status
        val (statusText, statusColor) = when {
            s.tx == TxState.TRANSMITTING -> {
                val left = ((s.txEndsAtMs ?: now) - now).coerceAtLeast(0) / 1000
                "YOU ARE TALKING · ${left}s left" to Pal.red
            }
            s.tx == TxState.REQUESTING -> "Requesting channel…" to Pal.amber
            s.tx == TxState.BUSY -> "Channel busy${s.remoteSpeaker?.let { " – $it is talking" } ?: ""}" to Pal.amber
            s.tx == TxState.DENIED -> "Listen only" to Pal.amber
            s.remoteSpeaker != null -> "${s.remoteSpeaker} is talking" to Pal.blue
            s.conn == ConnState.CONNECTED -> (if (s.canTalk) "Ready – hold to talk" else "Listen only") to Pal.green
            s.conn == ConnState.ERROR -> (s.message ?: "Connection problem") to Pal.red
            else -> "Connecting…" to Pal.amber
        }
        Spacer(Modifier.height(18.dp))
        Text(statusText, color = statusColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(56.dp))

        // ---- mic / speaker indicators
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Indicator(if (s.micOpen) Icons.Filled.Mic else Icons.Filled.MicOff, if (s.micOpen) "Mic ON" else "Mic off", if (s.micOpen) Pal.red else Pal.muted)
            Indicator(Icons.Filled.VolumeUp, if (s.remoteSpeaker != null) "Receiving" else "Speaker idle", if (s.remoteSpeaker != null) Pal.blue else Pal.muted)
        }

        Spacer(Modifier.weight(1f))
        PttButton(s.tx, s.remoteSpeaker != null, enabled = s.conn == ConnState.CONNECTED,
            onDown = { engine.pttDown(PttSource.SCREEN) }, onUp = { engine.pttUp(PttSource.SCREEN) })
        Spacer(Modifier.weight(1f))
        s.message?.takeIf { s.conn == ConnState.CONNECTED }?.let { Text(it, color = Pal.amber, fontSize = 13.sp) }
        Text("Volume Down = talk", color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }

    if (showMembers && s.channel != null) MembersSheet(s.channel!!, backend, onDismiss = { showMembers = false }, onDirectCall = { ch ->
        showMembers = false; onEnterChannel(ch)
    }, engine = engine)
}

@Composable
private fun PttButton(tx: TxState, receiving: Boolean, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    val color by animateColorAsState(
        when {
            !enabled -> Pal.line
            tx == TxState.TRANSMITTING -> Pal.red
            tx == TxState.REQUESTING -> Pal.amber
            tx == TxState.BUSY || tx == TxState.DENIED -> Color(0xFF5A4A2A)
            receiving -> Pal.blue.copy(alpha = 0.55f)
            else -> Pal.green
        }, label = "ptt",
    )
    val scale by animateFloatAsState(if (tx == TxState.TRANSMITTING) 1.06f else 1f, label = "scale")
    Box(
        Modifier.size(250.dp).scale(scale).clip(CircleShape).background(color.copy(alpha = 0.18f))
            .border(6.dp, color, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onDown()
                    try { waitForUpOrCancellation() } finally { onUp() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mic, null, tint = color, modifier = Modifier.size(72.dp))
            Text(
                when (tx) { TxState.TRANSMITTING -> "TALKING"; TxState.REQUESTING -> "…"; TxState.BUSY -> "BUSY"; TxState.DENIED -> "LISTEN ONLY"; else -> "PUSH TO TALK" },
                color = Pal.text, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            )
        }
    }
}

@Composable
private fun NetChip(n: NetType) {
    val (icon, color) = when (n) {
        NetType.WIFI -> Icons.Filled.Wifi to Pal.green
        NetType.CELLULAR, NetType.OTHER -> Icons.Filled.NetworkCell to Pal.green
        NetType.NONE -> Icons.Filled.WifiOff to Pal.red
    }
    Indicator(icon, n.label, color)
}

@Composable
private fun ConnChip(c: ConnState) {
    val (t, col) = when (c) {
        ConnState.CONNECTED -> "Connected" to Pal.green
        ConnState.CONNECTING -> "Connecting" to Pal.amber
        ConnState.RECONNECTING -> "Reconnecting" to Pal.amber
        ConnState.ERROR -> "Error" to Pal.red
        ConnState.IDLE -> "Idle" to Pal.muted
    }
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Pal.panel).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(6.dp)); Text(t, color = Pal.text, fontSize = 13.sp)
    }
}

@Composable
private fun Indicator(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Pal.panel).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(text, color = Pal.text, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MembersSheet(channel: Channel, backend: Backend, engine: PttEngine, onDismiss: () -> Unit, onDirectCall: (Channel) -> Unit) {
    val scope = rememberCoroutineScope()
    var roster by remember { mutableStateOf<List<RosterEntry>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    fun load() = scope.launch { try { roster = backend.roster(channel.id) } catch (e: Exception) { err = friendlyError(e) } }
    LaunchedEffect(channel.id) { load() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pal.panel) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Members of ${channel.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Pal.text, modifier = Modifier.weight(1f))
                if (channel.canManage && channel.kind == "private") TextButton(onClick = { showAdd = true }) { Text("Add") }
            }
            err?.let { Text(it, color = Pal.red) }
            roster.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (m.online) Pal.green else Pal.line))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Pal.text)
                        Text(m.role + if (!m.canTalk) " · listen only" else "", color = Pal.muted, fontSize = 12.sp)
                    }
                    if (channel.kind != "direct") TextButton(onClick = {
                        scope.launch {
                            try {
                                val id = backend.openDirectCall(m.userId)
                                engine.loadChannels().firstOrNull { it.id == id }?.let(onDirectCall)
                            } catch (e: Exception) { err = friendlyError(e) }
                        }
                    }) { Text("Call") }
                    if (channel.canManage && m.role != "owner") TextButton(onClick = {
                        scope.launch { try { backend.removeMember(channel.id, m.userId); load() } catch (e: Exception) { err = friendlyError(e) } }
                    }) { Text("Remove", color = Pal.red) }
                }
            }
        }
    }
    if (showAdd) UserPickerDialog(backend, "Add member", "Add", onDismiss = { showAdd = false }) { u ->
        scope.launch { try { backend.addMember(channel.id, u.userId); showAdd = false; load() } catch (e: Exception) { err = friendlyError(e) } }
    }
}
