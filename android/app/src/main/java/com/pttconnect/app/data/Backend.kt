package com.pttconnect.app.data

import android.os.Build
import com.pttconnect.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to:
 *  - Vercel  (/api/config, /api/token)
 *  - Supabase Auth  (/auth/v1/...)  and  PostgREST RPCs  (/rest/v1/rpc/...)
 * Uses plain HTTPS + JSON so there is no SDK version coupling.
 */
class Backend(private val settings: Settings) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val JSON = "application/json".toMediaType()

    private var supabaseUrl: String? = null
    private var anonKey: String? = null

    private val _session = MutableStateFlow(settings.loadSession()?.let { Session.fromJson(it) })
    val session: StateFlow<Session?> = _session
    private val refreshLock = Mutex()

    // ------------------------------------------------------------------ config
    suspend fun ensureConfig() {
        if (supabaseUrl != null) return
        val o = JSONObject(call(Request.Builder().url("${settings.apiBaseUrl}/api/config").get().build()))
        supabaseUrl = o.getString("supabaseUrl").trimEnd('/')
        anonKey = o.getString("supabaseAnonKey")
    }

    fun resetConfig() { supabaseUrl = null; anonKey = null }

    // ------------------------------------------------------------------ auth
    /** Returns true if signed in immediately, false if Supabase requires e-mail confirmation first. */
    suspend fun signUp(email: String, password: String, displayName: String, phone: String?): Boolean {
        ensureConfig()
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .put("data", JSONObject().put("display_name", displayName.trim()).put("phone", phone?.trim()?.ifBlank { null }))
        val res = JSONObject(call(sbRequest("/auth/v1/signup").post(body.toString().toRequestBody(JSON)).build()))
        return if (res.has("access_token")) { storeSession(res); true } else false
    }

    suspend fun signIn(email: String, password: String) {
        ensureConfig()
        val body = JSONObject().put("email", email.trim()).put("password", password)
        storeSession(JSONObject(call(sbRequest("/auth/v1/token?grant_type=password").post(body.toString().toRequestBody(JSON)).build())))
    }

    /** Sends a password-reset e-mail. The link opens <server>/reset-password.html to choose a new password. */
    suspend fun requestPasswordReset(email: String) {
        ensureConfig()
        val redirect = java.net.URLEncoder.encode("${settings.apiBaseUrl}/reset-password.html", "UTF-8")
        val body = JSONObject().put("email", email.trim())
        call(sbRequest("/auth/v1/recover?redirect_to=$redirect").post(body.toString().toRequestBody(JSON)).build())
    }

    /** Changes the password of the signed-in user. */
    suspend fun changePassword(newPassword: String) {
        ensureConfig()
        val body = JSONObject().put("password", newPassword)
        call(sbRequest("/auth/v1/user").header("Authorization", "Bearer ${validAccessToken()}")
            .put(body.toString().toRequestBody(JSON)).build())
    }

    fun signOut() {
        _session.value = null
        settings.saveSession(null)
    }

    private fun storeSession(o: JSONObject) {
        val user = o.getJSONObject("user")
        val meta = user.optJSONObject("user_metadata")
        val s = Session(
            accessToken = o.getString("access_token"),
            refreshToken = o.getString("refresh_token"),
            expiresAtEpochSec = o.optLong("expires_at", System.currentTimeMillis() / 1000 + o.optLong("expires_in", 3600)),
            userId = user.getString("id"),
            email = user.optString("email").ifBlank { null },
            displayName = meta?.optString("display_name")?.ifBlank { null } ?: user.optString("email", "me"),
        )
        _session.value = s
        settings.saveSession(s.toJson())
    }

    private suspend fun validAccessToken(forceRefresh: Boolean = false): String {
        val s = _session.value ?: throw BackendException("not_signed_in")
        if (!forceRefresh && s.expiresAtEpochSec - System.currentTimeMillis() / 1000 > 60) return s.accessToken
        return refreshLock.withLock { refreshLocked(forceRefresh) }
    }

    private suspend fun refreshLocked(forceRefresh: Boolean): String {
        val s = _session.value ?: throw BackendException("not_signed_in")
        // Another caller may have refreshed while we waited for the lock.
        if (!forceRefresh && s.expiresAtEpochSec - System.currentTimeMillis() / 1000 > 60) return s.accessToken
        ensureConfig()
        val body = JSONObject().put("refresh_token", s.refreshToken)
        try {
            storeSession(JSONObject(call(sbRequest("/auth/v1/token?grant_type=refresh_token").post(body.toString().toRequestBody(JSON)).build())))
        } catch (e: BackendException) {
            if (e.httpStatus in 400..499) { signOut(); throw BackendException("session_expired", "Please sign in again") }
            throw e
        }
        return _session.value!!.accessToken
    }

    // ------------------------------------------------------------------ RPC
    /** Calls a Postgres function. Returns raw JSON text (object, array, scalar or empty). */
    suspend fun rpc(fn: String, args: JSONObject = JSONObject()): String {
        ensureConfig()
        suspend fun attempt(force: Boolean): String {
            val token = validAccessToken(force)
            return call(sbRequest("/rest/v1/rpc/$fn").header("Authorization", "Bearer $token")
                .post(args.toString().toRequestBody(JSON)).build())
        }
        return try { attempt(false) } catch (e: BackendException) {
            if (e.httpStatus == 401) attempt(true) else throw e
        }
    }

    suspend fun registerDevice(): Pair<Boolean, String?> {
        val o = JSONObject(rpc("register_device", JSONObject()
            .put("p_device_uid", settings.deviceUid).put("p_platform", "android")
            .put("p_model", "${Build.MANUFACTURER} ${Build.MODEL}").put("p_app_version", BuildConfig.VERSION_NAME)))
        return o.optBoolean("blocked") to o.optString("reason").takeIf { it.isNotBlank() && it != "null" }
    }

    suspend fun myChannels(): List<Channel> = JSONArray(rpc("my_channels")).let { a -> List(a.length()) { Channel.from(a.getJSONObject(it)) } }

    suspend fun createChannel(name: String, isPrivate: Boolean): String =
        rpc("create_channel", JSONObject().put("p_name", name).put("p_kind", if (isPrivate) "private" else "public")).trim('"')

    suspend fun enterChannel(id: String): JSONObject = JSONObject(rpc("enter_channel", JSONObject().put("p_channel_id", id)))
    suspend fun exitChannel(id: String) { rpc("exit_channel", JSONObject().put("p_channel_id", id)) }
    suspend fun heartbeat(id: String?) { rpc("heartbeat", JSONObject().put("p_channel_id", id ?: JSONObject.NULL)) }

    suspend fun roster(id: String): List<RosterEntry> =
        JSONArray(rpc("channel_roster", JSONObject().put("p_channel_id", id))).let { a -> List(a.length()) { RosterEntry.from(a.getJSONObject(it)) } }

    suspend fun findUsers(q: String): List<UserHit> =
        JSONArray(rpc("find_users", JSONObject().put("p_query", q))).let { a ->
            List(a.length()) { a.getJSONObject(it).let { o -> UserHit(o.getString("user_id"), o.getString("display_name")) } }
        }

    suspend fun addMember(channelId: String, userId: String) { rpc("add_member", JSONObject().put("p_channel_id", channelId).put("p_user_id", userId)) }
    suspend fun removeMember(channelId: String, userId: String) { rpc("remove_member", JSONObject().put("p_channel_id", channelId).put("p_user_id", userId)) }
    suspend fun openDirectCall(userId: String): String = rpc("open_direct_call", JSONObject().put("p_other_user", userId)).trim('"')

    suspend fun requestFloor(channelId: String): FloorResult {
        val o = JSONObject(rpc("request_floor", JSONObject().put("p_channel_id", channelId)))
        return FloorResult(o.optBoolean("granted"), o.optString("reason").ifBlank { null },
            o.optString("holder_name").ifBlank { null }, o.optLong("max_ms", 60_000))
    }

    /** false = floor was lost (max time reached / revoked). */
    suspend fun keepaliveFloor(channelId: String): Boolean =
        JSONObject(rpc("keepalive_floor", JSONObject().put("p_channel_id", channelId))).optBoolean("ok")

    suspend fun releaseFloor(channelId: String) { rpc("release_floor", JSONObject().put("p_channel_id", channelId)) }

    /** Gets a LiveKit token for the channel's audio room from Vercel. Returns (url, token). */
    suspend fun liveKitToken(channelId: String): Pair<String, String> {
        val body = JSONObject().put("channelId", channelId).put("deviceUid", settings.deviceUid)
        suspend fun attempt(force: Boolean): String = call(Request.Builder().url("${settings.apiBaseUrl}/api/token")
            .header("Authorization", "Bearer ${validAccessToken(force)}")
            .post(body.toString().toRequestBody(JSON)).build())
        val o = JSONObject(try { attempt(false) } catch (e: BackendException) { if (e.httpStatus == 401) attempt(true) else throw e })
        return o.getString("url") to o.getString("token")
    }

    // ------------------------------------------------------------------ billing
    suspend fun plans(): List<Plan> {
        ensureConfig()
        suspend fun attempt(force: Boolean) = call(sbRequest("/rest/v1/plans?select=*&active=eq.true&order=sort_order")
            .header("Authorization", "Bearer ${validAccessToken(force)}").get().build())
        val text = try { attempt(false) } catch (e: BackendException) { if (e.httpStatus == 401) attempt(true) else throw e }
        return JSONArray(text).let { a -> List(a.length()) { Plan.from(a.getJSONObject(it)) } }
    }

    suspend fun mySubscription(): MySubscription = MySubscription.from(JSONObject(rpc("my_subscription")))

    /** Returns the URL to open in the browser (it forwards to PayFast). */
    suspend fun startCheckout(planCode: String, yearly: Boolean): String =
        JSONObject(apiPost("/api/pay/checkout", JSONObject().put("plan", planCode).put("period", if (yearly) "yearly" else "monthly"))).getString("url")

    suspend fun cancelSubscription() { apiPost("/api/pay/cancel", JSONObject()) }

    private suspend fun apiPost(path: String, body: JSONObject): String {
        suspend fun attempt(force: Boolean) = call(Request.Builder().url("${settings.apiBaseUrl}$path")
            .header("Authorization", "Bearer ${validAccessToken(force)}")
            .post(body.toString().toRequestBody(JSON)).build())
        return try { attempt(false) } catch (e: BackendException) { if (e.httpStatus == 401) attempt(true) else throw e }
    }

    // ------------------------------------------------------------------ plumbing
    private fun sbRequest(path: String) = Request.Builder()
        .url("${supabaseUrl ?: throw BackendException("no_config")}$path")
        .header("apikey", anonKey!!)
        .header("Content-Type", "application/json")

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val o = runCatching { JSONObject(text) }.getOrNull()
                    val code = o?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: o?.optString("error_code")?.takeIf { it.isNotBlank() }
                        ?: o?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "http_${r.code}"
                    val msg = o?.optString("msg")?.takeIf { it.isNotBlank() }
                        ?: o?.optString("error_description")?.takeIf { it.isNotBlank() }
                        ?: o?.optString("message")?.takeIf { it.isNotBlank() } ?: code
                    throw BackendException(code, msg, r.code)
                }
                text
            }
        } catch (e: IOException) {
            throw BackendException("network", "No connection to server", 0)
        }
    }
}

