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

import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import org.json.JSONObject
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeParseException

/**
 * The user's own body profile — height, weight, sex and birth date — as the sync sends it.
 *
 * Values are read straight out of the raw preferences, deliberately not through [ActivityUser]:
 * that class substitutes height 175, weight 70, a 2000-01-01 birthday and "female" whenever a
 * field is unset, so going through it would upload a fabricated body. Here an unset field is
 * simply absent from the object, and [build] returns null when nothing at all is set.
 */
object SelfHostedHealthProfile {

    /**
     * @param today the local date the age is measured against.
     * @return the profile fields that are actually set, or null when none of them are.
     */
    @JvmStatic
    fun build(
        gender: String?,
        birthday: String?,
        heightCm: String?,
        weightKg: String?,
        today: LocalDate
    ): JSONObject? {
        val profile = JSONObject()
        positive(heightCm)?.let { profile.put("height_cm", it) }
        positive(weightKg)?.let { profile.put("weight_kg", it) }
        genderName(gender)?.let { profile.put("gender", it) }
        // The age travels with the birthday it came from, so the server never has to guess which
        // date the number was measured against.
        parseDate(birthday)?.let {
            profile.put("birthday", it.toString())
            profile.put("age", Period.between(it, today).years)
        }
        return if (profile.length() == 0) null else profile
    }

    /** An empty field, a zero or a non-number is not a measurement, so it does not go on the wire. */
    private fun positive(value: String?): Int? =
        value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun genderName(gender: String?): String? = when (gender?.trim()?.toIntOrNull()) {
        ActivityUser.GENDER_MALE -> "male"
        ActivityUser.GENDER_FEMALE -> "female"
        ActivityUser.GENDER_OTHER -> "other"
        else -> null
    }

    /** A hand-restored preference can hold anything; a bad date drops the age, not the whole sync. */
    private fun parseDate(birthday: String?): LocalDate? = try {
        birthday?.trim()?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) }
    } catch (e: DateTimeParseException) {
        null
    }
}
