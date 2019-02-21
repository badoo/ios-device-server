package com.badoo.automation.deviceserver.data

import com.badoo.automation.deviceserver.JsonMapper
import org.junit.Assert.*
import org.junit.Test

class PermissionSetTest {
    private fun fromJson(json: String): PermissionSet {
        return JsonMapper().fromJson(json)
    }

    @Test
    fun fromJsonParsesPermissionSet() {
        val json = """
            {
                "location": "always",
                "calendar": "yes",
                "homekit": "unset"
            }
            """
        val actual = fromJson(json)

        val expected = PermissionSet()

        expected[PermissionType.Calendar] = PermissionAllowed.Yes
        expected[PermissionType.Location] = PermissionAllowed.Always
        expected[PermissionType.HomeKit] = PermissionAllowed.Unset

        assertEquals(expected, actual)
    }
}
