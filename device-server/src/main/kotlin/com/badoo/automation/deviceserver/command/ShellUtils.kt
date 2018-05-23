package com.badoo.automation.deviceserver.command

class ShellUtils {
    companion object {
        private val escapeRegex = Regex("([^A-Za-z0-9_\\-.,:/@\\n])")

        /// Copied from shellwords.rb, line 123
        fun escape(s: String): String {
            // An empty argument will be skipped, so return empty quotes.
            if (s.isEmpty()) {
                return "''"
            }

            // Treat multibyte characters as is.  It is caller's responsibility
            // to encode the string in the right encoding for the shell
            // environment.

            return s.replace(escapeRegex, { "\\" + it.value })
                // A LF cannot be escaped with a backslash because a backslash + LF
                // combo is regarded as line continuation and simply ignored.
                .replace("\n", "'\n'")
        }
    }
}
