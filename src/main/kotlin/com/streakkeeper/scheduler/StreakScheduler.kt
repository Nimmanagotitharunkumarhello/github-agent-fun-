package com.streakkeeper.scheduler

import com.streakkeeper.service.AccountManagerService
import com.streakkeeper.service.GithubContributionService
import com.streakkeeper.service.ReadmeRescueService
import com.streakkeeper.service.TelegramNotifierService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

data class StreakCheckResult(
    val accountKey: String,
    val username: String,
    val timestamp: String,
    val localTime: String,
    val timezone: String,
    val count: Int,
    val safe: Boolean,
    val actionTaken: String,
    val details: String
)

@Component
class StreakScheduler(
    private val accountManagerService: AccountManagerService,
    private val githubContributionService: GithubContributionService,
    private val readmeRescueService: ReadmeRescueService,
    private val telegramNotifierService: TelegramNotifierService,
    @Value("\${streakkeeper.timezone:Asia/Kolkata}") private val timezoneStr: String,
    @Value("\${streakkeeper.alert-time:21:00}") private val alertTimeStr: String,
    @Value("\${streakkeeper.rescue-time:22:30}") private val rescueTimeStr: String,
    var clock: Clock = Clock.systemDefaultZone()
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    val lastAlertedDates = ConcurrentHashMap<String, LocalDate>()
    val lastRescuedDates = ConcurrentHashMap<String, LocalDate>()

    @Scheduled(cron = "\${streakkeeper.scheduler.cron:0 0 * * * *}")
    fun scheduledCheck() {
        runAllChecks()
    }

    fun runAllChecks(): List<StreakCheckResult> {
        val allAccounts = accountManagerService.getAllAccounts()
        if (allAccounts.isEmpty()) {
            logger.warn("No GitHub accounts configured for scheduled check.")
            return emptyList()
        }

        val results = mutableListOf<StreakCheckResult>()
        for ((accountKey, account) in allAccounts) {
            results.add(checkSingleAccount(accountKey, account.token))
        }
        return results
    }

    fun runCheck(): StreakCheckResult {
        // Runs check on the currently active account
        val activeKey = accountManagerService.activeAccountKey
        val activeToken = accountManagerService.getActiveAccount().token
        return checkSingleAccount(activeKey, activeToken)
    }

    fun checkSingleAccount(accountKey: String, token: String): StreakCheckResult {
        val zoneId = try {
            ZoneId.of(timezoneStr)
        } catch (e: Exception) {
            logger.warn("Invalid timezone '{}', defaulting to Asia/Kolkata", timezoneStr)
            ZoneId.of("Asia/Kolkata")
        }

        val nowZoned = ZonedDateTime.now(clock.withZone(zoneId))
        val today = nowZoned.toLocalDate()
        val currentTime = nowZoned.toLocalTime()
        val alertTime = LocalTime.parse(alertTimeStr)
        val rescueTime = LocalTime.parse(rescueTimeStr)

        val initialContribution = try {
            githubContributionService.getTodayContributions(token)
        } catch (ex: Exception) {
            logger.error("Failed to retrieve contribution count for account '{}': {}", accountKey, ex.message)
            return StreakCheckResult(
                accountKey = accountKey,
                username = "unknown",
                timestamp = nowZoned.toString(),
                localTime = currentTime.toString(),
                timezone = zoneId.id,
                count = -1,
                safe = false,
                actionTaken = "ERROR",
                details = "GitHub check failed: ${ex.message}"
            )
        }

        val username = initialContribution.login
        val count = initialContribution.count
        logger.info(
            "Account '{}' (@{}) | Date: {} | Today's Count: {} | Local Time: {}",
            accountKey,
            username,
            initialContribution.date,
            count,
            currentTime
        )

        // 1. If count > 0 -> Streak is safe
        if (count > 0) {
            logger.info("Account '{}' (@{}) is SAFE ({} contributions).", accountKey, username, count)
            return StreakCheckResult(
                accountKey = accountKey,
                username = username,
                timestamp = nowZoned.toString(),
                localTime = currentTime.toString(),
                timezone = zoneId.id,
                count = count,
                safe = true,
                actionTaken = "NONE",
                details = "Streak is safe with $count contributions today."
            )
        }

        // 2. If count == 0 and currentTime >= rescueTime (22:30) -> Trigger RESCUE
        if (!currentTime.isBefore(rescueTime)) {
            logger.info("RESCUE NEEDED for account '{}' (@{})", accountKey, username)
            return performRescueForAccount(accountKey, username, today, nowZoned, currentTime, zoneId, token)
        }

        // 3. If count == 0 and currentTime >= alertTime (21:00) -> Send ALERT
        if (!currentTime.isBefore(alertTime)) {
            if (lastAlertedDates[accountKey] == today) {
                logger.info("Alert already sent today for account '{}' (@{}). Skipping duplicate.", accountKey, username)
                return StreakCheckResult(
                    accountKey = accountKey,
                    username = username,
                    timestamp = nowZoned.toString(),
                    localTime = currentTime.toString(),
                    timezone = zoneId.id,
                    count = 0,
                    safe = false,
                    actionTaken = "ALERT_ALREADY_SENT",
                    details = "Alert was already delivered today on $today."
                )
            }

            val alertMsg = "🔥 [$accountKey: @$username] No contribution today! Streak at risk — deadline 11:30 PM"
            logger.warn("Triggering alert for account '{}': {}", accountKey, alertMsg)
            try {
                telegramNotifierService.send(alertMsg)
                lastAlertedDates[accountKey] = today
                logger.info("Telegram alert sent successfully for account '{}'.", accountKey)
                return StreakCheckResult(
                    accountKey = accountKey,
                    username = username,
                    timestamp = nowZoned.toString(),
                    localTime = currentTime.toString(),
                    timezone = zoneId.id,
                    count = 0,
                    safe = false,
                    actionTaken = "ALERTED",
                    details = "Telegram notification sent: '$alertMsg'"
                )
            } catch (ex: Exception) {
                logger.error("Failed to send Telegram alert for account '{}': {}", accountKey, ex.message)
                return StreakCheckResult(
                    accountKey = accountKey,
                    username = username,
                    timestamp = nowZoned.toString(),
                    localTime = currentTime.toString(),
                    timezone = zoneId.id,
                    count = 0,
                    safe = false,
                    actionTaken = "ALERT_FAILED",
                    details = "Failed to send alert via Telegram: ${ex.message}"
                )
            }
        }

        // 4. Count is 0, but before alert time
        logger.info("Account '{}' (@{}) count is 0, before alert threshold ({}).", accountKey, username, alertTime)
        return StreakCheckResult(
            accountKey = accountKey,
            username = username,
            timestamp = nowZoned.toString(),
            localTime = currentTime.toString(),
            timezone = zoneId.id,
            count = 0,
            safe = false,
            actionTaken = "WAITING",
            details = "Before alert threshold ($alertTime). No alert triggered yet."
        )
    }

    private fun performRescueForAccount(
        accountKey: String,
        username: String,
        today: LocalDate,
        nowZoned: ZonedDateTime,
        currentTime: LocalTime,
        zoneId: ZoneId,
        token: String
    ): StreakCheckResult {
        var rescueAttempted = false
        var rescueError: String? = null

        try {
            readmeRescueService.rescueStreak(accountKey, today.toString())
            rescueAttempted = true
            lastRescuedDates[accountKey] = today
        } catch (ex: Exception) {
            rescueError = ex.message
            logger.error("Rescue commit failed for account '{}': {}", accountKey, ex.message)
        }

        // Re-check contributions after rescue attempt
        val postRescueContribution = try {
            githubContributionService.getTodayContributions(token)
        } catch (ex: Exception) {
            logger.error("Failed to re-check contributions after rescue for account '{}': {}", accountKey, ex.message)
            null
        }

        val newCount = postRescueContribution?.count ?: if (rescueAttempted) 1 else 0

        if (newCount > 0) {
            val successMsg = "🤖 [$accountKey: @$username] Auto-contribution done — streak saved ✅"
            logger.info("Rescue succeeded for account '{}'! Sending Telegram confirmation.", accountKey)
            try {
                telegramNotifierService.send(successMsg)
            } catch (e: Exception) {
                logger.error("Failed to send Telegram rescue success message: {}", e.message)
            }

            return StreakCheckResult(
                accountKey = accountKey,
                username = username,
                timestamp = nowZoned.toString(),
                localTime = currentTime.toString(),
                timezone = zoneId.id,
                count = newCount,
                safe = true,
                actionTaken = "RESCUE_SUCCESS",
                details = "Auto-contribution committed and verified (count=$newCount). Telegram confirmation sent."
            )
        } else {
            val failMsg = "❌ [$accountKey: @$username] Auto-contribution FAILED, commit manually!"
            logger.error("Rescue verification failed for account '{}'. Sending alert.", accountKey)
            try {
                telegramNotifierService.send(failMsg)
            } catch (e: Exception) {
                logger.error("Failed to send Telegram rescue failure message: {}", e.message)
            }

            return StreakCheckResult(
                accountKey = accountKey,
                username = username,
                timestamp = nowZoned.toString(),
                localTime = currentTime.toString(),
                timezone = zoneId.id,
                count = 0,
                safe = false,
                actionTaken = "RESCUE_FAILED",
                details = "Auto-contribution failed (Error: ${rescueError ?: "Contribution count still 0"}). Telegram alert sent."
            )
        }
    }
}
