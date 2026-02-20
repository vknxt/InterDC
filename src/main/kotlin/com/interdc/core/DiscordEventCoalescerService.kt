package com.interdc.core

import com.interdc.InterDCPlugin
import com.interdc.screen.ScreenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class DiscordEventCoalescerService(
    private val plugin: InterDCPlugin,
    private val scope: CoroutineScope,
    private val screenManager: ScreenManager,
    private val metricsService: PrometheusMetricsService,
    private val coalesceWindowMs: Long = 150L
) {

    data class Snapshot(
        val queueDepth: Int,
        val flushedBatches: Long,
        val flushedEvents: Long
    )

    private sealed class Event {
        data class Message(val channelId: String) : Event()
        data class ChannelUpdate(val guildId: String) : Event()
    }

    private var queue = Channel<Event>(Channel.UNLIMITED)
    private var worker: Job? = null
    private val queuedEvents = AtomicInteger(0)
    private val flushedBatches = AtomicLong(0)
    private val flushedEvents = AtomicLong(0)

    fun start() {
        if (worker != null) {
            return
        }

        if (queue.isClosedForSend) {
            queue = Channel(Channel.UNLIMITED)
        }

        worker = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val first = queue.receiveCatching().getOrNull() ?: break
                val messageChannels = linkedSetOf<String>()
                val guildUpdates = linkedSetOf<String>()
                collect(first, messageChannels, guildUpdates)

                val deadline = System.nanoTime() + (coalesceWindowMs * 1_000_000L)
                while (isActive) {
                    val remainingNs = deadline - System.nanoTime()
                    if (remainingNs <= 0L) {
                        break
                    }
                    val timeoutMs = max(1L, remainingNs / 1_000_000L)
                    val next = withTimeoutOrNull(timeoutMs) { queue.receive() } ?: break
                    collect(next, messageChannels, guildUpdates)

                    if (messageChannels.size + guildUpdates.size >= 512) {
                        break
                    }
                }

                flushToMainThread(messageChannels, guildUpdates)
            }
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        if (!queue.isClosedForSend) {
            queue.close()
        }
        queuedEvents.set(0)
    }

    fun snapshot(): Snapshot {
        return Snapshot(
            queueDepth = queuedEvents.get().coerceAtLeast(0),
            flushedBatches = flushedBatches.get(),
            flushedEvents = flushedEvents.get()
        )
    }

    fun enqueueMessage(channelId: String) {
        queuedEvents.incrementAndGet()
        if (queue.trySend(Event.Message(channelId)).isFailure) {
            queuedEvents.decrementAndGet()
        }
    }

    fun enqueueChannelUpdate(guildId: String) {
        queuedEvents.incrementAndGet()
        if (queue.trySend(Event.ChannelUpdate(guildId)).isFailure) {
            queuedEvents.decrementAndGet()
        }
    }

    private fun collect(event: Event, messageChannels: MutableSet<String>, guildUpdates: MutableSet<String>) {
        queuedEvents.decrementAndGet()
        when (event) {
            is Event.Message -> messageChannels.add(event.channelId)
            is Event.ChannelUpdate -> guildUpdates.add(event.guildId)
        }
    }

    private fun flushToMainThread(messageChannels: Set<String>, guildUpdates: Set<String>) {
        if (messageChannels.isEmpty() && guildUpdates.isEmpty()) {
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            val totalEvents = messageChannels.size + guildUpdates.size
            val screens = screenManager.allScreens()
            val affectedMessageGuilds = mutableSetOf<String>()

            if (messageChannels.isNotEmpty()) {
                screens.forEach { screen ->
                    if (screen.channelId in messageChannels || screen.secondaryChannelId in messageChannels) {
                        screenManager.markDirty(screen.id)
                        screen.guildId?.let { affectedMessageGuilds.add(it) }
                        screen.secondaryGuildId?.let { affectedMessageGuilds.add(it) }
                    }
                }
            }

            if (guildUpdates.isNotEmpty()) {
                screens.forEach { screen ->
                    if (screen.guildId in guildUpdates || screen.secondaryGuildId in guildUpdates) {
                        screenManager.markDirty(screen.id)
                    }
                }
            }

            if (messageChannels.isNotEmpty()) {
                if (affectedMessageGuilds.isEmpty()) {
                    metricsService.incrementDiscordMessageEvent()
                } else {
                    affectedMessageGuilds.forEach { guildId ->
                        metricsService.incrementDiscordMessageEvent(guildId)
                    }
                }
            }

            guildUpdates.forEach { guildId ->
                metricsService.incrementDiscordChannelUpdateEvent(guildId)
            }

            flushedBatches.incrementAndGet()
            flushedEvents.addAndGet(totalEvents.toLong())

            if (plugin.config.getBoolean("debug.enabled", false) && plugin.config.getBoolean("debug.discord-events", false)) {
                plugin.logger.info(
                    "[InterDC] Coalescer flush: messages=${messageChannels.size}, guildUpdates=${guildUpdates.size}, queueDepth=${queuedEvents.get().coerceAtLeast(0)}, screens=${screens.size}"
                )
            }
        })
    }
}
