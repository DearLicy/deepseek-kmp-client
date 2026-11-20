package com.deepseek.client.platform

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun platformNowMillis(): Long = Clock.System.now().toEpochMilliseconds()
