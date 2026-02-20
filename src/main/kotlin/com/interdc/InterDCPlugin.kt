package com.interdc

import com.interdc.cache.CacheHub
import com.interdc.core.BedrockIdentityService
import com.interdc.core.DiscordEventCoalescerService
import com.interdc.core.GuildConfigService
import com.interdc.core.InterDCCommand
import com.interdc.core.MessageService
import com.interdc.core.ModrinthAutoUpdateService
import com.interdc.core.PrometheusMetricsService
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
import com.interdc.render.ScreenTileRenderer
import com.interdc.screen.ScreenManager
import com.interdc.screen.ScreenRepository
import com.interdc.storage.SQLiteStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.plugin.java.JavaPlugin

class InterDCPlugin : JavaPlugin() {

    data class HealthSnapshot(
        val status: String,
        val screens: Int,
        val discordMode: String,
        val discordConnected: Boolean,
        val guilds: Int,
        val dbOk: Boolean,
        val cacheHitRate: Double,
        val coalescerQueueDepth: Int,
        val coalescerFlushedBatches: Long,
        val coalescerFlushedEvents: Long,
        val featureCoalescerEnabled: Boolean,
        val featureMetricsEnabled: Boolean,
        val debugEnabled: Boolean
    )

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
    private lateinit var metricsService: PrometheusMetricsService
    private lateinit var discordEventCoalescerService: DiscordEventCoalescerService
    private lateinit var discordListener: DiscordUpdateListener
    private var metricsRunning = false
    private var coalescerRunning = false

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
        screenManager = ScreenManager(this, screenRepository, canvasService, discordGateway, messageService, guildConfigService)
        composeService = ChatComposeService(screenManager, discordGateway, messageService, pluginScope, bedrockIdentityService)
        interactionService = InteractionService(screenManager, discordGateway, composeService, messageService, guildConfigService)
        panelFlyAssistService = PanelFlyAssistService(this, messageService)
        modrinthAutoUpdateService = ModrinthAutoUpdateService(this, pluginScope)
        metricsService = PrometheusMetricsService(this)
        discordEventCoalescerService = DiscordEventCoalescerService(this, pluginScope, screenManager, metricsService)

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
        refreshRuntimeServices()

        discordListener = object : DiscordUpdateListener {
            override fun onMessage(channelId: String, message: DiscordMessage) {
                if (featureCoalescerEnabled()) {
                    discordEventCoalescerService.enqueueMessage(channelId)
                } else {
                    processMessageUpdateNow(channelId)
                }
            }

            override fun onChannelUpdate(guildId: String) {
                if (featureCoalescerEnabled()) {
                    discordEventCoalescerService.enqueueChannelUpdate(guildId)
                } else {
                    processChannelUpdateNow(guildId)
                }
            }
        }
        discordGateway.registerListener(discordListener)

        discordGateway.start()
        modrinthAutoUpdateService.checkAndUpdateAsync()
        
        val asciiArt = """
            
             _____       _             ____   _____ 
            |_   _|     | |           |  _ \ / ____|
              | |  _ __ | |_ ___ _ __ | | | | |     
              | | | '_ \| __/ _ \ '__|| | | | |     
             _| |_| | | | ||  __/ |   | |_| | |____ 
            |_____|_| |_|\__\___|_|   |____/ \_____|
                                                    
        """.trimIndent()
        
        @Suppress("DEPRECATION")
        server.consoleSender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&9$asciiArt"))
        @Suppress("DEPRECATION")
        server.consoleSender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&8&l[&9&lInterDC&8&l] &7Version: &f${description.version} &7| Author: &f${description.authors.joinToString(", ")}"))
        @Suppress("DEPRECATION")
        server.consoleSender.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&8&l[&9&lInterDC&8&l] &7Thank you for using InterDC!"))
        
        messageService.sendConsole("startup-enabled")
    }

    override fun onDisable() {
        if (coalescerRunning) {
            discordEventCoalescerService.stop()
            coalescerRunning = false
        }
        if (metricsRunning) {
            metricsService.stop()
            metricsRunning = false
        }
        panelFlyAssistService.shutdown()
        screenManager.shutdown()
        screenRepository.save()
        discordGateway.shutdown()
        pluginScope.cancel()
        messageService.sendConsole("startup-disabled")
    }

    fun refreshRuntimeServices() {
        if (featureMetricsEnabled()) {
            if (!metricsRunning) {
                metricsService.start { screenManager.allScreens() }
                metricsRunning = true
            }
        } else if (metricsRunning) {
            metricsService.stop()
            metricsRunning = false
        }

        if (featureCoalescerEnabled()) {
            if (!coalescerRunning) {
                discordEventCoalescerService.start()
                coalescerRunning = true
            }
        } else if (coalescerRunning) {
            discordEventCoalescerService.stop()
            coalescerRunning = false
        }
    }

    fun healthSnapshot(): HealthSnapshot {
        val render = ScreenTileRenderer.metricsSnapshot()
        val totalLookups = render.cacheHits + render.cacheMisses
        val hitRate = if (totalLookups <= 0L) 0.0 else (render.cacheHits.toDouble() * 100.0) / totalLookups.toDouble()

        val discordMode = when (discordGateway) {
            is MockDiscordGateway -> "mock"
            is JdaDiscordGateway -> "jda"
            else -> "custom"
        }
        val discordConnected = (discordGateway as? JdaDiscordGateway)?.isConnected() ?: false
        val dbOk = sqliteStorage.ping()
        val coalescer = discordEventCoalescerService.snapshot()
        val status = if (dbOk && (discordMode != "jda" || discordConnected)) "ok" else "degraded"

        return HealthSnapshot(
            status = status,
            screens = screenManager.allScreens().size,
            discordMode = discordMode,
            discordConnected = discordConnected,
            guilds = discordGateway.guilds().size,
            dbOk = dbOk,
            cacheHitRate = hitRate,
            coalescerQueueDepth = coalescer.queueDepth,
            coalescerFlushedBatches = coalescer.flushedBatches,
            coalescerFlushedEvents = coalescer.flushedEvents,
            featureCoalescerEnabled = featureCoalescerEnabled(),
            featureMetricsEnabled = featureMetricsEnabled(),
            debugEnabled = config.getBoolean("debug.enabled", false)
        )
    }

    private fun processMessageUpdateNow(channelId: String) {
        val screens = screenManager.allScreens()
        val affectedGuilds = mutableSetOf<String>()

        screens.forEach { screen ->
            if (screen.channelId == channelId || screen.secondaryChannelId == channelId) {
                screenManager.markDirty(screen.id)
                screen.guildId?.let { affectedGuilds.add(it) }
                screen.secondaryGuildId?.let { affectedGuilds.add(it) }
            }
        }

        if (affectedGuilds.isEmpty()) {
            metricsService.incrementDiscordMessageEvent()
        } else {
            affectedGuilds.forEach { metricsService.incrementDiscordMessageEvent(it) }
        }
    }

    private fun processChannelUpdateNow(guildId: String) {
        screenManager.allScreens().forEach { screen ->
            if (screen.guildId == guildId || screen.secondaryGuildId == guildId) {
                screenManager.markDirty(screen.id)
            }
        }
        metricsService.incrementDiscordChannelUpdateEvent(guildId)
    }

    private fun featureCoalescerEnabled(): Boolean {
        return config.getBoolean("feature-flags.discord-event-coalescer", true)
    }

    private fun featureMetricsEnabled(): Boolean {
        return config.getBoolean("feature-flags.metrics-service", true)
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
