package com.interdc

import com.interdc.cache.CacheHub
import com.interdc.core.BedrockIdentityService
import com.interdc.core.GuildConfigService
import com.interdc.core.InterDCCommand
import com.interdc.core.MessageService
import com.interdc.core.ModrinthAutoUpdateService
import com.interdc.discord.DiscordGateway
import com.interdc.discord.DiscordMessage
import com.interdc.discord.DiscordUpdateListener
import com.interdc.discord.JdaDiscordGateway
import com.interdc.discord.MockDiscordGateway
import com.interdc.interaction.ChatComposeListener
import com.interdc.interaction.ChatComposeService
import com.interdc.interaction.InteractionService
import com.interdc.interaction.PanelFlyAssistService
import com.interdc.interaction.ScreenInteractionListener
import com.interdc.render.DiscordCanvasService
import com.interdc.screen.ScreenManager
import com.interdc.screen.ScreenRepository
import com.interdc.storage.SQLiteStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

class InterDCPlugin : JavaPlugin() {

    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var messageService: MessageService
    private lateinit var guildConfigService: GuildConfigService
    private lateinit var bedrockIdentityService: BedrockIdentityService
    private lateinit var sqliteStorage: SQLiteStorage
    private lateinit var cacheHub: CacheHub
    private lateinit var discordGateway: DiscordGateway
    private lateinit var screenRepository: ScreenRepository
    private lateinit var canvasService: DiscordCanvasService
    private lateinit var screenManager: ScreenManager
    private lateinit var composeService: ChatComposeService
    private lateinit var interactionService: InteractionService
    private lateinit var panelFlyAssistService: PanelFlyAssistService
    private lateinit var modrinthAutoUpdateService: ModrinthAutoUpdateService
    private val pendingGuildRefreshTasks = ConcurrentHashMap<String, BukkitTask>()

    override fun onEnable() {
        saveDefaultConfig()

        messageService = MessageService(this)
        bedrockIdentityService = BedrockIdentityService()
        sqliteStorage = SQLiteStorage(this)
        sqliteStorage.initialize()
        guildConfigService = GuildConfigService(this, sqliteStorage)
        cacheHub = CacheHub(sqliteStorage)
        discordGateway = createDiscordGateway()
        screenRepository = ScreenRepository(this, sqliteStorage)
        canvasService = DiscordCanvasService(guildConfigService, discordGateway)
        screenManager = ScreenManager(this, screenRepository, canvasService, discordGateway, messageService)
        composeService = ChatComposeService(screenManager, discordGateway, messageService, pluginScope, bedrockIdentityService)
        interactionService = InteractionService(screenManager, discordGateway, composeService, messageService, guildConfigService)
        panelFlyAssistService = PanelFlyAssistService(this, messageService)
        modrinthAutoUpdateService = ModrinthAutoUpdateService(this, pluginScope)

        guildConfigService.ensureServersDirectory()
        screenManager.reloadScreens()

        val interDCCommand = InterDCCommand(this, screenManager, messageService)
        getCommand("interdc")?.setExecutor(interDCCommand)
        getCommand("interdc")?.tabCompleter = interDCCommand

        server.pluginManager.registerEvents(
            ScreenInteractionListener(this, screenManager, interactionService, panelFlyAssistService),
            this
        )
        server.pluginManager.registerEvents(
            ChatComposeListener(composeService),
            this
        )
        server.pluginManager.registerEvents(panelFlyAssistService, this)

        discordGateway.registerListener(object : DiscordUpdateListener {
            override fun onMessage(channelId: String, message: DiscordMessage) {
                screenManager.allScreens().forEach { screen ->
                    if (screen.channelId == channelId || screen.secondaryChannelId == channelId) {
                        screenManager.markDirty(screen.id)
                    }
                }
            }

            override fun onChannelUpdate(guildId: String) {
                pendingGuildRefreshTasks.remove(guildId)?.cancel()
                val task = server.scheduler.runTaskLater(this@InterDCPlugin, Runnable {
                    pendingGuildRefreshTasks.remove(guildId)
                    screenManager.allScreens().forEach { screen ->
                        if (screen.guildId == guildId || screen.secondaryGuildId == guildId) {
                            screenManager.markDirty(screen.id)
                        }
                    }
                }, 6L)
                pendingGuildRefreshTasks[guildId] = task
            }
        })

        discordGateway.start()
        modrinthAutoUpdateService.checkAndUpdateAsync()
        messageService.sendConsole("startup-enabled")
    }

    override fun onDisable() {
        pendingGuildRefreshTasks.values.forEach { it.cancel() }
        pendingGuildRefreshTasks.clear()
        panelFlyAssistService.shutdown()
        screenManager.shutdown()
        screenRepository.save()
        discordGateway.shutdown()
        pluginScope.cancel()
        messageService.sendConsole("startup-disabled")
    }

    private fun createDiscordGateway(): DiscordGateway {
        val enabled = config.getBoolean("discord.enabled", false)
        val token = config.getString("discord.bot-token", "")?.trim().orEmpty()
        if (!enabled || token.isBlank()) {
            messageService.sendConsole("discord-mock")
            return MockDiscordGateway(cacheHub)
        }

        return try {
            val gateway = JdaDiscordGateway(cacheHub, token)
            messageService.sendConsole("discord-live")
            gateway
        } catch (ex: Exception) {
            messageService.sendConsole(
                "discord-connect-error",
                mapOf("error" to (ex.message ?: ex::class.simpleName.orEmpty()))
            )
            messageService.sendConsole("discord-mock")
            MockDiscordGateway(cacheHub)
        }
    }
}
