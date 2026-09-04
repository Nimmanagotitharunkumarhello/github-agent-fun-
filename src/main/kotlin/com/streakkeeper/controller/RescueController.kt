package com.streakkeeper.controller

import com.streakkeeper.service.ReadmeRescueService
import com.streakkeeper.service.RescueResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

@RestController
@RequestMapping("/rescue")
class RescueController(
    private val readmeRescueService: ReadmeRescueService,
    @Value("\${streakkeeper.timezone:Asia/Kolkata}") private val timezoneStr: String
) {

    @PostMapping("/run-now")
    fun triggerRescueNow(@RequestParam(required = false) account: String?): RescueResult {
        val zoneId = try { ZoneId.of(timezoneStr) } catch (e: Exception) { ZoneId.of("Asia/Kolkata") }
        val todayStr = LocalDate.now(zoneId).toString()
        return readmeRescueService.rescueStreak(account, todayStr)
    }

    @GetMapping("/run-now")
    fun triggerRescueNowGet(@RequestParam(required = false) account: String?): RescueResult {
        return triggerRescueNow(account)
    }
}
