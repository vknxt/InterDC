package com.interdc.discord

import com.interdc.cache.CacheHub
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.events.channel.ChannelCreateEvent
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent
import net.dv8tion.jda.api.events.channel.update.GenericChannelUpdateEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.guild.override.PermissionOverrideCreateEvent
import net.dv8tion.jda.api.events.guild.override.PermissionOverrideDeleteEvent
import net.dv8tion.jda.api.events.guild.override.PermissionOverrideUpdateEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.role.update.RoleUpdatePermissionsEvent
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.time.Instant
import java.time.Duration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class DiscordGuild(
    val id: String,
    val name: String,
    val iconUrl: String? = null
)

data class DiscordChannel(
    val id: String,
    val guildId: String,
    val name: String,
    val isVoice: Boolean = false,
    val categoryName: String? = null,
    val canView: Boolean = true,
    val canTalk: Boolean = true
)

data class DiscordMessage(
    val channelId: String,
    val author: String,
    val content: String,
    val timestamp: Instant = Instant.now()
)

data class DiscordMember(
    val id: String,
    val name: String,
    val roles: List<String> = emptyList(),
    val avatarUrl: String? = null
)

interface DiscordUpdateListener {
    fun onMessage(channelId: String, message: DiscordMessage)
    fun onChannelUpdate(guildId: String)
}

interface DiscordGateway {
    fun start()
    fun shutdown()
    fun guilds(): List<DiscordGuild>
    fun channels(guildId: String): List<DiscordChannel>
    fun members(guildId: String, limit: Int = 15): List<DiscordMember>
    fun latestMessages(channelId: String, limit: Int = 10): List<DiscordMessage>
    fun sendMinecraftMessage(channelId: String, author: String, content: String, webhookUrl: String? = null)
    fun registerListener(listener: DiscordUpdateListener)
}

class MockDiscordGateway(private val cacheHub: CacheHub) : DiscordGateway {

    private val listeners = CopyOnWriteArrayList<DiscordUpdateListener>()

    private val guilds = listOf(
        DiscordGuild("guild-demo", "InterDC Community", "https://mc-heads.net/avatar/discord")
    )

    private val channels = listOf(
        DiscordChannel("ch-anuncios", "guild-demo", "anuncios", categoryName = "Informações", canTalk = false),
        DiscordChannel("ch-geral", "guild-demo", "geral", categoryName = "Comunidade", canTalk = true),
        DiscordChannel("ch-suporte", "guild-demo", "suporte", categoryName = "Comunidade", canTalk = true),
        DiscordChannel("ch-voz-lobby", "guild-demo", "lobby-voz", isVoice = true, categoryName = "Voz", canTalk = false)
    )

    private val members = listOf(
        DiscordMember("u-admin", "Admin", listOf("Administrador"), "https://mc-heads.net/avatar/admin"),
        DiscordMember("u-helper", "Helper", listOf("Suporte"), "https://mc-heads.net/avatar/helper"),
        DiscordMember("u-builder", "Builder", listOf("Construtor"), "https://mc-heads.net/avatar/builder"),
        DiscordMember("u-player", "Minecraft", listOf("Membro"), "https://mc-heads.net/avatar/steve")
    )

    override fun start() {
        val seedMessages = listOf(
            DiscordMessage("ch-anuncios", "Sistema", "InterDC conectado com sucesso."),
            DiscordMessage("ch-geral", "Admin", "Bem-vindos ao lobby interativo!"),
            DiscordMessage("ch-suporte", "Helper", "Abra ticket no canal de suporte.")
        )
        seedMessages.forEach { msg ->
            cacheHub.messageCache.add(msg.channelId, msg)
        }
        cacheHub.channelCache.put("guild-demo", channels)
        listeners.forEach { it.onChannelUpdate("guild-demo") }
    }

    override fun shutdown() {
    }

    override fun guilds(): List<DiscordGuild> = guilds

    override fun channels(guildId: String): List<DiscordChannel> {
        return channels.filter { it.guildId == guildId }
    }

    override fun members(guildId: String, limit: Int): List<DiscordMember> {
        if (guildId != "guild-demo") {
            return emptyList()
        }
        return members.take(limit)
    }

    override fun latestMessages(channelId: String, limit: Int): List<DiscordMessage> {
        return cacheHub.messageCache.latest(channelId, limit)
    }

    override fun sendMinecraftMessage(channelId: String, author: String, content: String, webhookUrl: String?) {
        val message = DiscordMessage(channelId, author, content)
        cacheHub.messageCache.add(channelId, message)
        listeners.forEach { it.onMessage(channelId, message) }
    }

    override fun registerListener(listener: DiscordUpdateListener) {
        listeners.add(listener)
    }
}

