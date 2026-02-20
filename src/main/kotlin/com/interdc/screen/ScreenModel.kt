package com.interdc.screen

import org.bukkit.Location
import java.util.UUID

data class InterDCScreen(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val width: Int,
    val height: Int,
    var worldName: String,
    var x: Double,
    var y: Double,
    var z: Double,
    var facing: String? = null,
    var guildId: String? = null,
    var channelId: String? = null,
    var secondaryGuildId: String? = null,
    var secondaryChannelId: String? = null,
    var lockedGuildId: String? = null,
    var lockedChannelId: String? = null,
    var webhookUrl: String? = null,
    var showMemberList: Boolean = false,
    val supportBlocks: MutableList<String> = mutableListOf(),
    val mapIds: MutableList<Int> = mutableListOf()
) {
    fun updateLocation(location: Location) {
        worldName = location.world?.name ?: worldName
        x = location.x
        y = location.y
        z = location.z
    }
}
