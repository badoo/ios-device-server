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
                "location": "revoke",
                "calendar": "grant",
                "reminders": "reset"
            }
            """
        val actual = fromJson(json)

        val expected = PermissionSet()

        expected[PermissionType.Calendar] = PermissionAllowed.Grant
        expected[PermissionType.Location] = PermissionAllowed.Revoke
        expected[PermissionType.Reminders] = PermissionAllowed.Reset

        assertEquals(expected, actual)
    }
}
