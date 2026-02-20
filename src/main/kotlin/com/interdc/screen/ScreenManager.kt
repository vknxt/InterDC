package com.interdc.screen

import com.interdc.InterDCPlugin
import com.interdc.core.GuildConfigService
import com.interdc.core.MessageService
import com.interdc.discord.DiscordGateway
import com.interdc.render.DiscordCanvasService
import com.interdc.render.ScreenTileRenderer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.scheduler.BukkitTask
import java.util.Locale
import java.util.UUID

class ScreenManager(
    private val plugin: InterDCPlugin,
    private val repository: ScreenRepository,
    private val canvasService: DiscordCanvasService,
    private val discordGateway: DiscordGateway,
    private val messageService: MessageService,
    private val guildConfigService: GuildConfigService
) {

    enum class LinkResult {
        SUCCESS,
        NOT_LOOKING_SCREEN,
        INVALID_CHANNEL
    }

    sealed class StyleResult {
        data class Success(val style: String) : StyleResult()
        data object NotLookingScreen : StyleResult()
        data object NoGuild : StyleResult()
        data object InvalidStyle : StyleResult()
    }

    companion object {
        private const val FRAME_TAG_PREFIX = "interdc-screen:"
    }

    private val screenRenderVersions = mutableMapOf<String, Long>()
    private val lastRestoreWarningLogAt = mutableMapOf<String, Long>()
    private val frameHealthTask: BukkitTask? = run {
        val interval = plugin.config.getLong("screen.frame-health-check-interval-ticks", 100L).coerceAtLeast(0L)
        if (interval <= 0L) {
            null
        } else {
            plugin.server.scheduler.runTaskTimer(
                plugin,
                Runnable { restoreMissingFrames() },
                40L,
                interval
            )
        }
    }

    private data class TilePlacement(
        val tileX: Int,
        val tileY: Int,
        val support: Block,
        val frameBlock: Block
    )

    private data class BlockPos(
        val worldName: String,
        val x: Int,
        val y: Int,
        val z: Int
    )

    fun createScreen(player: Player, width: Int, height: Int): InterDCScreen {
        val maxDistance = interactionDistance()

        val targetedFrame = player.getTargetEntity(maxDistance, false) as? ItemFrame
        if (targetedFrame != null && findByFrame(targetedFrame) != null) {
            throw IllegalStateException("screen-overlap")
        }

        val target = player.rayTraceBlocks(maxDistance.toDouble())
        val hitBlock = target?.hitBlock
        val face = target?.hitBlockFace
        if (hitBlock == null || face == null || !face.isSupportedWall()) {
            throw IllegalStateException("invalid-wall-target")
        }

        val availableGuilds = discordGateway.guilds()
        val configuredGuild = plugin.config.getString("default-guild-id")?.takeIf { it.isNotBlank() }
        val defaultGuild = configuredGuild
            ?.takeIf { configured -> availableGuilds.any { it.id == configured } }
            ?: availableGuilds.firstOrNull()?.id
        val defaultChannel = defaultGuild
            ?.let { discordGateway.channels(it) }
            ?.firstOrNull { !it.isVoice && it.canTalk }
            ?.id

        val screen = InterDCScreen(
            id = UUID.randomUUID().toString().substring(0, 8),
            width = width,
            height = height,
            worldName = player.world.name,
            x = hitBlock.x.toDouble(),
            y = hitBlock.y.toDouble(),
            z = hitBlock.z.toDouble(),
            facing = face.name,
            guildId = defaultGuild,
            channelId = defaultChannel
        )

        val right = face.horizontalRight()
        val world = player.world
        val spawnedFrames = mutableListOf<ItemFrame>()
        val placements = mutableListOf<TilePlacement>()

        repeat(height) { tileY ->
            repeat(width) { tileX ->
                val support = hitBlock
                    .getRelative(right, tileX)
                    .getRelative(BlockFace.UP, height - 1 - tileY)
                val frameBlock = support.getRelative(face)

                if (!frameBlock.type.isAir || hasItemFrameAt(frameBlock)) {
                    throw IllegalStateException("invalid-frame-space")
                }

                placements += TilePlacement(tileX, tileY, support, frameBlock)
            }
        }

        try {
            placements.forEach { placement ->
                val mapView = Bukkit.createMap(player.world)
                mapView.renderers.toList().forEach { mapView.removeRenderer(it) }
                mapView.addRenderer(
                    ScreenTileRenderer(
                        screen.id,
                        placement.tileX,
                        placement.tileY,
                        repository,
                        canvasService,
                        ::renderVersion,
                        { viewer -> messageService.localeOf(viewer) }
                    )
                )

                val mapItem = ItemStack(Material.FILLED_MAP)
                val mapMeta = mapItem.itemMeta as? MapMeta
                mapMeta?.mapView = mapView
                mapItem.itemMeta = mapMeta

                val frameLocation = frameEntityLocation(placement.frameBlock)
                val frame = world.spawnEntity(frameLocation, EntityType.ITEM_FRAME) as ItemFrame
                val facingSet = frame.setFacingDirection(face, true)
                if (!facingSet) {
                    frame.remove()
                    throw IllegalStateException("invalid-frame-space")
                }
                frame.isVisible = false
                frame.isFixed = true
                frame.addScoreboardTag(frameTag(screen.id))
                frame.setItem(mapItem)
                spawnedFrames.add(frame)

                screen.mapIds.add(mapView.id)
                screen.supportBlocks.add(serializeBlock(placement.support))
            }
        } catch (ex: Exception) {
            spawnedFrames.forEach { it.remove() }
            throw ex
        }

        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return screen
    }

    fun linkFocusedScreen(player: Player, channelId: String, guildId: String?): LinkResult {
        val screen = findFocusedScreen(player) ?: return LinkResult.NOT_LOOKING_SCREEN

        val availableGuilds = discordGateway.guilds()
        val resolvedGuildId = guildId
            ?.takeIf { candidate -> availableGuilds.any { it.id == candidate } }
            ?: screen.guildId?.takeIf { candidate -> availableGuilds.any { it.id == candidate } }
            ?: availableGuilds.firstOrNull()?.id
            ?: return LinkResult.INVALID_CHANNEL

        val selectedChannel = discordGateway.channels(resolvedGuildId)
            .firstOrNull { it.id == channelId }
            ?: return LinkResult.INVALID_CHANNEL

        if (selectedChannel.isVoice || !selectedChannel.canView) {
            return LinkResult.INVALID_CHANNEL
        }

        screen.guildId = resolvedGuildId
        screen.channelId = selectedChannel.id
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return LinkResult.SUCCESS
    }

    fun linkFocusedScreenSecondary(player: Player, channelId: String, guildId: String?): LinkResult {
        val screen = findFocusedScreen(player) ?: return LinkResult.NOT_LOOKING_SCREEN

        val availableGuilds = discordGateway.guilds()
        val resolvedGuildId = guildId
            ?.takeIf { candidate -> availableGuilds.any { it.id == candidate } }
            ?: screen.secondaryGuildId?.takeIf { candidate -> availableGuilds.any { it.id == candidate } }
            ?: screen.guildId?.takeIf { candidate -> availableGuilds.any { it.id == candidate } }
            ?: availableGuilds.firstOrNull()?.id
            ?: return LinkResult.INVALID_CHANNEL

        val selectedChannel = discordGateway.channels(resolvedGuildId)
            .firstOrNull { it.id == channelId }
            ?: return LinkResult.INVALID_CHANNEL

        if (selectedChannel.isVoice || !selectedChannel.canView) {
            return LinkResult.INVALID_CHANNEL
        }

        screen.secondaryGuildId = resolvedGuildId
        screen.secondaryChannelId = selectedChannel.id
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return LinkResult.SUCCESS
    }

    fun removeFocusedScreen(player: Player): Boolean {
        val screen = findFocusedScreen(player) ?: return false
        cleanupScreenArtifacts(screen)
        val removed = repository.remove(screen.id)
        if (removed) {
            repository.save()
        }
        return removed
    }

    fun moveFocusedScreen(player: Player): Boolean {
        val screen = findFocusedScreen(player) ?: return false
        screen.updateLocation(player.location)
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return true
    }

    fun setWebhookFocusedScreen(player: Player, webhookUrl: String?): Boolean {
        val screen = findFocusedScreen(player) ?: return false
        screen.webhookUrl = webhookUrl
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return true
    }

    fun lockFocusedScreenCurrentChannel(player: Player): Boolean {
        val screen = findFocusedScreen(player) ?: return false
        val guildId = screen.guildId ?: return false
        val channelId = screen.channelId ?: return false

        screen.lockedGuildId = guildId
        screen.lockedChannelId = channelId
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return true
    }

    fun unlockFocusedScreenChannel(player: Player): Boolean {
        val screen = findFocusedScreen(player) ?: return false
        screen.lockedGuildId = null
        screen.lockedChannelId = null
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
        return true
    }

    fun persist(screen: InterDCScreen) {
        repository.put(screen)
        repository.save()
        markDirty(screen.id)
    }

    fun reloadScreens() {
        repository.load()
        rebindMapRenderers()
        restoreMissingFrames()
        repository.all().forEach { markDirty(it.id) }
    }

    fun shutdown() {
        frameHealthTask?.cancel()
    }

    fun cycleFocusedScreenStyle(player: Player): StyleResult {
        val screen = findFocusedScreen(player) ?: return StyleResult.NotLookingScreen
        val guildId = screen.guildId ?: screen.secondaryGuildId ?: return StyleResult.NoGuild

        val layouts = guildConfigService.supportedLayouts().toList().sorted()
        val current = guildConfigService.getTheme(guildId).layout
        val currentIndex = layouts.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = layouts[(currentIndex + 1) % layouts.size]
        val applied = guildConfigService.setGuildLayout(guildId, next) ?: return StyleResult.NoGuild

        allScreens().forEach { existing ->
            if (existing.guildId == guildId || existing.secondaryGuildId == guildId) {
                markDirty(existing.id)
            }
        }

        return StyleResult.Success(applied)
    }

    fun setFocusedScreenStyle(player: Player, requestedStyle: String): StyleResult {
        val screen = findFocusedScreen(player) ?: return StyleResult.NotLookingScreen
        val guildId = screen.guildId ?: screen.secondaryGuildId ?: return StyleResult.NoGuild

        val resolved = guildConfigService.resolveSupportedLayout(requestedStyle) ?: return StyleResult.InvalidStyle
        val applied = guildConfigService.setGuildLayout(guildId, resolved) ?: return StyleResult.InvalidStyle

        allScreens().forEach { existing ->
            if (existing.guildId == guildId || existing.secondaryGuildId == guildId) {
                markDirty(existing.id)
            }
        }

        return StyleResult.Success(applied)
    }

    fun markDirty(screenId: String) {
        ScreenTileRenderer.invalidateScreenCache(screenId)
        synchronized(screenRenderVersions) {
            val current = screenRenderVersions[screenId] ?: 0L
            screenRenderVersions[screenId] = current + 1L
        }
    }

    fun findByMapId(mapId: Int): InterDCScreen? {
        return repository.findByMapId(mapId)
    }

    fun findByFrame(frame: ItemFrame): InterDCScreen? {
        val mapId = frameMapId(frame)
        if (mapId != null) {
            return repository.findByMapId(mapId)
        }

        val tagged = frame.scoreboardTags.firstOrNull { it.startsWith(FRAME_TAG_PREFIX) }
            ?: return null
        val screenId = tagged.removePrefix(FRAME_TAG_PREFIX)
        return repository.get(screenId)
    }

    fun allScreens(): Collection<InterDCScreen> {
        return repository.all()
    }

    private fun rebindMapRenderers() {
        repository.all().forEach { screen ->
            val expectedTiles = screen.width * screen.height
            if (screen.mapIds.size != expectedTiles) {
                plugin.logger.warning(
                    "[InterDC] Tela ${screen.id} possui ${screen.mapIds.size} mapIds salvos, mas o esperado é $expectedTiles (${screen.width}x${screen.height})."
                )
            }

            var reboundCount = 0
            val missingMapIds = mutableListOf<Int>()

            screen.mapIds.forEachIndexed { index, mapId ->
                val mapView = Bukkit.getMap(mapId)
                if (mapView == null) {
                    missingMapIds.add(mapId)
                    return@forEachIndexed
                }
                val tileX = index % screen.width
                val tileY = index / screen.width

                mapView.renderers.toList().forEach { renderer ->
                    mapView.removeRenderer(renderer)
                }
                mapView.addRenderer(
                    ScreenTileRenderer(
                        screen.id,
                        tileX,
                        tileY,
                        repository,
                        canvasService,
                        ::renderVersion,
                        { player -> messageService.localeOf(player) }
                    )
                )
                reboundCount++
            }

            if (missingMapIds.isNotEmpty()) {
                val preview = missingMapIds.take(6).joinToString(", ")
                val suffix = if (missingMapIds.size > 6) "..." else ""
                plugin.logger.warning(
                    "[InterDC] Tela ${screen.id} com mapIds inválidos/ausentes (${missingMapIds.size}): $preview$suffix"
                )
            }

            if (reboundCount == 0 && screen.mapIds.isNotEmpty()) {
                plugin.logger.warning(
                    "[InterDC] Tela ${screen.id} não teve nenhum mapa restaurado. Considere recriar a tela com /interdc remove e /interdc create."
                )
            }
        }
    }

    private fun renderVersion(screenId: String): Long {
        synchronized(screenRenderVersions) {
            return screenRenderVersions[screenId] ?: 0L
        }
    }

    fun findFocusedScreen(player: Player): InterDCScreen? {
        val hit = player.getTargetEntity(interactionDistance(), false) as? ItemFrame ?: return null
        return findByFrame(hit)
    }

    private fun interactionDistance(): Int {
        return plugin.config.getInt("screen.interaction-distance", 6).coerceIn(2, 24)
    }

    fun notifyChannelSwitch(player: Player, channelName: String) {
        messageService.send(player, "channel-cycle", mapOf("channel" to channelName))
    }

    fun toggleMemberList(screen: InterDCScreen): Boolean {
        screen.showMemberList = !screen.showMemberList
        persist(screen)
        return screen.showMemberList
    }

    fun notifyMemberList(player: Player, visible: Boolean) {
        messageService.send(player, if (visible) "member-list-on" else "member-list-off")
    }

    private fun frameEntityLocation(block: Block): Location {
        return Location(
            block.world,
            block.x + 0.5,
            block.y + 0.5,
            block.z + 0.5
        )
    }

    private fun hasItemFrameAt(block: Block): Boolean {
        val anchor = frameEntityLocation(block)
        return block.world.getNearbyEntities(anchor, 1.0, 1.0, 1.0)
            .filterIsInstance<ItemFrame>()
            .any { frame ->
                val location = frame.location
                val sameBlock = location.blockX == block.x &&
                    location.blockY == block.y &&
                    location.blockZ == block.z
                val sameSpot = location.world == block.world &&
                    location.distanceSquared(anchor) <= 0.85
                sameBlock || sameSpot
            }
    }

    private fun restoreMissingFrames() {
        val screensByWorld = repository.all().groupBy { it.worldName }
        screensByWorld.forEach { (worldName, worldScreens) ->
            val world = plugin.server.getWorld(worldName) ?: return@forEach
            val allFrames = world.entities.filterIsInstance<ItemFrame>()
            val screenIds = worldScreens.map { it.id }.toHashSet()

            val mapIdToScreenId = HashMap<Int, String>()
            worldScreens.forEach { screen ->
                screen.mapIds.forEach { mapId ->
                    mapIdToScreenId[mapId] = screen.id
                }
            }

            val framesByScreenId = HashMap<String, MutableList<ItemFrame>>()
            allFrames.forEach { frame ->
                val byTag = frameIdFromTag(frame)
                val resolvedScreenId = when {
                    byTag != null && byTag in screenIds -> byTag
                    else -> frameMapId(frame)?.let { mapId -> mapIdToScreenId[mapId] }
                }
                if (resolvedScreenId != null) {
                    framesByScreenId.computeIfAbsent(resolvedScreenId) { mutableListOf() }.add(frame)
                }
            }

            val occupiedPositions = allFrames
                .map { frame ->
                    BlockPos(
                        worldName = world.name,
                        x = frame.location.blockX,
                        y = frame.location.blockY,
                        z = frame.location.blockZ
                    )
                }
                .toHashSet()

            worldScreens.forEach { screen ->
                runCatching {
                    restoreMissingFrames(
                        screen = screen,
                        world = world,
                        knownScreenFrames = framesByScreenId[screen.id].orEmpty(),
                        occupiedPositions = occupiedPositions
                    )
                }.onFailure { ex ->
                    if (shouldLogWarning(screen.id)) {
                        plugin.logger.warning("[InterDC] Failed to restore frames for screen ${screen.id}: ${ex.message}")
                    }
                }
            }
        }
    }

    private fun restoreMissingFrames(
        screen: InterDCScreen,
        world: World,
        knownScreenFrames: List<ItemFrame>,
        occupiedPositions: MutableSet<BlockPos>
    ) {
        val face = resolveFacing(screen, knownScreenFrames) ?: return
        val existingByMapId = knownScreenFrames.mapNotNull { frame ->
            frameMapId(frame)?.let { mapId -> mapId to frame }
        }.toMap()
        val existingByPos = knownScreenFrames.associateBy { frame ->
            BlockPos(world.name, frame.location.blockX, frame.location.blockY, frame.location.blockZ)
        }

        var restored = 0
        screen.supportBlocks.forEachIndexed { index, serializedSupport ->
            val support = deserializeBlock(serializedSupport) ?: return@forEachIndexed
            val frameBlock = support.getRelative(face)
            val mapId = screen.mapIds.getOrNull(index) ?: return@forEachIndexed
            val framePos = BlockPos(world.name, frameBlock.x, frameBlock.y, frameBlock.z)

            if (existingByPos.containsKey(framePos) || existingByMapId.containsKey(mapId)) {
                return@forEachIndexed
            }

            if (!frameBlock.type.isAir || framePos in occupiedPositions) {
                return@forEachIndexed
            }

            val mapView = Bukkit.getMap(mapId) ?: return@forEachIndexed
            val mapItem = ItemStack(Material.FILLED_MAP)
            val mapMeta = mapItem.itemMeta as? MapMeta ?: return@forEachIndexed
            mapMeta.mapView = mapView
            mapItem.itemMeta = mapMeta

            val frame = world.spawnEntity(frameEntityLocation(frameBlock), EntityType.ITEM_FRAME) as ItemFrame
            val facingSet = frame.setFacingDirection(face, true)
            if (!facingSet) {
                frame.remove()
                return@forEachIndexed
            }
            frame.isVisible = false
            frame.isFixed = true
            frame.addScoreboardTag(frameTag(screen.id))
            frame.setItem(mapItem)
            occupiedPositions.add(framePos)
            restored++
        }

        if (restored > 0) {
            markDirty(screen.id)
        }
    }

    private fun shouldLogWarning(screenId: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lastRestoreWarningLogAt) {
            val last = lastRestoreWarningLogAt[screenId] ?: 0L
            if (now - last < 12_000L) {
                return false
            }
            lastRestoreWarningLogAt[screenId] = now
            return true
        }
    }

    private fun frameBelongsToScreen(frame: ItemFrame, screen: InterDCScreen): Boolean {
        return screen.id.equals(frameIdFromTag(frame), ignoreCase = true) ||
            screen.mapIds.contains(frameMapId(frame))
    }

    private fun resolveFacing(screen: InterDCScreen, knownScreenFrames: List<ItemFrame>): BlockFace? {
        val persisted = screen.facing
            ?.let { raw -> runCatching { BlockFace.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull() }
            ?.takeIf { it.isSupportedWall() }
        if (persisted != null) {
            return persisted
        }

        val fromExisting = knownScreenFrames
            .firstOrNull()
            ?.facing
            ?.takeIf { it.isSupportedWall() }
        if (fromExisting != null) {
            screen.facing = fromExisting.name
            repository.put(screen)
            return fromExisting
        }

        return null
    }

    private fun cleanupScreenArtifacts(screen: InterDCScreen) {
        plugin.server.worlds.forEach { world ->
            world.entities
                .filterIsInstance<ItemFrame>()
                .filter { frame ->
                    screen.id.equals(frameIdFromTag(frame), ignoreCase = true) ||
                        screen.mapIds.contains(frameMapId(frame))
                }
                .forEach { it.remove() }
        }

        screen.supportBlocks.forEach { serialized ->
            val block = deserializeBlock(serialized) ?: return@forEach
            val usedElsewhere = repository.all()
                .filter { it.id != screen.id }
                .any { other -> other.supportBlocks.contains(serialized) }
            if (!usedElsewhere && block.type == Material.BARRIER) {
                block.type = Material.AIR
            }
        }
    }

    private fun frameMapId(frame: ItemFrame): Int? {
        val frameItem = frame.item
        if (frameItem.type != Material.FILLED_MAP) {
            return null
        }

        val mapMeta = frameItem.itemMeta as? MapMeta ?: return null
        return mapMeta.mapView?.id
    }

    private fun frameTag(screenId: String): String = "$FRAME_TAG_PREFIX$screenId"

    private fun frameIdFromTag(frame: ItemFrame): String? {
        val tag = frame.scoreboardTags.firstOrNull { it.startsWith(FRAME_TAG_PREFIX) } ?: return null
        return tag.removePrefix(FRAME_TAG_PREFIX)
    }

    private fun serializeBlock(block: Block): String {
        return "${block.world.name};${block.x};${block.y};${block.z}".lowercase(Locale.ROOT)
    }

    private fun deserializeBlock(serialized: String): Block? {
        val parts = serialized.split(';')
        if (parts.size != 4) {
            return null
        }

        val world = plugin.server.getWorld(parts[0]) ?: return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        val z = parts[3].toIntOrNull() ?: return null
        return world.getBlockAt(x, y, z)
    }

    private fun BlockFace.isSupportedWall(): Boolean {
        return this == BlockFace.NORTH || this == BlockFace.SOUTH || this == BlockFace.EAST || this == BlockFace.WEST
    }

    private fun BlockFace.horizontalRight(): BlockFace {
        return when (this) {
            BlockFace.NORTH -> BlockFace.WEST
            BlockFace.SOUTH -> BlockFace.EAST
            BlockFace.EAST -> BlockFace.NORTH
            BlockFace.WEST -> BlockFace.SOUTH
            else -> BlockFace.EAST
        }
    }
}
