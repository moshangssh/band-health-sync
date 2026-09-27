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

import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiSleepStatsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pins how one Huawei per-night sleep report becomes a payload point: it is anchored on a real time,
 * so a night never lands on the 1970/1969 sentinel day, and a field the watch did not report is
 * omitted rather than sent as -1 or as a fabricated date.
 */
class HuaweiSleepStatsPayloadTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `a resolved wakeup time anchors the night on the wake day`() {
        val sample = HuaweiSleepStatsSample()
        sample.timestamp = ts(2026, 9, 1, 23, 0) * 1000L
        sample.wakeupTime = ts(2026, 9, 2, 7, 0) * 1000L
        sample.bedTime = ts(2026, 9, 1, 22, 30) * 1000L
        sample.risingTime = ts(2026, 9, 2, 7, 5) * 1000L

        val point = sleepStatsPoint(sample, zone)

        assertEquals(sample.wakeupTime, point.timestamp)
        assertEquals("2026-09-02T07:00:00+08:00", point.fields["wakeup_time"])
        assertEquals("2026-09-01T22:30:00+08:00", point.fields["bed_time"])
    }

    @Test
    fun `a missing wakeup time falls back to the rising time`() {
        val sample = HuaweiSleepStatsSample()
        sample.timestamp = ts(2026, 9, 1, 23, 0) * 1000L
        sample.wakeupTime = -1000L
        sample.risingTime = ts(2026, 9, 2, 7, 5) * 1000L

        val point = sleepStatsPoint(sample, zone)

        assertEquals(sample.risingTime, point.timestamp)
        assertFalse(point.fields.containsKey("wakeup_time"))
        assertEquals("2026-09-02T07:05:00+08:00", point.fields["rising_time"])
    }

    @Test
    fun `a night with no reported end falls back to the falling-asleep time, never to 1970`() {
        // The watch reported the falling-asleep time but neither the wakeup nor the rising tag, and
        // correctSummary zeroed the bed and rising times: every time field is a sentinel.
        val sample = HuaweiSleepStatsSample()
        sample.timestamp = ts(2026, 9, 1, 23, 0) * 1000L
        sample.wakeupTime = -1000L
        sample.bedTime = 0L
        sample.risingTime = 0L

        val point = sleepStatsPoint(sample, zone)

        assertEquals(sample.timestamp, point.timestamp)
        assertNoSentinelDates(point)
    }

    @Test
    fun `unreported numbers are omitted instead of sent as -1`() {
        val sample = HuaweiSleepStatsSample()
        sample.timestamp = ts(2026, 9, 1, 23, 0) * 1000L
        sample.wakeupTime = ts(2026, 9, 2, 7, 0) * 1000L
        sample.sleepScore = 88
        sample.maxHeartRate = 120
        sample.wakeCount = 0
        sample.minHeartRate = -1
        sample.wakeUpFeeling = -1
        sample.prepareSleepTime = -1L

        val point = sleepStatsPoint(sample, zone)

        assertEquals(88, point.fields["sleep_score"])
        assertEquals(120, point.fields["max_heart_rate"])
        assertEquals(0, point.fields["wake_count"])
        assertFalse(point.fields.containsKey("min_heart_rate"))
        assertFalse(point.fields.containsKey("wake_up_feeling"))
        assertFalse(point.fields.containsKey("prepare_sleep_time"))
    }

    private fun assertNoSentinelDates(point: SelfHostedHealthPoint) {
        for ((name, value) in point.fields) {
            assertFalse("$name must not carry a fabricated date, was $value", value.toString().contains("197"))
        }
    }

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond()
}
