package com.interdc.interaction

import com.interdc.core.BedrockIdentityService
import com.interdc.core.MessageService
import com.interdc.discord.DiscordGateway
import com.interdc.screen.InterDCScreen
import com.interdc.screen.ScreenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ComposeSession(
    val screenId: String,
    val guildId: String?,
    val channelId: String,
    val secondaryGuildId: String?,
    val secondaryChannelId: String?,
    val webhookUrl: String?
)

enum class ComposeBeginResult {
    STARTED,
    NO_CHANNEL,
    NO_PERMISSION
}

class ChatComposeService(
    private val screenManager: ScreenManager,
    private val discordGateway: DiscordGateway,
    private val messageService: MessageService,
    private val scope: CoroutineScope,
    private val bedrockIdentityService: BedrockIdentityService
) {

    private val sessions = ConcurrentHashMap<UUID, ComposeSession>()
    private val lastComposeSendAt = ConcurrentHashMap<UUID, Long>()

    fun begin(player: Player, screen: InterDCScreen): ComposeBeginResult {
        val channelId = screen.lockedChannelId ?: screen.channelId ?: return ComposeBeginResult.NO_CHANNEL
        val guildId = screen.lockedGuildId ?: screen.guildId

        val targets = linkedSetOf<Pair<String?, String>>()
        targets.add(guildId to channelId)
        val secondaryChannelId = screen.secondaryChannelId
        if (!secondaryChannelId.isNullOrBlank()) {
            targets.add(screen.secondaryGuildId to secondaryChannelId)
        }

        val hasAnyAllowedTarget = targets.any { (guildId, targetChannelId) ->
            canTalkInChannel(guildId, targetChannelId)
        }

        if (!hasAnyAllowedTarget) {
            return ComposeBeginResult.NO_PERMISSION
        }

        val current = sessions[player.uniqueId]
        if (current != null && current.screenId == screen.id && current.channelId == channelId) {
            return ComposeBeginResult.STARTED
        }

        sessions[player.uniqueId] = ComposeSession(
            screen.id,
            guildId,
            channelId,
            screen.secondaryGuildId,
            screen.secondaryChannelId,
            screen.webhookUrl
        )
        messageService.send(
            player,
            "compose-start",
            mapOf("channel" to channelId)
        )
        return ComposeBeginResult.STARTED
    }

    fun processChat(player: Player, text: String): Boolean {
        val session = sessions[player.uniqueId] ?: return false

        if (isCancelCommand(text)) {
            sessions.remove(player.uniqueId)
            messageService.send(player, "compose-cancelled")
            return true
        }

        val now = System.currentTimeMillis()
        val cooldownMs = 2000L
        val last = lastComposeSendAt[player.uniqueId] ?: 0L
        if (now - last < cooldownMs) {
            messageService.send(player, "compose-cooldown")
            return true
        }

        val targets = linkedSetOf<Pair<String?, String>>()
        targets.add(session.guildId to session.channelId)
        val secondaryChannelId = session.secondaryChannelId
        if (!secondaryChannelId.isNullOrBlank()) {
            targets.add(session.secondaryGuildId to secondaryChannelId)
        }

        val allowedTargets = targets.filter { (guildId, channelId) ->
            canTalkInChannel(guildId, channelId)
        }

        if (allowedTargets.isEmpty()) {
            sessions.remove(player.uniqueId)
            messageService.send(player, "compose-no-permission")
            return true
        }

        sessions.remove(player.uniqueId)
        lastComposeSendAt[player.uniqueId] = now
        val senderName = bedrockIdentityService.resolvePlayerName(player.name, player.uniqueId)
        scope.launch {
            allowedTargets.forEach { (_, channelId) ->
                val webhook = if (channelId == session.channelId) session.webhookUrl else null
                discordGateway.sendMinecraftMessage(
                    channelId = channelId,
                    author = senderName,
                    content = text,
                    webhookUrl = webhook
                )
            }
            screenManager.markDirty(session.screenId)
            messageService.send(player, "compose-sent")
        }
        return true
    }

    fun isComposing(playerId: UUID): Boolean {
        return sessions.containsKey(playerId)
    }

    private fun canTalkInChannel(guildId: String?, channelId: String): Boolean {
        if (!guildId.isNullOrBlank()) {
            val channel = discordGateway.channels(guildId).firstOrNull { it.id == channelId }
            if (channel != null) {
                return channel.canTalk && !channel.isVoice
            }
            return false
        }

        val guilds = discordGateway.guilds()
        guilds.forEach { guild ->
            val channel = discordGateway.channels(guild.id).firstOrNull { it.id == channelId }
            if (channel != null) {
                return channel.canTalk && !channel.isVoice
            }
        }
        return false
    }

    private fun isCancelCommand(text: String): Boolean {
        val normalized = text.trim().lowercase(Locale.ROOT)
        return normalized in setOf(
            "cancel",
            "cancelar",
            "c",
            "stop",
            "sair",
            "exit"
        )
    }
}
