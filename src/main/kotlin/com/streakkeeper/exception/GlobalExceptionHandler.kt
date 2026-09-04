package com.streakkeeper.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(GitHubAuthException::class)
    fun handleAuthException(ex: GitHubAuthException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            mapOf(
                "error" to "Unauthorized",
                "message" to (ex.message ?: "Authentication failed with GitHub API"),
                "status" to HttpStatus.UNAUTHORIZED.value()
            )
        )
    }

    @ExceptionHandler(GitHubRepoNotFoundException::class)
    fun handleRepoNotFoundException(ex: GitHubRepoNotFoundException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            mapOf(
                "error" to "Repository Not Found",
                "message" to (ex.message ?: "GitHub repository or file not found"),
                "status" to HttpStatus.NOT_FOUND.value()
            )
        )
    }

    @ExceptionHandler(GitHubConflictException::class)
    fun handleConflictException(ex: GitHubConflictException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "Conflict",
                "message" to (ex.message ?: "GitHub SHA conflict when committing file"),
                "status" to HttpStatus.CONFLICT.value()
            )
        )
    }

    @ExceptionHandler(GitHubRateLimitException::class)
    fun handleRateLimitException(ex: GitHubRateLimitException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
            mapOf(
                "error" to "Rate Limit Exceeded",
                "message" to (ex.message ?: "GitHub API rate limit exceeded. Try again later."),
                "status" to HttpStatus.TOO_MANY_REQUESTS.value()
            )
        )
    }

    @ExceptionHandler(GitHubApiException::class)
    fun handleApiException(ex: GitHubApiException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
            mapOf(
                "error" to "GitHub API Error",
                "message" to (ex.message ?: "Error interacting with GitHub API"),
                "status" to HttpStatus.BAD_GATEWAY.value()
            )
        )
    }

    @ExceptionHandler(TelegramException::class)
    fun handleTelegramException(ex: TelegramException): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            mapOf(
                "error" to "Telegram Notification Error",
                "message" to (ex.message ?: "Failed to send Telegram notification"),
                "status" to HttpStatus.BAD_REQUEST.value()
            )
        )
    }
}
