package com.streakkeeper.service

import com.streakkeeper.config.AccountConfig
import com.streakkeeper.config.StreakKeeperProperties
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class AccountManagerService(
    private val streakKeeperProperties: StreakKeeperProperties,
    private val githubContributionService: GithubContributionService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    var activeAccountKey: String = "main"
        internal set

    val cachedLogins = ConcurrentHashMap<String, String>()

    @PostConstruct
    fun init() {
        validateAllAccounts()
    }

    fun validateAllAccounts() {
        val configured = getAllAccounts()
        if (configured.isEmpty()) {
            logger.warn("⚠️ No GitHub accounts configured with valid tokens.")
            return
        }

        var foundActive = false
        for ((key, account) in configured) {
            if (account.token.isNotBlank()) {
                try {
                    val login = githubContributionService.validateTokenAndGetLogin(account.token)
                    cachedLogins[key] = login
                    logger.info("Account '{}' = @{} ✅", key, login)
                    if (!foundActive) {
                        activeAccountKey = key
                        foundActive = true
                    }
                } catch (e: Exception) {
                    logger.error("Account '{}' failed ❌: {}", key, e.message)
                }
            } else {
                logger.warn("Account '{}' has empty token ❌", key)
            }
        }
    }

    fun getAllAccounts(): Map<String, AccountConfig> {
        val map = mutableMapOf<String, AccountConfig>()
        // Load from streakkeeper.accounts
        for ((k, v) in streakKeeperProperties.accounts) {
            if (v.token.isNotBlank()) {
                map[k] = v
            }
        }
        // Fallback for legacy streakkeeper.github.token
        if (map.isEmpty() && streakKeeperProperties.github.token.isNotBlank()) {
            map["main"] = AccountConfig(
                token = streakKeeperProperties.github.token,
                rescueRepo = streakKeeperProperties.github.rescueRepo,
                owner = streakKeeperProperties.github.owner
            )
        }
        return map
    }

    fun getActiveAccount(): AccountConfig {
        val all = getAllAccounts()
        return all[activeAccountKey]
            ?: all.values.firstOrNull()
            ?: AccountConfig(token = "")
    }

    fun getActiveAccountLogin(): String {
        return cachedLogins[activeAccountKey] ?: run {
            val token = getActiveAccount().token
            if (token.isNotBlank()) {
                try {
                    val login = githubContributionService.validateTokenAndGetLogin(token)
                    cachedLogins[activeAccountKey] = login
                    login
                } catch (e: Exception) {
                    "unknown"
                }
            } else {
                "unknown"
            }
        }
    }

    fun switchActiveAccount(name: String): String {
        val all = getAllAccounts()
        val account = all[name] ?: throw IllegalArgumentException("Account '$name' not found in configuration.")
        if (account.token.isBlank()) {
            throw IllegalArgumentException("Account '$name' has no token configured.")
        }

        val login = try {
            githubContributionService.validateTokenAndGetLogin(account.token)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to validate account '$name': ${e.message}", e)
        }

        activeAccountKey = name
        cachedLogins[name] = login
        logger.info("Switched active account to '{}' (@{})", name, login)
        return login
    }
}