class JdaDiscordGateway(
    private val cacheHub: CacheHub,
    private val token: String
) : DiscordGateway {

    companion object {
        private const val CHANNEL_CACHE_TTL_MS = 5_000L
        private const val MEMBER_CACHE_TTL_MS = 15_000L
        private const val MEMBER_CACHE_SIZE = 80
    }

    private val listeners = CopyOnWriteArrayList<DiscordUpdateListener>()
    private val guildStore = ConcurrentHashMap<String, DiscordGuild>()
    private val channelStore = ConcurrentHashMap<String, List<DiscordChannel>>()
    private val memberStore = ConcurrentHashMap<String, List<DiscordMember>>()
    private val channelStoreUpdatedAt = ConcurrentHashMap<String, Long>()
    private val memberStoreUpdatedAt = ConcurrentHashMap<String, Long>()
    private val webhookStore = ConcurrentHashMap<String, String>()
    private val http = HttpClient.newHttpClient()
    private var jda: JDA? = null

    fun isConnected(): Boolean {
        val status = jda?.status ?: return false
        return status == JDA.Status.CONNECTED || status == JDA.Status.LOADING_SUBSYSTEMS
    }

    override fun start() {
        if (jda != null) {
            return
        }

        val builder = JDABuilder.createDefault(token)
            .enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_VOICE_STATES,
                GatewayIntent.GUILD_MEMBERS
            )
            .disableCache(CacheFlag.ACTIVITY, CacheFlag.VOICE_STATE)
            .addEventListeners(object : ListenerAdapter() {
                override fun onGuildReady(event: GuildReadyEvent) {
                    cacheGuild(event.guild.id)
                    listeners.forEach { it.onChannelUpdate(event.guild.id) }
                }

                override fun onMessageReceived(event: MessageReceivedEvent) {
                    if (!event.isFromGuild) {
                        return
                    }

                    val selfUserId = jda?.selfUser?.id
                    if (!event.message.isWebhookMessage && selfUserId != null && event.author.id == selfUserId) {
                        return
                    }

                    val content = composeDisplayContent(event)
                    if (content.isBlank()) {
                        return
                    }

                    val message = DiscordMessage(
                        channelId = event.channel.id,
                        author = event.author.effectiveName,
                        content = content,
                        timestamp = Instant.now()
                    )
                    cacheHub.messageCache.add(event.channel.id, message)
                    listeners.forEach { it.onMessage(event.channel.id, message) }
                }

                override fun onGenericChannelUpdate(event: GenericChannelUpdateEvent<*>) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }

                override fun onChannelCreate(event: ChannelCreateEvent) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }

                override fun onChannelDelete(event: ChannelDeleteEvent) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }

                override fun onPermissionOverrideCreate(event: PermissionOverrideCreateEvent) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }

                override fun onPermissionOverrideUpdate(event: PermissionOverrideUpdateEvent) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }

                override fun onPermissionOverrideDelete(event: PermissionOverrideDeleteEvent) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }

                override fun onRoleUpdatePermissions(event: RoleUpdatePermissionsEvent) {
                    val guild = event.guild
                    cacheGuild(guild.id)
                    listeners.forEach { it.onChannelUpdate(guild.id) }
                }
            })

        jda = builder.build()
    }

    override fun shutdown() {
        val instance = jda
        jda = null
        webhookStore.clear()
        channelStoreUpdatedAt.clear()
        memberStoreUpdatedAt.clear()
        if (instance != null) {
            instance.shutdown()
            runCatching {
                instance.awaitShutdown(Duration.ofSeconds(5))
            }
            if (instance.status != JDA.Status.SHUTDOWN) {
                instance.shutdownNow()
            }
        }
    }

    override fun guilds(): List<DiscordGuild> {
        val live = jda?.guilds.orEmpty().map {
            DiscordGuild(it.id, it.name, it.iconUrl)
        }
        if (live.isNotEmpty()) {
            live.forEach { guildStore[it.id] = it }
            return live
        }
        return guildStore.values.toList()
    }

    override fun channels(guildId: String): List<DiscordChannel> {
        val jdaGuild = jda?.getGuildById(guildId)
        if (jdaGuild != null) {
            val now = System.currentTimeMillis()
            val cached = channelStore[guildId]
            val updatedAt = channelStoreUpdatedAt[guildId] ?: 0L
            if (cached != null && now - updatedAt <= CHANNEL_CACHE_TTL_MS) {
                return cached
            }

            val merged = collectChannels(jdaGuild)
            channelStore[guildId] = merged
            channelStoreUpdatedAt[guildId] = now
            cacheHub.channelCache.put(guildId, merged)
            return merged
        }

        return channelStore[guildId]
            ?: cacheHub.channelCache.get(guildId)
    }

    override fun members(guildId: String, limit: Int): List<DiscordMember> {
        val jdaGuild = jda?.getGuildById(guildId)
        if (jdaGuild != null) {
            val now = System.currentTimeMillis()
            val cached = memberStore[guildId]
            val updatedAt = memberStoreUpdatedAt[guildId] ?: 0L
            if (cached != null && now - updatedAt <= MEMBER_CACHE_TTL_MS) {
                return cached.take(limit)
            }

            val liveMembers = collectMembers(jdaGuild, MEMBER_CACHE_SIZE)
            if (liveMembers.isNotEmpty()) {
                memberStore[guildId] = liveMembers
                memberStoreUpdatedAt[guildId] = now
                return liveMembers.take(limit)
            }
        }

        return memberStore[guildId].orEmpty().take(limit)
    }

    override fun latestMessages(channelId: String, limit: Int): List<DiscordMessage> {
        return cacheHub.messageCache.latest(channelId, limit)
    }

    override fun sendMinecraftMessage(channelId: String, author: String, content: String, webhookUrl: String?) {
        val channel = jda?.getChannelById(TextChannel::class.java, channelId)
        if (channel != null && (!channel.canTalk() || !isPublicSendAllowed(channel))) {
            return
        }

        if (!webhookUrl.isNullOrBlank()) {
            sendByWebhook(webhookUrl, author, content)
        } else {
            if (channel != null) {
                val autoWebhookUrl = resolveOrCreateWebhookUrl(channel)
                if (autoWebhookUrl != null) {
                    sendByWebhook(autoWebhookUrl, author, content)
                } else {
                    channel.sendMessage(content).queue()
                }
            }
        }
    }

    override fun registerListener(listener: DiscordUpdateListener) {
        listeners.add(listener)
    }

    private fun cacheGuild(guildId: String) {
        val guild = jda?.getGuildById(guildId) ?: return
        guildStore[guild.id] = DiscordGuild(guild.id, guild.name, guild.iconUrl)

        val now = System.currentTimeMillis()
        val channels = collectChannels(guild)
        channelStore[guild.id] = channels
        channelStoreUpdatedAt[guild.id] = now
        cacheHub.channelCache.put(guild.id, channels)

        val members = collectMembers(guild, MEMBER_CACHE_SIZE)
        if (members.isNotEmpty()) {
            memberStore[guild.id] = members
            memberStoreUpdatedAt[guild.id] = now
        }
    }

    private data class OrderedChannel(
        val channel: DiscordChannel,
        val categoryPosition: Int,
        val channelPosition: Int
    )

    private fun collectChannels(guild: net.dv8tion.jda.api.entities.Guild): List<DiscordChannel> {
        val text = guild.textChannels.map {
            OrderedChannel(
                channel = DiscordChannel(
                    id = it.id,
                    guildId = guild.id,
                    name = it.name,
                    isVoice = false,
                    categoryName = it.parentCategory?.name,
                    canView = isPublicViewAllowed(it),
                    canTalk = it.canTalk() && isPublicSendAllowed(it)
                ),
                categoryPosition = it.parentCategory?.positionRaw ?: Int.MIN_VALUE,
                channelPosition = it.positionRaw
            )
        }

        val voice = guild.voiceChannels.map {
            OrderedChannel(
                channel = DiscordChannel(
                    id = it.id,
                    guildId = guild.id,
                    name = it.name,
                    isVoice = true,
                    categoryName = it.parentCategory?.name,
                    canView = isPublicViewAllowed(it),
                    canTalk = false
                ),
                categoryPosition = it.parentCategory?.positionRaw ?: Int.MIN_VALUE,
                channelPosition = it.positionRaw
            )
        }

        return (text + voice)
            .filter { it.channel.canView }
            .sortedWith(
                compareBy<OrderedChannel> { it.categoryPosition }
                    .thenBy { if (it.channel.isVoice) 1 else 0 }
                    .thenBy { it.channelPosition }
            )
            .map { it.channel }
    }

    private fun collectMembers(guild: net.dv8tion.jda.api.entities.Guild, limit: Int): List<DiscordMember> {
        return guild.members
            .sortedBy { it.effectiveName.lowercase() }
            .map { member ->
                DiscordMember(
                    id = member.id,
                    name = member.effectiveName,
                    roles = member.roles
                        .filter { !it.isPublicRole }
                        .map { it.name }
                        .take(2),
                    avatarUrl = member.effectiveAvatarUrl
                )
            }
            .take(limit)
    }

    private fun resolveOrCreateWebhookUrl(channel: TextChannel): String? {
        webhookStore[channel.id]?.let { return it }

        val existing = runCatching {
            channel.retrieveWebhooks().complete()
                .firstOrNull { hook -> hook.owner?.id == jda?.selfUser?.id }
        }.getOrNull()

        val webhook = if (existing != null) {
            existing
        } else {
            val canManageWebhooks = runCatching {
                channel.guild.selfMember.hasPermission(channel, Permission.MANAGE_WEBHOOKS)
            }.getOrDefault(false)

            if (!canManageWebhooks) {
                return null
            }

            runCatching {
                channel.createWebhook("InterDC").complete()
            }.getOrNull()
        }

        val resolvedUrl = webhook?.url
        if (!resolvedUrl.isNullOrBlank()) {
            webhookStore[channel.id] = resolvedUrl
        }
        return resolvedUrl
    }

    private fun isPublicSendAllowed(channel: TextChannel): Boolean {
        val publicRole = channel.guild.publicRole

        val channelOverride = channel.getPermissionOverride(publicRole)
        if (channelOverride != null) {
            if (channelOverride.denied.contains(Permission.MESSAGE_SEND)) {
                return false
            }
            if (channelOverride.allowed.contains(Permission.MESSAGE_SEND)) {
                return true
            }
        }

        val parentOverride = channel.parentCategory?.getPermissionOverride(publicRole)
        if (parentOverride != null) {
            if (parentOverride.denied.contains(Permission.MESSAGE_SEND)) {
                return false
            }
            if (parentOverride.allowed.contains(Permission.MESSAGE_SEND)) {
                return true
            }
        }

        return publicRole.hasPermission(Permission.MESSAGE_SEND)
    }

    private fun isPublicViewAllowed(channel: TextChannel): Boolean {
        val publicRole = channel.guild.publicRole

        val channelOverride = channel.getPermissionOverride(publicRole)
        if (channelOverride != null) {
            if (channelOverride.denied.contains(Permission.VIEW_CHANNEL)) {
                return false
            }
            if (channelOverride.allowed.contains(Permission.VIEW_CHANNEL)) {
                return true
            }
        }

        val parentOverride = channel.parentCategory?.getPermissionOverride(publicRole)
        if (parentOverride != null) {
            if (parentOverride.denied.contains(Permission.VIEW_CHANNEL)) {
                return false
            }
            if (parentOverride.allowed.contains(Permission.VIEW_CHANNEL)) {
                return true
            }
        }

        return publicRole.hasPermission(Permission.VIEW_CHANNEL)
    }

    private fun isPublicViewAllowed(channel: VoiceChannel): Boolean {
        val publicRole = channel.guild.publicRole

        val channelOverride = channel.getPermissionOverride(publicRole)
        if (channelOverride != null) {
            if (channelOverride.denied.contains(Permission.VIEW_CHANNEL)) {
                return false
            }
            if (channelOverride.allowed.contains(Permission.VIEW_CHANNEL)) {
                return true
            }
        }

        val parentOverride = channel.parentCategory?.getPermissionOverride(publicRole)
        if (parentOverride != null) {
            if (parentOverride.denied.contains(Permission.VIEW_CHANNEL)) {
                return false
            }
            if (parentOverride.allowed.contains(Permission.VIEW_CHANNEL)) {
                return true
            }
        }

        return publicRole.hasPermission(Permission.VIEW_CHANNEL)
    }

    private fun sendByWebhook(webhookUrl: String, author: String, content: String) {
        val payload = """
            {
              "username": "${escapeJson(author)}",
                            "avatar_url": "https://mc-heads.net/avatar/${escapeJson(author)}",
              "content": "${escapeJson(content)}"
            }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun composeDisplayContent(event: MessageReceivedEvent): String {
        val text = event.message.contentDisplay.trim()
        if (text.isNotBlank()) {
            return text
        }

        val embedText = event.message.embeds
            .mapNotNull { embed -> embedToText(embed) }
            .joinToString("\n")
            .trim()

        if (embedText.isNotBlank()) {
            return embedText
        }

        if (event.message.attachments.isNotEmpty()) {
            return "📎 ${event.message.attachments.size} attachment(s)"
        }

        return ""
    }

    private fun embedToText(embed: MessageEmbed): String? {
        val parts = mutableListOf<String>()
        embed.title?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        embed.description?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        embed.fields
            .take(2)
            .mapNotNull { field ->
                val value = field.value?.trim().orEmpty()
                val name = field.name?.trim().orEmpty()
                when {
                    name.isNotBlank() && value.isNotBlank() -> "$name: $value"
                    value.isNotBlank() -> value
                    else -> null
                }
            }
            .forEach { parts.add(it) }

        return if (parts.isEmpty()) null else parts.joinToString(" | ")
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
}
