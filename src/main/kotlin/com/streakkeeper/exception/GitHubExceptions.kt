package com.streakkeeper.exception

class GitHubAuthException(message: String) : RuntimeException(message)

class GitHubRateLimitException(message: String) : RuntimeException(message)

class GitHubRepoNotFoundException(message: String) : RuntimeException(message)

class GitHubConflictException(message: String) : RuntimeException(message)

class GitHubApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
