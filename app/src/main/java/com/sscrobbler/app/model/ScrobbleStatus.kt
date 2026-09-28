package com.sscrobbler.app.model

enum class ScrobbleStatus {
    Listening,
    Paused,
    Eligible,
    WaitingForEnd,
    Scrobbled,
    Skipped,
    Pending,
    Failed
}
