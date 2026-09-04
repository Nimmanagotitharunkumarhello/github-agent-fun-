package com.streakkeeper.controller

import com.streakkeeper.exception.GlobalExceptionHandler
import com.streakkeeper.scheduler.StreakCheckResult
import com.streakkeeper.scheduler.StreakScheduler
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(SchedulerController::class)
@Import(GlobalExceptionHandler::class)
class SchedulerControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var streakScheduler: StreakScheduler

    @Test
    fun `manual run-now returns check result`() {
        given(streakScheduler.runCheck()).willReturn(
            StreakCheckResult(
                accountKey = "main",
                username = "octocat",
                timestamp = "2026-09-03T21:05:00+05:30",
                localTime = "21:05:00",
                timezone = "Asia/Kolkata",
                count = 0,
                safe = false,
                actionTaken = "ALERTED",
                details = "Telegram notification sent"
            )
        )

        mockMvc.post("/scheduler/run-now")
            .andExpect {
                status { isOk() }
                jsonPath("$.accountKey") { value("main") }
                jsonPath("$.username") { value("octocat") }
                jsonPath("$.actionTaken") { value("ALERTED") }
                jsonPath("$.count") { value(0) }
                jsonPath("$.safe") { value(false) }
            }
    }
}
