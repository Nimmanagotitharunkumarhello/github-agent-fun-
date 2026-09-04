package com.streakkeeper.service

import com.streakkeeper.config.AccountConfig
import com.streakkeeper.config.StreakKeeperProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import kotlin.test.assertEquals

class AccountManagerServiceTest {

    @Test
    fun `validates and discovers multiple accounts`() {
        val githubService = mock(GithubContributionService::class.java)
        `when`(githubService.validateTokenAndGetLogin("tok_main")).thenReturn("user_one")
        `when`(githubService.validateTokenAndGetLogin("tok_second")).thenReturn("user_two")

        val props = StreakKeeperProperties(
            accounts = mutableMapOf(
                "main" to AccountConfig(token = "tok_main", rescueRepo = "daily-log"),
                "second" to AccountConfig(token = "tok_second", rescueRepo = "daily-log")
            )
        )

        val service = AccountManagerService(props, githubService)
        service.validateAllAccounts()

        assertEquals("main", service.activeAccountKey)
        assertEquals("user_one", service.cachedLogins["main"])
        assertEquals("user_two", service.cachedLogins["second"])

        val switchedLogin = service.switchActiveAccount("second")
        assertEquals("user_two", switchedLogin)
        assertEquals("second", service.activeAccountKey)
    }

    @Test
    fun `throws exception when switching to non-existent account`() {
        val githubService = mock(GithubContributionService::class.java)
        val props = StreakKeeperProperties(
            accounts = mutableMapOf(
                "main" to AccountConfig(token = "tok_main", rescueRepo = "daily-log")
            )
        )

        val service = AccountManagerService(props, githubService)

        assertThrows<IllegalArgumentException> {
            service.switchActiveAccount("unknown_account")
        }
    }
}
