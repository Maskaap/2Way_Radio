package com.pttconnect.app

import android.app.Application
import com.pttconnect.app.audio.Tones
import com.pttconnect.app.data.Backend
import com.pttconnect.app.data.Settings
import com.pttconnect.app.net.NetworkMonitor
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
}

class PttApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
