package com.pttconnect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pttconnect.app.ptt.NfcCheckpoints
import kotlinx.coroutines.delay

/** Shows the result of an NFC checkpoint scan on top of any screen, and the "register tag" form for admins. */
@Composable
fun NfcOverlay(nfc: NfcCheckpoints) {
    val r by nfc.last.collectAsState()
    val res = r ?: return

    if (res.canRegister) {
        var name by remember(res.at) { mutableStateOf("") }
        var site by remember(res.at) { mutableStateOf("") }
        var every by remember(res.at) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { nfc.dismiss() },
            title = { Text("Register checkpoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tag ${res.tagUid}. Your current GPS position is saved with it.", color = Pal.muted, fontSize = 13.sp)
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("Checkpoint name, e.g. Gate 3") }, singleLine = true)
                    OutlinedTextField(site, { site = it.take(80) }, label = { Text("Site (optional)") }, singleLine = true)
                    OutlinedTextField(every, { every = it.filter(Char::isDigit).take(5) }, label = { Text("Must be scanned every … minutes (optional)") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val mins = every.toIntOrNull()?.coerceIn(5, 10080)
                    nfc.register(res.tagUid, name.trim(), site.trim().ifBlank { null }, mins)
                }) { Text("Register") }
            },
            dismissButton = { TextButton(onClick = { nfc.dismiss() }) { Text("Not now") } },
        )
        return
    }

    LaunchedEffect(res.at) { delay(5_000); nfc.dismiss() }
    val color = when { !res.ok -> Pal.red; res.known -> Pal.green; else -> Pal.amber }
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color).clickable { nfc.dismiss() }.padding(16.dp)) {
            Text(if (res.known) "NFC CHECKPOINT" else "NFC TAG", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(res.text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(res.at)),
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}
