package com.interdc.core

import com.interdc.InterDCPlugin
import com.interdc.screen.ScreenManager
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

class InterDCCommand(
    private val plugin: InterDCPlugin,
    private val screenManager: ScreenManager,
    private val messageService: MessageService
) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (args.isEmpty()) {
            messageService.send(sender, "usage")
            return true
        }

        val sub = args[0].lowercase()
        if (sub == "lang") {
            handleLang(sender, args)
            return true
        }

        if (!sender.hasPermission("interdc.admin")) {
            messageService.send(sender, "no-permission")
            return true
        }

        when (sub) {
            "create" -> handleCreate(sender, args)
            "link" -> handleLink(sender, args)
            "link2" -> handleLink2(sender, args)
            "lockchannel" -> handleLockChannel(sender, args)
            "webhook" -> handleWebhook(sender, args)
            "remove" -> handleRemove(sender)
            "reload" -> handleReload(sender)
            "move" -> handleMove(sender)
            else -> messageService.send(sender, "usage")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            val subcommands = mutableListOf("lang")
            if (sender.hasPermission("interdc.admin")) {
                subcommands.addAll(listOf("create", "link", "link2", "lockchannel", "webhook", "remove", "reload", "move"))
            }
            return subcommands
                .filter { it.startsWith(args[0], ignoreCase = true) }
                .toMutableList()
        }

        if (args.size == 2 && args[0].equals("lang", ignoreCase = true)) {
            val options = messageService.supportedLocales().toMutableList().apply { add("auto") }
            return options
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .sorted()
                .toMutableList()
        }

        if (args.size == 2 && args[0].equals("lockchannel", ignoreCase = true)) {
            return listOf("on", "off")
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .toMutableList()
        }

        return mutableListOf()
    }

    private fun handleLang(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        val available = messageService.supportedLocales().sorted().joinToString(", ")
        if (args.size < 2) {
            val effective = messageService.localeOf(player)
            val override = messageService.getPlayerLocaleOverride(player.uniqueId) ?: "auto"
            messageService.send(
                player,
                "lang-current",
                mapOf("locale" to effective, "override" to override)
            )
            messageService.send(player, "lang-usage", mapOf("available" to available))
            return
        }

        val raw = args[1].lowercase(Locale.ROOT)
        if (raw == "auto") {
            messageService.setPlayerLocale(player.uniqueId, null)
            messageService.send(player, "lang-auto")
            return
        }

        val resolved = messageService.resolveSupportedLocale(raw)
        if (resolved == null) {
            messageService.send(
                player,
                "lang-invalid",
                mapOf("locale" to raw, "available" to available)
            )
            return
        }

        messageService.setPlayerLocale(player.uniqueId, resolved)
        messageService.send(player, "lang-set", mapOf("locale" to resolved))
    }

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        val defaultWidth = plugin.config.getInt("screen.default-width", 3)
        val defaultHeight = plugin.config.getInt("screen.default-height", 3)

        val width = if (args.size >= 2) args[1].toIntOrNull() else defaultWidth
        val height = if (args.size >= 3) args[2].toIntOrNull() else defaultHeight
        val explicitSize = args.size >= 2

        if (width == null || height == null || width !in 1..8 || height !in 1..8) {
            messageService.send(sender, "invalid-size")
            return
        }

        try {
            val created = screenManager.createScreen(player, width, height)
            messageService.send(sender, "created", mapOf("id" to created.id))
        } catch (_: IllegalStateException) {
            if (!explicitSize && (width != 1 || height != 1)) {
                val fallback = runCatching { screenManager.createScreen(player, 1, 1) }.getOrNull()
                if (fallback != null) {
                    messageService.send(sender, "created", mapOf("id" to fallback.id))
                    messageService.send(sender, "created-fallback-size")
                    return
                }
            }
            messageService.send(sender, "create-wall-invalid")
        }
    }

    private fun handleLink(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        if (args.size < 2) {
            messageService.send(sender, "usage")
            return
        }

        val channelId = args[1]
        val guildId = args.getOrNull(2)
        when (screenManager.linkFocusedScreen(player, channelId, guildId)) {
            ScreenManager.LinkResult.NOT_LOOKING_SCREEN -> {
                messageService.send(sender, "not-looking-screen")
                return
            }

            ScreenManager.LinkResult.INVALID_CHANNEL -> {
                messageService.send(sender, "link-invalid-channel")
                return
            }

            ScreenManager.LinkResult.SUCCESS -> {
            }
        }
        messageService.send(sender, "linked", mapOf("channel" to channelId))
    }

    private fun handleWebhook(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        if (args.size < 2) {
            messageService.send(sender, "usage")
            return
        }

        val input = args[1]
        val webhookUrl = if (input.equals("clear", ignoreCase = true)) null else input
        val ok = screenManager.setWebhookFocusedScreen(player, webhookUrl)
        if (!ok) {
            messageService.send(sender, "not-looking-screen")
            return
        }

        if (webhookUrl == null) {
            messageService.send(sender, "webhook-cleared")
        } else {
            messageService.send(sender, "webhook-set")
        }
    }

    private fun handleLink2(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        if (args.size < 2) {
            messageService.send(sender, "usage")
            return
        }

        val channelId = args[1]
        val guildId = args.getOrNull(2)
        when (screenManager.linkFocusedScreenSecondary(player, channelId, guildId)) {
            ScreenManager.LinkResult.NOT_LOOKING_SCREEN -> {
                messageService.send(sender, "not-looking-screen")
                return
            }

            ScreenManager.LinkResult.INVALID_CHANNEL -> {
                messageService.send(sender, "link-invalid-channel")
                return
            }

            ScreenManager.LinkResult.SUCCESS -> {
            }
        }
        messageService.send(sender, "linked", mapOf("channel" to channelId))
    }

    private fun handleRemove(sender: CommandSender) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        val removed = screenManager.removeFocusedScreen(player)
        if (!removed) {
            messageService.send(sender, "not-looking-screen")
            return
        }
        messageService.send(sender, "removed")
    }

    private fun handleReload(sender: CommandSender) {
        plugin.reloadConfig()
        messageService.reload()
        screenManager.reloadScreens()
        messageService.send(sender, "reloaded")
    }

    private fun handleMove(sender: CommandSender) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        val moved = screenManager.moveFocusedScreen(player)
        if (!moved) {
            messageService.send(sender, "not-looking-screen")
            return
        }
        messageService.send(sender, "moved")
    }

    private fun handleLockChannel(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            messageService.send(sender, "only-player")
            return
        }

        val mode = args.getOrNull(1)?.lowercase(Locale.ROOT)
        when (mode) {
            "on" -> {
                val ok = screenManager.lockFocusedScreenCurrentChannel(player)
                if (!ok) {
                    messageService.send(sender, "not-looking-screen")
                    return
                }
                messageService.send(sender, "channel-lock-on")
            }

            "off" -> {
                val ok = screenManager.unlockFocusedScreenChannel(player)
                if (!ok) {
                    messageService.send(sender, "not-looking-screen")
                    return
                }
                messageService.send(sender, "channel-lock-off")
            }

            else -> messageService.send(sender, "usage")
        }
    }
}
