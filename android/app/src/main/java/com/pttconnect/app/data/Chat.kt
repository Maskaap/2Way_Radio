package com.pttconnect.app.data

import android.util.Log
import com.pttconnect.app.net.NetType
import com.pttconnect.app.net.NetworkMonitor
import com.pttconnect.app.ptt.ConnState
import com.pttconnect.app.ptt.PttEngine
import com.pttconnect.app.voice.VoiceEvent
import com.pttconnect.app.voice.VoiceLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Instant messaging for the current channel.
 *
 *  - Messages are stored in the database (history + archive). The database is the single source of truth.
 *  - After sending, a tiny "sync" hint goes over the live voice connection; everyone in the channel then
 *    fetches new messages from the database (so a message can't be faked over the data channel).
 *  - Offline: typed messages wait in a saved outbox and are sent automatically when the connection returns.
 *    Each has a client id, so a re-send never creates a duplicate.
 *  - On (re)connect the app fetches everything it missed.
 */
class ChatRepository(
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val settings: Settings,
    private val engine: PttEngine,
    voice: VoiceLink,
    network: NetworkMonitor,
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread
    private val _hasOlder = MutableStateFlow(true)
    val hasOlder: StateFlow<Boolean> = _hasOlder

    @Volatile var chatOpen = false
        set(v) { field = v; if (v) _unread.value = 0 }

    private var channelId: String? = null
    private var lastId = 0L
    private val lock = Mutex()
    private val myId get() = backend.session.value?.userId

    init {
        // Channel switched → load recent history
        scope.launch {
            engine.state.map { it.channel?.id }.distinctUntilChanged().collect { id ->
                channelId = id; lastId = 0; _unread.value = 0; _hasOlder.value = true
                _messages.value = outboxFor(id)
                if (id != null) runCatching { loadLatest(id) }.onFailure { Log.w(TAG, "history", it) }
            }
        }
        // (Re)connected → send outbox, fetch what we missed
        scope.launch {
            engine.state.map { it.conn }.distinctUntilChanged().collect { c ->
                if (c == ConnState.CONNECTED) { flushOutbox(); sync() }
            }
        }
        // Someone in the channel posted → fetch
        scope.launch {
            voice.events.collect { e -> if (e is VoiceEvent.Signal && e.text == "sync") sync() }
        }
        // Network came back → try the outbox
        scope.launch {
            network.type.collect { t -> if (t != NetType.NONE) flushOutbox() }
        }
        // Safety net: poll every 45 s while in a channel (covers missed hints)
        scope.launch {
            while (isActive) { delay(45_000); if (channelId != null) sync() }
        }
    }

    fun send(text: String) {
        val ch = channelId ?: return
        val body = text.trim().take(2000)
        if (body.isEmpty()) return
        val item = JSONObject().put("client_id", UUID.randomUUID().toString()).put("channel_id", ch)
            .put("body", body).put("created_at", java.time.OffsetDateTime.now().toString())
        val box = JSONArray(settings.loadOutbox()).put(item)
        settings.saveOutbox(box.toString())
        _messages.update { it + pendingFrom(item) }
        scope.launch { flushOutbox() }
    }

    fun loadOlder() = scope.launch {
        val ch = channelId ?: return@launch
        val first = _messages.value.firstOrNull { it.id > 0 }?.id ?: return@launch
        runCatching { backend.messagesBefore(ch, first, 50) }.onSuccess { older ->
            if (older.size < 50) _hasOlder.value = false
            _messages.update { (older + it).distinctBy { m -> if (m.id > 0) "id${m.id}" else "c${m.clientId}" } }
        }
    }

    fun sync() = scope.launch {
        val ch = channelId ?: return@launch
        lock.withLock {
            val fresh = runCatching { backend.messagesSince(ch, lastId) }.getOrElse { return@withLock }
            if (fresh.isEmpty() || ch != channelId) return@withLock
            lastId = maxOf(lastId, fresh.maxOf { it.id })
            val fromOthers = fresh.count { it.senderId != myId }
            _messages.update { cur ->
                val sentIds = fresh.mapNotNull { it.clientId }.toSet()
                (cur.filterNot { it.pending && it.clientId in sentIds } + fresh)
                    .distinctBy { m -> if (m.id > 0) "id${m.id}" else "c${m.clientId}" }
                    .sortedWith(compareBy<ChatMessage> { it.pending }.thenBy { it.id })
            }
            if (!chatOpen) _unread.update { it + fromOthers }
        }
    }

    private suspend fun loadLatest(ch: String) = lock.withLock {
        val recent = backend.messagesBefore(ch, Long.MAX_VALUE, 50)
        if (ch != channelId) return@withLock
        if (recent.size < 50) _hasOlder.value = false
        lastId = recent.maxOfOrNull { it.id } ?: 0
        _messages.value = recent + outboxFor(ch)
    }

    private val flushLock = Mutex()
    private suspend fun flushOutbox() = flushLock.withLock {
        val box = JSONArray(settings.loadOutbox())
        if (box.length() == 0) return@withLock
        val remaining = JSONArray()
        var sentAny = false
        for (i in 0 until box.length()) {
            val o = box.getJSONObject(i)
            try {
                backend.sendMessage(o.getString("channel_id"), o.getString("body"), o.getString("client_id"))
                sentAny = true
            } catch (e: BackendException) {
                // Permanent problems (left channel, channel deleted) → drop; network → keep for later
                if (e.code == "network" || e.httpStatus >= 500 || e.httpStatus == 0) remaining.put(o)
                else Log.w(TAG, "dropping undeliverable message: ${e.code}")
            } catch (e: Exception) { remaining.put(o) }
        }
        settings.saveOutbox(remaining.toString())
        if (sentAny) { engine.signal("sync"); sync() }
    }

    private fun outboxFor(ch: String?): List<ChatMessage> {
        if (ch == null) return emptyList()
        val box = JSONArray(settings.loadOutbox())
        return List(box.length()) { box.getJSONObject(it) }.filter { it.getString("channel_id") == ch }.map { pendingFrom(it) }
    }

    private fun pendingFrom(o: JSONObject) = ChatMessage(
        id = 0, channelId = o.getString("channel_id"), senderId = myId,
        senderName = backend.session.value?.displayName ?: "Me", kind = "text", body = o.getString("body"),
        createdAt = o.optString("created_at"), clientId = o.getString("client_id"), pending = true,
    )

    companion object { private const val TAG = "Chat" }
}
