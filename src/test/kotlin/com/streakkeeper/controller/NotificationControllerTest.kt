package com.streakkeeper.controller

import com.streakkeeper.exception.GlobalExceptionHandler
import com.streakkeeper.service.TelegramNotifierService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(NotificationController::class)
@Import(GlobalExceptionHandler::class)
class NotificationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean
    private lateinit var telegramNotifierService: TelegramNotifierService

    @Test
    fun `post notify sends message and returns status ok`() {
        mockMvc.post("/notify") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message": "Test notification message"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("sent") }
            jsonPath("$.message") { value("Test notification message") }
        }

        verify(telegramNotifierService).send("Test notification message")
    }
}
