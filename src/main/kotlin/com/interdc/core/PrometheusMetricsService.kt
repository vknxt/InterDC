package com.interdc.core

import com.interdc.InterDCPlugin
import com.interdc.render.ScreenTileRenderer
import com.interdc.screen.InterDCScreen
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.bukkit.scheduler.BukkitTask

class PrometheusMetricsService(private val plugin: InterDCPlugin) {

    private var registry: PrometheusMeterRegistry? = null
    private var server: HttpServer? = null
    private var screensByGuildGauge: MultiGauge? = null
    private var gaugeRefreshTask: BukkitTask? = null
    private var discordMessageCounter: Counter? = null
    private var discordChannelUpdateCounter: Counter? = null
    private val guildMessageCounters = ConcurrentHashMap<String, Counter>()
    private val guildChannelUpdateCounters = ConcurrentHashMap<String, Counter>()
    private var maxGuildLabelCardinality: Int = 50
    private val otherGuildLabel = "__other__"

    fun start(screenSnapshotProvider: () -> Collection<InterDCScreen>) {
        val enabled = plugin.config.getBoolean("metrics.enabled", false)
        if (!enabled) {
            return
        }

        maxGuildLabelCardinality = plugin.config.getInt("metrics.guild-labels-limit", 50).coerceIn(1, 500)

        val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registry = meterRegistry

        discordMessageCounter = Counter.builder("interdc_discord_messages_total")
            .description("Total discord message events processed by InterDC")
            .register(meterRegistry)

        discordChannelUpdateCounter = Counter.builder("interdc_discord_channel_updates_total")
            .description("Total discord channel update events processed by InterDC")
            .register(meterRegistry)

        screensByGuildGauge = MultiGauge.builder("interdc_screens_by_guild")
            .description("Current registered InterDC screens by guild")
            .register(meterRegistry)

        Gauge.builder("interdc_screens_total") { screenSnapshotProvider().size.toDouble() }
            .description("Current total registered InterDC screens")
            .register(meterRegistry)

        Gauge.builder("interdc_render_requests_total") { ScreenTileRenderer.metricsSnapshot().requests.toDouble() }
            .description("Total render requests")
            .register(meterRegistry)

        Gauge.builder("interdc_render_cache_hits_total") { ScreenTileRenderer.metricsSnapshot().cacheHits.toDouble() }
            .description("Total render cache hits")
            .register(meterRegistry)

        Gauge.builder("interdc_render_cache_misses_total") { ScreenTileRenderer.metricsSnapshot().cacheMisses.toDouble() }
            .description("Total render cache misses")
            .register(meterRegistry)

        Gauge.builder("interdc_render_avg_ms") { ScreenTileRenderer.metricsSnapshot().avgRenderMs }
            .description("Average render duration in milliseconds")
            .register(meterRegistry)

        Gauge.builder("interdc_render_hit_rate_percent") {
            val snap = ScreenTileRenderer.metricsSnapshot()
            val total = snap.cacheHits + snap.cacheMisses
            if (total <= 0L) 0.0 else (snap.cacheHits.toDouble() * 100.0) / total.toDouble()
        }
            .description("Render cache hit rate in percent")
            .register(meterRegistry)

        refreshScreenByGuildGauge(screenSnapshotProvider)
        gaugeRefreshTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { refreshScreenByGuildGauge(screenSnapshotProvider) },
            200L,
            200L
        )

        val httpEnabled = plugin.config.getBoolean("metrics.prometheus.enabled", false)
        if (!httpEnabled) {
            plugin.logger.info("[InterDC] Metrics initialized (in-memory only, prometheus endpoint disabled).")
            return
        }

        val host = plugin.config.getString("metrics.prometheus.host", "0.0.0.0").orEmpty().ifBlank { "0.0.0.0" }
        val port = plugin.config.getInt("metrics.prometheus.port", 9464).coerceIn(1, 65535)
        val path = normalizePath(plugin.config.getString("metrics.prometheus.path", "/metrics").orEmpty())

        val httpServer = HttpServer.create(InetSocketAddress(host, port), 0)
        httpServer.createContext(path) { exchange ->
            val body = meterRegistry.scrape().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
        httpServer.executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "InterDC-Metrics").apply { isDaemon = true }
        }
        httpServer.start()
        server = httpServer

        plugin.logger.info("[InterDC] Prometheus metrics endpoint running on http://$host:$port$path")
    }

    fun incrementDiscordMessageEvent(guildId: String? = null) {
        discordMessageCounter?.increment()
        val labeled = guildLabel(guildId)
        val counter = getGuildCounter(
            target = guildMessageCounters,
            metricName = "interdc_discord_messages_by_guild_total",
            description = "Discord message events by guild",
            guildLabel = labeled
        )
        counter?.increment()
    }

    fun incrementDiscordChannelUpdateEvent(guildId: String? = null) {
        discordChannelUpdateCounter?.increment()
        val labeled = guildLabel(guildId)
        val counter = getGuildCounter(
            target = guildChannelUpdateCounters,
            metricName = "interdc_discord_channel_updates_by_guild_total",
            description = "Discord channel update events by guild",
            guildLabel = labeled
        )
        counter?.increment()
    }

    fun stop() {
        gaugeRefreshTask?.cancel()
        gaugeRefreshTask = null
        server?.stop(0)
        server = null
        guildMessageCounters.clear()
        guildChannelUpdateCounters.clear()
        registry?.close()
        registry = null
    }

    private fun refreshScreenByGuildGauge(screenSnapshotProvider: () -> Collection<InterDCScreen>) {
        val gauge = screensByGuildGauge ?: return
        val grouped = screenSnapshotProvider()
            .groupingBy { it.guildId ?: "unlinked" }
            .eachCount()

        val rows = grouped.map { (guild, count) ->
            MultiGauge.Row.of(Tags.of("guild", guildLabel(guild)), count.toDouble())
        }
        gauge.register(rows, true)
    }

    private fun getGuildCounter(
        target: ConcurrentHashMap<String, Counter>,
        metricName: String,
        description: String,
        guildLabel: String
    ): Counter? {
        val currentRegistry = registry ?: return null
        val bounded = guildLabel(guildLabel)
        return target.computeIfAbsent(bounded) {
            Counter.builder(metricName)
                .description(description)
                .tag("guild", bounded)
                .register(currentRegistry)
        }
    }

    private fun guildLabel(guildId: String?): String {
        val normalized = guildId?.trim().orEmpty()
        if (normalized.isBlank()) {
            return "unknown"
        }
        if (guildMessageCounters.containsKey(normalized) || guildChannelUpdateCounters.containsKey(normalized)) {
            return normalized
        }
        val distinct = (guildMessageCounters.keys + guildChannelUpdateCounters.keys).toSet().size
        return if (distinct < maxGuildLabelCardinality) normalized else otherGuildLabel
    }

    private fun normalizePath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return "/metrics"
        }
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }
}
