package com.cyberphantom.contextkeeper

import org.json.JSONObject

data class CapturedMessage(
    val id: String,
    val role: String,
    val text: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val source: String = "android-accessibility"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("role", role); put("text", text)
        put("firstSeenAt", firstSeenAt); put("lastSeenAt", lastSeenAt); put("source", source)
    }
}
