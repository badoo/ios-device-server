package com.badoo.automation.deviceserver.host.management

class RuntimeVersion(runtime: String) {
    var name: String
        private set

    var fragments: List<String>
        private set

    init {
        val (a, b) = runtime.split(' ', ignoreCase = false, limit = 2)
        name = a
        fragments = b.split('.').toList()
    }

}