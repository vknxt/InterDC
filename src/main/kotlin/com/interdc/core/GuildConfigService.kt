package com.interdc.core

import com.interdc.InterDCPlugin
import com.interdc.storage.SQLiteStorage
import org.bukkit.Color
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class GuildTheme(
    val primaryColor: Color,
    val backgroundColor: Color,
    val sidebarColor: Color,
    val messageColor: Color,
    val showVoiceChannels: Boolean,
    val layout: String
)

class GuildConfigService(private val plugin: InterDCPlugin) {

    constructor(plugin: InterDCPlugin, storage: SQLiteStorage) : this(plugin) {
        this.storage = storage
    }

    private var storage: SQLiteStorage? = null

    private val serversDirectory = File(plugin.dataFolder, "servers")

    fun ensureServersDirectory() {
        if (!serversDirectory.exists()) {
            serversDirectory.mkdirs()
        }
        migrateLegacyThemesToDatabaseIfNeeded()
    }

    fun getTheme(guildId: String?): GuildTheme {
        if (guildId.isNullOrBlank()) {
            return defaultTheme()
        }

        val db = storage
        if (db != null) {
            val existing = runCatching { db.loadGuildTheme(guildId) }.getOrNull()
            if (existing != null) {
                return GuildTheme(
                    primaryColor = parseHexColor(existing.primaryHex, Color.fromRGB(0x58, 0x65, 0xF2)),
                    backgroundColor = parseHexColor(existing.backgroundHex, Color.fromRGB(0x1E, 0x1F, 0x22)),
                    sidebarColor = parseHexColor(existing.sidebarHex, Color.fromRGB(0x2B, 0x2D, 0x31)),
                    messageColor = parseHexColor(existing.messageHex, Color.fromRGB(0x31, 0x33, 0x38)),
                    showVoiceChannels = existing.showVoiceChannels,
                    layout = existing.layout
                )
            }
        }

        val file = File(serversDirectory, "$guildId.yml")
        if (!file.exists()) {
            val defaults = YamlConfiguration()
            defaults.set("theme.primary", "#5865F2")
            defaults.set("theme.background", "#1E1F22")
            defaults.set("theme.sidebar", "#2B2D31")
            defaults.set("theme.message", "#313338")
            defaults.set("layout", "discord")
            defaults.set("show-voice-channels", true)
            defaults.save(file)
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val theme = GuildTheme(
            primaryColor = parseHexColor(config.getString("theme.primary"), Color.fromRGB(0x58, 0x65, 0xF2)),
            backgroundColor = parseHexColor(config.getString("theme.background"), Color.fromRGB(0x1E, 0x1F, 0x22)),
            sidebarColor = parseHexColor(config.getString("theme.sidebar"), Color.fromRGB(0x2B, 0x2D, 0x31)),
            messageColor = parseHexColor(config.getString("theme.message"), Color.fromRGB(0x31, 0x33, 0x38)),
            showVoiceChannels = config.getBoolean("show-voice-channels", true),
            layout = config.getString("layout", "discord") ?: "discord"
        )

        if (db != null) {
            runCatching {
                db.saveGuildTheme(
                    SQLiteStorage.StoredGuildTheme(
                        guildId = guildId,
                        primaryHex = colorToHex(theme.primaryColor),
                        backgroundHex = colorToHex(theme.backgroundColor),
                        sidebarHex = colorToHex(theme.sidebarColor),
                        messageHex = colorToHex(theme.messageColor),
                        showVoiceChannels = theme.showVoiceChannels,
                        layout = theme.layout
                    )
                )
            }
        }

        return theme
    }

    private fun defaultTheme(): GuildTheme {
        return GuildTheme(
            primaryColor = Color.fromRGB(0x58, 0x65, 0xF2),
            backgroundColor = Color.fromRGB(0x1E, 0x1F, 0x22),
            sidebarColor = Color.fromRGB(0x2B, 0x2D, 0x31),
            messageColor = Color.fromRGB(0x31, 0x33, 0x38),
            showVoiceChannels = true,
            layout = "discord"
        )
    }

    private fun parseHexColor(raw: String?, fallback: Color): Color {
        if (raw.isNullOrBlank()) {
            return fallback
        }

        val clean = raw.removePrefix("#")
        return runCatching {
            val rgb = clean.toInt(16)
            Color.fromRGB(rgb)
        }.getOrDefault(fallback)
    }

    private fun colorToHex(color: Color): String {
        return "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun migrateLegacyThemesToDatabaseIfNeeded() {
        val db = storage ?: return
        if (!serversDirectory.exists()) {
            return
        }

        serversDirectory.listFiles { f -> f.isFile && f.extension.equals("yml", ignoreCase = true) }
            ?.forEach { file ->
                val guildId = file.nameWithoutExtension
                if (guildId.isBlank()) {
                    return@forEach
                }

                val already = runCatching { db.hasGuildTheme(guildId) }.getOrDefault(false)
                if (already) {
                    return@forEach
                }

                val config = YamlConfiguration.loadConfiguration(file)
                val theme = GuildTheme(
                    primaryColor = parseHexColor(config.getString("theme.primary"), Color.fromRGB(0x58, 0x65, 0xF2)),
                    backgroundColor = parseHexColor(config.getString("theme.background"), Color.fromRGB(0x1E, 0x1F, 0x22)),
                    sidebarColor = parseHexColor(config.getString("theme.sidebar"), Color.fromRGB(0x2B, 0x2D, 0x31)),
                    messageColor = parseHexColor(config.getString("theme.message"), Color.fromRGB(0x31, 0x33, 0x38)),
                    showVoiceChannels = config.getBoolean("show-voice-channels", true),
                    layout = config.getString("layout", "discord") ?: "discord"
                )

                runCatching {
                    db.saveGuildTheme(
                        SQLiteStorage.StoredGuildTheme(
                            guildId = guildId,
                            primaryHex = colorToHex(theme.primaryColor),
                            backgroundHex = colorToHex(theme.backgroundColor),
                            sidebarHex = colorToHex(theme.sidebarColor),
                            messageHex = colorToHex(theme.messageColor),
                            showVoiceChannels = theme.showVoiceChannels,
                            layout = theme.layout
                        )
                    )
                }
            }
    }
}
