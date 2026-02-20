package com.interdc.interaction

import com.interdc.InterDCPlugin
import com.interdc.screen.ScreenManager
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScreenInteractionListener(
    private val plugin: InterDCPlugin,
    private val screenManager: ScreenManager,
    private val interactionService: InteractionService,
    private val panelFlyAssistService: PanelFlyAssistService
) : Listener {

    private val lastProcessedClickAt = ConcurrentHashMap<UUID, Long>()

    @EventHandler(ignoreCancelled = true)
    fun onRightClickAir(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_AIR) {
            return
        }

        val player = event.player
        if (isDuplicateClick(player.uniqueId)) {
            return
        }

        val maxDistance = plugin.config.getInt("screen.interaction-distance", 10)
            .coerceIn(2, 48)
            .toDouble()

        val rayResult = player.world.rayTraceEntities(
            player.eyeLocation,
            player.eyeLocation.direction,
            maxDistance,
            0.15
        ) { entity ->
            val frame = entity as? ItemFrame ?: return@rayTraceEntities false
            screenManager.findByFrame(frame) != null
        } ?: return

        val frame = rayResult.hitEntity as? ItemFrame ?: return
        val screen = screenManager.findByFrame(frame) ?: return
        val hitPosition = rayResult.hitPosition
        val distanceFromEye = player.eyeLocation.toVector().distance(hitPosition)

        if (distanceFromEye <= 6.25) {
            return
        }
        panelFlyAssistService.tryActivate(player, screen, frame, notify = true)
    }

    @EventHandler(ignoreCancelled = true)
    fun onRightClick(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) {
            return
        }

        if (isDuplicateClick(event.player.uniqueId)) {
            return
        }

        val frame = event.rightClicked as? ItemFrame ?: return
        val screen = screenManager.findByFrame(frame) ?: return

        event.isCancelled = true
        markClick(event.player.uniqueId)
        panelFlyAssistService.tryActivate(event.player, screen, frame, notify = true)
        interactionService.handleRightClick(event.player, screen, frame, event.clickedPosition)
    }

    @EventHandler(ignoreCancelled = true)
    fun onLeftClick(event: EntityDamageByEntityEvent) {
        val frame = event.entity as? ItemFrame ?: return
        val screen = screenManager.findByFrame(frame) ?: return
        val player = event.damager as? Player ?: return

        event.isCancelled = true
        panelFlyAssistService.tryActivate(player, screen, frame)
        interactionService.handleLeftClick(player, screen)
    }

    @EventHandler(ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        val frame = event.entity as? ItemFrame ?: return
        if (screenManager.findByFrame(frame) != null) {
            event.isCancelled = true
        }
    }

    private fun isDuplicateClick(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val last = lastProcessedClickAt[playerId] ?: return false
        return now - last < 120L
    }

    private fun markClick(playerId: UUID) {
        lastProcessedClickAt[playerId] = System.currentTimeMillis()
    }

}
