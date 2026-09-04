package com.streakkeeper.service

import com.streakkeeper.exception.TelegramException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class TelegramNotifierService(
    @Value("\${streakkeeper.telegram.bot-token:}") private val botToken: String,
    @Value("\${streakkeeper.telegram.chat-id:}") private val chatId: String,
    restClientBuilder: RestClient.Builder
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val restClient: RestClient = restClientBuilder
        .baseUrl("https://api.telegram.org")
        .defaultHeader("User-Agent", "StreakKeeper-App")
        .build()

    fun send(message: String) {
        if (botToken.isBlank()) {
            throw TelegramException("TELEGRAM_BOT_TOKEN environment variable is not configured.")
        }
        if (chatId.isBlank()) {
            throw TelegramException("TELEGRAM_CHAT_ID environment variable is not configured.")
        }

        val payload = mapOf(
            "chat_id" to chatId,
            "text" to message
        )

        try {
            restClient.post()
                .uri("/bot$botToken/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, response ->
                    val status = response.statusCode.value()
                    val body = response.body.bufferedReader().use { it.readText() }
                    logger.error("Telegram API error response: HTTP {} - {}", status, body)
                    throw TelegramException("Telegram API error (HTTP $status): $body")
                }
                .toBodilessEntity()

            logger.info("Successfully sent Telegram notification to chat_id {}", chatId)
        } catch (ex: TelegramException) {
            throw ex
        } catch (ex: Exception) {
            logger.error("Failed to deliver Telegram notification", ex)
            throw TelegramException("Failed to send Telegram message: ${ex.message}", ex)
        }
    }

    fun sendStreakAlert(alertMessage: String) {
        send("🔥 [StreakKeeper Alert]\n$alertMessage")
    }

    fun sendAutoAction(actionMessage: String) {
        send("🤖 [StreakKeeper Auto-Action]\n$actionMessage")
    }
}
