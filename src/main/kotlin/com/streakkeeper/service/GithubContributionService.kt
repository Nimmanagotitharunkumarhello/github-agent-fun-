package com.streakkeeper.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.streakkeeper.config.StreakKeeperProperties
import com.streakkeeper.exception.GitHubApiException
import com.streakkeeper.exception.GitHubAuthException
import com.streakkeeper.exception.GitHubRateLimitException
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class TodayContributionResponse(
    val login: String,
    val date: String,
    val count: Int,
    val safe: Boolean
)

data class ContributionDay(
    val date: String,
    val count: Int,
    val color: String? = null
)

data class StreakStatsResponse(
    val login: String,
    val totalThisYear: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val todayCount: Int,
    val days: List<ContributionDay>
)

@Service
class GithubContributionService(
    private val streakKeeperProperties: StreakKeeperProperties,
    private val objectMapper: ObjectMapper,
    restClientBuilder: RestClient.Builder
) {

    private val restClient: RestClient = restClientBuilder
        .baseUrl("https://api.github.com")
        .defaultHeader("User-Agent", "StreakKeeper-App")
        .defaultHeader("Accept", "application/vnd.github+json")
        .build()

    fun resolveActiveToken(): String {
        val mainAcc = streakKeeperProperties.accounts["main"]
        val tokenFromAcc = mainAcc?.token?.takeIf { it.isNotBlank() }
        val tokenFromLegacy = streakKeeperProperties.github.token.takeIf { it.isNotBlank() }
        return tokenFromAcc ?: tokenFromLegacy ?: ""
    }

    fun validateTokenAndGetLogin(token: String): String {
        if (token.isBlank()) {
            throw GitHubAuthException("Token is blank")
        }

        val graphQlQuery = """
            query {
              viewer {
                login
              }
            }
        """.trimIndent()

        val rawResponse = executeGraphQL(token, mapOf("query" to graphQlQuery))
        val root: JsonNode = objectMapper.readTree(rawResponse)
        checkGraphQLErrors(root)

        val login = root.path("data").path("viewer").path("login").asText("")
        if (login.isBlank()) {
            throw GitHubApiException("Could not extract login from GraphQL viewer")
        }
        return login
    }

    fun getTodayContributions(explicitToken: String? = null): TodayContributionResponse {
        val token = explicitToken ?: resolveActiveToken()
        if (token.isBlank()) {
            throw GitHubAuthException("GITHUB_TOKEN environment variable is not set or is blank. Please configure a valid GitHub Personal Access Token.")
        }

        val nowUtc = Instant.now()
        val todayUtc = nowUtc.atZone(ZoneOffset.UTC).toLocalDate()
        val fromInstant = todayUtc.atStartOfDay(ZoneOffset.UTC).toInstant()

        val graphQlQuery = """
            query(${'$'}from: DateTime!, ${'$'}to: DateTime!) {
              viewer {
                login
                contributionsCollection(from: ${'$'}from, to: ${'$'}to) {
                  contributionCalendar {
                    totalContributions
                    weeks {
                      contributionDays {
                        date
                        contributionCount
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val requestPayload = mapOf(
            "query" to graphQlQuery,
            "variables" to mapOf(
                "from" to fromInstant.toString(),
                "to" to nowUtc.toString()
            )
        )

        val rawResponse = executeGraphQL(token, requestPayload)
        return parseTodayResponse(rawResponse, todayUtc.toString())
    }

    fun getYearlyStats(explicitToken: String? = null): StreakStatsResponse {
        val token = explicitToken ?: resolveActiveToken()
        if (token.isBlank()) {
            throw GitHubAuthException("GITHUB_TOKEN environment variable is not set or is blank. Please configure a valid GitHub Personal Access Token.")
        }

        val nowUtc = Instant.now()
        val fromInstant = nowUtc.minus(365, ChronoUnit.DAYS)

        val graphQlQuery = """
            query(${'$'}from: DateTime!, ${'$'}to: DateTime!) {
              viewer {
                login
                contributionsCollection(from: ${'$'}from, to: ${'$'}to) {
                  contributionCalendar {
                    totalContributions
                    weeks {
                      contributionDays {
                        date
                        contributionCount
                        color
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val requestPayload = mapOf(
            "query" to graphQlQuery,
            "variables" to mapOf(
                "from" to fromInstant.toString(),
                "to" to nowUtc.toString()
            )
        )

        val rawResponse = executeGraphQL(token, requestPayload)
        return parseYearlyStatsResponse(rawResponse)
    }

    private fun executeGraphQL(token: String, requestPayload: Map<String, Any>): String {
        return try {
            restClient.post()
                .uri("/graphql")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestPayload)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                    val status = response.statusCode.value()
                    val body = response.body.bufferedReader().use { it.readText() }
                    if (status == 401) {
                        throw GitHubAuthException("Invalid or expired GITHUB_TOKEN (HTTP 401). Please check your personal access token.")
                    }
                    if (status == 403) {
                        if (body.contains("rate limit", ignoreCase = true)) {
                            throw GitHubRateLimitException("GitHub API rate limit exceeded (HTTP 403): $body")
                        }
                        throw GitHubAuthException("Access forbidden by GitHub API (HTTP 403). Check token scopes: $body")
                    }
                    throw GitHubApiException("GitHub API client error (HTTP $status): $body")
                }
                .onStatus(HttpStatusCode::is5xxServerError) { _, response ->
                    val status = response.statusCode.value()
                    val body = response.body.bufferedReader().use { it.readText() }
                    throw GitHubApiException("GitHub API server error (HTTP $status): $body")
                }
                .body(String::class.java)
        } catch (ex: GitHubAuthException) {
            throw ex
        } catch (ex: GitHubRateLimitException) {
            throw ex
        } catch (ex: GitHubApiException) {
            throw ex
        } catch (ex: Exception) {
            throw GitHubApiException("Failed to communicate with GitHub API: ${ex.message}", ex)
        } ?: throw GitHubApiException("Received empty response from GitHub API")
    }

    private fun parseTodayResponse(jsonString: String, todayDateStr: String): TodayContributionResponse {
        val root: JsonNode = objectMapper.readTree(jsonString)
        checkGraphQLErrors(root)

        val viewerNode = root.path("data").path("viewer")
        val login = viewerNode.path("login").asText("")
        if (login.isBlank()) {
            throw GitHubApiException("Unable to determine GitHub login from response: $jsonString")
        }

        val calendarNode = viewerNode.path("contributionsCollection").path("contributionCalendar")
        val totalContributions = calendarNode.path("totalContributions").asInt(0)

        var dayCount = 0
        var foundDay = false
        val weeks = calendarNode.path("weeks")
        if (weeks.isArray) {
            for (week in weeks) {
                val days = week.path("contributionDays")
                if (days.isArray) {
                    for (day in days) {
                        if (day.path("date").asText() == todayDateStr) {
                            dayCount += day.path("contributionCount").asInt(0)
                            foundDay = true
                        }
                    }
                }
            }
        }

        val count = if (foundDay) dayCount else totalContributions
        val safe = count > 0

        return TodayContributionResponse(
            login = login,
            date = todayDateStr,
            count = count,
            safe = safe
        )
    }

    private fun parseYearlyStatsResponse(jsonString: String): StreakStatsResponse {
        val root: JsonNode = objectMapper.readTree(jsonString)
        checkGraphQLErrors(root)

        val viewerNode = root.path("data").path("viewer")
        val login = viewerNode.path("login").asText("")
        if (login.isBlank()) {
            throw GitHubApiException("Unable to determine GitHub login from response")
        }

        val calendarNode = viewerNode.path("contributionsCollection").path("contributionCalendar")
        val totalThisYear = calendarNode.path("totalContributions").asInt(0)

        val dailyList = mutableListOf<ContributionDay>()
        val weeks = calendarNode.path("weeks")
        if (weeks.isArray) {
            for (week in weeks) {
                val days = week.path("contributionDays")
                if (days.isArray) {
                    for (day in days) {
                        val date = day.path("date").asText()
                        val count = day.path("contributionCount").asInt(0)
                        val color = if (day.has("color")) day.path("color").asText() else null
                        dailyList.add(ContributionDay(date = date, count = count, color = color))
                    }
                }
            }
        }

        val sortedDays = dailyList.sortedBy { it.date }

        var longestStreak = 0
        var tempStreak = 0
        for (d in sortedDays) {
            if (d.count > 0) {
                tempStreak++
                if (tempStreak > longestStreak) {
                    longestStreak = tempStreak
                }
            } else {
                tempStreak = 0
            }
        }

        val todayUtc = LocalDate.now(ZoneOffset.UTC)
        val todayStr = todayUtc.toString()
        val yesterdayStr = todayUtc.minusDays(1).toString()

        val dayMap = sortedDays.associateBy { it.date }
        val todayContribution = dayMap[todayStr]?.count ?: 0
        val yesterdayContribution = dayMap[yesterdayStr]?.count ?: 0

        var currentStreak = 0
        if (todayContribution > 0) {
            var checkDate = todayUtc
            while (true) {
                val c = dayMap[checkDate.toString()]?.count ?: 0
                if (c > 0) {
                    currentStreak++
                    checkDate = checkDate.minusDays(1)
                } else {
                    break
                }
            }
        } else if (yesterdayContribution > 0) {
            var checkDate = todayUtc.minusDays(1)
            while (true) {
                val c = dayMap[checkDate.toString()]?.count ?: 0
                if (c > 0) {
                    currentStreak++
                    checkDate = checkDate.minusDays(1)
                } else {
                    break
                }
            }
        }

        return StreakStatsResponse(
            login = login,
            totalThisYear = totalThisYear,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            todayCount = todayContribution,
            days = sortedDays
        )
    }

    private fun checkGraphQLErrors(root: JsonNode) {
        val errorsNode = root.path("errors")
        if (!errorsNode.isMissingNode && errorsNode.isArray && !errorsNode.isEmpty) {
            val firstErrorMsg = errorsNode[0].path("message").asText("Unknown GraphQL error")
            if (firstErrorMsg.contains("rate limit", ignoreCase = true)) {
                throw GitHubRateLimitException("GitHub GraphQL rate limit exceeded: $firstErrorMsg")
            }
            if (firstErrorMsg.contains("Bad credentials", ignoreCase = true) ||
                firstErrorMsg.contains("Requires authentication", ignoreCase = true)
            ) {
                throw GitHubAuthException("GitHub authentication error: $firstErrorMsg")
            }
            throw GitHubApiException("GitHub GraphQL returned error: $firstErrorMsg")
        }
    }
}
