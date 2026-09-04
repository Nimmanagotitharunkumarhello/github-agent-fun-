package com.streakkeeper

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class StreakKeeperApplication

fun main(args: Array<String>) {
    runApplication<StreakKeeperApplication>(*args)
}
