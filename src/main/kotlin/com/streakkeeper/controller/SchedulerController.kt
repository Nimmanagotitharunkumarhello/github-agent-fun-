package com.streakkeeper.controller

import com.streakkeeper.scheduler.StreakCheckResult
import com.streakkeeper.scheduler.StreakScheduler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/scheduler")
class SchedulerController(
    private val streakScheduler: StreakScheduler
) {

    @PostMapping("/run-now")
    fun triggerManualCheck(): StreakCheckResult {
        return streakScheduler.runCheck()
    }

    @GetMapping("/run-now")
    fun triggerManualCheckGet(): StreakCheckResult {
        return streakScheduler.runCheck()
    }
}
