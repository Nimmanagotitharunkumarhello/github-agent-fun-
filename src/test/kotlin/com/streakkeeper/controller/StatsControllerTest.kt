package com.streakkeeper.controller

import com.streakkeeper.exception.GlobalExceptionHandler
import com.streakkeeper.service.ContributionDay
import com.streakkeeper.service.GithubContributionService
import com.streakkeeper.service.StreakStatsResponse
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(StatsController::class)
@Import(GlobalExceptionHandler::class)
class StatsControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var githubContributionService: GithubContributionService

    @Test
    fun `get stats returns streak stats and day array`() {
        given(githubContributionService.getYearlyStats()).willReturn(
            StreakStatsResponse(
                login = "octocat",
                totalThisYear = 42,
                currentStreak = 7,
                longestStreak = 15,
                todayCount = 1,
                days = listOf(
                    ContributionDay("2026-09-02", 2, "#9be9a8"),
                    ContributionDay("2026-09-03", 1, "#9be9a8")
                )
            )
        )

        mockMvc.get("/stats")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.login") { value("octocat") }
                jsonPath("$.totalThisYear") { value(42) }
                jsonPath("$.currentStreak") { value(7) }
                jsonPath("$.longestStreak") { value(15) }
                jsonPath("$.todayCount") { value(1) }
                jsonPath("$.days[0].date") { value("2026-09-02") }
            }
    }
}
