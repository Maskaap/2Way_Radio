package com.pttconnect.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.pttconnect.app.PttApp
import com.pttconnect.app.service.PttService

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as PttApp).graph }
    private var onPermissions: (() -> Unit)? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onPermissions?.invoke(); onPermissions = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PttTheme { Root() } }
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    /** App opened (or brought forward) by tapping an NFC tag. */
    private fun handleNfcIntent(i: android.content.Intent?) {
        if (i?.action in setOf(android.nfc.NfcAdapter.ACTION_TECH_DISCOVERED, android.nfc.NfcAdapter.ACTION_TAG_DISCOVERED)) {
            graph.nfc.onTagId(i?.getByteArrayExtra(android.nfc.NfcAdapter.EXTRA_ID))
        }
    }

    override fun onResume() {
        super.onResume()
        graph.nfc.enable(this)
    }

    /** All key events pass through here first – this is where Volume Down becomes PTT. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        graph.keys.onKeyEvent(event) || super.dispatchKeyEvent(event)

    /** If we lose focus while a key is held, Android may never send key-up: release to avoid a stuck mic. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) graph.engine.releaseAll()
    }

    override fun onPause() {
        graph.nfc.disable(this)
        graph.engine.releaseAll()
        super.onPause()
    }

    fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun withPermissions(then: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (graph.settings.prefs.value.shareLocation) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) then() else { onPermissions = then; permLauncher.launch(needed.toTypedArray()) }
    }

    sealed interface Screen { data object Channels : Screen; data object Ptt : Screen; data object Settings : Screen; data object Subscription : Screen; data object Chat : Screen }

    @Composable
    private fun Root() {
        val session by graph.backend.session.collectAsState()
        var screen by remember { mutableStateOf<Screen>(Screen.Channels) }
        var autoJoinTried by rememberSaveable { mutableStateOf(false) }

        Box(Modifier.fillMaxSize().background(Pal.bg).safeDrawingPadding()) {
            if (session == null) {
                AuthScreen(graph.backend, graph.settings)
                return@Box
            }
            val enter: (com.pttconnect.app.data.Channel) -> Unit = { ch ->
                withPermissions {
                    graph.engine.enterChannel(ch)
                    if (hasMic()) PttService.start(this@MainActivity)
                    screen = Screen.Ptt
                }
            }
            when (screen) {
                Screen.Channels -> ChannelsScreen(
                    engine = graph.engine, backend = graph.backend,
                    lastChannelId = graph.settings.lastChannelId.takeIf { !autoJoinTried },
                    onAutoJoinHandled = { autoJoinTried = true },
                    onEnter = enter,
                    onBack = if (graph.engine.state.value.channel != null) ({ screen = Screen.Ptt }) else null,
                    onSettings = { screen = Screen.Settings },
                    onSubscription = { screen = Screen.Subscription },
                )
                Screen.Chat -> ChatScreen(
                    engine = graph.engine, chat = graph.chat,
                    onBack = { screen = Screen.Ptt },
                )
                Screen.Ptt -> PttScreen(
                    engine = graph.engine, backend = graph.backend,
                    chat = graph.chat, emergency = graph.emergency,
                    onChat = { screen = Screen.Chat },
                    onChannels = { screen = Screen.Channels },
                    onSettings = { screen = Screen.Settings },
                    onEnterChannel = enter,
                )
                Screen.Subscription -> SubscriptionScreen(
                    backend = graph.backend,
                    onBack = { screen = if (graph.engine.state.value.channel != null) Screen.Ptt else Screen.Channels },
                )
                Screen.Settings -> SettingsScreen(
                    settings = graph.settings, backend = graph.backend, keys = graph.keys,
                    onSubscription = { screen = Screen.Subscription },
                    onBack = { screen = if (graph.engine.state.value.channel != null) Screen.Ptt else Screen.Channels },
                    onSignOut = {
                        graph.engine.leaveChannel()
                        PttService.stop(this@MainActivity)
                        graph.backend.signOut()
                        screen = Screen.Channels
                    },
                )
            }
            NfcOverlay(graph.nfc)
        }
    }
}
