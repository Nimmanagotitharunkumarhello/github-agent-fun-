package com.streakkeeper.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.streakkeeper.config.AccountConfig
import com.streakkeeper.exception.GitHubApiException
import com.streakkeeper.exception.GitHubAuthException
import com.streakkeeper.exception.GitHubConflictException
import com.streakkeeper.exception.GitHubRateLimitException
import com.streakkeeper.exception.GitHubRepoNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.Base64

data class RescueResult(
    val success: Boolean,
    val accountKey: String,
    val owner: String,
    val repo: String,
    val commitSha: String?,
    val message: String
)

@Service
class ReadmeRescueService(
    private val accountManagerService: AccountManagerService,
    private val objectMapper: ObjectMapper,
    restClientBuilder: RestClient.Builder
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = restClientBuilder
        .baseUrl("https://api.github.com")
        .defaultHeader("User-Agent", "StreakKeeper-App")
        .defaultHeader("Accept", "application/vnd.github+json")
        .build()

    fun rescueStreak(accountKey: String? = null, todayDateStr: String): RescueResult {
        val targetKey = accountKey ?: accountManagerService.activeAccountKey
        val account = accountManagerService.getAllAccounts()[targetKey]
            ?: accountManagerService.getActiveAccount()

        if (account.token.isBlank()) {
            throw GitHubAuthException("Token for account '$targetKey' is not configured.")
        }

        val owner = getOwnerLogin(account)
        val rescueRepo = account.rescueRepo.ifBlank { "daily-log" }
        logger.info("Starting README rescue for account '{}' on repository: {}/{}", targetKey, owner, rescueRepo)

        val commitSha = commitDailyLog(account.token, owner, rescueRepo, todayDateStr, isRetry = false)
        return RescueResult(
            success = true,
            accountKey = targetKey,
            owner = owner,
            repo = rescueRepo,
            commitSha = commitSha,
            message = "Successfully committed daily log entry to $owner/$rescueRepo/README.md for account '$targetKey'"
        )
    }

    private fun getOwnerLogin(account: AccountConfig): String {
        if (account.owner.isNotBlank()) {
            return account.owner
        }

        val responseBody = try {
            restClient.get()
                .uri("/user")
                .header("Authorization", "Bearer ${account.token}")
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, response ->
                    val status = response.statusCode.value()
                    val body = response.body.bufferedReader().use { it.readText() }
                    if (status == 401) throw GitHubAuthException("Invalid GITHUB_TOKEN for account (HTTP 401)")
                    throw GitHubApiException("Failed to fetch authenticated user (HTTP $status): $body")
                }
                .body(String::class.java)
        } catch (ex: Exception) {
            if (ex is GitHubAuthException || ex is GitHubApiException) throw ex
            throw GitHubApiException("Failed to determine GitHub user: ${ex.message}", ex)
        } ?: throw GitHubApiException("Empty response when resolving GitHub user")

        val root: JsonNode = objectMapper.readTree(responseBody)
        val login = root.path("login").asText()
        if (login.isBlank()) {
            throw GitHubApiException("Could not extract login username from /user response")
        }
        return login
    }

    private fun commitDailyLog(token: String, owner: String, repo: String, todayDateStr: String, isRetry: Boolean): String {
        val readmePath = "/repos/$owner/$repo/contents/README.md"

        var currentSha: String? = null
        var currentDecoded = "# Daily Log\n"

        // 1. Fetch current README.md content and SHA (or handle fresh creation if 404)
        var fileExists = true
        val getResponse = try {
            restClient.get()
                .uri(readmePath)
                .header("Authorization", "Bearer $token")
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                    val status = response.statusCode.value()
                    val body = response.body.bufferedReader().use { it.readText() }
                    if (status == 404) {
                        fileExists = false
                    } else if (status == 401) {
                        throw GitHubAuthException("Invalid or expired GITHUB_TOKEN (HTTP 401).")
                    } else if (status == 403) {
                        if (body.contains("rate limit", ignoreCase = true)) {
                            throw GitHubRateLimitException("GitHub API rate limit exceeded (HTTP 403)")
                        }
                        throw GitHubAuthException("Access forbidden to '$owner/$repo' (HTTP 403). Make sure token has 'repo' scope.")
                    } else {
                        throw GitHubApiException("GitHub client error (HTTP $status): $body")
                    }
                }
                .body(String::class.java)
        } catch (ex: Exception) {
            if (ex is GitHubAuthException || ex is GitHubApiException || ex is GitHubRateLimitException) {
                throw ex
            }
            null
        }

        if (fileExists && getResponse != null) {
            val rootNode = objectMapper.readTree(getResponse)
            currentSha = rootNode.path("sha").asText()
            val rawEncodedContent = rootNode.path("content").asText("")
            try {
                val cleanBase64 = rawEncodedContent.replace("\n", "").replace("\r", "")
                currentDecoded = String(Base64.getMimeDecoder().decode(cleanBase64), Charsets.UTF_8)
            } catch (e: Exception) {
                logger.warn("Could not decode existing README content, initializing fresh. Cause: {}", e.message)
                currentDecoded = "# Daily Log\n"
            }
        } else {
            logger.info("README.md not found in {}/{}. Will initialize and create it.", owner, repo)
        }

        // 2. Append new daily log entry
        val entry = "\n### $todayDateStr — Day kept alive 🔥\nAuto-log by StreakKeeper.\n"
        val updatedContent = currentDecoded + entry
        val base64Updated = Base64.getEncoder().encodeToString(updatedContent.toByteArray(Charsets.UTF_8))
        val commitMessage = "docs: daily log $todayDateStr"

        val putPayload = mutableMapOf<String, Any>(
            "message" to commitMessage,
            "content" to base64Updated
        )
        if (currentSha != null && currentSha.isNotBlank()) {
            putPayload["sha"] = currentSha
        }

        // 3. PUT updated content directly to default branch
        val putResponse = try {
            restClient.put()
                .uri(readmePath)
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(putPayload)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                    val status = response.statusCode.value()
                    val body = response.body.bufferedReader().use { it.readText() }
                    if (status == 409) {
                        if (!isRetry) {
                            logger.warn("SHA conflict (409) committing to {}/{}. Retrying once...", owner, repo)
                            throw GitHubConflictException("SHA conflict during commit")
                        } else {
                            throw GitHubConflictException("Persistent SHA conflict (409) for $owner/$repo/README.md after retry.")
                        }
                    }
                    if (status == 404) {
                        throw GitHubRepoNotFoundException("Repository '$owner/$repo' not found (HTTP 404). Please verify repository name.")
                    }
                    throw GitHubApiException("GitHub error while committing (HTTP $status): $body")
                }
                .body(String::class.java)
        } catch (ex: GitHubConflictException) {
            if (!isRetry) {
                return commitDailyLog(token, owner, repo, todayDateStr, isRetry = true)
            }
            throw ex
        } catch (ex: Exception) {
            if (ex is GitHubRepoNotFoundException || ex is GitHubAuthException || ex is GitHubApiException || ex is GitHubRateLimitException) {
                throw ex
            }
            throw GitHubApiException("Failed to commit README.md update: ${ex.message}", ex)
        } ?: throw GitHubApiException("Empty response after committing README.md")

        val putResultNode = objectMapper.readTree(putResponse)
        val newSha = putResultNode.path("commit").path("sha").asText(currentSha ?: "created")
        logger.info("Successfully pushed rescue commit to {}/{} [SHA: {}]", owner, repo, newSha)
        return newSha
    }
}
