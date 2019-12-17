package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class DesiredCapabilities(
        val udid: String?,
        val model: String?,
        val os: String?,
        val headless: Boolean = true,
        val existing: Boolean = true,
        val arch: String? = null,

        @JsonProperty("use_wda")
        val useWda: Boolean = true
) {
        override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as DesiredCapabilities

                if (udid != other.udid) return false
                if (model != other.model) return false
                if (os != other.os) return false
                if (arch != other.arch) return false
                if (useWda != other.useWda) return false

                return true
        }

        override fun hashCode(): Int {
                var result = udid?.hashCode() ?: 0
                result = 31 * result + (model?.hashCode() ?: 0)
                result = 31 * result + (os?.hashCode() ?: 0)
                result = 31 * result + (arch?.hashCode() ?: 0)
                result = 31 * result + useWda.hashCode()
                return result
        }
}
