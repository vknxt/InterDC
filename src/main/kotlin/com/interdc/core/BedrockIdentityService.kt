package com.interdc.core

import java.util.UUID

class BedrockIdentityService {

    private val floodgateApiClass: Class<*>? = runCatching {
        Class.forName("org.geysermc.floodgate.api.FloodgateApi")
    }.getOrNull()

    private val floodgateInstance: Any? = runCatching {
        floodgateApiClass
            ?.getMethod("getInstance")
            ?.invoke(null)
    }.getOrNull()

    fun resolvePlayerName(javaPlayerName: String, playerUuid: UUID): String {
        val floodgateName = resolveFloodgateName(playerUuid)
        return floodgateName?.takeIf { it.isNotBlank() } ?: javaPlayerName
    }

    private fun resolveFloodgateName(playerUuid: UUID): String? {
        val api = floodgateInstance ?: return null
        val apiClass = floodgateApiClass ?: return null

        val isFloodgatePlayer = runCatching {
            apiClass
                .getMethod("isFloodgatePlayer", UUID::class.java)
                .invoke(api, playerUuid) as? Boolean
        }.getOrNull() ?: false

        if (!isFloodgatePlayer) {
            return null
        }

        val floodgatePlayer = runCatching {
            apiClass
                .getMethod("getPlayer", UUID::class.java)
                .invoke(api, playerUuid)
        }.getOrNull() ?: return null

        return runCatching {
            floodgatePlayer.javaClass
                .getMethod("getUsername")
                .invoke(floodgatePlayer) as? String
        }.getOrNull()
    }
}
