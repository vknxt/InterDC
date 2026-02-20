package com.interdc.render

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.interdc.screen.ScreenRepository
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.image.BufferedImage
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class ScreenTileRenderer(
    private val screenId: String,
    private val tileX: Int,
    private val tileY: Int,
    private val repository: ScreenRepository,
    private val canvasService: DiscordCanvasService,
    private val renderVersionProvider: (String) -> Long,
    private val localeResolver: (Player) -> String
) : MapRenderer(false) {

    companion object {
        data class MetricsSnapshot(
            val requests: Long,
            val cacheHits: Long,
            val cacheMisses: Long,
            val renderCount: Long,
            val avgRenderMs: Double
        )

        private data class SharedRender(
            val version: Long,
            val image: BufferedImage,
            val estimatedBytes: Long
        )

        private val sharedCache: Cache<String, SharedRender> = Caffeine.newBuilder()
            .maximumWeight(MAX_CACHE_BYTES)
            .weigher<String, SharedRender> { _, value ->
                value.estimatedBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            .expireAfterAccess(Duration.ofMinutes(15))
            .build()
        private val cacheLock = Any()
        private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        private val requests = AtomicLong(0)
        private val cacheHits = AtomicLong(0)
        private val cacheMisses = AtomicLong(0)
        private val renderCount = AtomicLong(0)
        private val renderNanosTotal = AtomicLong(0)

        fun invalidateScreenCache(screenId: String) {
            synchronized(cacheLock) {
                val prefix = "$screenId:"
                sharedCache.asMap().keys
                    .filter { it.startsWith(prefix) }
                    .forEach { key -> sharedCache.invalidate(key) }
            }
        }

        fun metricsSnapshot(): MetricsSnapshot {
            val renders = renderCount.get()
            val totalNanos = renderNanosTotal.get()
            val avgMs = if (renders <= 0L) 0.0 else (totalNanos.toDouble() / renders.toDouble()) / 1_000_000.0
            return MetricsSnapshot(
                requests = requests.get(),
                cacheHits = cacheHits.get(),
                cacheMisses = cacheMisses.get(),
                renderCount = renders,
                avgRenderMs = avgMs
            )
        }
    }

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        requests.incrementAndGet()
        val screen = repository.get(screenId) ?: return
        val locale = localeResolver(player)
        val key = "${screen.id}:$locale"
        val currentVersion = renderVersionProvider(screen.id)

        val fullImage = synchronized(cacheLock) {
            val cached = sharedCache.getIfPresent(key)
            if (cached != null && cached.version == currentVersion) {
                cacheHits.incrementAndGet()
                cached.image
            } else {
                cacheMisses.incrementAndGet()
                val startNs = System.nanoTime()
                val rendered = canvasService.render(screen, locale)
                renderNanosTotal.addAndGet(System.nanoTime() - startNs)
                renderCount.incrementAndGet()
                sharedCache.put(key, SharedRender(
                    version = currentVersion,
                    image = rendered,
                    estimatedBytes = estimateImageBytes(rendered)
                ))
                rendered
            }
        }

        val startX = tileX * 128
        val startY = tileY * 128
        if (startX + 128 > fullImage.width || startY + 128 > fullImage.height) {
            return
        }

        val sub = fullImage.getSubimage(startX, startY, 128, 128)
        canvas.drawImage(0, 0, sub)
    }

    private fun estimateImageBytes(image: BufferedImage): Long {
        return image.width.toLong() * image.height.toLong() * 4L
    }
}
