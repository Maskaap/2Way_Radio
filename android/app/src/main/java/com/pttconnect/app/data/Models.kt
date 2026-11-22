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

class BackendException(val code: String, message: String = code, val httpStatus: Int = 0) : Exception(message)
