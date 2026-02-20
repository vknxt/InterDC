package com.interdc.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.interdc.InterDCPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class ModrinthAutoUpdateService(
    private val plugin: InterDCPlugin,
    private val scope: CoroutineScope
) {

    private val pluginVersion: String
        @Suppress("DEPRECATION")
        get() = runCatching { plugin.pluginMeta.version }.getOrNull()
            ?: runCatching { plugin.description.version }.getOrDefault("unknown")

    private data class RemoteVersion(
        val id: String,
        val versionNumber: String,
        val versionType: String,
        val publishedAt: Instant,
        val fileUrl: String,
        val fileName: String
    )

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun checkAndUpdateAsync() {
        if (!plugin.config.getBoolean("auto-update.enabled", true)) {
            return
        }

        scope.launch(Dispatchers.IO) {
            runCatching { checkAndUpdate() }
                .onFailure { ex ->
                    plugin.logger.warning("[InterDC] Auto-update failed: ${ex.message}")
                }
        }
    }

    private fun checkAndUpdate() {
        val projectId = plugin.config.getString("auto-update.project-id", "interdc")
            ?.trim()
            .orEmpty()
            .ifBlank { "interdc" }

        val releaseChannel = plugin.config.getString("auto-update.release-channel", "release")
            ?.trim()
            ?.lowercase()
            .orEmpty()

        val loaders = plugin.config.getStringList("auto-update.loaders")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("paper") }

        val preferredGameVersion = plugin.config.getString("auto-update.game-version")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: resolveServerGameVersion()

        val currentVersion = pluginVersion.trim().removePrefix("v")
        val latest = fetchLatestVersion(projectId, loaders, preferredGameVersion, releaseChannel)
            ?: run {
                plugin.logger.info("[InterDC] Auto-update: no Modrinth release found for current filters.")
                return
            }

        if (!isRemoteVersionNewer(latest.versionNumber, currentVersion)) {
            return
        }

        plugin.logger.info(
            "[InterDC] New version found on Modrinth: ${latest.versionNumber} (current: $currentVersion)."
        )

        if (!plugin.config.getBoolean("auto-update.download", true)) {
            plugin.logger.info("[InterDC] Auto-update: automatic download disabled by configuration.")
            return
        }

        val updateFolder = File(plugin.dataFolder.parentFile, "update")
        updateFolder.mkdirs()

        val fileName = latest.fileName.ifBlank { "InterDC-${latest.versionNumber}.jar" }
        val targetFile = File(updateFolder, fileName)
        if (targetFile.exists()) {
            plugin.logger.info("[InterDC] Auto-update: file already downloaded at ${targetFile.absolutePath}")
            return
        }

        val tempFile = File(updateFolder, "$fileName.part")
        downloadFile(latest.fileUrl, tempFile)
        Files.move(
            tempFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )

        plugin.logger.info(
            "[InterDC] Update downloaded to ${targetFile.absolutePath}. Restart the server to apply it."
        )
    }

    private fun fetchLatestVersion(
        projectId: String,
        loaders: List<String>,
        gameVersion: String,
        releaseChannel: String
    ): RemoteVersion? {
        val attempts = listOf(
            buildVersionApiUrl(projectId, loaders, gameVersion),
            buildVersionApiUrl(projectId, loaders, null),
            buildVersionApiUrl(projectId, null, null)
        )

        attempts.forEach { url ->
            val versions = fetchVersions(url)
            if (versions.isEmpty()) {
                return@forEach
            }

            val filtered = if (releaseChannel.isBlank()) {
                versions
            } else {
                versions.filter { it.versionType.equals(releaseChannel, ignoreCase = true) }
            }

            val chosen = (if (filtered.isNotEmpty()) filtered else versions)
                .maxByOrNull { it.publishedAt }
            if (chosen != null) {
                return chosen
            }
        }

        return null
    }

    private fun fetchVersions(url: String): List<RemoteVersion> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "InterDC/$pluginVersion (auto-updater)")
            .GET()
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return emptyList()
        }

        val root = runCatching {
            JsonParser.parseString(response.body()).asJsonArray
        }.getOrNull() ?: return emptyList()

        return root.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val versionId = obj.getStringOrNull("id") ?: return@mapNotNull null
            val versionNumber = obj.getStringOrNull("version_number") ?: return@mapNotNull null
            val versionType = obj.getStringOrNull("version_type") ?: "release"
            val publishedAt = obj.getStringOrNull("date_published")
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.EPOCH

            val files = obj.getAsJsonArray("files") ?: JsonArray()
            val selectedFile = files
                .mapNotNull { it as? JsonObject }
                .firstOrNull { file ->
                    file.getBooleanOrNull("primary") == true &&
                        (file.getStringOrNull("filename")?.endsWith(".jar", ignoreCase = true) == true)
                }
                ?: files
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { file ->
                        file.getStringOrNull("filename")?.endsWith(".jar", ignoreCase = true) == true
                    }
                ?: return@mapNotNull null

            val fileUrl = selectedFile.getStringOrNull("url") ?: return@mapNotNull null
            val fileName = selectedFile.getStringOrNull("filename") ?: "InterDC-$versionNumber.jar"

            RemoteVersion(
                id = versionId,
                versionNumber = versionNumber,
                versionType = versionType,
                publishedAt = publishedAt,
                fileUrl = fileUrl,
                fileName = fileName
            )
        }
    }

    private fun downloadFile(url: String, targetFile: File) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .header("User-Agent", "InterDC/$pluginVersion (auto-updater)")
            .GET()
            .build()

        val targetPath: Path = targetFile.toPath()
        val response = http.send(request, HttpResponse.BodyHandlers.ofFile(targetPath))
        if (response.statusCode() !in 200..299) {
            Files.deleteIfExists(targetPath)
            error("Failed to download update (HTTP ${response.statusCode()})")
        }
    }

    private fun buildVersionApiUrl(projectId: String, loaders: List<String>?, gameVersion: String?): String {
        val params = mutableListOf<String>()
        if (!loaders.isNullOrEmpty()) {
            params += "loaders=${encodeJsonArray(loaders)}"
        }
        if (!gameVersion.isNullOrBlank()) {
            params += "game_versions=${encodeJsonArray(listOf(gameVersion))}"
        }
        val query = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        return "https://api.modrinth.com/v2/project/$projectId/version$query"
    }

    private fun encodeJsonArray(values: List<String>): String {
        val json = values.joinToString(prefix = "[", postfix = "]") { value ->
            "\"${value.replace("\"", "\\\"")}\""
        }
        return URLEncoder.encode(json, StandardCharsets.UTF_8)
    }

    private fun resolveServerGameVersion(): String {
        val raw = plugin.server.bukkitVersion
        val match = Regex("(\\d+\\.\\d+(?:\\.\\d+)?)").find(raw)
        return match?.groupValues?.get(1) ?: "1.21"
    }

    private fun isRemoteVersionNewer(remoteRaw: String, currentRaw: String): Boolean {
        val remote = remoteRaw.trim().removePrefix("v")
        val current = currentRaw.trim().removePrefix("v")

        if (remote.equals(current, ignoreCase = true)) {
            return false
        }

        val remoteNums = Regex("\\d+").findAll(remote).map { it.value.toInt() }.toList()
        val currentNums = Regex("\\d+").findAll(current).map { it.value.toInt() }.toList()

        val len = max(remoteNums.size, currentNums.size)
        for (i in 0 until len) {
            val left = remoteNums.getOrElse(i) { 0 }
            val right = currentNums.getOrElse(i) { 0 }
            if (left != right) {
                return left > right
            }
        }

        return false
    }

    private fun JsonObject.getStringOrNull(name: String): String? {
        val value = this.get(name) ?: return null
        if (value.isJsonNull) {
            return null
        }
        return runCatching { value.asString }.getOrNull()
    }

    private fun JsonObject.getBooleanOrNull(name: String): Boolean? {
        val value = this.get(name) ?: return null
        if (value.isJsonNull) {
            return null
        }
        return runCatching { value.asBoolean }.getOrNull()
    }
}
