package com.interdc.render

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.interdc.core.GuildConfigService
import com.interdc.discord.DiscordGateway
import com.interdc.discord.DiscordMember
import com.interdc.screen.InterDCScreen
import org.bukkit.Color
import java.awt.BasicStroke
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import java.net.URLEncoder
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.max
import kotlin.math.min

class DiscordCanvasService(
    private val guildConfigService: GuildConfigService,
    private val discordGateway: DiscordGateway
) {

    private data class VisualStylePreset(
        val backgroundBlend: Double,
        val sidebarBlend: Double,
        val guildRailBlend: Double,
        val cardBlend: Double,
        val accentBlend: Double,
        val chatGradientAlpha: Int,
        val sidebarGradientAlpha: Int,
        val topOverlayAlpha: Int,
        val guildHeaderAlpha: Int
    )

    companion object {
        private const val MAX_AVATAR_CACHE_ENTRIES = 128
        private const val AVATAR_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val FAILED_AVATAR_TTL_MS = 10 * 60 * 1000L
        private const val MAX_FAILED_CACHE_ENTRIES = 256
        private const val IMAGE_CONNECT_TIMEOUT_MS = 3_000
        private const val IMAGE_READ_TIMEOUT_MS = 5_000
        private const val IMAGE_FETCH_ATTEMPTS = 2

        private val TOKEN_REGEX = Regex("\\S+|\\s+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DIACRITICS_REGEX = Regex("\\p{M}+")
        private val MULTI_QM_REGEX = Regex("\\?{2,}")

        private val FONT_8_BOLD = Font("SansSerif", Font.BOLD, 8)
        private val FONT_8_PLAIN = Font("SansSerif", Font.PLAIN, 8)
        private val FONT_9_BOLD = Font("SansSerif", Font.BOLD, 9)
        private val FONT_9_PLAIN = Font("SansSerif", Font.PLAIN, 9)
        private val FONT_10_BOLD = Font("SansSerif", Font.BOLD, 10)
        private val FONT_10_PLAIN = Font("SansSerif", Font.PLAIN, 10)
        private val FONT_11_BOLD = Font("SansSerif", Font.BOLD, 11)
        private val FONT_13_BOLD = Font("SansSerif", Font.BOLD, 13)
    }

    private val hourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val avatarImageCache: Cache<String, BufferedImage> = Caffeine.newBuilder()
        .maximumSize(MAX_AVATAR_CACHE_ENTRIES.toLong())
        .expireAfterAccess(Duration.ofMillis(AVATAR_CACHE_TTL_MS))
        .build()
    private val failedAvatarUrls: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(MAX_FAILED_CACHE_ENTRIES.toLong())
        .expireAfterWrite(Duration.ofMillis(FAILED_AVATAR_TTL_MS))
        .build()

    fun render(screen: InterDCScreen, locale: String = "en_us"): BufferedImage {
        val widthPx = screen.width * 128
        val heightPx = screen.height * 128
        val totalTiles = screen.width * screen.height
        val performanceMode = guildConfigService.renderPerformanceMode()
        val qualityScale = resolveQualityScale(totalTiles, performanceMode)
        val workingImage = BufferedImage(
            widthPx * qualityScale,
            heightPx * qualityScale,
            BufferedImage.TYPE_INT_ARGB
        )
        val g = workingImage.createGraphics()

        if (qualityScale > 1) {
            g.scale(qualityScale.toDouble(), qualityScale.toDouble())
        }

        applyRenderHints(g, performanceMode)

        val allGuilds = discordGateway.guilds()
        val activeGuild = screen.guildId?.let { id -> allGuilds.firstOrNull { it.id == id } } ?: allGuilds.firstOrNull()
        val activeGuildId = activeGuild?.id

        val theme = guildConfigService.getTheme(activeGuildId)
        val style = stylePreset(theme.layout)
        val discordBg = Color.fromRGB(49, 51, 56)
        val discordSidebar = Color.fromRGB(43, 45, 49)
        val discordGuildRail = Color.fromRGB(30, 31, 34)
        val discordCard = Color.fromRGB(56, 58, 64)
        val discordAccent = Color.fromRGB(88, 101, 242)
        val baseBackground = mix(theme.backgroundColor, discordBg, style.backgroundBlend)
        val baseSidebar = mix(theme.sidebarColor, discordSidebar, style.sidebarBlend)
        val baseGuildRail = mix(theme.sidebarColor, discordGuildRail, style.guildRailBlend)
        val baseCard = mix(theme.messageColor, discordCard, style.cardBlend)
        val baseAccent = mix(theme.primaryColor, discordAccent, style.accentBlend)
        val textPrimary = bestTextColor(baseBackground)
        val textSecondary = bestMutedTextColor(baseBackground)
        val textSidebarPrimary = bestTextColor(baseSidebar)
        val textSidebarSecondary = bestMutedTextColor(baseSidebar)
        val textOnCard = bestTextColor(baseCard)
        val textOnCardMuted = bestMutedTextColor(baseCard)
        val guild = activeGuild
        val channels = activeGuildId?.let { discordGateway.channels(it) }.orEmpty()
        val selectedChannel = channels.firstOrNull { it.id == screen.channelId }
            ?: channels.firstOrNull { !it.isVoice && it.canView }
        val messages = selectedChannel?.id?.let { discordGateway.latestMessages(it, 14) }.orEmpty()
        val knownMembers = if (activeGuildId != null) {
            discordGateway.members(activeGuildId, 40)
        } else {
            emptyList()
        }
        val membersByName = knownMembers.associateBy { it.name.lowercase() }
        val canShowMemberPanel = widthPx >= 512
        val showMemberPanel = canShowMemberPanel && screen.showMemberList
        val members = if (showMemberPanel) {
            knownMembers.take(12)
        } else {
            emptyList()
        }

        val guildRailWidth = max(18, (widthPx * 0.07).toInt())
        val channelPanelWidth = max(80, (widthPx * 0.25).toInt())
        val memberPanelWidth = if (showMemberPanel) max(76, (widthPx * 0.2).toInt()) else 0
        val topBarHeight = 24
        val inputHeight = 24

        val chatStartX = guildRailWidth + channelPanelWidth
        val chatWidth = widthPx - chatStartX - memberPanelWidth

        val appX = 2
        val appY = 2
        val appWidth = max(1, widthPx - 4)
        val appHeight = max(1, heightPx - 4)
        val appShape = RoundRectangle2D.Float(appX.toFloat(), appY.toFloat(), appWidth.toFloat(), appHeight.toFloat(), 12f, 12f)
        val oldClip = g.clip
        g.clip = appShape

        fillRect(g, 0, 0, widthPx, heightPx, baseBackground)

        fillRect(g, 0, 0, guildRailWidth, heightPx, baseGuildRail)
        fillRect(g, guildRailWidth, 0, channelPanelWidth, heightPx, baseSidebar)
        fillRect(g, chatStartX, 0, chatWidth, topBarHeight, adjust(baseBackground, -14))
        fillRect(g, chatStartX, topBarHeight, chatWidth, heightPx - topBarHeight, baseBackground)
        fillVerticalGradient(
            g = g,
            x = chatStartX,
            y = topBarHeight,
            width = chatWidth,
            height = heightPx - topBarHeight,
            topColor = adjust(baseBackground, 6),
            bottomColor = baseBackground,
            alpha = style.chatGradientAlpha
        )
        fillVerticalGradient(
            g = g,
            x = chatStartX,
            y = topBarHeight,
            width = chatWidth,
            height = max(1, heightPx - topBarHeight),
            topColor = mix(baseAccent, Color.WHITE, 0.2),
            bottomColor = baseBackground,
            alpha = 16
        )

        if (showMemberPanel) {
            fillRect(g, widthPx - memberPanelWidth, 0, memberPanelWidth, heightPx, adjust(baseSidebar, -6))
        }

        fillVerticalGradient(
            g = g,
            x = guildRailWidth,
            y = 0,
            width = channelPanelWidth,
            height = heightPx,
            topColor = adjust(baseSidebar, 8),
            bottomColor = baseSidebar,
            alpha = style.sidebarGradientAlpha
        )

        g.color = awt(adjust(baseBackground, 9), style.topOverlayAlpha)
        g.fillRoundRect(chatStartX + 4, topBarHeight + 6, chatWidth - 8, 18, 8, 8)

        g.color = java.awt.Color(255, 255, 255, style.guildHeaderAlpha)
        g.fillRoundRect(guildRailWidth + 6, 4, channelPanelWidth - 12, 18, 8, 8)

        g.color = java.awt.Color(255, 255, 255, 22)
        g.drawLine(guildRailWidth, 0, guildRailWidth, heightPx)
        g.drawLine(chatStartX, 0, chatStartX, heightPx)
        g.color = java.awt.Color(255, 255, 255, 28)
        g.drawLine(chatStartX, topBarHeight - 1, chatStartX + chatWidth, topBarHeight - 1)
        if (showMemberPanel) {
            g.drawLine(widthPx - memberPanelWidth, 0, widthPx - memberPanelWidth, heightPx)
        }

        val guildName = guild?.name ?: tr(locale, "discord-disconnected")
        g.color = java.awt.Color(0, 0, 0, 80)
        g.font = FONT_11_BOLD
        g.drawString(truncate(guildName, 20), guildRailWidth + 8, 17)
        g.color = textSidebarPrimary
        g.font = FONT_11_BOLD
        g.drawString(truncate(guildName, 20), guildRailWidth + 8, 16)

        val guildBadgeSize = max(10, guildRailWidth - 8)
        val badgeX = (guildRailWidth - guildBadgeSize) / 2
        val badgeY = 8
        drawGuildBadge(
            g = g,
            guild = guild,
            guildName = guildName,
            x = badgeX,
            y = badgeY,
            size = guildBadgeSize,
            primaryColor = baseAccent
        )

        g.font = FONT_10_PLAIN
        var channelY = 34
        val visibleChannels = channels
            .filter { channel ->
                when {
                    channel.isVoice -> theme.showVoiceChannels
                    else -> channel.canView
                }
            }
        val uncategorized = visibleChannels.filter { it.categoryName.isNullOrBlank() }
        val categorized = visibleChannels
            .filter { !it.categoryName.isNullOrBlank() }
            .groupBy { it.categoryName!! }

        fun drawCategory(title: String, entries: List<com.interdc.discord.DiscordChannel>) {
            g.color = textSidebarSecondary
            g.font = FONT_9_BOLD
            val safeCategory = normalizeUiText(title)
            g.drawString(truncate(safeCategory.uppercase(), 16), guildRailWidth + 8, channelY)
            g.color = java.awt.Color(255, 255, 255, 28)
            g.drawLine(guildRailWidth + 8, channelY + 3, guildRailWidth + channelPanelWidth - 10, channelY + 3)
            channelY += 12

            g.font = FONT_10_PLAIN
            entries.take(9).forEach { channel ->
                if (channel.id == selectedChannel?.id) {
                    drawSoftShadow(g, guildRailWidth + 6, channelY - 10, channelPanelWidth - 12, 14, 6)
                    g.color = awt(adjust(baseCard, 6))
                    g.fillRoundRect(guildRailWidth + 6, channelY - 10, channelPanelWidth - 12, 14, 6, 6)
                    g.color = awt(baseAccent)
                    g.fillRoundRect(guildRailWidth + 6, channelY - 10, 3, 14, 3, 3)
                }
                g.color = if (channel.canTalk) textSidebarPrimary else textSidebarSecondary
                g.font = if (channel.id == selectedChannel?.id) {
                    FONT_10_BOLD
                } else {
                    FONT_10_PLAIN
                }
                val prefix = when {
                    channel.isVoice -> ">"
                    channel.canTalk -> "#"
                    else -> "-"
                }
                val safeChannelName = normalizeUiText(channel.name)
                g.drawString("$prefix ${truncate(safeChannelName, 18)}", guildRailWidth + 10, channelY)
                channelY += 14
            }
            channelY += 4
        }

        if (uncategorized.isNotEmpty()) {
            drawCategory(tr(locale, "uncategorized"), uncategorized)
        }

        categorized.forEach { (category, entries) ->
            drawCategory(category, entries)
        }

        g.color = textPrimary
        g.font = FONT_11_BOLD
        val selectedChannelLabel = normalizeUiText(selectedChannel?.name ?: tr(locale, "unlinked"))
        g.drawString("# ${truncate(selectedChannelLabel, 24)}", chatStartX + 8, 16)

        g.font = FONT_9_PLAIN
        g.color = textSecondary
        val summary = if (messages.isEmpty()) {
            tr(locale, "no-messages")
        } else {
            "${messages.size} ${tr(locale, "messages")}".trim()
        }
        g.drawString(truncateByWidth(g, summary, chatWidth / 2), chatStartX + 8, 23)

        if (canShowMemberPanel) {
            val buttonWidth = max(76, min(108, chatWidth / 3))
            val buttonHeight = 14
            val buttonX = chatStartX + chatWidth - buttonWidth - 8
            val buttonY = 5
            drawSoftShadow(g, buttonX, buttonY, buttonWidth, buttonHeight, 7)
            g.color = if (showMemberPanel) awt(adjust(baseAccent, -6), 220) else awt(adjust(baseCard, 10), 210)
            g.fillRoundRect(buttonX, buttonY, buttonWidth, buttonHeight, 7, 7)
            g.color = if (showMemberPanel) bestTextColor(baseAccent) else textPrimary
            g.font = FONT_9_BOLD
            val buttonText = if (showMemberPanel) tr(locale, "hide-members") else tr(locale, "show-members")
            g.drawString(truncateByWidth(g, buttonText, buttonWidth - 10), buttonX + 5, buttonY + 10)
        }

        g.color = awt(baseAccent)
        g.fillOval(chatStartX + chatWidth - 14, 8, 6, 6)

        val baseMessageAreaTop = topBarHeight + 12
        val messageAreaBottom = heightPx - inputHeight - 12
        var messageAreaTop = baseMessageAreaTop
        val bubbleX = chatStartX + 8
        val bubbleWidth = chatWidth - 16
        val avatarSize = 14
        val contentStartX = bubbleX + avatarSize + 12
        val contentWidth = bubbleWidth - (contentStartX - bubbleX) - 8

        if (messages.size <= 2) {
            val welcomeHeight = drawWelcomeCard(
                g = g,
                x = bubbleX,
                y = messageAreaTop,
                width = bubbleWidth,
                channelName = normalizeUiText(selectedChannel?.name ?: tr(locale, "unlinked")),
                theme = theme,
                locale = locale
            )
            messageAreaTop += welcomeHeight + 8
        }

        if (messages.isEmpty()) {
            val emptyHeight = drawEmptyChatCard(
                g = g,
                x = bubbleX,
                y = messageAreaTop,
                width = bubbleWidth,
                theme = theme,
                locale = locale
            )
            messageAreaTop += emptyHeight + 8
        }

        var cursorBottom = messageAreaBottom
        messages.take(20).forEachIndexed { index, message ->
            g.font = FONT_10_PLAIN
            val wrapped = wrapText(g, message.content.ifBlank { " " }, contentWidth)
                .take(if (showMemberPanel) 3 else 4)

            val bubbleHeight = 16 + (wrapped.size * 11) + 8
            val bubbleTop = cursorBottom - bubbleHeight
            if (bubbleTop < messageAreaTop) {
                return@forEachIndexed
            }

            val bubbleColor = if (index % 2 == 0) {
                adjust(baseCard, 4)
            } else {
                baseCard
            }
            drawSoftShadow(g, bubbleX, bubbleTop, bubbleWidth, bubbleHeight, 8)
            g.color = awt(bubbleColor)
            g.fillRoundRect(bubbleX, bubbleTop, bubbleWidth, bubbleHeight, 8, 8)

            g.color = awt(adjust(bubbleColor, 14), 120)
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(bubbleX, bubbleTop, bubbleWidth, bubbleHeight, 8, 8)

            g.color = awt(adjust(baseAccent, 8), 180)
            g.fillRoundRect(bubbleX + 2, bubbleTop + 4, 2, bubbleHeight - 8, 2, 2)

            val member = membersByName[message.author.lowercase()]
            if (member != null) {
                drawMemberAvatar(
                    g = g,
                    member = member,
                    x = bubbleX + 6,
                    y = bubbleTop + 5,
                    size = avatarSize,
                    fallbackColor = baseAccent
                )
            } else {
                drawExternalAvatar(
                    g = g,
                    author = message.author,
                    x = bubbleX + 6,
                    y = bubbleTop + 5,
                    size = avatarSize,
                    fallbackColor = baseAccent
                )
            }

            g.font = FONT_10_BOLD
            g.color = textOnCard
            g.drawString(truncate(message.author, 16), contentStartX, bubbleTop + 14)

            g.font = FONT_9_PLAIN
            g.color = textOnCardMuted
            val timeText = formatTime(message.timestamp)
            val timeWidth = g.fontMetrics.stringWidth(timeText)
            g.drawString(timeText, bubbleX + bubbleWidth - timeWidth - 8, bubbleTop + 14)

            g.font = FONT_10_PLAIN
            g.color = textOnCard
            var lineY = bubbleTop + 26
            wrapped.forEach { line ->
                drawMessageLine(g, line, contentStartX, lineY)
                lineY += 11
            }

            cursorBottom = bubbleTop - 6
        }

        val inputX = chatStartX + 8
        val inputY = heightPx - inputHeight - 6
        val inputWidth = chatWidth - 16
        val canSend = selectedChannel == null || (!selectedChannel.isVoice && selectedChannel.canTalk)

        drawSoftShadow(g, inputX, inputY, inputWidth, inputHeight, 8)
        g.color = if (canSend) {
            awt(adjust(baseCard, 8))
        } else {
            awt(adjust(baseBackground, -4), 220)
        }
        g.fillRoundRect(inputX, inputY, inputWidth, inputHeight, 8, 8)
        g.color = if (canSend) {
            awt(adjust(baseCard, 20), 90)
        } else {
            awt(adjust(baseSidebar, 12), 120)
        }
        g.drawRoundRect(inputX, inputY, inputWidth, inputHeight, 8, 8)

        if (canSend) {
            g.color = awt(baseAccent, 180)
            g.fillOval(inputX + 8, inputY + 8, 7, 7)
        } else {
            g.color = java.awt.Color(168, 172, 182)
            g.font = FONT_13_BOLD
            g.drawString("+", inputX + 8, inputY + 16)
        }

        g.color = if (canSend) textSecondary else textSidebarSecondary
        g.font = FONT_10_PLAIN
        val inputHint = if (canSend) {
            tr(locale, "input-chat")
        } else {
            tr(locale, "input-no-permission")
        }
        g.drawString(truncateByWidth(g, inputHint, inputWidth - 24), inputX + 20, inputY + 15)

        if (showMemberPanel) {
            g.color = textSidebarPrimary
            g.font = FONT_9_BOLD
            g.drawString(tr(locale, "members-title").uppercase(), widthPx - memberPanelWidth + 8, 16)

            g.font = FONT_9_PLAIN
            g.color = textSidebarSecondary
            g.drawString("${tr(locale, "online")} • ${members.size}", widthPx - memberPanelWidth + 8, 30)

            var memberY = 44
            val avatarSize = 16
            val memberNameX = widthPx - memberPanelWidth + 8 + avatarSize + 6
            val roleWidth = memberPanelWidth - (avatarSize + 20)

            if (members.isEmpty()) {
                g.color = textSidebarSecondary
                g.drawString(tr(locale, "no-members"), widthPx - memberPanelWidth + 8, memberY)
            }

            members.forEach { member ->
                if (memberY + 20 > heightPx - 8) {
                    return@forEach
                }

                drawMemberAvatar(
                    g = g,
                    member = member,
                    x = widthPx - memberPanelWidth + 8,
                    y = memberY - 11,
                    size = avatarSize,
                    fallbackColor = baseAccent
                )

                g.font = FONT_9_BOLD
                g.color = textSidebarPrimary
                g.drawString(truncateByWidth(g, member.name, roleWidth), memberNameX, memberY - 1)

                g.color = awt(baseAccent, 220)
                g.fillOval(memberNameX + roleWidth - 5, memberY - 8, 4, 4)

                g.font = FONT_8_PLAIN
                g.color = textSidebarSecondary
                val role = member.roles.firstOrNull() ?: tr(locale, "no-role")
                g.drawString(truncateByWidth(g, role, roleWidth), memberNameX, memberY + 9)

                memberY += 22
            }
        }

        g.color = java.awt.Color(255, 255, 255, 70)
        g.clip = oldClip
        g.color = java.awt.Color(255, 255, 255, 52)
        g.drawRoundRect(appX, appY, appWidth - 1, appHeight - 1, 12, 12)
        g.color = java.awt.Color(0, 0, 0, 38)
        g.drawRoundRect(appX + 1, appY + 1, appWidth - 3, appHeight - 3, 12, 12)
        g.dispose()
        return if (qualityScale == 1) {
            workingImage
        } else {
            downscaleImage(workingImage, widthPx, heightPx)
        }
    }

    private fun fillRect(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, color: Color) {
        g.color = awt(color)
        g.fillRect(x, y, width, height)
    }

    private fun fillVerticalGradient(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        topColor: Color,
        bottomColor: Color,
        alpha: Int
    ) {
        if (width <= 0 || height <= 0) {
            return
        }

        val oldPaint = g.paint
        g.paint = GradientPaint(
            x.toFloat(),
            y.toFloat(),
            awt(topColor, alpha),
            x.toFloat(),
            (y + height).toFloat(),
            awt(bottomColor, alpha)
        )
        g.fillRect(x, y, width, height)
        g.paint = oldPaint
    }

    private fun downscaleImage(source: BufferedImage, width: Int, height: Int): BufferedImage {
        var current = source
        var currentWidth = source.width
        var currentHeight = source.height

        while (currentWidth / 2 >= width && currentHeight / 2 >= height) {
            val nextWidth = max(width, currentWidth / 2)
            val nextHeight = max(height, currentHeight / 2)
            val next = BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB)
            val g = next.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            g.drawImage(current, 0, 0, nextWidth, nextHeight, null)
            g.dispose()
            current = next
            currentWidth = nextWidth
            currentHeight = nextHeight
        }

        if (currentWidth == width && currentHeight == height) {
            return current
        }

        val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = output.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
        graphics.drawImage(current, 0, 0, width, height, null)
        graphics.dispose()
        return output
    }

    private fun drawSoftShadow(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, arc: Int) {
        g.color = java.awt.Color(0, 0, 0, 36)
        g.fillRoundRect(x + 1, y + 1, width, height, arc, arc)
        g.color = java.awt.Color(0, 0, 0, 20)
        g.fillRoundRect(x + 2, y + 2, width, height, arc, arc)
    }

    private fun awt(color: Color, alpha: Int = 255): java.awt.Color {
        return java.awt.Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
    }

    private fun drawMemberAvatar(
        g: Graphics2D,
        member: DiscordMember,
        x: Int,
        y: Int,
        size: Int,
        fallbackColor: Color
    ) {
        val originalClip = g.clip
        g.clip = Ellipse2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat())

        val avatar = loadAvatar(member)
        if (avatar != null) {
            g.drawImage(avatar, x, y, size, size, null)
        } else {
            g.color = awt(adjust(fallbackColor, -8))
            g.fillOval(x, y, size, size)
            g.color = java.awt.Color.WHITE
            g.font = FONT_9_BOLD
            g.drawString(member.name.take(1).uppercase(), x + 5, y + 11)
        }

        g.clip = originalClip
        g.color = java.awt.Color(255, 255, 255, 48)
        g.drawOval(x, y, size, size)
    }

    private fun drawGuildBadge(
        g: Graphics2D,
        guild: com.interdc.discord.DiscordGuild?,
        guildName: String,
        x: Int,
        y: Int,
        size: Int,
        primaryColor: Color
    ) {
        drawSoftShadow(g, x, y, size, size, 8)
        val icon = loadImageUrl(guild?.iconUrl)

        if (icon != null) {
            val originalClip = g.clip
            g.clip = Ellipse2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat())
            g.drawImage(icon, x, y, size, size, null)
            g.clip = originalClip
            g.color = java.awt.Color(255, 255, 255, 70)
            g.drawOval(x, y, size, size)
            return
        }

        g.color = awt(primaryColor)
        g.fillRoundRect(x, y, size, size, 6, 6)
        g.color = java.awt.Color.WHITE
        g.font = FONT_8_BOLD
        g.drawString(guildName.take(2).uppercase(), x + 2, y + size - 3)
    }

    private fun drawExternalAvatar(
        g: Graphics2D,
        author: String,
        x: Int,
        y: Int,
        size: Int,
        fallbackColor: Color
    ) {
        val encoded = URLEncoder.encode(author.trim(), StandardCharsets.UTF_8)
        val avatar = loadImageUrl("https://mc-heads.net/avatar/$encoded")
        if (avatar != null) {
            val originalClip = g.clip
            g.clip = Ellipse2D.Float(x.toFloat(), y.toFloat(), size.toFloat(), size.toFloat())
            g.drawImage(avatar, x, y, size, size, null)
            g.clip = originalClip
            g.color = java.awt.Color(255, 255, 255, 48)
            g.drawOval(x, y, size, size)
            return
        }

        g.color = awt(fallbackColor)
        g.fillOval(x, y, size, size)
        g.font = FONT_8_BOLD
        g.color = java.awt.Color.WHITE
        g.drawString(author.take(1).uppercase(), x + 4, y + 11)
    }

    private fun drawMessageLine(g: Graphics2D, line: String, startX: Int, baselineY: Int) {
        val tokens = TOKEN_REGEX.findAll(line).map { it.value }.toList()
        var cursorX = startX
        val mentionBg = java.awt.Color(246, 201, 90, 120)
        val mentionFg = java.awt.Color(255, 229, 132)
        val normalFg = java.awt.Color(220, 222, 228)
        val fm = g.fontMetrics

        tokens.forEach { token ->
            if (token.isBlank()) {
                cursorX += fm.stringWidth(token)
                return@forEach
            }

            val clean = token.trim().trimEnd(',', '.', ';', ':', '!', '?')
            val isMention = clean.equals("@everyone", ignoreCase = true) || clean.equals("@here", ignoreCase = true)

            if (isMention) {
                val width = fm.stringWidth(token)
                g.color = mentionBg
                g.fillRoundRect(cursorX - 1, baselineY - 9, width + 2, 11, 4, 4)
                g.color = mentionFg
                g.drawString(token, cursorX, baselineY)
                cursorX += width
            } else {
                g.color = normalFg
                g.drawString(token, cursorX, baselineY)
                cursorX += fm.stringWidth(token)
            }
        }
    }

    private fun drawWelcomeCard(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        channelName: String,
        theme: com.interdc.core.GuildTheme,
        locale: String
    ): Int {
        val cardHeight = 48
        drawSoftShadow(g, x, y, width, cardHeight, 8)
        g.color = awt(adjust(theme.messageColor, 5), 220)
        g.fillRoundRect(x, y, width, cardHeight, 8, 8)
        g.color = awt(adjust(theme.messageColor, 16), 110)
        g.drawRoundRect(x, y, width, cardHeight, 8, 8)

        g.font = FONT_11_BOLD
        g.color = java.awt.Color(240, 242, 246)
        g.drawString(
            truncateByWidth(g, tr(locale, "welcome-channel", mapOf("channel" to channelName)), width - 16),
            x + 8,
            y + 18
        )

        g.font = FONT_9_PLAIN
        g.color = java.awt.Color(186, 190, 200)
        g.drawString(
            truncateByWidth(g, tr(locale, "welcome-start", mapOf("channel" to channelName)), width - 16),
            x + 8,
            y + 34
        )

        return cardHeight
    }

    private fun drawEmptyChatCard(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        theme: com.interdc.core.GuildTheme,
        locale: String
    ): Int {
        val cardHeight = 36
        drawSoftShadow(g, x, y, width, cardHeight, 8)
        g.color = awt(adjust(theme.messageColor, 2), 200)
        g.fillRoundRect(x, y, width, cardHeight, 8, 8)
        g.color = awt(adjust(theme.messageColor, 14), 110)
        g.drawRoundRect(x, y, width, cardHeight, 8, 8)

        g.font = FONT_9_PLAIN
        g.color = java.awt.Color(192, 196, 206)
        g.drawString(
            truncateByWidth(g, tr(locale, "empty-channel-1"), width - 16),
            x + 8,
            y + 16
        )
        g.drawString(
            truncateByWidth(g, tr(locale, "empty-channel-2"), width - 16),
            x + 8,
            y + 28
        )

        return cardHeight
    }

    private fun loadAvatar(member: DiscordMember): BufferedImage? {
        return loadImageUrl(member.avatarUrl)
    }

    private fun loadImageUrl(rawUrl: String?): BufferedImage? {
        val url = normalizeImageUrl(rawUrl) ?: return null
        avatarImageCache.getIfPresent(url)?.let { return it }
        if (failedAvatarUrls.getIfPresent(url) != null) {
            return null
        }

        val loaded = fetchImageWithRetry(url)

        if (loaded != null) {
            avatarImageCache.put(url, loaded)
            return loaded
        }

        failedAvatarUrls.put(url, System.currentTimeMillis())
        return null
    }

    private fun fetchImageWithRetry(url: String): BufferedImage? {
        repeat(IMAGE_FETCH_ATTEMPTS) {
            val loaded = runCatching { downloadImage(url) }.getOrNull()
            if (loaded != null) {
                return loaded
            }
        }
        return null
    }

    private fun downloadImage(url: String): BufferedImage? {
        val connection: URLConnection = URI(url).toURL().openConnection()
        connection.connectTimeout = IMAGE_CONNECT_TIMEOUT_MS
        connection.readTimeout = IMAGE_READ_TIMEOUT_MS
        connection.getInputStream().use { stream ->
            return ImageIO.read(stream)
        }
    }

    private fun normalizeImageUrl(rawUrl: String?): String? {
        val input = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val lower = input.lowercase()

        if ("discord" !in lower) {
            return input
        }

        val hasQuery = input.contains('?')
        val base = if (hasQuery) input.substringBefore('?') else input
        val query = if (hasQuery) input.substringAfter('?', "") else ""

        val pngBase = when {
            base.endsWith(".webp", ignoreCase = true) -> base.removeSuffix(".webp") + ".png"
            base.endsWith(".gif", ignoreCase = true) -> base
            base.endsWith(".png", ignoreCase = true) -> base
            else -> "$base.png"
        }

        val finalQuery = when {
            query.isBlank() -> "size=128"
            "size=" in query.lowercase() -> query
            else -> "$query&size=128"
        }

        return "$pngBase?$finalQuery"
    }

    private fun wrapText(g: Graphics2D, text: String, maxWidthPx: Int): List<String> {
        if (text.isBlank()) {
            return listOf("")
        }

        val words = text.split(WHITESPACE_REGEX)
        val lines = mutableListOf<String>()
        var current = ""

        words.forEach { word ->
            val candidate = if (current.isBlank()) word else "$current $word"
            if (g.fontMetrics.stringWidth(candidate) <= maxWidthPx) {
                current = candidate
            } else {
                if (current.isNotBlank()) {
                    lines.add(current)
                }
                current = if (g.fontMetrics.stringWidth(word) <= maxWidthPx) {
                    word
                } else {
                    truncateByWidth(g, word, maxWidthPx)
                }
            }
        }

        if (current.isNotBlank()) {
            lines.add(current)
        }

        return if (lines.isEmpty()) listOf("") else lines
    }

    private fun truncateByWidth(g: Graphics2D, text: String, maxWidthPx: Int): String {
        if (g.fontMetrics.stringWidth(text) <= maxWidthPx) {
            return text
        }

        val safeWidth = max(4, maxWidthPx)
        var low = 0
        var high = text.length
        var best = ""

        while (low <= high) {
            val mid = (low + high) / 2
            val probe = text.take(mid).trimEnd() + "…"
            if (g.fontMetrics.stringWidth(probe) <= safeWidth) {
                best = probe
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return if (best.isBlank()) "…" else best
    }

    private fun formatTime(timestamp: java.time.Instant): String {
        return hourFormatter.format(timestamp.atZone(ZoneId.systemDefault()))
    }

    private fun truncate(text: String, max: Int): String {
        if (text.length <= max) {
            return text
        }
        return text.take(max(0, min(text.length, max) - 1)) + "…"
    }

    private fun normalizeUiText(text: String): String {
        if (text.isBlank()) {
            return text
        }

        val mapped = text
            .replace("📁", " ")
            .replace("📌", " ")
            .replace("📢", " ")
            .replace("🔊", " ")
            .replace("💬", " ")
            .replace("❓", "?")
            .replace("❗", "!")
            .replace("✅", "+")
            .replace("❌", "x")

        val normalized = Normalizer.normalize(mapped, Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")

        val ascii = buildString(normalized.length) {
            normalized.forEach { ch ->
                when {
                    ch == '\n' || ch == '\r' || ch == '\t' -> append(' ')
                    ch.code in 32..126 -> append(ch)
                    else -> append('?')
                }
            }
        }
            .replace(MULTI_QM_REGEX, "?")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        return if (ascii.isBlank()) "channel" else ascii
    }

    private fun adjust(color: Color, delta: Int): Color {
        fun clamp(value: Int): Int = value.coerceIn(0, 255)
        return Color.fromRGB(
            clamp(color.red + delta),
            clamp(color.green + delta),
            clamp(color.blue + delta)
        )
    }

    private fun mix(base: Color, target: Color, targetWeight: Double): Color {
        val weight = targetWeight.coerceIn(0.0, 1.0)
        val baseWeight = 1.0 - weight
        fun ch(baseValue: Int, targetValue: Int): Int {
            return (baseValue * baseWeight + targetValue * weight).toInt().coerceIn(0, 255)
        }

        return Color.fromRGB(
            ch(base.red, target.red),
            ch(base.green, target.green),
            ch(base.blue, target.blue)
        )
    }

    private fun bestTextColor(background: Color): java.awt.Color {
        val darkBackground = relativeLuminance(background) < 0.42
        return if (darkBackground) {
            java.awt.Color(242, 245, 252)
        } else {
            java.awt.Color(22, 24, 31)
        }
    }

    private fun bestMutedTextColor(background: Color): java.awt.Color {
        val darkBackground = relativeLuminance(background) < 0.42
        return if (darkBackground) {
            java.awt.Color(186, 191, 203)
        } else {
            java.awt.Color(88, 93, 106)
        }
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }

        val r = channel(color.red)
        val g = channel(color.green)
        val b = channel(color.blue)
        return (0.2126 * r) + (0.7152 * g) + (0.0722 * b)
    }

    private fun stylePreset(layout: String): VisualStylePreset {
        return when (layout.trim().lowercase()) {
            "glass" -> VisualStylePreset(
                backgroundBlend = 0.42,
                sidebarBlend = 0.5,
                guildRailBlend = 0.58,
                cardBlend = 0.4,
                accentBlend = 0.35,
                chatGradientAlpha = 52,
                sidebarGradientAlpha = 38,
                topOverlayAlpha = 48,
                guildHeaderAlpha = 28
            )

            "ultra" -> VisualStylePreset(
                backgroundBlend = 0.65,
                sidebarBlend = 0.75,
                guildRailBlend = 0.85,
                cardBlend = 0.60,
                accentBlend = 0.50,
                chatGradientAlpha = 70,
                sidebarGradientAlpha = 50,
                topOverlayAlpha = 60,
                guildHeaderAlpha = 40
            )

            "classic" -> VisualStylePreset(
                backgroundBlend = 0.2,
                sidebarBlend = 0.26,
                guildRailBlend = 0.32,
                cardBlend = 0.24,
                accentBlend = 0.12,
                chatGradientAlpha = 20,
                sidebarGradientAlpha = 14,
                topOverlayAlpha = 20,
                guildHeaderAlpha = 10
            )

            else -> VisualStylePreset(
                backgroundBlend = 0.58,
                sidebarBlend = 0.62,
                guildRailBlend = 0.75,
                cardBlend = 0.55,
                accentBlend = 0.45,
                chatGradientAlpha = 36,
                sidebarGradientAlpha = 24,
                topOverlayAlpha = 32,
                guildHeaderAlpha = 18
            )
        }
    }

    private fun tr(locale: String, key: String, replacements: Map<String, String> = emptyMap()): String {
        val normalized = locale.lowercase()
        val text = when (key) {
            "discord-disconnected" -> when {
                normalized.startsWith("pt") -> "Discord desconectado"
                normalized.startsWith("es") -> "Discord desconectado"
                normalized.startsWith("fr") -> "Discord déconnecté"
                normalized.startsWith("de") -> "Discord getrennt"
                normalized.startsWith("it") -> "Discord disconnesso"
                normalized.startsWith("ja") -> "Discord 未接続"
                else -> "Discord disconnected"
            }
            "uncategorized" -> when {
                normalized.startsWith("pt") -> "Sem categoria"
                normalized.startsWith("es") -> "Sin categoría"
                normalized.startsWith("fr") -> "Sans catégorie"
                normalized.startsWith("de") -> "Ohne Kategorie"
                normalized.startsWith("it") -> "Senza categoria"
                normalized.startsWith("ja") -> "カテゴリなし"
                else -> "Uncategorized"
            }
            "unlinked" -> when {
                normalized.startsWith("pt") -> "não-vinculado"
                normalized.startsWith("es") -> "sin-vincular"
                normalized.startsWith("fr") -> "non-lié"
                normalized.startsWith("de") -> "nicht-verknüpft"
                normalized.startsWith("it") -> "non-collegato"
                normalized.startsWith("ja") -> "未リンク"
                else -> "unlinked"
            }
            "no-messages" -> when {
                normalized.startsWith("pt") -> "Sem mensagens"
                normalized.startsWith("es") -> "Sin mensajes"
                normalized.startsWith("fr") -> "Aucun message"
                normalized.startsWith("de") -> "Keine Nachrichten"
                normalized.startsWith("it") -> "Nessun messaggio"
                normalized.startsWith("ja") -> "メッセージなし"
                else -> "No messages"
            }
            "messages" -> when {
                normalized.startsWith("pt") -> "mensagens"
                normalized.startsWith("es") -> "mensajes"
                normalized.startsWith("fr") -> "messages"
                normalized.startsWith("de") -> "Nachrichten"
                normalized.startsWith("it") -> "messaggi"
                normalized.startsWith("ja") -> "件"
                else -> "messages"
            }
            "show-members" -> if (normalized.startsWith("pt")) "Mostrar membros" else "Show members"
            "hide-members" -> if (normalized.startsWith("pt")) "Ocultar membros" else "Hide members"
            "input-chat" -> when {
                normalized.startsWith("pt") -> "Converse pelo chat do Minecraft (digite cancel/cancelar para sair)"
                else -> "Chat from Minecraft here (type cancel to exit)"
            }
            "input-no-permission" -> when {
                normalized.startsWith("pt") -> "Você não tem permissão para enviar mensagens neste canal"
                normalized.startsWith("es") -> "No tienes permiso para enviar mensajes en este canal"
                normalized.startsWith("fr") -> "Vous n'avez pas la permission d'envoyer des messages dans ce canal"
                normalized.startsWith("de") -> "Du hast keine Berechtigung, in diesem Kanal Nachrichten zu senden"
                normalized.startsWith("it") -> "Non hai il permesso di inviare messaggi in questo canale"
                normalized.startsWith("ja") -> "このチャンネルでメッセージを送信する権限がありません"
                else -> "You do not have permission to send messages in this channel"
            }
            "members-title" -> if (normalized.startsWith("pt")) "Membros" else "Members"
            "online" -> if (normalized.startsWith("pt")) "Online" else "Online"
            "no-members" -> if (normalized.startsWith("pt")) "Sem membros para exibir" else "No members to display"
            "no-role" -> if (normalized.startsWith("pt")) "Sem cargo" else "No role"
            "welcome-channel" -> if (normalized.startsWith("pt")) "Bem-vindo(a) a {channel}" else "Welcome to {channel}"
            "welcome-start" -> if (normalized.startsWith("pt")) "Este é o começo do canal {channel}." else "This is the beginning of {channel}."
            "empty-channel-1" -> if (normalized.startsWith("pt")) "Ainda não há mensagens neste canal." else "There are no messages in this channel yet."
            "empty-channel-2" -> if (normalized.startsWith("pt")) "Envie algo no Discord ou pelo chat do jogo." else "Send something on Discord or from Minecraft chat."
            else -> key
        }

        var result = text
        replacements.forEach { (k, v) -> result = result.replace("{$k}", v) }
        return result
    }

    private fun resolveQualityScale(totalTiles: Int, mode: String): Int {
        return when (mode) {
            "quality" -> when {
                totalTiles <= 4 -> 4
                totalTiles <= 9 -> 3
                totalTiles <= 16 -> 2
                else -> 1
            }

            "performance" -> when {
                totalTiles <= 4 -> 2
                totalTiles <= 9 -> 2
                else -> 1
            }

            else -> when {
                totalTiles <= 4 -> 3
                totalTiles <= 9 -> 2
                totalTiles <= 16 -> 2
                else -> 1
            }
        }
    }

    private fun applyRenderHints(g: Graphics2D, mode: String) {
        when (mode) {
            "performance" -> {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT)
            }

            "quality" -> {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            }

            else -> {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_DEFAULT)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_DEFAULT)
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
            }
        }
    }
}
