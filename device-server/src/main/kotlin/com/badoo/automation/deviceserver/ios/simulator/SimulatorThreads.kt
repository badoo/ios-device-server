package com.badoo.automation.deviceserver.ios.simulator

import kotlinx.coroutines.experimental.ThreadPoolDispatcher
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import java.util.concurrent.ScheduledThreadPoolExecutor

// lots of blocking and nothing memory consuming, so should be ok
val simulatorsThreadPool: ThreadPoolDispatcher = newFixedThreadPoolContext(100, "Simulator_Thread")
val periodicTasksPool = ScheduledThreadPoolExecutor(4)