/** Human-friendly text for error codes coming from the database / API. */
fun friendlyError(e: Throwable): String = when ((e as? BackendException)?.code ?: "") {
    "network" -> "No connection to server"
    "device_blocked" -> "This phone has been blocked by an administrator" + ((e as BackendException).message.takeIf { it != "device_blocked" }?.let { ": $it" } ?: "")
    "account_disabled" -> "Your account is disabled"
    "not_a_member" -> "You are not a member of this channel"
    "invalid_credentials" -> "Wrong e-mail or password"
    "session_expired" -> "Please sign in again"
    "email_not_confirmed" -> "Confirm your e-mail first (check your inbox)"
    "user_already_exists" -> "An account with this e-mail already exists"
    "over_email_send_rate_limit", "over_request_rate_limit" -> "Too many requests – please wait a few minutes and try again"
    "weak_password" -> "Password is too weak – use at least 8 characters with letters and numbers"
    "same_password" -> "The new password must be different from the old one"
    "reauthentication_needed" -> "For security, sign out and use “Forgot password?” instead"
    "subscription_required" -> "Your subscription or trial has ended – open Subscription to choose a plan"
    "plan_limit_channels" -> "Your plan's channel limit is reached – upgrade your plan for more channels"
    "plan_limit_members" -> "This channel is full for the owner's plan – the owner can upgrade for more users"
    "plan_not_for_sale" -> "This plan is sold on request – please contact us"
    "payments_not_configured" -> "Online payments are not set up yet – please contact us"
    "manual_subscription" -> "Your plan is managed by the administrator – please contact us to change it"
    else -> e.message ?: "Something went wrong"
}
