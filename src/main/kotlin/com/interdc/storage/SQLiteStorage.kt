package com.interdc.storage

import com.interdc.InterDCPlugin
import com.interdc.discord.DiscordMessage
import com.interdc.screen.InterDCScreen
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

class SQLiteStorage(private val plugin: InterDCPlugin) {

    data class StoredGuildTheme(
        val guildId: String,
        val primaryHex: String,
        val backgroundHex: String,
        val sidebarHex: String,
        val messageHex: String,
        val showVoiceChannels: Boolean,
        val layout: String
    )

    private val dbFile = File(plugin.dataFolder, "interdc.db")
    private val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

    fun initialize() {
        plugin.dataFolder.mkdirs()
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA temp_store=MEMORY")
                st.execute("PRAGMA cache_size=-20000")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS screens (
                        id TEXT PRIMARY KEY,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        world_name TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        facing TEXT,
                        guild_id TEXT,
                        channel_id TEXT,
                        webhook_url TEXT,
                        show_member_list INTEGER NOT NULL,
                        support_blocks TEXT NOT NULL,
                        map_ids TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS discord_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        channel_id TEXT NOT NULL,
                        author TEXT NOT NULL,
                        content TEXT NOT NULL,
                        timestamp TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS guild_themes (
                        guild_id TEXT PRIMARY KEY,
                        primary_hex TEXT NOT NULL,
                        background_hex TEXT NOT NULL,
                        sidebar_hex TEXT NOT NULL,
                        message_hex TEXT NOT NULL,
                        show_voice_channels INTEGER NOT NULL,
                        layout TEXT NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_discord_messages_channel_id ON discord_messages(channel_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_screens_guild_id ON screens(guild_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_screens_secondary_guild_id ON screens(secondary_guild_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_screens_channel_id ON screens(channel_id)")
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_screens_secondary_channel_id ON screens(secondary_channel_id)")
            }

            ensureScreensColumns(conn)
        }

        if (plugin.config.getBoolean("debug.enabled", false) && plugin.config.getBoolean("debug.storage", false)) {
            plugin.logger.info("[InterDC] SQLite initialized at ${dbFile.absolutePath}")
        }
    }

    fun ping(): Boolean {
        return runCatching {
            connection().use { conn ->
                conn.prepareStatement("SELECT 1").use { ps ->
                    ps.executeQuery().use { rs -> rs.next() }
                }
            }
        }.getOrDefault(false)
    }

    private fun ensureScreensColumns(conn: Connection) {
        val columns = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(screens)").use { rs ->
                while (rs.next()) {
                    columns.add(rs.getString("name"))
                }
            }
        }

        conn.createStatement().use { st ->
            if (!columns.contains("secondary_guild_id")) {
                st.executeUpdate("ALTER TABLE screens ADD COLUMN secondary_guild_id TEXT")
            }
            if (!columns.contains("secondary_channel_id")) {
                st.executeUpdate("ALTER TABLE screens ADD COLUMN secondary_channel_id TEXT")
            }
            if (!columns.contains("facing")) {
                st.executeUpdate("ALTER TABLE screens ADD COLUMN facing TEXT")
            }
            if (!columns.contains("locked_guild_id")) {
                st.executeUpdate("ALTER TABLE screens ADD COLUMN locked_guild_id TEXT")
            }
            if (!columns.contains("locked_channel_id")) {
                st.executeUpdate("ALTER TABLE screens ADD COLUMN locked_channel_id TEXT")
            }
        }
    }

    fun hasGuildTheme(guildId: String): Boolean {
        connection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM guild_themes WHERE guild_id = ? LIMIT 1").use { ps ->
                ps.setString(1, guildId)
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    fun loadGuildTheme(guildId: String): StoredGuildTheme? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT guild_id, primary_hex, background_hex, sidebar_hex, message_hex,
                       show_voice_channels, layout
                FROM guild_themes
                WHERE guild_id = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, guildId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return StoredGuildTheme(
                        guildId = rs.getString("guild_id"),
                        primaryHex = rs.getString("primary_hex"),
                        backgroundHex = rs.getString("background_hex"),
                        sidebarHex = rs.getString("sidebar_hex"),
                        messageHex = rs.getString("message_hex"),
                        showVoiceChannels = rs.getInt("show_voice_channels") == 1,
                        layout = rs.getString("layout")
                    )
                }
            }
        }
    }

    fun saveGuildTheme(theme: StoredGuildTheme) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO guild_themes (
                    guild_id, primary_hex, background_hex, sidebar_hex, message_hex,
                    show_voice_channels, layout, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(guild_id) DO UPDATE SET
                    primary_hex = excluded.primary_hex,
                    background_hex = excluded.background_hex,
                    sidebar_hex = excluded.sidebar_hex,
                    message_hex = excluded.message_hex,
                    show_voice_channels = excluded.show_voice_channels,
                    layout = excluded.layout,
                    updated_at = excluded.updated_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, theme.guildId)
                ps.setString(2, theme.primaryHex)
                ps.setString(3, theme.backgroundHex)
                ps.setString(4, theme.sidebarHex)
                ps.setString(5, theme.messageHex)
                ps.setInt(6, if (theme.showVoiceChannels) 1 else 0)
                ps.setString(7, theme.layout)
                ps.setLong(8, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    fun loadScreens(): List<InterDCScreen> {
        val output = mutableListOf<InterDCScreen>()
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, width, height, world_name, x, y, z, guild_id, channel_id,
                        facing,
                      secondary_guild_id, secondary_channel_id,
                        locked_guild_id, locked_channel_id,
                      webhook_url, show_member_list, support_blocks, map_ids
                FROM screens
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        output.add(
                            InterDCScreen(
                                id = rs.getString("id"),
                                width = rs.getInt("width"),
                                height = rs.getInt("height"),
                                worldName = rs.getString("world_name"),
                                x = rs.getDouble("x"),
                                y = rs.getDouble("y"),
                                z = rs.getDouble("z"),
                                facing = rs.getString("facing"),
                                guildId = rs.getString("guild_id"),
                                channelId = rs.getString("channel_id"),
                                secondaryGuildId = rs.getString("secondary_guild_id"),
                                secondaryChannelId = rs.getString("secondary_channel_id"),
                                lockedGuildId = rs.getString("locked_guild_id"),
                                lockedChannelId = rs.getString("locked_channel_id"),
                                webhookUrl = rs.getString("webhook_url"),
                                showMemberList = rs.getInt("show_member_list") == 1,
                                supportBlocks = decodeSupportBlocks(rs.getString("support_blocks")).toMutableList(),
                                mapIds = decodeMapIds(rs.getString("map_ids")).toMutableList()
                            )
                        )
                    }
                }
            }
        }
        return output
    }

    fun saveScreens(screens: Collection<InterDCScreen>) {
        connection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("DELETE FROM screens").use { it.executeUpdate() }

            conn.prepareStatement(
                """
                INSERT INTO screens (
                    id, width, height, world_name, x, y, z,
                    facing, guild_id, channel_id, secondary_guild_id, secondary_channel_id,
                    locked_guild_id, locked_channel_id,
                    webhook_url, show_member_list,
                    support_blocks, map_ids, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                screens.forEach { screen ->
                    ps.setString(1, screen.id)
                    ps.setInt(2, screen.width)
                    ps.setInt(3, screen.height)
                    ps.setString(4, screen.worldName)
                    ps.setDouble(5, screen.x)
                    ps.setDouble(6, screen.y)
                    ps.setDouble(7, screen.z)
                    ps.setString(8, screen.facing)
                    ps.setString(9, screen.guildId)
                    ps.setString(10, screen.channelId)
                    ps.setString(11, screen.secondaryGuildId)
                    ps.setString(12, screen.secondaryChannelId)
                    ps.setString(13, screen.lockedGuildId)
                    ps.setString(14, screen.lockedChannelId)
                    ps.setString(15, screen.webhookUrl)
                    ps.setInt(16, if (screen.showMemberList) 1 else 0)
                    ps.setString(17, encodeSupportBlocks(screen.supportBlocks))
                    ps.setString(18, encodeMapIds(screen.mapIds))
                    ps.setLong(19, System.currentTimeMillis())
                    ps.addBatch()
                }
                ps.executeBatch()
            }

            conn.commit()
            conn.autoCommit = true
        }
    }

    fun hasAnyScreen(): Boolean {
        connection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM screens LIMIT 1").use { ps ->
                ps.executeQuery().use { rs ->
                    return rs.next()
                }
            }
        }
    }

    fun addMessage(channelId: String, message: DiscordMessage, keepMax: Int = 200) {
        connection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO discord_messages(channel_id, author, content, timestamp) VALUES (?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, channelId)
                ps.setString(2, message.author)
                ps.setString(3, message.content)
                ps.setString(4, message.timestamp.toString())
                ps.executeUpdate()
            }

            conn.prepareStatement(
                """
                DELETE FROM discord_messages
                WHERE channel_id = ?
                  AND id NOT IN (
                    SELECT id FROM discord_messages WHERE channel_id = ? ORDER BY id DESC LIMIT ?
                  )
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, channelId)
                ps.setString(2, channelId)
                ps.setInt(3, keepMax)
                ps.executeUpdate()
            }
        }
    }

    fun latestMessages(channelId: String, limit: Int = 10): List<DiscordMessage> {
        val rows = mutableListOf<DiscordMessage>()
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT channel_id, author, content, timestamp
                FROM discord_messages
                WHERE channel_id = ?
                ORDER BY id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, channelId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        rows.add(
                            DiscordMessage(
                                channelId = rs.getString("channel_id"),
                                author = rs.getString("author"),
                                content = rs.getString("content"),
                                timestamp = runCatching { Instant.parse(rs.getString("timestamp")) }
                                    .getOrDefault(Instant.now())
                            )
                        )
                    }
                }
            }
        }
        return rows
    }

    private fun connection(): Connection {
        return DriverManager.getConnection(jdbcUrl).apply {
            createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA busy_timeout=5000")
                st.execute("PRAGMA temp_store=MEMORY")
                st.execute("PRAGMA cache_size=-20000")
            }
        }
    }

    private fun encodeSupportBlocks(values: List<String>): String {
        return values.joinToString("\n")
    }

    private fun decodeSupportBlocks(raw: String?): List<String> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun encodeMapIds(values: List<Int>): String {
        return values.joinToString(",")
    }

    private fun decodeMapIds(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(",").mapNotNull { it.toIntOrNull() }
    }
}
