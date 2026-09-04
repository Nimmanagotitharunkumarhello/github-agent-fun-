package com.streakkeeper.controller

import com.streakkeeper.service.TelegramNotifierService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class NotifyRequest(
    val message: String = ""
)

@RestController
class NotificationController(
    private val telegramNotifierService: TelegramNotifierService
) {

    @PostMapping("/notify")
    fun notify(@RequestBody request: NotifyRequest): ResponseEntity<Map<String, Any>> {
        val msg = if (request.message.isNotBlank()) request.message else "Test notification from StreakKeeper! 🚀"
        telegramNotifierService.send(msg)
        return ResponseEntity.ok(
            mapOf(
                "status" to "sent",
                "message" to msg
            )
        )
    }
}
