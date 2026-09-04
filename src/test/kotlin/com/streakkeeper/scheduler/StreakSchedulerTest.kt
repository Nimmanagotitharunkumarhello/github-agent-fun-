package com.streakkeeper.scheduler

import com.streakkeeper.config.AccountConfig
import com.streakkeeper.config.StreakKeeperProperties
import com.streakkeeper.service.AccountManagerService
import com.streakkeeper.service.GithubContributionService
import com.streakkeeper.service.ReadmeRescueService
import com.streakkeeper.service.RescueResult
import com.streakkeeper.service.TelegramNotifierService
import com.streakkeeper.service.TodayContributionResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.time.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakSchedulerTest {

    private lateinit var githubService: GithubContributionService
    private lateinit var rescueService: ReadmeRescueService
    private lateinit var telegramService: TelegramNotifierService
    private lateinit var accountManagerService: AccountManagerService
    private val zoneId = ZoneId.of("Asia/Kolkata")

    @BeforeEach
    fun setUp() {
        githubService = mock(GithubContributionService::class.java)
        rescueService = mock(ReadmeRescueService::class.java)
        telegramService = mock(TelegramNotifierService::class.java)

        val properties = StreakKeeperProperties(
            accounts = mutableMapOf(
                "main" to AccountConfig(token = "tok_main", rescueRepo = "daily-log")
            )
        )
        accountManagerService = AccountManagerService(properties, githubService)
    }

    private fun createSchedulerWithFixedTime(dateTime: LocalDateTime): StreakScheduler {
        val fixedInstant = dateTime.atZone(zoneId).toInstant()
        val clock = Clock.fixed(fixedInstant, zoneId)
        return StreakScheduler(
            accountManagerService = accountManagerService,
            githubContributionService = githubService,
            readmeRescueService = rescueService,
            telegramNotifierService = telegramService,
            timezoneStr = "Asia/Kolkata",
            alertTimeStr = "21:00",
            rescueTimeStr = "22:30",
            clock = clock
        )
    }

    @Test
    fun `when count is positive then do nothing and return safe`() {
        `when`(githubService.getTodayContributions("tok_main")).thenReturn(
            TodayContributionResponse("user", "2026-09-03", 2, true)
        )

        val scheduler = createSchedulerWithFixedTime(LocalDateTime.of(2026, 9, 3, 21, 30))
        val result = scheduler.runCheck()

        assertTrue(result.safe)
        assertEquals(2, result.count)
        assertEquals("NONE", result.actionTaken)
        verifyNoInteractions(telegramService)
        verifyNoInteractions(rescueService)
    }

    @Test
    fun `when count is 0 and time is before alert time then wait and do not send alert`() {
        `when`(githubService.getTodayContributions("tok_main")).thenReturn(
            TodayContributionResponse("user", "2026-09-03", 0, false)
        )

        val scheduler = createSchedulerWithFixedTime(LocalDateTime.of(2026, 9, 3, 16, 0))
        val result = scheduler.runCheck()

        assertFalse(result.safe)
        assertEquals(0, result.count)
        assertEquals("WAITING", result.actionTaken)
        verifyNoInteractions(telegramService)
        verifyNoInteractions(rescueService)
    }

    @Test
    fun `when count is 0 and time is past alert time then send telegram alert`() {
        `when`(githubService.getTodayContributions("tok_main")).thenReturn(
            TodayContributionResponse("user", "2026-09-03", 0, false)
        )

        val scheduler = createSchedulerWithFixedTime(LocalDateTime.of(2026, 9, 3, 21, 15))
        val result = scheduler.runCheck()

        assertFalse(result.safe)
        assertEquals(0, result.count)
        assertEquals("ALERTED", result.actionTaken)
        verify(telegramService).send(contains("No contribution today"))
        assertEquals(LocalDate.of(2026, 9, 3), scheduler.lastAlertedDates["main"])
    }

    @Test
    fun `when count is 0 and past rescue time then execute rescue and send success notification`() {
        `when`(githubService.getTodayContributions("tok_main"))
            .thenReturn(TodayContributionResponse("user", "2026-09-03", 0, false))
            .thenReturn(TodayContributionResponse("user", "2026-09-03", 1, true))

        `when`(rescueService.rescueStreak(anyString(), anyString())).thenReturn(
            RescueResult(true, "main", "user", "daily-log", "sha123", "Commit done")
        )

        val scheduler = createSchedulerWithFixedTime(LocalDateTime.of(2026, 9, 3, 22, 45))
        val result = scheduler.runCheck()

        assertTrue(result.safe)
        assertEquals(1, result.count)
        assertEquals("RESCUE_SUCCESS", result.actionTaken)
        verify(rescueService).rescueStreak("main", "2026-09-03")
        verify(telegramService).send(contains("Auto-contribution done"))
    }
}
