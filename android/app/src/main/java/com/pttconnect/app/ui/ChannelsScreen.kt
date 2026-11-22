package com.pttconnect.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.pttconnect.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.Channel
import com.pttconnect.app.data.UserHit
import com.pttconnect.app.data.friendlyError
import com.pttconnect.app.ptt.PttEngine
import kotlinx.coroutines.launch

@Composable
fun ChannelsScreen(
    engine: PttEngine,
    backend: Backend,
    lastChannelId: String?,
    onAutoJoinHandled: () -> Unit,
    onEnter: (Channel) -> Unit,
    onBack: (() -> Unit)?,
    onSettings: () -> Unit,
    onSubscription: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var showCall by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<Channel?>(null) }

    fun reload() = scope.launch {
        error = null
        try { channels = engine.loadChannels() } catch (e: Exception) { error = friendlyError(e) }
    }

    LaunchedEffect(Unit) {
        reload().join()
        // Reconnect to the last-used channel on app start.
        if (lastChannelId != null) {
            channels?.firstOrNull { it.id == lastChannelId }?.let(onEnter)
            onAutoJoinHandled()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Pal.text) }
            Image(painterResource(R.drawable.brand_logo), "Infinity H Group", Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Text("Channels", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Pal.text, modifier = Modifier.weight(1f))
            TextButton(onClick = onSubscription) { Text("Plan", color = Color(0xFFF47B20), fontWeight = FontWeight.SemiBold) }
            IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, "Refresh", tint = Pal.muted) }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Pal.muted) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 10.dp)) {
            OutlinedButton(onClick = { showCreate = true }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("New channel") }
            OutlinedButton(onClick = { showCall = true }) { Icon(Icons.Filled.Person, null); Spacer(Modifier.width(6.dp)); Text("Private call") }
        }
        error?.let {
            Text(it, color = Pal.red, modifier = Modifier.padding(vertical = 8.dp))
            if (it.contains("plan", ignoreCase = true) || it.contains("subscription", ignoreCase = true))
                OutlinedButton(onClick = onSubscription) { Text("View plans") }
        }
        val list = channels
        when {
            list == null && error == null -> Text("Loading…", color = Pal.muted)
            list != null && list.isEmpty() -> Text("No channels yet. Create the first one.", color = Pal.muted)
            list != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { ch -> ChannelRow(ch, onClick = { onEnter(ch) }, onManage = { manage = ch }) }
            }
        }
    }

    manage?.let { ch ->
        val isOwner = ch.myRole == "owner"
        val what = if (ch.kind == "direct") "private call" else if (ch.kind == "private") "group" else "channel"
        AlertDialog(
            onDismissRequest = { manage = null },
            title = { Text(if (isOwner) "Delete $what?" else "Leave $what?") },
            text = {
                Text(if (isOwner) "\"${ch.name}\" will be deleted for everyone. All members are removed and anyone talking is disconnected. This can't be undone."
                     else "You will be removed from \"${ch.name}\". ${if (ch.kind == "public") "You can join again any time." else "The owner must add you again to come back."}")
            },
            confirmButton = { TextButton(onClick = {
                manage = null
                scope.launch {
                    try {
                        if (isOwner) backend.deleteChannel(ch.id) else backend.leaveChannel(ch.id)
                        engine.channelGone(ch.id)
                        channels = engine.loadChannels()
                    } catch (e: Exception) { error = friendlyError(e) }
                }
            }) { Text(if (isOwner) "Delete" else "Leave", color = Pal.red) } },
            dismissButton = { TextButton(onClick = { manage = null }) { Text("Cancel") } },
        )
    }

    if (showCreate) CreateChannelDialog(onDismiss = { showCreate = false }) { name, isPrivate ->
        scope.launch {
            try {
                val id = backend.createChannel(name, isPrivate)
                showCreate = false
                channels = engine.loadChannels()
                channels?.firstOrNull { it.id == id }?.let(onEnter)
            } catch (e: Exception) { error = friendlyError(e); showCreate = false }
        }
    }
    if (showCall) UserPickerDialog(backend, title = "Private call", action = "Call", onDismiss = { showCall = false }) { user ->
        scope.launch {
            try {
                val id = backend.openDirectCall(user.userId)
                showCall = false
                channels = engine.loadChannels()
                channels?.firstOrNull { it.id == id }?.let(onEnter)
            } catch (e: Exception) { error = friendlyError(e); showCall = false }
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, onClick: () -> Unit, onManage: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Pal.panel)
            .border(1.dp, Pal.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (ch.kind) { "private" -> Icons.Filled.Lock; "direct" -> Icons.Filled.Person; else -> Icons.Filled.Public },
            null, tint = Pal.muted, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Pal.text)
            Text(
                buildString {
                    append("${ch.onlineCount} online · ${ch.memberCount} members")
                    if (!ch.isMember) append(" · tap to join")
                    if (ch.isMember && !ch.canTalk) append(" · listen only")
                },
                fontSize = 13.sp, color = Pal.muted,
            )
        }
        if (ch.onlineCount > 0) Spacer(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Pal.green))
        if (ch.isMember) IconButton(onClick = onManage) {
            Icon(if (ch.myRole == "owner") Icons.Filled.Delete else Icons.Filled.Logout,
                if (ch.myRole == "owner") "Delete" else "Leave", tint = Pal.muted)
        }
    }
}

@Composable
private fun CreateChannelDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New channel") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Channel name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(isPrivate, { isPrivate = it }); Text("Private (members only – you add people)")
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), isPrivate) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Search by exact e-mail, phone number, or the start of a name. */
@Composable
fun UserPickerDialog(backend: Backend, title: String, action: String, onDismiss: () -> Unit, onPick: (UserHit) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<UserHit>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Name, e-mail or phone") }, singleLine = true)
                TextButton(enabled = q.length >= 2, onClick = {
                    scope.launch { try { hits = backend.findUsers(q); err = if (hits.isEmpty()) "Nobody found" else null } catch (e: Exception) { err = friendlyError(e) } }
                }) { Text("Search") }
                err?.let { Text(it, color = Pal.muted) }
                hits.forEach { h ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(h.name, Modifier.weight(1f), color = Pal.text); Text(action, color = Pal.green)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
