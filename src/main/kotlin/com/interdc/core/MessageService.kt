package com.interdc.core

import com.interdc.InterDCPlugin
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessageService(private val plugin: InterDCPlugin) {

    private val legacyFile = File(plugin.dataFolder, "messages.yml")
    private val langDirectory = File(plugin.dataFolder, "lang")
    private val playerLocaleFile = File(langDirectory, "player_locale_overrides.yml")
    private val localeFiles = mapOf(
        "en_us" to File(langDirectory, "messages_en_us.yml"),
        "pt_br" to File(langDirectory, "messages_pt_br.yml"),
        "es_es" to File(langDirectory, "messages_es_es.yml"),
        "fr_fr" to File(langDirectory, "messages_fr_fr.yml"),
        "de_de" to File(langDirectory, "messages_de_de.yml"),
        "it_it" to File(langDirectory, "messages_it_it.yml"),
        "ja_jp" to File(langDirectory, "messages_ja_jp.yml")
    )
    private val localeConfigs = ConcurrentHashMap<String, YamlConfiguration>()
    private val playerLocaleOverrides = ConcurrentHashMap<UUID, String>()

    init {
        createDefaultFilesIfNeeded()
        reload()
    }

    fun reload() {
        migrateLegacyIfNeeded()
        localeFiles.forEach { (locale, file) ->
            val config = YamlConfiguration.loadConfiguration(file)
            ensureDefaults(locale, config, file)
            localeConfigs[locale] = config
        }
        loadPlayerLocaleOverrides()
    }

    fun supportedLocales(): Set<String> {
        return localeFiles.keys
    }

    fun localeOf(sender: CommandSender): String {
        return resolveLocale(sender)
    }

    fun resolveSupportedLocale(raw: String): String? {
        val normalized = raw.lowercase(Locale.ROOT).replace('-', '_')
        if (localeFiles.containsKey(normalized)) {
            return normalized
        }

        return when {
            normalized.startsWith("en") -> "en_us"
            normalized.startsWith("pt") -> "pt_br"
            normalized.startsWith("es") -> "es_es"
            normalized.startsWith("fr") -> "fr_fr"
            normalized.startsWith("de") -> "de_de"
            normalized.startsWith("it") -> "it_it"
            normalized.startsWith("ja") -> "ja_jp"
            else -> null
        }
    }

    fun setPlayerLocale(playerId: UUID, locale: String?) {
        if (locale.isNullOrBlank()) {
            playerLocaleOverrides.remove(playerId)
            savePlayerLocaleOverrides()
            return
        }

        val resolved = resolveSupportedLocale(locale) ?: return
        playerLocaleOverrides[playerId] = resolved
        savePlayerLocaleOverrides()
    }

    fun getPlayerLocaleOverride(playerId: UUID): String? {
        return playerLocaleOverrides[playerId]
    }

    fun send(sender: CommandSender, key: String, replacements: Map<String, String> = emptyMap()) {
        val locale = resolveLocale(sender)
        val config = localeConfigs[locale] ?: localeConfigs["en_us"] ?: YamlConfiguration()
        val fallback = localeConfigs["en_us"]

        val rawPrefix = config.getString("prefix")
            ?: fallback?.getString("prefix")
            ?: defaultPrefix(locale)
        val rawMessage = config.getString("messages.$key")
            ?: fallback?.getString("messages.$key")
            ?: "&cMessage not found: $key"

        val formatted = applyReplacements(rawPrefix + rawMessage, replacements)
        sender.sendMessage(colorize(formatted))
    }

    fun sendConsole(key: String, replacements: Map<String, String> = emptyMap()) {
        send(plugin.server.consoleSender, key, replacements)
    }

    fun colorize(message: String): String {
        return ChatColor.translateAlternateColorCodes('&', message)
    }

    private fun applyReplacements(base: String, replacements: Map<String, String>): String {
        var result = base
        replacements.forEach { (k, v) ->
            result = result.replace("{$k}", v)
        }
        return result
    }

    private fun createDefaultFilesIfNeeded() {
        plugin.dataFolder.mkdirs()
        langDirectory.mkdirs()
        localeFiles.forEach { (locale, file) ->
            if (file.exists()) {
                return@forEach
            }
            val defaults = YamlConfiguration()
            defaults.set("prefix", defaultPrefix(locale))
            defaultMessages(locale).forEach { (key, value) ->
                defaults.set("messages.$key", value)
            }
            defaults.save(file)
        }
    }

    private fun ensureDefaults(locale: String, config: YamlConfiguration, file: File) {
        var changed = false
        fun putIfMissing(path: String, value: String) {
            if (!config.contains(path)) {
                config.set(path, value)
                changed = true
            }
        }

        val defaults = defaultMessages(locale)
        val legacy = legacyMessages(locale)
        val newPrefix = defaultPrefix(locale)
        val oldPrefix = if (locale == "pt_br") "&9[InterDC] &7" else null

        putIfMissing("prefix", newPrefix)
        if (!oldPrefix.isNullOrBlank() && config.getString("prefix") == oldPrefix) {
            config.set("prefix", newPrefix)
            changed = true
        }

        defaults.forEach { (key, value) ->
            val path = "messages.$key"
            putIfMissing(path, value)

            val current = config.getString(path)
            val old = legacy[key]
            if (old != null && current == old) {
                config.set(path, value)
                changed = true
            }

            if (key == "compose-start" && current != null && current in deprecatedComposeStartVariants(locale)) {
                config.set(path, value)
                changed = true
            }
        }

        if (changed) {
            config.save(file)
        }
    }

    private fun deprecatedComposeStartVariants(locale: String): Set<String> {
        return when (locale) {
            "en_us" -> setOf("&fType your message in chat to send it to Discord &7(&ccancel&7 to exit&7).")
            "es_es" -> setOf("&fEscribe tu mensaje en el chat para enviarlo a Discord &7(&ccancelar&7 para salir&7).")
            "fr_fr" -> setOf("&fTapez votre message dans le chat pour l'envoyer à Discord &7(&cannuler&7 pour quitter&7).")
            "de_de" -> setOf("&fSchreibe deine Nachricht im Chat, um sie an Discord zu senden &7(&cabbrechen&7 zum Beenden&7).")
            "it_it" -> setOf("&fScrivi il tuo messaggio in chat per inviarlo a Discord &7(&cannulla&7 per uscire&7).")
            "ja_jp" -> setOf("&fDiscordに送信するメッセージをチャットに入力してください &7(&ccancel&7 で終了&7)。")
            else -> setOf(
                "&fDigite sua mensagem no chat para enviar ao Discord &7(&ccancelar&7 para sair&7).",
                "&fDigite sua mensagem no chat para enviar ao Discord (&ccancelar&f para cancelar)."
            )
        }
    }

    private fun resolveLocale(sender: CommandSender): String {
        val player = sender as? Player ?: return "en_us"

        val override = playerLocaleOverrides[player.uniqueId]
        if (override != null && localeFiles.containsKey(override)) {
            return override
        }

        val configuredDefault = plugin.config.getString("messages.default-locale", "en_us")
            ?.trim()
            .orEmpty()
        val defaultLocale = resolveSupportedLocale(configuredDefault) ?: "en_us"

        val useClientLocale = plugin.config.getBoolean("messages.use-client-locale", true)
        if (!useClientLocale) {
            return defaultLocale
        }

        val rawLocale = resolvePlayerLocale(player)
        return resolveSupportedLocale(rawLocale) ?: defaultLocale
    }

    private fun resolvePlayerLocale(player: Player): String {
        runCatching {
            val localeMethod = player.javaClass.getMethod("locale")
            val result = localeMethod.invoke(player)
            when (result) {
                is Locale -> return result.toLanguageTag()
                is String -> return result
            }
        }

        runCatching {
            val localeMethod = player.javaClass.getMethod("getLocale")
            val result = localeMethod.invoke(player)
            if (result is String) {
                return result
            }
        }

        return "en_us"
    }

    private fun migrateLegacyIfNeeded() {
        langDirectory.mkdirs()

        localeFiles.forEach { (locale, targetFile) ->
            val oldFile = File(plugin.dataFolder, "messages_${locale}.yml")
            if (!oldFile.exists() || targetFile.exists()) {
                return@forEach
            }

            runCatching {
                val oldConfig = YamlConfiguration.loadConfiguration(oldFile)
                oldConfig.save(targetFile)
                oldFile.delete()
            }
        }

        if (!legacyFile.exists()) {
            return
        }

        val targetFile = localeFiles["pt_br"] ?: return
        if (targetFile.exists()) {
            return
        }

        val legacyConfig = YamlConfiguration.loadConfiguration(legacyFile)
        legacyConfig.save(targetFile)
    }

    private fun loadPlayerLocaleOverrides() {
        playerLocaleOverrides.clear()
        if (!playerLocaleFile.exists()) {
            return
        }

        val config = YamlConfiguration.loadConfiguration(playerLocaleFile)
        val section = config.getConfigurationSection("players") ?: return
        section.getKeys(false).forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            val locale = resolveSupportedLocale(section.getString(key).orEmpty()) ?: return@forEach
            playerLocaleOverrides[uuid] = locale
        }
    }

    private fun savePlayerLocaleOverrides() {
        langDirectory.mkdirs()
        val config = YamlConfiguration()
        playerLocaleOverrides.forEach { (uuid, locale) ->
            config.set("players.$uuid", locale)
        }
        config.save(playerLocaleFile)
    }

    private fun defaultPrefix(locale: String): String {
        return when (locale) {
            "en_us" -> "&8&l[&9&lInterDC&8&l] &7"
            else -> "&8&l[&9&lInterDC&8&l] &7"
        }
    }

    private fun defaultMessages(locale: String): Map<String, String> {
        return when (locale) {
            "en_us" -> linkedMapOf(
                "only-player" to "&c✖ Only players can use this command.",
                "no-permission" to "&c✖ You don't have permission to use this command.",
                "usage" to "&fUse &b/interdc&f or &b/dc &f<create|link|link2|lockchannel|webhook|remove|reload|move|lang>\n&7Examples:\n&8- &f/dc create 3 3\n&8- &f/dc link <channelId>\n&8- &f/dc lockchannel on\n&8- &f/dc lang en_us",
                "lang-usage" to "&fUse &b/dc lang <locale|auto>&f. Available: &b{available}",
                "lang-set" to "&a✔ Language set to &f{locale}&a.",
                "lang-auto" to "&a✔ Language set to automatic (client locale).",
                "lang-invalid" to "&c✖ Invalid locale &f{locale}&c. Available: &f{available}",
                "lang-current" to "&b➜ Current language: &f{locale}&7 (override: &f{override}&7)",
                "created" to "&a✔ Screen created successfully! &7ID: &f{id}",
                "invalid-size" to "&c✖ Invalid size. Use values between &f1&c and &f8&c. Example: &f/dc create 3 3",
                "linked" to "&a✔ Screen linked to channel &f{channel}&a.",
                "link-invalid-channel" to "&c✖ Invalid channel for linking. Choose a text channel where the bot can send messages.",
                "removed" to "&a✔ Screen removed successfully.",
                "moved" to "&a✔ Screen moved to your current location.",
                "reloaded" to "&a✔ Settings and sync reloaded.",
                "not-looking-screen" to "&c✖ Look at an ItemFrame containing an InterDC screen map.",
                "startup-enabled" to "&a✔ InterDC started and sync is active.",
                "startup-disabled" to "&e• InterDC stopped safely.",
                "fly-assist-enabled" to "&b➜ Fly assist enabled near this panel.",
                "fly-assist-disabled" to "&e• Fly assist disabled (you left the panel area).",
                "channel-cycle" to "&b➜ Active channel changed to: &f{channel}",
                "discord-live" to "&a✔ Discord connected via JDA.",
                "discord-mock" to "&e• Discord running in mock mode (no token/disabled config).",
                "discord-connect-error" to "&c✖ Failed to start JDA: &f{error}",
                "compose-start" to "&fType your message in chat to send it to Discord &7(&c/cancel&7 to exit&7).",
                "compose-cancelled" to "&e• Send cancelled.",
                "compose-sent" to "&a✔ Message sent to Discord.",
                "compose-cooldown" to "&e• Wait 2 seconds before sending another message.",
                "compose-no-channel" to "&c✖ This screen has no text channel selected.",
                "compose-no-permission" to "&c✖ You don't have permission to send messages in this channel.",
                "member-list-on" to "&a✔ Member list enabled.",
                "member-list-off" to "&e• Member list hidden.",
                "channel-lock-on" to "&a✔ Channel lock enabled for this screen.",
                "channel-lock-off" to "&e• Channel lock disabled for this screen.",
                "webhook-set" to "&a✔ Webhook configured for this screen.",
                "webhook-cleared" to "&e• Webhook removed from this screen.",
                "created-fallback-size" to "&e• The wall didn't fit the default size; a &f1x1&e screen was created automatically.",
                "create-wall-invalid" to "&c✖ Look at a solid wall with free space for the screen."
            )

            "es_es" -> linkedMapOf(
                "only-player" to "&c✖ Solo los jugadores pueden usar este comando.",
                "no-permission" to "&c✖ No tienes permiso para usar este comando.",
                "usage" to "&fUsa &b/interdc&f o &b/dc &f<create|link|webhook|remove|reload|move>",
                "created" to "&a✔ Pantalla creada correctamente. &7ID: &f{id}",
                "invalid-size" to "&c✖ Tamaño inválido. Usa valores entre &f1&c y &f8&c.",
                "linked" to "&a✔ Pantalla vinculada al canal &f{channel}&a.",
                "removed" to "&a✔ Pantalla eliminada correctamente.",
                "moved" to "&a✔ Pantalla movida a tu ubicación actual.",
                "reloaded" to "&a✔ Configuración y sincronización recargadas.",
                "not-looking-screen" to "&c✖ Mira un ItemFrame con un mapa de pantalla InterDC.",
                "startup-enabled" to "&a✔ InterDC iniciado y sincronización activa.",
                "startup-disabled" to "&e• InterDC finalizado con seguridad.",
                "channel-cycle" to "&b➜ Canal activo cambiado a: &f{channel}",
                "discord-live" to "&a✔ Discord conectado por JDA.",
                "discord-mock" to "&e• Discord en modo mock (sin token/config activa).",
                "discord-connect-error" to "&c✖ Error al iniciar JDA: &f{error}",
                "compose-start" to "&fEscribe tu mensaje en el chat para enviarlo a Discord &7(&c/cancel&7 para salir&7).",
                "compose-cancelled" to "&e• Envío cancelado.",
                "compose-sent" to "&a✔ Mensaje enviado a Discord.",
                "compose-cooldown" to "&e• Espera 2 segundos antes de enviar otro mensaje.",
                "compose-no-channel" to "&c✖ Esta pantalla no tiene canal de texto seleccionado.",
                "compose-no-permission" to "&c✖ No tienes permiso para enviar mensajes en este canal.",
                "member-list-on" to "&a✔ Lista de miembros activada.",
                "member-list-off" to "&e• Lista de miembros oculta.",
                "channel-lock-on" to "&a✔ Bloqueo de canal activado para esta pantalla.",
                "channel-lock-off" to "&e• Bloqueo de canal desactivado para esta pantalla.",
                "webhook-set" to "&a✔ Webhook configurado para esta pantalla.",
                "webhook-cleared" to "&e• Webhook removido de esta pantalla.",
                "created-fallback-size" to "&e• La pared no tenía espacio para el tamaño por defecto; se creó una pantalla &f1x1&e.",
                "create-wall-invalid" to "&c✖ Mira una pared sólida con espacio libre para la pantalla."
            )

            "fr_fr" -> linkedMapOf(
                "only-player" to "&c✖ Seuls les joueurs peuvent utiliser cette commande.",
                "no-permission" to "&c✖ Vous n'avez pas la permission d'utiliser cette commande.",
                "usage" to "&fUtilisez &b/interdc&f ou &b/dc &f<create|link|webhook|remove|reload|move>",
                "created" to "&a✔ Écran créé avec succès. &7ID : &f{id}",
                "invalid-size" to "&c✖ Taille invalide. Utilisez des valeurs entre &f1&c et &f8&c.",
                "linked" to "&a✔ Écran lié au canal &f{channel}&a.",
                "removed" to "&a✔ Écran supprimé avec succès.",
                "moved" to "&a✔ Écran déplacé vers votre position actuelle.",
                "reloaded" to "&a✔ Configuration et synchronisation rechargées.",
                "not-looking-screen" to "&c✖ Regardez un ItemFrame avec une carte d'écran InterDC.",
                "startup-enabled" to "&a✔ InterDC démarré et synchronisation active.",
                "startup-disabled" to "&e• InterDC arrêté en toute sécurité.",
                "channel-cycle" to "&b➜ Canal actif changé vers : &f{channel}",
                "discord-live" to "&a✔ Discord connecté via JDA.",
                "discord-mock" to "&e• Discord en mode mock (token/config désactivé).",
                "discord-connect-error" to "&c✖ Échec du démarrage JDA : &f{error}",
                "compose-start" to "&fTapez votre message dans le chat pour l'envoyer à Discord &7(&c/cancel&7 pour quitter&7).",
                "compose-cancelled" to "&e• Envoi annulé.",
                "compose-sent" to "&a✔ Message envoyé sur Discord.",
                "compose-cooldown" to "&e• Attendez 2 secondes avant d'envoyer un autre message.",
                "compose-no-channel" to "&c✖ Cet écran n'a pas de canal texte sélectionné.",
                "compose-no-permission" to "&c✖ Vous n'avez pas la permission d'envoyer des messages dans ce canal.",
                "member-list-on" to "&a✔ Liste des membres activée.",
                "member-list-off" to "&e• Liste des membres masquée.",
                "channel-lock-on" to "&a✔ Verrouillage du canal activé pour cet écran.",
                "channel-lock-off" to "&e• Verrouillage du canal désactivé pour cet écran.",
                "webhook-set" to "&a✔ Webhook configuré pour cet écran.",
                "webhook-cleared" to "&e• Webhook supprimé de cet écran.",
                "created-fallback-size" to "&e• Le mur ne permettait pas la taille par défaut ; un écran &f1x1&e a été créé.",
                "create-wall-invalid" to "&c✖ Regardez un mur solide avec de l'espace libre pour l'écran."
            )

            "de_de" -> linkedMapOf(
                "only-player" to "&c✖ Nur Spieler können diesen Befehl benutzen.",
                "no-permission" to "&c✖ Du hast keine Berechtigung für diesen Befehl.",
                "usage" to "&fNutze &b/interdc&f oder &b/dc &f<create|link|webhook|remove|reload|move>",
                "created" to "&a✔ Bildschirm erfolgreich erstellt. &7ID: &f{id}",
                "invalid-size" to "&c✖ Ungültige Größe. Nutze Werte zwischen &f1&c und &f8&c.",
                "linked" to "&a✔ Bildschirm mit Kanal &f{channel}&a verknüpft.",
                "removed" to "&a✔ Bildschirm erfolgreich entfernt.",
                "moved" to "&a✔ Bildschirm an deine aktuelle Position verschoben.",
                "reloaded" to "&a✔ Einstellungen und Synchronisierung neu geladen.",
                "not-looking-screen" to "&c✖ Schau auf einen ItemFrame mit einer InterDC-Bildschirmkarte.",
                "startup-enabled" to "&a✔ InterDC gestartet und Synchronisierung aktiv.",
                "startup-disabled" to "&e• InterDC sicher beendet.",
                "channel-cycle" to "&b➜ Aktiver Kanal gewechselt zu: &f{channel}",
                "discord-live" to "&a✔ Discord über JDA verbunden.",
                "discord-mock" to "&e• Discord im Mock-Modus (kein Token/Config deaktiviert).",
                "discord-connect-error" to "&c✖ JDA-Start fehlgeschlagen: &f{error}",
                "compose-start" to "&fSchreibe deine Nachricht im Chat, um sie an Discord zu senden &7(&c/cancel&7 zum Beenden&7).",
                "compose-cancelled" to "&e• Senden abgebrochen.",
                "compose-sent" to "&a✔ Nachricht an Discord gesendet.",
                "compose-cooldown" to "&e• Warte 2 Sekunden, bevor du erneut sendest.",
                "compose-no-channel" to "&c✖ Dieser Bildschirm hat keinen ausgewählten Textkanal.",
                "compose-no-permission" to "&c✖ Du darfst in diesem Kanal keine Nachrichten senden.",
                "member-list-on" to "&a✔ Mitgliederliste aktiviert.",
                "member-list-off" to "&e• Mitgliederliste ausgeblendet.",
                "channel-lock-on" to "&a✔ Kanal-Sperre für diesen Bildschirm aktiviert.",
                "channel-lock-off" to "&e• Kanal-Sperre für diesen Bildschirm deaktiviert.",
                "webhook-set" to "&a✔ Webhook für diesen Bildschirm gesetzt.",
                "webhook-cleared" to "&e• Webhook von diesem Bildschirm entfernt.",
                "created-fallback-size" to "&e• Die Wand passte nicht zur Standardgröße; ein &f1x1&e Bildschirm wurde erstellt.",
                "create-wall-invalid" to "&c✖ Schau auf eine feste Wand mit freiem Platz für den Bildschirm."
            )

            "it_it" -> linkedMapOf(
                "only-player" to "&c✖ Solo i giocatori possono usare questo comando.",
                "no-permission" to "&c✖ Non hai il permesso di usare questo comando.",
                "usage" to "&fUsa &b/interdc&f o &b/dc &f<create|link|webhook|remove|reload|move>",
                "created" to "&a✔ Schermo creato con successo. &7ID: &f{id}",
                "invalid-size" to "&c✖ Dimensione non valida. Usa valori tra &f1&c e &f8&c.",
                "linked" to "&a✔ Schermo collegato al canale &f{channel}&a.",
                "removed" to "&a✔ Schermo rimosso con successo.",
                "moved" to "&a✔ Schermo spostato nella tua posizione attuale.",
                "reloaded" to "&a✔ Configurazioni e sincronizzazione ricaricate.",
                "not-looking-screen" to "&c✖ Guarda un ItemFrame con una mappa schermo InterDC.",
                "startup-enabled" to "&a✔ InterDC avviato e sincronizzazione attiva.",
                "startup-disabled" to "&e• InterDC chiuso in sicurezza.",
                "channel-cycle" to "&b➜ Canale attivo cambiato in: &f{channel}",
                "discord-live" to "&a✔ Discord connesso via JDA.",
                "discord-mock" to "&e• Discord in modalità mock (senza token/config disattivata).",
                "discord-connect-error" to "&c✖ Avvio JDA non riuscito: &f{error}",
                "compose-start" to "&fScrivi il tuo messaggio in chat per inviarlo a Discord &7(&c/cancel&7 per uscire&7).",
                "compose-cancelled" to "&e• Invio annullato.",
                "compose-sent" to "&a✔ Messaggio inviato a Discord.",
                "compose-cooldown" to "&e• Attendi 2 secondi prima di inviare un altro messaggio.",
                "compose-no-channel" to "&c✖ Questo schermo non ha un canale testuale selezionato.",
                "compose-no-permission" to "&c✖ Non hai il permesso di inviare messaggi in questo canale.",
                "member-list-on" to "&a✔ Lista membri attivata.",
                "member-list-off" to "&e• Lista membri nascosta.",
                "channel-lock-on" to "&a✔ Blocco canale attivato per questo schermo.",
                "channel-lock-off" to "&e• Blocco canale disattivato per questo schermo.",
                "webhook-set" to "&a✔ Webhook configurato per questo schermo.",
                "webhook-cleared" to "&e• Webhook rimosso da questo schermo.",
                "created-fallback-size" to "&e• Il muro non supportava la dimensione predefinita; creato schermo &f1x1&e.",
                "create-wall-invalid" to "&c✖ Guarda un muro solido con spazio libero per lo schermo."
            )

            "ja_jp" -> linkedMapOf(
                "only-player" to "&c✖ このコマンドはプレイヤーのみ使用できます。",
                "no-permission" to "&c✖ このコマンドを使う権限がありません。",
                "usage" to "&f&b/interdc&f または &b/dc &f<create|link|webhook|remove|reload|move> を使用してください。",
                "created" to "&a✔ スクリーンを作成しました。 &7ID: &f{id}",
                "invalid-size" to "&c✖ サイズが無効です。&f1&c〜&f8&c の範囲で指定してください。",
                "linked" to "&a✔ スクリーンをチャンネル &f{channel}&a にリンクしました。",
                "removed" to "&a✔ スクリーンを削除しました。",
                "moved" to "&a✔ スクリーンを現在位置へ移動しました。",
                "reloaded" to "&a✔ 設定と同期を再読み込みしました。",
                "not-looking-screen" to "&c✖ InterDCスクリーンのマップが入ったItemFrameを見てください。",
                "startup-enabled" to "&a✔ InterDC を開始し、同期を有効化しました。",
                "startup-disabled" to "&e• InterDC を安全に停止しました。",
                "channel-cycle" to "&b➜ アクティブチャンネルを変更: &f{channel}",
                "discord-live" to "&a✔ JDAでDiscordに接続しました。",
                "discord-mock" to "&e• Discordはモックモードです（トークン未設定/無効）。",
                "discord-connect-error" to "&c✖ JDAの起動に失敗しました: &f{error}",
                "compose-start" to "&fDiscordに送信するメッセージをチャットに入力してください &7(&c/cancel&7 で終了&7)。",
                "compose-cancelled" to "&e• 送信をキャンセルしました。",
                "compose-sent" to "&a✔ Discordへメッセージを送信しました。",
                "compose-cooldown" to "&e• 次の送信まで2秒待ってください。",
                "compose-no-channel" to "&c✖ このスクリーンにはテキストチャンネルが選択されていません。",
                "compose-no-permission" to "&c✖ このチャンネルでメッセージを送信する権限がありません。",
                "member-list-on" to "&a✔ メンバーリストを表示しました。",
                "member-list-off" to "&e• メンバーリストを非表示にしました。",
                "channel-lock-on" to "&a✔ このスクリーンのチャンネル固定を有効にしました。",
                "channel-lock-off" to "&e• このスクリーンのチャンネル固定を解除しました。",
                "webhook-set" to "&a✔ このスクリーンにWebhookを設定しました。",
                "webhook-cleared" to "&e• このスクリーンのWebhookを削除しました。",
                "created-fallback-size" to "&e• 壁の都合で標準サイズを使えなかったため、&f1x1&e で自動作成しました。",
                "create-wall-invalid" to "&c✖ スクリーン用の空きがある固体壁を見てください。"
            )

            else -> linkedMapOf(
                "only-player" to "&c✖ Apenas jogadores podem usar este comando.",
                "no-permission" to "&c✖ Você não tem permissão para usar este comando.",
                "usage" to "&fUse &b/interdc&f ou &b/dc &f<create|link|link2|lockchannel|webhook|remove|reload|move|lang>\n&7Exemplos:\n&8- &f/dc create 3 3\n&8- &f/dc link <channelId>\n&8- &f/dc lockchannel on\n&8- &f/dc lang pt_br",
                "lang-usage" to "&fUse &b/dc lang <locale|auto>&f. Disponíveis: &b{available}",
                "lang-set" to "&a✔ Idioma definido para &f{locale}&a.",
                "lang-auto" to "&a✔ Idioma definido para automático (locale do cliente).",
                "lang-invalid" to "&c✖ Locale inválido &f{locale}&c. Disponíveis: &f{available}",
                "lang-current" to "&b➜ Idioma atual: &f{locale}&7 (override: &f{override}&7)",
                "created" to "&a✔ Tela criada com sucesso! &7ID: &f{id}",
                "invalid-size" to "&c✖ Tamanho inválido. Use valores entre &f1&c e &f8&c. Ex: &f/dc create 3 3",
                "linked" to "&a✔ Tela vinculada ao canal &f{channel}&a.",
                "link-invalid-channel" to "&c✖ Canal inválido para vínculo. Escolha um canal de texto onde o bot possa enviar mensagens.",
                "removed" to "&a✔ Tela removida com sucesso.",
                "moved" to "&a✔ Tela reposicionada para sua localização atual.",
                "reloaded" to "&a✔ Configurações e sincronizações recarregadas.",
                "not-looking-screen" to "&c✖ Olhe para um ItemFrame com mapa de uma tela InterDC.",
                "startup-enabled" to "&a✔ InterDC iniciado e sincronização ativa.",
                "startup-disabled" to "&e• InterDC finalizado com segurança.",
                "fly-assist-enabled" to "&b➜ Fly assist ativado perto deste painel.",
                "fly-assist-disabled" to "&e• Fly assist desativado (você saiu da área do painel).",
                "channel-cycle" to "&b➜ Canal ativo alterado para: &f{channel}",
                "discord-live" to "&a✔ Discord conectado via JDA.",
                "discord-mock" to "&e• Discord em modo mock (sem token/config ativa).",
                "discord-connect-error" to "&c✖ Falha ao iniciar JDA: &f{error}",
                "compose-start" to "&fDigite sua mensagem no chat para enviar ao Discord &7(&c/cancel ou cancelar&7 para sair&7).",
                "compose-cancelled" to "&e• Envio cancelado.",
                "compose-sent" to "&a✔ Mensagem enviada ao Discord.",
                "compose-cooldown" to "&e• Aguarde 2 segundos para enviar outra mensagem.",
                "compose-no-channel" to "&c✖ Essa tela não possui canal de texto selecionado.",
                "compose-no-permission" to "&c✖ Você não tem permissão para enviar mensagens neste canal.",
                "member-list-on" to "&a✔ Lista de membros ativada.",
                "member-list-off" to "&e• Lista de membros ocultada.",
                "channel-lock-on" to "&a✔ Bloqueio de canal ativado para esta tela.",
                "channel-lock-off" to "&e• Bloqueio de canal desativado para esta tela.",
                "webhook-set" to "&a✔ Webhook configurado para esta tela.",
                "webhook-cleared" to "&e• Webhook removido desta tela.",
                "created-fallback-size" to "&e• A parede não comportava o tamanho padrão; tela criada automaticamente em &f1x1&e.",
                "create-wall-invalid" to "&c✖ Olhe para uma parede sólida com espaço livre para a tela."
            )
        }
    }

    private fun legacyMessages(locale: String): Map<String, String> {
        if (locale != "pt_br") {
            return emptyMap()
        }

        return mapOf(
            "only-player" to "&cApenas jogadores podem usar este comando.",
            "no-permission" to "&cVocê não tem permissão para usar este comando.",
            "usage" to "&fUso: &b/interdc <create|link|webhook|remove|reload|move>",
            "created" to "&aTela criada na parede com sucesso. ID: &f{id}&a.",
            "invalid-size" to "&cLargura e altura devem estar entre 1 e 8. Use &f/interdc create 3 3&c ou apenas &f/interdc create&c.",
            "linked" to "&aTela vinculada ao canal &f{channel}&a.",
            "removed" to "&aTela removida com sucesso.",
            "moved" to "&aTela reposicionada para sua localização atual.",
            "reloaded" to "&aSincronizações e configurações recarregadas.",
            "not-looking-screen" to "&cOlhe para um ItemFrame com mapa de uma tela InterDC.",
            "startup-enabled" to "&aSincronização iniciada.",
            "startup-disabled" to "&cSincronização encerrada.",
            "channel-cycle" to "&bCanal ativo alterado para: &f{channel}",
            "discord-live" to "&aDiscord conectado via JDA.",
            "discord-mock" to "&eDiscord em modo mock (sem token/config ativa).",
            "discord-connect-error" to "&cFalha ao iniciar JDA: &f{error}",
            "compose-start" to "&fDigite sua mensagem no chat para enviar ao Discord (&c/cancel ou cancelar&f para cancelar).",
            "compose-cancelled" to "&eEnvio cancelado.",
            "compose-sent" to "&aMensagem enviada ao Discord.",
            "compose-cooldown" to "&eAguarde 2 segundos para enviar outra mensagem.",
            "compose-no-channel" to "&cEssa tela não possui canal de texto selecionado.",
            "compose-no-permission" to "&cVocê não tem permissão para enviar mensagens neste canal.",
            "member-list-on" to "&aLista de membros ativada.",
            "member-list-off" to "&eLista de membros ocultada.",
            "channel-lock-on" to "&aBloqueio de canal ativado para esta tela.",
            "channel-lock-off" to "&eBloqueio de canal desativado para esta tela.",
            "webhook-set" to "&aWebhook configurado para esta tela.",
            "webhook-cleared" to "&eWebhook removido desta tela.",
            "created-fallback-size" to "&eA parede não comportava o tamanho padrão; tela criada automaticamente em &f1x1&e.",
            "create-wall-invalid" to "&cOlhe para uma parede sólida com espaço livre para a tela."
        )
    }
}
