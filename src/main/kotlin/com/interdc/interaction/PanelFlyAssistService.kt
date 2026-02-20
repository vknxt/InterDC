package com.interdc.interaction

import com.interdc.InterDCPlugin
import com.interdc.core.MessageService
import com.interdc.screen.InterDCScreen
import org.bukkit.GameMode
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

class PanelFlyAssistService(
    private val plugin: InterDCPlugin,
    private val messageService: MessageService
) : Listener {

    private val monitorTask: BukkitTask = plugin.server.scheduler.runTaskTimer(
        plugin,
        Runnable { tickActiveSessions() },
        10L,
        10L
    )

    private data class FlySession(
        val worldName: String,
        val center: Vector,
        val right: Vector,
        val up: Vector,
        val out: Vector,
        val halfWidth: Double,
        val halfHeight: Double,
        val backDistance: Double,
        val frontDistance: Double,
        val previousAllowFlight: Boolean,
        val previousFlying: Boolean,
        var outsideChecks: Int = 0
    )

    private val active = ConcurrentHashMap<UUID, FlySession>()
    private val protectedFromFallDamage = ConcurrentHashMap.newKeySet<UUID>()
    private val lastEnableNoticeAt = ConcurrentHashMap<UUID, Long>()

    fun tryActivate(player: Player, screen: InterDCScreen, frame: ItemFrame, notify: Boolean = true) {
        if (!isEnabled()) {
            return
        }
        if (!isLargeScreen(screen)) {
            deactivate(player)
            return
        }
        if (player.gameMode == GameMode.SPECTATOR || player.gameMode == GameMode.CREATIVE) {
            return
        }

        val tile = resolveTileCoordinates(screen, frame) ?: return
        val basis = resolveBasis(frame.facing)

        val clicked = frame.location.toVector()
        val topLeft = clicked
            .clone()
            .subtract(basis.right.clone().multiply(tile.tileX.toDouble()))
            .add(basis.up.clone().multiply(tile.tileY.toDouble()))

        val center = topLeft
            .clone()
            .add(basis.right.clone().multiply((screen.width - 1) / 2.0))
            .subtract(basis.up.clone().multiply((screen.height - 1) / 2.0))

        val sideMargin = configSideMargin()
        val verticalMargin = configVerticalMargin()
        val existing = active[player.uniqueId]

        if (existing != null) {
            val current = player.location.toVector()
            if (player.world.name == existing.worldName && isInsideBounds(current, existing)) {
                return
            }
        }

        val outTowardPlayer = basis.out.clone()
        val playerOffset = player.location.toVector().subtract(center)
        if (playerOffset.dot(outTowardPlayer) < 0) {
            outTowardPlayer.multiply(-1)
        }

        val session = FlySession(
            worldName = player.world.name,
            center = center,
            right = basis.right,
            up = basis.up,
            out = outTowardPlayer,
            halfWidth = (screen.width / 2.0) + sideMargin,
            halfHeight = (screen.height / 2.0) + verticalMargin,
            backDistance = configBackDistance(),
            frontDistance = configFrontDistance(),
            previousAllowFlight = existing?.previousAllowFlight ?: player.allowFlight,
            previousFlying = existing?.previousFlying ?: player.isFlying,
            outsideChecks = 0
        )

        active[player.uniqueId] = session
        player.allowFlight = true
        player.isFlying = true
        player.fallDistance = 0f
        if (notify && existing == null && shouldSendEnableNotice(player.uniqueId)) {
            messageService.send(player, "fly-assist-enabled")
        }
    }

    fun shutdown() {
        monitorTask.cancel()

        active.keys.toList().forEach { playerId ->
            val player = plugin.server.getPlayer(playerId) ?: return@forEach
            deactivate(player)
        }
        active.clear()
        protectedFromFallDamage.clear()
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.world.name == to.world.name &&
            from.blockX == to.blockX &&
            from.blockY == to.blockY &&
            from.blockZ == to.blockZ
        ) {
            return
        }

        val player = event.player
        val session = active[player.uniqueId] ?: return
        val current = event.to.toVector()
        evaluateSession(player, session, current)
    }

    @EventHandler(ignoreCancelled = true)
    fun onQuit(event: PlayerQuitEvent) {
        active.remove(event.player.uniqueId)
        protectedFromFallDamage.remove(event.player.uniqueId)
        lastEnableNoticeAt.remove(event.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onFallDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.cause != EntityDamageEvent.DamageCause.FALL) {
            return
        }
        if (protectedFromFallDamage.remove(player.uniqueId)) {
            event.isCancelled = true
        }
    }

    private fun deactivate(player: Player) {
        val session = active.remove(player.uniqueId) ?: return

        player.isFlying = false
        player.allowFlight = session.previousAllowFlight
        if (session.previousAllowFlight && session.previousFlying) {
            player.isFlying = true
        }

        player.fallDistance = 0f
        protectedFromFallDamage.add(player.uniqueId)
        messageService.send(player, "fly-assist-disabled")
    }

    private fun tickActiveSessions() {
        if (active.isEmpty()) {
            return
        }

        val ids = active.keys.toList()
        for (playerId in ids) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null) {
                active.remove(playerId)
                protectedFromFallDamage.remove(playerId)
                continue
            }

            val session = active[playerId] ?: continue
            evaluateSession(player, session, player.location.toVector())
        }
    }

    private fun evaluateSession(player: Player, session: FlySession, location: Vector) {
        if (player.world.name != session.worldName) {
            deactivate(player)
            return
        }

        if (isInsideBounds(location, session)) {
            session.outsideChecks = 0
            return
        }

        session.outsideChecks++
        if (session.outsideChecks >= configOutsideChecksBeforeDisable()) {
            deactivate(player)
        }
    }

    private fun isInsideBounds(location: Vector, session: FlySession): Boolean {
        val rel = location.clone().subtract(session.center)
        val rightCoord = rel.dot(session.right)
        val upCoord = rel.dot(session.up)
        val outCoord = rel.dot(session.out)
        val exitBuffer = configExitBuffer()

        val insideHorizontal = abs(rightCoord) <= session.halfWidth + exitBuffer
        val insideVertical = abs(upCoord) <= session.halfHeight + exitBuffer
        val insideDepth = outCoord >= -(session.backDistance + exitBuffer) && outCoord <= (session.frontDistance + exitBuffer)

        return insideHorizontal && insideVertical && insideDepth
    }

    private fun isLargeScreen(screen: InterDCScreen): Boolean {
        val minTiles = plugin.config.getInt("fly-assist.min-tiles", 9)
        return screen.width * screen.height >= max(1, minTiles)
    }

    private fun isEnabled(): Boolean {
        return plugin.config.getBoolean("fly-assist.enabled", true)
    }

    private fun configBackDistance(): Double {
        return plugin.config.getDouble("fly-assist.back-distance", 4.0).coerceAtLeast(1.0)
    }

    private fun configFrontDistance(): Double {
        return plugin.config.getDouble("fly-assist.front-distance", 1.5).coerceAtLeast(0.5)
    }

    private fun configSideMargin(): Double {
        return plugin.config.getDouble("fly-assist.side-margin", 1.5).coerceAtLeast(0.0)
    }

    private fun configVerticalMargin(): Double {
        return plugin.config.getDouble("fly-assist.vertical-margin", 1.5).coerceAtLeast(0.0)
    }

    private fun configExitBuffer(): Double {
        return plugin.config.getDouble("fly-assist.exit-buffer", 1.25).coerceAtLeast(0.0)
    }

    private fun configOutsideChecksBeforeDisable(): Int {
        return plugin.config.getInt("fly-assist.outside-checks-before-disable", 3).coerceAtLeast(1)
    }

    private data class Basis(
        val right: Vector,
        val up: Vector,
        val out: Vector
    )

    private data class TilePoint(
        val tileX: Int,
        val tileY: Int
    )

    private fun resolveBasis(face: BlockFace): Basis {
        val rightFace = face.horizontalRight()
        val right = rightFace.direction.normalize()
        val up = Vector(0, 1, 0)
        val out = face.direction.normalize()
        return Basis(right, up, out)
    }

    private fun resolveTileCoordinates(screen: InterDCScreen, frame: ItemFrame): TilePoint? {
        val item = frame.item
        val mapMeta = item.itemMeta as? MapMeta ?: return null
        val mapId = mapMeta.mapView?.id ?: return null
        val index = screen.mapIds.indexOf(mapId)
        if (index < 0) {
            return null
        }

        return TilePoint(
            tileX = index % screen.width,
            tileY = index / screen.width
        )
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

    private fun shouldSendEnableNotice(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val last = lastEnableNoticeAt[playerId] ?: 0L
        if (now - last < 2000L) {
            return false
        }
        lastEnableNoticeAt[playerId] = now
        return true
    }
}
