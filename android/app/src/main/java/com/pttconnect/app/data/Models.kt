package com.pttconnect.app.data

import org.json.JSONObject

data class Channel(
    val id: String,
    val name: String,
    val kind: String,          // public | private | direct
    val description: String?,
    val maxTalkSeconds: Int,
    val isMember: Boolean,
    val myRole: String?,       // owner | moderator | member | null
    val canTalk: Boolean,
    val memberCount: Int,
    val onlineCount: Int,
) {
    val canManage get() = myRole == "owner" || myRole == "moderator"

    companion object {
        fun from(o: JSONObject) = Channel(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = o.getString("kind"),
            description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
            maxTalkSeconds = o.optInt("max_talk_seconds", 60),
            isMember = o.optBoolean("is_member"),
            myRole = o.optString("my_role").takeIf { it.isNotBlank() && it != "null" },
            canTalk = o.optBoolean("can_talk"),
            memberCount = o.optInt("member_count"),
            onlineCount = o.optInt("online_count"),
        )
    }
}

data class RosterEntry(val userId: String, val name: String, val role: String, val canTalk: Boolean, val online: Boolean) {
    companion object {
        fun from(o: JSONObject) = RosterEntry(
            o.getString("user_id"), o.getString("display_name"), o.optString("role", "member"),
            o.optBoolean("can_talk", true), o.optBoolean("online"),
        )
    }
}

data class UserHit(val userId: String, val name: String)

data class FloorResult(
    val granted: Boolean,
    val reason: String?,       // busy | listen_only
    val holderName: String?,
    val maxMs: Long,
)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long,
    val userId: String,
    val email: String?,
    val displayName: String,
) {
    fun toJson(): String = JSONObject()
        .put("a", accessToken).put("r", refreshToken).put("e", expiresAtEpochSec)
        .put("u", userId).put("m", email).put("n", displayName).toString()

    companion object {
        fun fromJson(s: String): Session? = runCatching {
            val o = JSONObject(s)
            Session(o.getString("a"), o.getString("r"), o.getLong("e"), o.getString("u"),
                o.optString("m").ifBlank { null }, o.optString("n"))
        }.getOrNull()
    }
}

data class Plan(
    val code: String,
    val name: String,
    val description: String?,
    val monthlyCents: Int?,
    val yearlyCents: Int?,
    val isCustom: Boolean,
    val maxChannels: Int?,
    val maxMembers: Int?,
    val features: List<String>,
) {
    fun priceCents(yearly: Boolean) = if (yearly) yearlyCents else monthlyCents
    /** Yearly saving vs 12 × monthly, in %, or null. */
    val yearlySavingPct: Int? get() = if (monthlyCents != null && yearlyCents != null && monthlyCents > 0)
        Math.round(100 - yearlyCents * 100.0 / (monthlyCents * 12)).toInt().takeIf { it > 0 } else null

    companion object {
        fun from(o: JSONObject) = Plan(
            code = o.getString("code"), name = o.getString("name"),
            description = o.optString("description").takeIf { it.isNotBlank() && it != "null" },
            monthlyCents = if (o.isNull("monthly_price_cents")) null else o.getInt("monthly_price_cents"),
            yearlyCents = if (o.isNull("yearly_price_cents")) null else o.getInt("yearly_price_cents"),
            isCustom = o.optBoolean("is_custom"),
            maxChannels = if (o.isNull("max_channels")) null else o.getInt("max_channels"),
            maxMembers = if (o.isNull("max_members_per_channel")) null else o.getInt("max_members_per_channel"),
            features = o.optJSONArray("features")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        )
    }
}

data class MySubscription(
    val hasAccess: Boolean,
    val source: String,          // subscription | trial | admin | none
    val planCode: String?,
    val planName: String?,
    val period: String?,
    val status: String?,
    val endsAt: String?,
    val cancelAtPeriodEnd: Boolean,
    val provider: String?,
    val salesEmail: String?,
) {
    companion object {
        fun from(o: JSONObject) = MySubscription(
            hasAccess = o.optBoolean("has_access"), source = o.optString("source", "none"),
            planCode = o.optString("plan_code").takeIf { it.isNotBlank() && it != "null" },
            planName = o.optString("plan_name").takeIf { it.isNotBlank() && it != "null" },
            period = o.optString("period").takeIf { it.isNotBlank() && it != "null" },
            status = o.optString("status").takeIf { it.isNotBlank() && it != "null" },
            endsAt = (o.optString("ends_at").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("ended_at").takeIf { it.isNotBlank() && it != "null" })?.take(10),
            cancelAtPeriodEnd = o.optBoolean("cancel_at_period_end"),
            provider = o.optString("provider").takeIf { it.isNotBlank() && it != "null" },
            salesEmail = o.optString("sales_email").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

data class ChatMessage(
    val id: Long,                 // 0 while still waiting in the outbox
    val channelId: String,
    val senderId: String?,
    val senderName: String,
    val kind: String,             // text | system | emergency
    val body: String,
    val createdAt: String,
    val clientId: String?,
    val pending: Boolean = false,
) {
    companion object {
        fun from(o: JSONObject) = ChatMessage(
            id = o.getLong("id"), channelId = o.getString("channel_id"),
            senderId = o.optString("sender_id").takeIf { it.isNotBlank() && it != "null" },
            senderName = o.optString("sender_name", "?"), kind = o.optString("kind", "text"),
            body = o.optString("body"), createdAt = o.optString("created_at"),
            clientId = o.optString("client_id").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

data class Alert(
    val id: Long,
    val kind: String,
    val status: String,
    val userId: String?,
    val userName: String?,
    val channelId: String?,
    val lat: Double?,
    val lng: Double?,
    val message: String?,
    val createdAt: String,
) {
    companion object {
        fun from(o: JSONObject) = Alert(
            id = o.getLong("id"), kind = o.optString("kind"), status = o.optString("status"),
            userId = o.optString("user_id").takeIf { it.isNotBlank() && it != "null" },
            userName = o.optString("user_name").takeIf { it.isNotBlank() && it != "null" },
            channelId = o.optString("channel_id").takeIf { it.isNotBlank() && it != "null" },
            lat = if (o.isNull("lat")) null else o.optDouble("lat"),
            lng = if (o.isNull("lng")) null else o.optDouble("lng"),
            message = o.optString("message").takeIf { it.isNotBlank() && it != "null" },
            createdAt = o.optString("created_at"),
        )
    }
}

/** "14:05" for today, otherwise "22 Nov 14:05". */
fun shortTime(iso: String): String = runCatching {
    val t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    t.format(java.time.format.DateTimeFormatter.ofPattern(if (t.toLocalDate() == today) "HH:mm" else "d MMM HH:mm"))
}.getOrDefault("")

/** Minutes since an ISO time, for "2 min ago". */
fun minutesAgo(iso: String): Long = runCatching {
    java.time.Duration.between(java.time.OffsetDateTime.parse(iso).toInstant(), java.time.Instant.now()).toMinutes()
}.getOrDefault(0)

fun formatRand(cents: Int): String = if (cents % 100 == 0) "R${cents / 100}" else "R%.2f".format(cents / 100.0)

class BackendException(val code: String, message: String = code, val httpStatus: Int = 0) : Exception(message)
