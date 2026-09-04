package com.streakkeeper.controller

import com.streakkeeper.service.GithubContributionService
import com.streakkeeper.service.TodayContributionResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/contributions")
class ContributionController(
    private val githubContributionService: GithubContributionService
) {

    @GetMapping("/today")
    fun getTodayContributions(): TodayContributionResponse {
        return githubContributionService.getTodayContributions()
    }
}
