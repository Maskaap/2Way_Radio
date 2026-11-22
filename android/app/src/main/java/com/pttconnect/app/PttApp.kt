package com.pttconnect.app

import android.app.Application
import com.pttconnect.app.audio.Tones
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.ChatRepository
import com.pttconnect.app.data.Settings
import com.pttconnect.app.net.LocationReporter
import com.pttconnect.app.net.NetworkMonitor
import com.pttconnect.app.ptt.EmergencyManager
import com.pttconnect.app.ptt.NfcCheckpoints
import com.pttconnect.app.ptt.KeyRouter
import com.pttconnect.app.ptt.PttEngine
import com.pttconnect.app.voice.VoiceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Simple manual dependency graph – one instance of each for the whole process. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = Settings(app)
    val backend = Backend(settings)
    val network = NetworkMonitor(app)
    val tones = Tones()
    val voice = VoiceLink(app, scope)
    val engine = PttEngine(scope, backend, voice, settings, tones, network)
    val keys = KeyRouter(settings, engine)
    val chat = ChatRepository(scope, backend, settings, engine, voice, network)
    val location = LocationReporter(app, scope, backend, settings, engine)
    val emergency = EmergencyManager(app, scope, backend, engine, location, voice)
    val nfc = NfcCheckpoints(app, scope, backend, location)
}

class PttApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
