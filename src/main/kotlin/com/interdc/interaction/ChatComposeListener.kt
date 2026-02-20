package com.interdc.interaction

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatComposeListener(
    private val composeService: ChatComposeService
) : Listener {

    private val plainSerializer = PlainTextComponentSerializer.plainText()

    @EventHandler(ignoreCancelled = true)
    fun onAsyncChat(event: AsyncChatEvent) {
        if (!composeService.isComposing(event.player.uniqueId)) {
            return
        }

        val raw = plainSerializer.serialize(event.message())
        val handled = composeService.processChat(event.player, raw)
        if (handled) {
            event.isCancelled = true
        }
    }

    @Suppress("DEPRECATION")
    @EventHandler(ignoreCancelled = true)
    fun onLegacyAsyncChat(event: AsyncPlayerChatEvent) {
        if (!composeService.isComposing(event.player.uniqueId)) {
            return
        }

        val handled = composeService.processChat(event.player, event.message)
        if (handled) {
            event.isCancelled = true
        }
    }
}
