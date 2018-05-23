package com.badoo.automation.deviceserver.util

import com.badoo.automation.deviceserver.util.HttpCodes.*

data class HttpResult(
        val httpCode: Int,
        val responseBody: String? = null,
        val isSuccess: Boolean = httpCode == OK.code
)