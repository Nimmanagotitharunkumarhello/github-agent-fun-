package com.streakkeeper.controller

import com.streakkeeper.exception.GlobalExceptionHandler
import com.streakkeeper.service.GithubContributionService
import com.streakkeeper.service.TodayContributionResponse
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(ContributionController::class)
@Import(GlobalExceptionHandler::class)
class ContributionControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var githubContributionService: GithubContributionService

    @Test
    fun `get today contributions returns expected response`() {
        given(githubContributionService.getTodayContributions()).willReturn(
            TodayContributionResponse(
                login = "octocat",
                date = "2026-09-03",
                count = 5,
                safe = true
            )
        )

        mockMvc.get("/contributions/today")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.login") { value("octocat") }
                jsonPath("$.date") { value("2026-09-03") }
                jsonPath("$.count") { value(5) }
                jsonPath("$.safe") { value(true) }
            }
    }
}
