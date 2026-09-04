package com.streakkeeper.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.streakkeeper.config.AccountConfig
import com.streakkeeper.config.StreakKeeperProperties
import com.streakkeeper.exception.GitHubAuthException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadmeRescueServiceTest {

    private val objectMapper = ObjectMapper()
    private lateinit var accountManagerService: AccountManagerService

    @BeforeEach
    fun setUp() {
        val properties = StreakKeeperProperties(
            accounts = mutableMapOf(
                "main" to AccountConfig(token = "token123", rescueRepo = "daily-log", owner = "testuser"),
                "empty" to AccountConfig(token = "", rescueRepo = "daily-log", owner = "emptyuser")
            )
        )
        val githubService = mock(GithubContributionService::class.java)
        accountManagerService = AccountManagerService(properties, githubService)
    }

    @Test
    fun `throws exception when token is blank`() {
        val restClientBuilder = RestClient.builder()
        val service = ReadmeRescueService(
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        assertThrows<GitHubAuthException> {
            service.rescueStreak("empty", "2026-09-03")
        }
    }

    @Test
    fun `successfully updates existing README and commits new content`() {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        val initialContent = "# Daily Log\nExisting logs here."
        val encodedInitial = Base64.getEncoder().encodeToString(initialContent.toByteArray(Charsets.UTF_8))
        val initialSha = "sha123456"

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer token123"))
            .andRespond(
                withSuccess(
                    """{"sha": "$initialSha", "content": "$encodedInitial"}""",
                    MediaType.APPLICATION_JSON
                )
            )

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(header("Authorization", "Bearer token123"))
            .andExpect(jsonPath("$.message").value("docs: daily log 2026-09-03"))
            .andExpect(jsonPath("$.sha").value(initialSha))
            .andRespond(
                withSuccess(
                    """{"commit": {"sha": "newsha789"}}""",
                    MediaType.APPLICATION_JSON
                )
            )

        val service = ReadmeRescueService(
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val result = service.rescueStreak("main", "2026-09-03")

        assertTrue(result.success)
        assertEquals("testuser", result.owner)
        assertEquals("daily-log", result.repo)
        assertEquals("newsha789", result.commitSha)
        server.verify()
    }

    @Test
    fun `creates fresh README when not found on GET`() {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("""{"message": "Not Found"}"""))

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(jsonPath("$.message").value("docs: daily log 2026-09-03"))
            .andRespond(withSuccess("""{"commit": {"sha": "created_sha_111"}}""", MediaType.APPLICATION_JSON))

        val service = ReadmeRescueService(
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val result = service.rescueStreak("main", "2026-09-03")

        assertTrue(result.success)
        assertEquals("created_sha_111", result.commitSha)
        server.verify()
    }

    @Test
    fun `retries once on 409 SHA conflict and succeeds`() {
        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        val initialContent = "# Daily Log"
        val encodedInitial = Base64.getEncoder().encodeToString(initialContent.toByteArray(Charsets.UTF_8))

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"sha": "sha_old", "content": "$encodedInitial"}""", MediaType.APPLICATION_JSON))

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withStatus(HttpStatus.CONFLICT).body("""{"message": "sha mismatch"}"""))

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"sha": "sha_new", "content": "$encodedInitial"}""", MediaType.APPLICATION_JSON))

        server.expect(requestTo("https://api.github.com/repos/testuser/daily-log/contents/README.md"))
            .andExpect(method(HttpMethod.PUT))
            .andExpect(jsonPath("$.sha").value("sha_new"))
            .andRespond(withSuccess("""{"commit": {"sha": "final_sha_999"}}""", MediaType.APPLICATION_JSON))

        val service = ReadmeRescueService(
            accountManagerService = accountManagerService,
            objectMapper = objectMapper,
            restClientBuilder = restClientBuilder
        )

        val result = service.rescueStreak("main", "2026-09-03")

        assertTrue(result.success)
        assertEquals("final_sha_999", result.commitSha)
        server.verify()
    }
}
