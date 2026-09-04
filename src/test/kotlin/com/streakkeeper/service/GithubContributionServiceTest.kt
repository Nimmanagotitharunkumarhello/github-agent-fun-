package com.streakkeeper.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.streakkeeper.config.AccountConfig
import com.streakkeeper.config.StreakKeeperProperties
import com.streakkeeper.exception.GitHubAuthException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GithubContributionServiceTest {

    private val objectMapper = ObjectMapper()

    private fun createProperties(token: String = ""): StreakKeeperProperties {
        return StreakKeeperProperties(
            accounts = mutableMapOf("main" to AccountConfig(token = token))
        )
    }

    @Test
    fun `throws GitHubAuthException when token is blank`() {
        val restClientBuilder = RestClient.builder()
        val service = GithubContributionService(
            streakKeeperProperties = createProperties(""),
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        assertThrows<GitHubAuthException> {
            service.getTodayContributions()
        }
    }

    @Test
    fun `validates token and gets login`() {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        val mockJson = """
            {
              "data": {
                "viewer": {
                  "login": "octocat"
                }
              }
            }
        """.trimIndent()

        server.expect(requestTo("https://api.github.com/graphql"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer valid_token"))
            .andRespond(withSuccess(mockJson, MediaType.APPLICATION_JSON))

        val service = GithubContributionService(
            streakKeeperProperties = createProperties("valid_token"),
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val login = service.validateTokenAndGetLogin("valid_token")
        assertEquals("octocat", login)
        server.verify()
    }

    @Test
    fun `parses successful GraphQL response correctly with contributions`() {
        val todayStr = LocalDate.now(ZoneOffset.UTC).toString()
        val mockJson = """
            {
              "data": {
                "viewer": {
                  "login": "octocat",
                  "contributionsCollection": {
                    "contributionCalendar": {
                      "totalContributions": 3,
                      "weeks": [
                        {
                          "contributionDays": [
                            {
                              "date": "$todayStr",
                              "contributionCount": 3
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.github.com/graphql"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer dummy_token"))
            .andRespond(withSuccess(mockJson, MediaType.APPLICATION_JSON))

        val service = GithubContributionService(
            streakKeeperProperties = createProperties("dummy_token"),
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val result = service.getTodayContributions()

        assertEquals("octocat", result.login)
        assertEquals(todayStr, result.date)
        assertEquals(3, result.count)
        assertTrue(result.safe)
        server.verify()
    }

    @Test
    fun `parses yearly stats and calculates current and longest streaks correctly`() {
        val today = LocalDate.now(ZoneOffset.UTC)
        val d0 = today.toString()
        val d1 = today.minusDays(1).toString()
        val d2 = today.minusDays(2).toString()
        val d3 = today.minusDays(3).toString()
        val d4 = today.minusDays(4).toString()

        val mockJson = """
            {
              "data": {
                "viewer": {
                  "login": "octocat",
                  "contributionsCollection": {
                    "contributionCalendar": {
                      "totalContributions": 10,
                      "weeks": [
                        {
                          "contributionDays": [
                            { "date": "$d4", "contributionCount": 5, "color": "#216e39" },
                            { "date": "$d3", "contributionCount": 0, "color": "#ebedf0" },
                            { "date": "$d2", "contributionCount": 2, "color": "#9be9a8" },
                            { "date": "$d1", "contributionCount": 2, "color": "#9be9a8" },
                            { "date": "$d0", "contributionCount": 1, "color": "#9be9a8" }
                          ]
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.github.com/graphql"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(mockJson, MediaType.APPLICATION_JSON))

        val service = GithubContributionService(
            streakKeeperProperties = createProperties("dummy_token"),
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val stats = service.getYearlyStats()

        assertEquals("octocat", stats.login)
        assertEquals(10, stats.totalThisYear)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        assertEquals(1, stats.todayCount)
        assertEquals(5, stats.days.size)
        server.verify()
    }

    @Test
    fun `handles 401 unauthorized from GitHub`() {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.github.com/graphql"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("""{"message":"Bad credentials"}"""))

        val service = GithubContributionService(
            streakKeeperProperties = createProperties("bad_token"),
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val ex = assertThrows<GitHubAuthException> {
            service.getTodayContributions()
        }
        assertTrue(ex.message!!.contains("401"))
        server.verify()
    }
}
