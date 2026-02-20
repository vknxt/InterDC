package com.interdc.screen

import com.interdc.InterDCPlugin
import com.interdc.storage.SQLiteStorage
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ScreenRepository(
    private val plugin: InterDCPlugin,
    private val storage: SQLiteStorage
) {

    private val file = File(plugin.dataFolder, "screens.yml")
    private val screens = ConcurrentHashMap<String, InterDCScreen>()

    fun load() {
        screens.clear()

        val dbScreens = runCatching { storage.loadScreens() }.getOrDefault(emptyList())
        if (dbScreens.isNotEmpty()) {
            dbScreens.forEach { screens[it.id] = it }
            return
        }

        val migrated = loadFromLegacyYaml()
        if (migrated.isNotEmpty()) {
            runCatching { storage.saveScreens(migrated) }
                .onFailure { plugin.logger.warning("[InterDC] Falha ao migrar telas para SQLite: ${it.message}") }
                .onSuccess {
                    plugin.logger.info("[InterDC] Migração de screens.yml para SQLite concluída (${migrated.size} telas).")
                }
        }
    }

    fun save() {
        runCatching {
            storage.saveScreens(screens.values)
        }.onFailure {
            plugin.logger.warning("[InterDC] Falha ao salvar telas no SQLite: ${it.message}")
        }
    }

    fun put(screen: InterDCScreen) {
        screens[screen.id] = screen
    }

    fun remove(screenId: String): Boolean {
        return screens.remove(screenId) != null
    }

    fun get(screenId: String): InterDCScreen? {
        return screens[screenId]
    }

    fun all(): Collection<InterDCScreen> {
        return screens.values
    }

    fun findByMapId(mapId: Int): InterDCScreen? {
        return screens.values.firstOrNull { mapId in it.mapIds }
    }

    private fun loadFromLegacyYaml(): List<InterDCScreen> {
        if (!file.exists()) {
            plugin.dataFolder.mkdirs()
            file.createNewFile()
            return emptyList()
        }

        val migrated = mutableListOf<InterDCScreen>()
        val config = YamlConfiguration.loadConfiguration(file)
        val root = config.getConfigurationSection("screens") ?: return emptyList()

        root.getKeys(false).forEach { id ->
            val section = root.getConfigurationSection(id) ?: return@forEach
            val screen = InterDCScreen(
                id = id,
                width = section.getInt("width", 1),
                height = section.getInt("height", 1),
                worldName = section.getString("world", "world") ?: "world",
                x = section.getDouble("x"),
                y = section.getDouble("y"),
                z = section.getDouble("z"),
                facing = section.getString("facing"),
                guildId = section.getString("guildId"),
                channelId = section.getString("channelId"),
                secondaryGuildId = section.getString("secondaryGuildId"),
                secondaryChannelId = section.getString("secondaryChannelId"),
                lockedGuildId = section.getString("lockedGuildId"),
                lockedChannelId = section.getString("lockedChannelId"),
                webhookUrl = section.getString("webhookUrl"),
                showMemberList = section.getBoolean("showMemberList", false),
                supportBlocks = section.getStringList("supportBlocks").toMutableList(),
                mapIds = section.getIntegerList("mapIds").toMutableList()
            )
            screens[id] = screen
            migrated.add(screen)
        }

        return migrated
    }
}
