package com.streakkeeper.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.ZoneId

@Service
class TelegramCommandService(
    @Value("\${streakkeeper.telegram.bot-token:}") private val botToken: String,
    @Value("\${streakkeeper.telegram.chat-id:}") private val authorizedChatId: String,
    @Value("\${streakkeeper.timezone:Asia/Kolkata}") private val timezoneStr: String,
    private val telegramNotifierService: TelegramNotifierService,
    private val githubContributionService: GithubContributionService,
    private val readmeRescueService: ReadmeRescueService,
    private val accountManagerService: AccountManagerService,
    private val objectMapper: ObjectMapper,
    restClientBuilder: RestClient.Builder
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    val cleanChatId: String = authorizedChatId.trim().trim('"').trim('\'')

    private val restClient: RestClient = restClientBuilder
        .baseUrl("https://api.telegram.org")
        .defaultHeader("User-Agent", "StreakKeeper-App")
        .build()

    var lastUpdateId: Long = 0
        internal set

    @PostConstruct
    fun init() {
        if (botToken.isBlank()) {
            logger.warn("⚠️ TELEGRAM_BOT_TOKEN is not set. Telegram command polling is inactive.")
        } else if (cleanChatId.isBlank()) {
            logger.warn("⚠️ TELEGRAM_CHAT_ID is not set. Telegram command polling is inactive.")
        } else {
            logger.info("✅ TelegramCommandService active! Polling for authorized chat_id: {}", cleanChatId)
        }
    }

    @Scheduled(fixedDelayString = "\${streakkeeper.telegram.poll-interval-ms:3000}")
    fun pollUpdates(): Int {
        if (botToken.isBlank() || cleanChatId.isBlank()) {
            return 0
        }

        return try {
            val uri = if (lastUpdateId > 0) {
                "/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=3"
            } else {
                "/bot$botToken/getUpdates?timeout=3"
            }

            val responseBody = restClient.get()
                .uri(uri)
                .retrieve()
                .body(String::class.java) ?: return 0

            processUpdatesJson(responseBody)
        } catch (ex: Exception) {
            logger.error("Error polling Telegram getUpdates: {}", ex.message)
            0
        }
    }

    fun processUpdatesJson(jsonString: String): Int {
        val root: JsonNode = objectMapper.readTree(jsonString)
        if (!root.path("ok").asBoolean(false)) {
            return 0
        }

        val results = root.path("result")
        if (!results.isArray || results.isEmpty) {
            return 0
        }

        var processedCount = 0
        for (update in results) {
            val updateId = update.path("update_id").asLong(0)
            if (updateId > lastUpdateId) {
                lastUpdateId = updateId
            }

            val messageNode = update.path("message")
            if (messageNode.isMissingNode) continue

            val chatId = messageNode.path("chat").path("id").asText("").trim()
            val text = messageNode.path("text").asText("").trim()

            logger.info("Received Telegram message [update_id={} | chat_id={}]: {}", updateId, chatId, text)

            // Security Check: Only accept messages from authorized TELEGRAM_CHAT_ID
            if (chatId != cleanChatId) {
                logger.warn("Security Alert: Ignored command from unauthorized chat_id: {} (Expected: {})", chatId, cleanChatId)
                continue
            }

            if (text.isNotBlank()) {
                handleCommand(text)
                processedCount++
            }
        }

        return processedCount
    }

    private fun handleCommand(commandText: String) {
        val parts = commandText.trim().split("\\s+".toRegex())
        val cmd = parts[0].lowercase().split("@")[0]
        logger.info("Executing authorized Telegram command: {}", cmd)

        when (cmd) {
            "/accounts" -> handleAccounts()
            "/account" -> {
                if (parts.size > 1) {
                    handleSwitchAccount(parts[1])
                } else {
                    telegramNotifierService.send("Usage: `/account <name>` (e.g. `/account second`)")
                }
            }
            "/status" -> handleStatus()
            "/rescue" -> handleRescue()
            "/help", "/start" -> handleHelp()
            else -> {
                telegramNotifierService.send(
                    "❓ Unknown command: `$commandText`\nType /help to see available commands."
                )
            }
        }
    }

    private fun handleAccounts() {
        val allAccounts = accountManagerService.getAllAccounts()
        if (allAccounts.isEmpty()) {
            telegramNotifierService.send("⚠️ No accounts configured.")
            return
        }

        val activeKey = accountManagerService.activeAccountKey
        val sb = StringBuilder("👥 *StreakKeeper Accounts:*\n\n")

        for ((key, account) in allAccounts) {
            val isActive = key == activeKey
            val marker = if (isActive) "⭐️ [ACTIVE]" else "▫️"
            val username = accountManagerService.cachedLogins[key] ?: try {
                val u = githubContributionService.validateTokenAndGetLogin(account.token)
                accountManagerService.cachedLogins[key] = u
                u
            } catch (e: Exception) {
                "unknown"
            }

            val todayCount = try {
                githubContributionService.getTodayContributions(account.token).count
            } catch (e: Exception) {
                -1
            }

            val countDisplay = if (todayCount >= 0) "$todayCount contributions" else "error checking"
            sb.append("$marker *$key* (@$username)\n")
            sb.append("   └ Today: $countDisplay\n\n")
        }

        sb.append("Switch active account using `/account <name>`")
        telegramNotifierService.send(sb.toString())
    }

    private fun handleSwitchAccount(accountName: String) {
        try {
            val username = accountManagerService.switchActiveAccount(accountName)
            telegramNotifierService.send("Switched to '$accountName' ➔ @$username ✅")
        } catch (e: Exception) {
            logger.error("Failed to switch account to '{}': {}", accountName, e.message)
            telegramNotifierService.send("❌ Failed to switch to '$accountName': ${e.message}")
        }
    }

    private fun handleStatus() {
        try {
            val activeKey = accountManagerService.activeAccountKey
            val activeToken = accountManagerService.getActiveAccount().token
            val stats = githubContributionService.getYearlyStats(activeToken)
            val zoneId = try { ZoneId.of(timezoneStr) } catch (e: Exception) { ZoneId.of("Asia/Kolkata") }
            val todayStr = LocalDate.now(zoneId).toString()

            val statusMsg = """
                📊 [StreakKeeper Status - Account '$activeKey']
                👤 User: @${stats.login}
                📅 Date: $todayStr
                🔥 Today's Contributions: ${stats.todayCount}
                ⚡ Current Streak: ${stats.currentStreak} day(s)
                🏆 Longest Streak: ${stats.longestStreak} day(s)
                📦 Total Contributions: ${stats.totalThisYear}
            """.trimIndent()

            telegramNotifierService.send(statusMsg)
        } catch (ex: Exception) {
            logger.error("Error generating status reply: {}", ex.message, ex)
            telegramNotifierService.send("⚠️ Failed to retrieve status: ${ex.message}")
        }
    }

    private fun handleRescue() {
        try {
            val activeKey = accountManagerService.activeAccountKey
            val activeAccount = accountManagerService.getActiveAccount()
            val zoneId = try { ZoneId.of(timezoneStr) } catch (e: Exception) { ZoneId.of("Asia/Kolkata") }
            val todayStr = LocalDate.now(zoneId).toString()

            val rescueResult = readmeRescueService.rescueStreak(activeKey, todayStr)
            val check = githubContributionService.getTodayContributions(activeAccount.token)

            val replyMsg = if (check.count > 0 || rescueResult.success) {
                """
                🤖 [Manual Rescue Success - '$activeKey']
                ✅ Daily log committed to ${rescueResult.owner}/${rescueResult.repo}
                📝 Commit SHA: ${rescueResult.commitSha ?: "N/A"}
                🔥 Today's Count: ${check.count}
                Streak is saved for @${rescueResult.owner}! 🎉
                """.trimIndent()
            } else {
                """
                ❌ [Manual Rescue Alert - '$activeKey']
                Commit attempted but contribution count remains 0. Please verify on GitHub!
                """.trimIndent()
            }

            telegramNotifierService.send(replyMsg)
        } catch (ex: Exception) {
            logger.error("Error executing manual rescue: {}", ex.message, ex)
            telegramNotifierService.send("❌ Rescue failed: ${ex.message}")
        }
    }

    private fun handleHelp() {
        val helpMsg = """
            🤖 *StreakKeeper Telegram Bot*
            
            Available commands:
            /status         - View active account's stats & streak
            /rescue         - Manually commit daily rescue to active account
            /accounts       - List all accounts & today's counts
            /account <name> - Switch the active account
            /help           - List available commands
        """.trimIndent()

        telegramNotifierService.send(helpMsg)
    }
}
