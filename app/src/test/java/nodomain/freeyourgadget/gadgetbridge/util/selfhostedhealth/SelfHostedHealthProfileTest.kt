/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** What the "About you" screen contributes to an upload, and what it must not. */
class SelfHostedHealthProfileTest {

    private val today: LocalDate = LocalDate.of(2026, 9, 27)

    @Test
    fun `set fields go on the wire and the age is measured from the birthday`() {
        val profile = SelfHostedHealthProfile.build(
            gender = "1",
            birthday = "1990-05-01",
            heightCm = "175",
            weightKg = "70",
            today = today
        )!!

        assertEquals(175, profile.getInt("height_cm"))
        assertEquals(70, profile.getInt("weight_kg"))
        assertEquals("male", profile.getString("gender"))
        assertEquals("1990-05-01", profile.getString("birthday"))
        assertEquals(36, profile.getInt("age"))
    }

    /** The whole point of reading the raw preferences: an unfilled field stays absent. */
    @Test
    fun `unset fields are omitted instead of taking ActivityUser's defaults`() {
        val profile = SelfHostedHealthProfile.build(
            gender = null,
            birthday = null,
            heightCm = "175",
            weightKg = null,
            today = today
        )!!

        assertEquals(listOf("height_cm"), profile.keys().asSequence().toList())
    }

    @Test
    fun `nothing set means nothing to send`() {
        assertNull(SelfHostedHealthProfile.build(null, null, "", "0", today))
    }
}
