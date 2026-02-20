package com.interdc.interaction

import com.interdc.core.GuildConfigService
import com.interdc.core.MessageService
import com.interdc.discord.DiscordChannel
import com.interdc.discord.DiscordGateway
import com.interdc.screen.InterDCScreen
import com.interdc.screen.ScreenManager
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.util.Vector
import kotlin.math.max
import kotlin.math.min
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InteractionService(
    private val screenManager: ScreenManager,
    private val discordGateway: DiscordGateway,
    private val composeService: ChatComposeService,
    private val messageService: MessageService,
    private val guildConfigService: GuildConfigService
) {

    private val lastChannelSwitch = ConcurrentHashMap<UUID, Long>()
    private val lastMemberToggle = ConcurrentHashMap<UUID, Long>()

    fun handleRightClick(player: Player, screen: InterDCScreen, frame: ItemFrame, clickedPosition: Vector) {
        val clickPoint = resolveClickPoint(screen, frame, clickedPosition) ?: return

        if (tryToggleMemberList(player, screen, clickPoint)) {
            return
        }

        if (trySelectChannel(player, screen, clickPoint)) {
            return
        }

        if (isInsideInputArea(screen, clickPoint)) {
            when (composeService.begin(player, screen)) {
                ComposeBeginResult.NO_CHANNEL -> messageService.send(player, "compose-no-channel")
                ComposeBeginResult.NO_PERMISSION -> messageService.send(player, "compose-no-permission")
                ComposeBeginResult.STARTED -> {
                }
            }
            return
        }
    }

    fun handleLeftClick(player: Player, screen: InterDCScreen) {
        return
    }

    private fun trySelectChannel(player: Player, screen: InterDCScreen, clickPoint: ClickPoint): Boolean {
        val now = System.currentTimeMillis()
        val last = lastChannelSwitch[player.uniqueId] ?: 0L
        if (now - last < 220L) {
            return true
        }

        if (!screen.lockedChannelId.isNullOrBlank()) {
            return true
        }

        val guildId = resolveGuildId(screen) ?: return false
        val theme = guildConfigService.getTheme(guildId)
        val channels = discordGateway.channels(guildId)
            .filter { channel ->
                when {
                    channel.isVoice -> theme.showVoiceChannels
                    else -> channel.canView
                }
            }

        val selected = resolveClickedChannel(screen, clickPoint, channels) ?: return false
        if (selected.isVoice) {
            return true
        }

        if (screen.channelId == selected.id && screen.guildId == guildId) {
            return true
        }

        screen.guildId = guildId
        screen.channelId = selected.id
        lastChannelSwitch[player.uniqueId] = now
        screenManager.persist(screen)
        screenManager.notifyChannelSwitch(player, selected.name)
        return true
    }

    private fun tryToggleMemberList(player: Player, screen: InterDCScreen, clickPoint: ClickPoint): Boolean {
        val layout = computeLayout(screen)
        if (!layout.canShowMemberPanel) {
            return false
        }

        val buttonRect = memberToggleRect(layout).inflate(4, 3)
        val fallbackTopBarToggle = player.isSneaking &&
            clickPoint.y in 0..(layout.topBarHeight + 4) &&
            clickPoint.x in layout.chatStartX..(layout.chatStartX + layout.chatWidth)

        if (!buttonRect.contains(clickPoint.x, clickPoint.y) && !fallbackTopBarToggle) {
            return false
        }

        val now = System.currentTimeMillis()
        val last = lastMemberToggle[player.uniqueId] ?: 0L
        if (now - last < 220L) {
            return true
        }
        lastMemberToggle[player.uniqueId] = now

        val visible = screenManager.toggleMemberList(screen)
        screenManager.notifyMemberList(player, visible)
        return true
    }

    private fun isInsideInputArea(screen: InterDCScreen, clickPoint: ClickPoint): Boolean {
        val layout = computeLayout(screen)
        val inputRect = inputRect(layout)
        return inputRect.contains(clickPoint.x, clickPoint.y)
    }

    private fun resolveClickedChannel(screen: InterDCScreen, clickPoint: ClickPoint, channels: List<DiscordChannel>): DiscordChannel? {
        val layout = computeLayout(screen)
        if (clickPoint.x < layout.guildRailWidth || clickPoint.x > layout.guildRailWidth + layout.channelPanelWidth) {
            return null
        }

        var channelY = 34
        val uncategorized = channels.filter { it.categoryName.isNullOrBlank() }
        val grouped = channels
            .filter { !it.categoryName.isNullOrBlank() }
            .groupBy { it.categoryName!! }

        fun findInEntries(entries: List<DiscordChannel>): DiscordChannel? {
            if (entries.isEmpty()) {
                return null
            }

            channelY += 12
            entries.take(9).forEach { channel ->
                val rowTop = channelY - 10
                val rowBottomExclusive = channelY + 4
                val rowLeft = layout.guildRailWidth + 6
                val rowRightExclusive = layout.guildRailWidth + layout.channelPanelWidth - 5

                if (clickPoint.x >= rowLeft && clickPoint.x < rowRightExclusive &&
                    clickPoint.y >= rowTop && clickPoint.y < rowBottomExclusive
                ) {
                    return channel
                }

                channelY += 14
            }
            channelY += 4
            return null
        }

        val uncategorizedMatch = findInEntries(uncategorized)
        if (uncategorizedMatch != null) {
            return uncategorizedMatch
        }

        grouped.forEach { (_, entries) ->
            val match = findInEntries(entries)
            if (match != null) {
                return match
            }
        }

        return null
    }

    private fun resolveGuildId(screen: InterDCScreen): String? {
        val guilds = discordGateway.guilds()
        return screen.guildId
            ?.takeIf { candidate -> guilds.any { it.id == candidate } }
            ?: guilds.firstOrNull()?.id
    }

    private fun resolveClickPoint(screen: InterDCScreen, frame: ItemFrame, clickedPosition: Vector): ClickPoint? {
        val tile = resolveTileCoordinates(screen, frame) ?: return null
        val local = resolveLocalPixel(frame, clickedPosition)

        val globalX = tile.tileX * 128 + local.localX
        val globalY = tile.tileY * 128 + local.localY
        return ClickPoint(globalX, globalY)
    }

    private fun resolveTileCoordinates(screen: InterDCScreen, frame: ItemFrame): TilePoint? {
        resolveTileCoordinatesByFramePosition(screen, frame)?.let { return it }

        val item = frame.item
        if (item.type != Material.FILLED_MAP) {
            return null
        }

        val mapMeta = item.itemMeta as? MapMeta ?: return null
        val mapId = mapMeta.mapView?.id ?: return null
        val index = screen.mapIds.indexOf(mapId)
        if (index < 0) {
            return null
        }

        val tileX = index % screen.width
        val tileY = index / screen.width
        return TilePoint(tileX, tileY)
    }

    private fun resolveTileCoordinatesByFramePosition(screen: InterDCScreen, frame: ItemFrame): TilePoint? {
        val face = frame.facing
        if (!face.isSupportedWall()) {
            return null
        }

        val right = face.horizontalRight()
        val support = frame.location.block.getRelative(face.oppositeFace)
        val originX = screen.x.toInt()
        val originY = screen.y.toInt()
        val originZ = screen.z.toInt()

        val diffX = support.x - originX
        val diffY = support.y - originY
        val diffZ = support.z - originZ

        val tileX = (diffX * right.modX) + (diffZ * right.modZ)
        val tileY = (screen.height - 1) - diffY

        if (tileX !in 0 until screen.width || tileY !in 0 until screen.height) {
            return null
        }

        return TilePoint(tileX, tileY)
    }

    private fun resolveLocalPixel(frame: ItemFrame, clickedPosition: Vector): LocalPixel {
        val face = frame.facing
        val box = frame.boundingBox
        val worldX = frame.location.x + clickedPosition.x
        val worldY = frame.location.y + clickedPosition.y
        val worldZ = frame.location.z + clickedPosition.z

        val xSpan = (box.maxX - box.minX).coerceAtLeast(1.0E-6)
        val ySpan = (box.maxY - box.minY).coerceAtLeast(1.0E-6)
        val zSpan = (box.maxZ - box.minZ).coerceAtLeast(1.0E-6)

        val rightNormalized = when (face) {
            BlockFace.NORTH -> (box.maxX - worldX) / xSpan
            BlockFace.SOUTH -> (worldX - box.minX) / xSpan
            BlockFace.EAST -> (box.maxZ - worldZ) / zSpan
            BlockFace.WEST -> (worldZ - box.minZ) / zSpan
            else -> (worldX - box.minX) / xSpan
        }.coerceIn(0.0, 1.0)

        val upNormalized = ((box.maxY - worldY) / ySpan).coerceIn(0.0, 1.0)

        val localX = (rightNormalized * 128.0).toInt().coerceIn(0, 127)
        val localY = (upNormalized * 128.0).toInt().coerceIn(0, 127)
        return LocalPixel(localX, localY)
    }

    private fun computeLayout(screen: InterDCScreen): UiLayout {
        val widthPx = screen.width * 128
        val heightPx = screen.height * 128
        val guildRailWidth = max(18, (widthPx * 0.07).toInt())
        val channelPanelWidth = max(80, (widthPx * 0.25).toInt())
        val canShowMemberPanel = widthPx >= 512
        val showMemberPanel = canShowMemberPanel && screen.showMemberList
        val memberPanelWidth = if (showMemberPanel) max(76, (widthPx * 0.2).toInt()) else 0
        val topBarHeight = 24
        val inputHeight = 24
        val chatStartX = guildRailWidth + channelPanelWidth
        val chatWidth = widthPx - chatStartX - memberPanelWidth

        return UiLayout(
            widthPx = widthPx,
            heightPx = heightPx,
            guildRailWidth = guildRailWidth,
            channelPanelWidth = channelPanelWidth,
            canShowMemberPanel = canShowMemberPanel,
            chatStartX = chatStartX,
            chatWidth = chatWidth,
            inputHeight = inputHeight,
            topBarHeight = topBarHeight
        )
    }

    private fun memberToggleRect(layout: UiLayout): Rect {
        val buttonWidth = max(76, min(108, layout.chatWidth / 3))
        val buttonHeight = 14
        val buttonX = layout.chatStartX + layout.chatWidth - buttonWidth - 8
        val buttonY = 5
        return Rect(buttonX, buttonY, buttonWidth, buttonHeight)
    }

    private fun inputRect(layout: UiLayout): Rect {
        val inputX = layout.chatStartX + 8
        val inputY = layout.heightPx - layout.inputHeight - 6
        val inputWidth = layout.chatWidth - 16
        return Rect(inputX, inputY, inputWidth, layout.inputHeight)
    }

    private data class TilePoint(
        val tileX: Int,
        val tileY: Int
    )

    private data class LocalPixel(
        val localX: Int,
        val localY: Int
    )

    private data class ClickPoint(
        val x: Int,
        val y: Int
    )

    private data class UiLayout(
        val widthPx: Int,
        val heightPx: Int,
        val guildRailWidth: Int,
        val channelPanelWidth: Int,
        val canShowMemberPanel: Boolean,
        val chatStartX: Int,
        val chatWidth: Int,
        val inputHeight: Int,
        val topBarHeight: Int
    )

    private data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    ) {
        fun contains(pointX: Int, pointY: Int): Boolean {
            return pointX >= x && pointX <= x + width && pointY >= y && pointY <= y + height
        }

        fun inflate(horizontal: Int, vertical: Int): Rect {
            return Rect(
                x = x - horizontal,
                y = y - vertical,
                width = width + (horizontal * 2),
                height = height + (vertical * 2)
            )
        }
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
