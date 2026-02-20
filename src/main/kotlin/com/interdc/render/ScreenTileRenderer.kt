package com.interdc.render

import com.interdc.screen.ScreenRepository
import org.bukkit.entity.Player
import org.bukkit.map.MapCanvas
import org.bukkit.map.MapRenderer
import org.bukkit.map.MapView
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap

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
        private data class SharedRender(
            val version: Long,
            val image: BufferedImage,
            var lastAccessEpochMs: Long,
            val estimatedBytes: Long
        )

        private val sharedCache = ConcurrentHashMap<String, SharedRender>()
        private val cacheLock = Any()
        private const val MAX_CACHE_ENTRIES = 24
        private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        private const val MAX_IDLE_MS = 15L * 60L * 1000L

        fun invalidateScreenCache(screenId: String) {
            synchronized(cacheLock) {
                val prefix = "$screenId:"
                sharedCache.keys
                    .filter { it.startsWith(prefix) }
                    .forEach { key -> sharedCache.remove(key) }
            }
        }
    }

    override fun render(view: MapView, canvas: MapCanvas, player: Player) {
        val screen = repository.get(screenId) ?: return
        val locale = localeResolver(player)
        val key = "${screen.id}:$locale"
        val currentVersion = renderVersionProvider(screen.id)

        val fullImage = synchronized(cacheLock) {
            val now = System.currentTimeMillis()
            val cached = sharedCache[key]
            if (cached != null && cached.version == currentVersion) {
                cached.lastAccessEpochMs = now
                cached.image
            } else {
                val rendered = canvasService.render(screen, locale)
                sharedCache[key] = SharedRender(
                    version = currentVersion,
                    image = rendered,
                    lastAccessEpochMs = now,
                    estimatedBytes = estimateImageBytes(rendered)
                )
                trimCacheIfNeeded(now)
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

    private fun trimCacheIfNeeded(now: Long) {
        sharedCache.entries
            .filter { now - it.value.lastAccessEpochMs > MAX_IDLE_MS }
            .forEach { entry -> sharedCache.remove(entry.key) }

        while (sharedCache.size > MAX_CACHE_ENTRIES || currentCacheBytes() > MAX_CACHE_BYTES) {
            val oldest = sharedCache.entries.minByOrNull { it.value.lastAccessEpochMs } ?: break
            sharedCache.remove(oldest.key)
        }
    }

    private fun estimateImageBytes(image: BufferedImage): Long {
        return image.width.toLong() * image.height.toLong() * 4L
    }

    private fun currentCacheBytes(): Long {
        return sharedCache.values.sumOf { it.estimatedBytes }
    }
}
