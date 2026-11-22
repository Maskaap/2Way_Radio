package com.pttconnect.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.pttconnect.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.Settings
import com.pttconnect.app.data.friendlyError
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(backend: Backend, settings: Settings) {
    var register by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(settings.apiBaseUrl) }
    var showServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.brand_logo),
            contentDescription = "Infinity H Group",
            modifier = Modifier.size(140.dp).align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(8.dp))
        Text("PTT Connect", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        Text(if (register) "Create your account" else "Sign in to your radio network", color = Pal.muted)
        Spacer(Modifier.height(12.dp))
        if (register) {
            OutlinedTextField(name, { name = it }, label = { Text("Name / call sign") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password (min. 8 characters)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

        msg?.let { Text(it, color = if (it.startsWith("✓")) Pal.green else Pal.red) }

        Button(
            enabled = !busy && email.isNotBlank() && password.length >= 8 && (!register || name.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                busy = true; msg = null
                scope.launch {
                    try {
                        if (register) {
                            val signedIn = backend.signUp(email, password, name, phone)
                            if (!signedIn) { msg = "✓ Account created. Confirm the e-mail we sent you, then sign in."; register = false }
                        } else backend.signIn(email, password)
                    } catch (e: Exception) { msg = friendlyError(e) }
                    busy = false
                }
            },
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(22.dp), strokeWidth = 2.dp) else Text(if (register) "Create account" else "Sign in", fontSize = 18.sp)
        }
        if (!register) TextButton(onClick = { showReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Forgot password?", color = Pal.muted)
        }
        TextButton(onClick = { register = !register; msg = null }, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "I already have an account" else "New here? Create an account")
        }
        TextButton(onClick = { showServer = !showServer }) { Text("Server settings", color = Pal.muted) }
        if (showServer) {
            OutlinedTextField(server, { server = it }, label = { Text("Server URL (your Vercel app)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            Button(onClick = { settings.apiBaseUrl = server; backend.resetConfig(); msg = "✓ Server saved" }) { Text("Save server") }
        }
    }

    if (showReset) {
        var resetEmail by remember { mutableStateOf(email) }
        var sending by remember { mutableStateOf(false) }
        var resetMsg by remember { mutableStateOf<String?>(null) }
        var sent by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!sent) {
                        Text("Enter your e-mail. We'll send you a link to choose a new password.")
                        OutlinedTextField(resetEmail, { resetEmail = it }, label = { Text("E-mail") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    }
                    resetMsg?.let { Text(it, color = if (sent) Pal.green else Pal.red) }
                }
            },
            confirmButton = {
                if (sent) TextButton(onClick = { showReset = false }) { Text("OK") }
                else TextButton(enabled = !sending && resetEmail.contains("@"), onClick = {
                    sending = true
                    scope.launch {
                        try {
                            backend.requestPasswordReset(resetEmail)
                            sent = true
                            resetMsg = "If an account exists for ${resetEmail.trim()}, a reset link is on its way. " +
                                "Check your inbox (and spam), open the link, choose a new password, then sign in here."
                        } catch (e: Exception) { resetMsg = friendlyError(e) }
                        sending = false
                    }
                }) { Text(if (sending) "Sending…" else "Send link") }
            },
            dismissButton = { if (!sent) TextButton(onClick = { showReset = false }) { Text("Cancel") } },
        )
    }
}
