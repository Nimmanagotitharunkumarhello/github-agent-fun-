package com.streakkeeper.controller

import com.streakkeeper.service.GithubContributionService
import com.streakkeeper.service.StreakStatsResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StatsController(
    private val githubContributionService: GithubContributionService
) {

    @GetMapping("/stats")
    fun getStats(): StreakStatsResponse {
        return githubContributionService.getYearlyStats()
    }
}
