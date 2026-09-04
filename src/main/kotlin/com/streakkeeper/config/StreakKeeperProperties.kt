package com.streakkeeper.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

data class AccountConfig(
    var token: String = "",
    var rescueRepo: String = "daily-log",
    var owner: String = ""
)

@ConfigurationProperties(prefix = "streakkeeper")
data class StreakKeeperProperties(
    var timezone: String = "Asia/Kolkata",
    var alertTime: String = "21:00",
    var rescueTime: String = "22:30",
    var accounts: MutableMap<String, AccountConfig> = mutableMapOf(),
    var github: GitHubLegacyProperties = GitHubLegacyProperties(),
    var telegram: TelegramProperties = TelegramProperties()
)

data class GitHubLegacyProperties(
    var token: String = "",
    var owner: String = "",
    var rescueRepo: String = "daily-log"
)

data class TelegramProperties(
    var botToken: String = "",
    var chatId: String = "",
    var pollIntervalMs: Long = 3000
)

@Configuration
@EnableConfigurationProperties(StreakKeeperProperties::class)
class PropertiesConfig
