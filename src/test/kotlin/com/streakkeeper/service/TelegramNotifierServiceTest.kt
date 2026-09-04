package com.streakkeeper.service

import com.streakkeeper.exception.TelegramException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import kotlin.test.assertTrue

class TelegramNotifierServiceTest {

    @Test
    fun `throws TelegramException when bot token or chat id is blank`() {
        val restClientBuilder = RestClient.builder()
        val serviceNoToken = TelegramNotifierService(botToken = "", chatId = "123456", restClientBuilder = restClientBuilder)
        assertThrows<TelegramException> { serviceNoToken.send("test") }

        val serviceNoChat = TelegramNotifierService(botToken = "my_token", chatId = "", restClientBuilder = restClientBuilder)
        assertThrows<TelegramException> { serviceNoChat.send("test") }
    }

    @Test
    fun `sends telegram message successfully`() {
        val botToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
        val chatId = "987654321"

        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.telegram.org/bot$botToken/sendMessage"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.chat_id").value(chatId))
            .andExpect(jsonPath("$.text").value("Hello Telegram"))
            .andRespond(withSuccess("""{"ok":true,"result":{"message_id":1}}""", MediaType.APPLICATION_JSON))

        val service = TelegramNotifierService(
            botToken = botToken,
            chatId = chatId,
            restClientBuilder = restClientBuilder
        )

        service.send("Hello Telegram")
        server.verify()
    }

    @Test
    fun `sends emoji streak alert and auto action`() {
        val botToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
        val chatId = "987654321"

        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.telegram.org/bot$botToken/sendMessage"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.text").value("🔥 [StreakKeeper Alert]\nNo contributions yet!"))
            .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

        server.expect(requestTo("https://api.telegram.org/bot$botToken/sendMessage"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.text").value("🤖 [StreakKeeper Auto-Action]\nRescued streak!"))
            .andRespond(withSuccess("""{"ok":true}""", MediaType.APPLICATION_JSON))

        val service = TelegramNotifierService(
            botToken = botToken,
            chatId = chatId,
            restClientBuilder = restClientBuilder
        )

        service.sendStreakAlert("No contributions yet!")
        service.sendAutoAction("Rescued streak!")
        server.verify()
    }

    @Test
    fun `handles Telegram 400 Bad Request error`() {
        val botToken = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"
        val chatId = "987654321"

        val restClientBuilder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(restClientBuilder).build()

        server.expect(requestTo("https://api.telegram.org/bot$botToken/sendMessage"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"ok":false,"description":"Bad Request: chat not found"}"""))

        val service = TelegramNotifierService(
            botToken = botToken,
            chatId = chatId,
            restClientBuilder = restClientBuilder
        )

        val ex = assertThrows<TelegramException> {
            service.send("Test")
        }
        assertTrue(ex.message!!.contains("chat not found") || ex.message!!.contains("400"))
        server.verify()
    }
}
