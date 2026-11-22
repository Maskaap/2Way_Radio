package com.pttconnect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pttconnect.app.data.ChatMessage
import com.pttconnect.app.data.ChatRepository
import com.pttconnect.app.data.shortTime
import com.pttconnect.app.ptt.ConnState
import com.pttconnect.app.ptt.PttEngine

private val Orange = Color(0xFFF47B20)

@Composable
fun ChatScreen(engine: PttEngine, chat: ChatRepository, onBack: () -> Unit) {
    val s by engine.state.collectAsState()
    val messages by chat.messages.collectAsState()
    val hasOlder by chat.hasOlder.collectAsState()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()

    DisposableEffect(Unit) { chat.chatOpen = true; chat.sync(); onDispose { chat.chatOpen = false } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Pal.text) }
            Column(Modifier.weight(1f)) {
                Text(s.channel?.name ?: "Chat", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Pal.text)
                Text(if (s.conn == ConnState.CONNECTED) "Messages are saved and synced" else "Offline – messages will send when you reconnect",
                    fontSize = 12.sp, color = if (s.conn == ConnState.CONNECTED) Pal.muted else Pal.amber)
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                if (hasOlder && messages.isNotEmpty()) TextButton(onClick = { chat.loadOlder() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Load older messages", color = Pal.muted)
                } else if (messages.isEmpty()) Text("No messages yet. Say hello 👋", color = Pal.muted,
                    modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
            }
            items(messages, key = { if (it.id > 0) "id${it.id}" else "c${it.clientId}" }) { m -> Bubble(m, mine = isMine(m, engine)) }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it.take(2000) }, placeholder = { Text("Message") },
                modifier = Modifier.weight(1f), maxLines = 4)
            IconButton(onClick = { chat.send(text); text = "" }, enabled = text.isNotBlank() && s.channel != null) {
                Icon(Icons.Filled.Send, "Send", tint = if (text.isNotBlank()) Orange else Pal.muted)
            }
        }
    }
}

private fun isMine(m: ChatMessage, engine: PttEngine) = m.pending || m.senderId == engine.myUserId()

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean) {
    if (m.kind == "system" || m.kind == "emergency") {
        val sos = m.kind == "emergency"
        Text("${m.body}  ·  ${shortTime(m.createdAt)}", color = if (sos) Color.White else Pal.muted, fontSize = 13.sp,
            fontWeight = if (sos) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (sos) Pal.red else Color.Transparent).padding(8.dp))
        return
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp))
                .background(if (mine) Orange.copy(alpha = 0.9f) else Pal.panel).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!mine) Text(m.senderName, color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(m.body, color = if (mine) Color.White else Pal.text, fontSize = 15.sp)
            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (m.pending) { Icon(Icons.Filled.Schedule, "Waiting to send", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(12.dp)); Spacer(Modifier.size(4.dp)) }
                Text(if (m.pending) "waiting to send" else shortTime(m.createdAt),
                    color = if (mine) Color.White.copy(alpha = 0.8f) else Pal.muted, fontSize = 11.sp)
            }
        }
    }
}
