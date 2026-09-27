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
import org.junit.Test
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The periodic upload's start-time grid, which decides when the timed upload actually fires. */
class SelfHostedHealthSyncWorkerTest {

    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun `a daily interval waits for tomorrow when the start time has passed`() {
        val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone)

        assertEquals(
            Duration.ofHours(22).seconds,
            nextRunDelaySeconds(LocalTime.of(8, 0), 1440, now)
        )
    }

    @Test
    fun `a start time later today is the first run`() {
        val now = ZonedDateTime.of(2026, 9, 27, 6, 0, 0, 0, zone)

        assertEquals(
            Duration.ofHours(2).seconds,
            nextRunDelaySeconds(LocalTime.of(8, 0), 1440, now)
        )
    }

    @Test
    fun `a sub-daily interval snaps forward to the grid, not one period from now`() {
        val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone)

        // 08:00 + n*6h: 14:00 today, not 16:00.
        assertEquals(
            Duration.ofHours(4).seconds,
            nextRunDelaySeconds(LocalTime.of(8, 0), 360, now)
        )
    }
}
