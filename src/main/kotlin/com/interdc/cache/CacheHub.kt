package com.interdc.cache

import com.interdc.discord.DiscordChannel
import com.interdc.discord.DiscordMessage
import com.interdc.storage.SQLiteStorage
import java.awt.image.BufferedImage
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class CacheHub(storage: SQLiteStorage? = null) {
    val messageCache = MessageCache(storage)
    val channelCache = ChannelCache()
    val avatarCache = AvatarCache()
}

class MessageCache(private val storage: SQLiteStorage? = null) {
    private val store = ConcurrentHashMap<String, ArrayDeque<DiscordMessage>>()

    fun add(channelId: String, message: DiscordMessage, maxSize: Int = 50) {
        val deque = store.computeIfAbsent(channelId) { ArrayDeque() }
        synchronized(deque) {
            deque.addFirst(message)
            while (deque.size > maxSize) {
                deque.removeLast()
            }
        }
        runCatching {
            storage?.addMessage(channelId, message)
        }
    }

    fun latest(channelId: String, limit: Int = 10): List<DiscordMessage> {
        val deque = store[channelId]
        if (deque != null) {
            synchronized(deque) {
                val inMemory = deque.take(limit)
                if (inMemory.isNotEmpty()) {
                    return inMemory
                }
            }
        }

        val fromDb = runCatching {
            storage?.latestMessages(channelId, limit).orEmpty()
        }.getOrDefault(emptyList())

        if (fromDb.isNotEmpty()) {
            val target = store.computeIfAbsent(channelId) { ArrayDeque() }
            synchronized(target) {
                fromDb.asReversed().forEach { target.addFirst(it) }
                while (target.size > 50) {
                    target.removeLast()
                }
                return target.take(limit)
            }
        }

        return emptyList()
    }
}

class ChannelCache {
    private val store = ConcurrentHashMap<String, List<DiscordChannel>>()

    fun put(guildId: String, channels: List<DiscordChannel>) {
        store[guildId] = channels
    }

    fun get(guildId: String): List<DiscordChannel> {
        return store[guildId].orEmpty()
    }
}

class AvatarCache {
    private val store = ConcurrentHashMap<String, BufferedImage>()

    fun put(userId: String, avatar: BufferedImage) {
        store[userId] = avatar
    }

    fun get(userId: String): BufferedImage? {
        return store[userId]
    }
}
