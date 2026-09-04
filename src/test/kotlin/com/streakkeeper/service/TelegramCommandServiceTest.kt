package com.streakkeeper.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.streakkeeper.config.AccountConfig
import com.streakkeeper.config.StreakKeeperProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.*
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals

class TelegramCommandServiceTest {

    private val objectMapper = ObjectMapper()
    private lateinit var telegramNotifierService: TelegramNotifierService
    private lateinit var githubContributionService: GithubContributionService
    private lateinit var readmeRescueService: ReadmeRescueService
    private lateinit var accountManagerService: AccountManagerService
    private val authorizedChatId = "123456789"

    @BeforeEach
    fun setUp() {
        telegramNotifierService = mock(TelegramNotifierService::class.java)
        githubContributionService = mock(GithubContributionService::class.java)
        readmeRescueService = mock(ReadmeRescueService::class.java)

        val properties = StreakKeeperProperties(
            accounts = mutableMapOf(
                "main" to AccountConfig(token = "tok_main", rescueRepo = "daily-log"),
                "second" to AccountConfig(token = "tok_second", rescueRepo = "daily-log")
            )
        )
        accountManagerService = AccountManagerService(properties, githubContributionService)
        accountManagerService.cachedLogins["main"] = "octocat"
        accountManagerService.cachedLogins["second"] = "secondcat"
    }

    @Test
    fun `ignores messages from unauthorized chat id`() {
        val service = TelegramCommandService(
            botToken = "token",
            authorizedChatId = authorizedChatId,
            timezoneStr = "Asia/Kolkata",
            telegramNotifierService = telegramNotifierService,
            githubContributionService = githubContributionService,
            readmeRescueService = readmeRescueService,
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = RestClient.builder()
        )

        val json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 100,
                  "message": {
                    "chat": { "id": 999999999 },
                    "text": "/status"
                  }
                }
              ]
            }
        """.trimIndent()

        val count = service.processUpdatesJson(json)

        assertEquals(0, count)
        assertEquals(100L, service.lastUpdateId)
        verifyNoInteractions(telegramNotifierService)
    }

    @Test
    fun `processes status command for active account`() {
        `when`(githubContributionService.getYearlyStats("tok_main")).thenReturn(
            StreakStatsResponse(
                login = "octocat",
                totalThisYear = 50,
                currentStreak = 5,
                longestStreak = 10,
                todayCount = 2,
                days = emptyList()
            )
        )

        val service = TelegramCommandService(
            botToken = "token",
            authorizedChatId = authorizedChatId,
            timezoneStr = "Asia/Kolkata",
            telegramNotifierService = telegramNotifierService,
            githubContributionService = githubContributionService,
            readmeRescueService = readmeRescueService,
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = RestClient.builder()
        )

        val json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 101,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "/status"
                  }
                }
              ]
            }
        """.trimIndent()

        val count = service.processUpdatesJson(json)

        assertEquals(1, count)
        assertEquals(101L, service.lastUpdateId)
        verify(telegramNotifierService).send(contains("StreakKeeper Status"))
        verify(telegramNotifierService).send(contains("octocat"))
    }

    @Test
    fun `processes accounts command listing all accounts`() {
        `when`(githubContributionService.getTodayContributions("tok_main")).thenReturn(
            TodayContributionResponse("octocat", "2026-09-03", 2, true)
        )
        `when`(githubContributionService.getTodayContributions("tok_second")).thenReturn(
            TodayContributionResponse("secondcat", "2026-09-03", 0, false)
        )

        val service = TelegramCommandService(
            botToken = "token",
            authorizedChatId = authorizedChatId,
            timezoneStr = "Asia/Kolkata",
            telegramNotifierService = telegramNotifierService,
            githubContributionService = githubContributionService,
            readmeRescueService = readmeRescueService,
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = RestClient.builder()
        )

        val json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 102,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "/accounts"
                  }
                }
              ]
            }
        """.trimIndent()

        val count = service.processUpdatesJson(json)

        assertEquals(1, count)
        verify(telegramNotifierService).send(contains("StreakKeeper Accounts"))
        verify(telegramNotifierService).send(contains("ACTIVE"))
        verify(telegramNotifierService).send(contains("main"))
        verify(telegramNotifierService).send(contains("second"))
    }

    @Test
    fun `processes account switch command`() {
        `when`(githubContributionService.validateTokenAndGetLogin("tok_second")).thenReturn("secondcat")

        val service = TelegramCommandService(
            botToken = "token",
            authorizedChatId = authorizedChatId,
            timezoneStr = "Asia/Kolkata",
            telegramNotifierService = telegramNotifierService,
            githubContributionService = githubContributionService,
            readmeRescueService = readmeRescueService,
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = RestClient.builder()
        )

        val json = """
            {
              "ok": true,
              "result": [
                {
                  "update_id": 103,
                  "message": {
                    "chat": { "id": 123456789 },
                    "text": "/account second"
                  }
                }
              ]
            }
        """.trimIndent()

        val count = service.processUpdatesJson(json)

        assertEquals(1, count)
        assertEquals("second", accountManagerService.activeAccountKey)
        verify(telegramNotifierService).send(contains("Switched to 'second' ➔ @secondcat"))
    }
}
