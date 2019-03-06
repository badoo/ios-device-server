package com.badoo.automation.deviceserver.host.management

class RuntimeVersion(runtime: String) {
    var name: String
        private set

    var fragments: List<String>
        private set

    init {
        val parts = runtime.split(' ', ignoreCase = false, limit = 2)

        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid runtime '$runtime'. Runtime is expected to contain name and version specifier separated by space, for example, 'iOS 11'.")
        }

        val (a, b) = parts
        name = a
        fragments = b.split('.').toList()
    }

}