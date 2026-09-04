package com.streakkeeper.controller

import com.streakkeeper.exception.GlobalExceptionHandler
import com.streakkeeper.service.ReadmeRescueService
import com.streakkeeper.service.RescueResult
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(RescueController::class)
@Import(GlobalExceptionHandler::class)
class RescueControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var readmeRescueService: ReadmeRescueService

    @Test
    fun `manual rescue run-now triggers rescue service and returns success`() {
        given(readmeRescueService.rescueStreak(any(), anyString())).willReturn(
            RescueResult(
                success = true,
                accountKey = "main",
                owner = "octocat",
                repo = "daily-log",
                commitSha = "commit_sha_123",
                message = "Committed successfully"
            )
        )

        mockMvc.post("/rescue/run-now")
            .andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.owner") { value("octocat") }
                jsonPath("$.repo") { value("daily-log") }
                jsonPath("$.commitSha") { value("commit_sha_123") }
            }
    }
}
