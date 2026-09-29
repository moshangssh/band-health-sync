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

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummaryProgressEntry
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

/** What a recorded workout contributes to an upload, and what it must not. */
class SelfHostedHealthWorkoutTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `the aggregate of a workout goes on the wire keyed on the start time`() {
        val summary = workout(
            kind = ActivityKind.RUNNING,
            name = "Morning run",
            start = ts(2026, 9, 2, 7, 30),
            end = ts(2026, 9, 2, 8, 5),
            ActivitySummaryEntries.DISTANCE_METERS to (5200.0 to ActivitySummaryEntries.UNIT_METERS),
            ActivitySummaryEntries.CALORIES_BURNT to (310.0 to ActivitySummaryEntries.UNIT_KCAL),
            ActivitySummaryEntries.HR_AVG to (148.0 to ActivitySummaryEntries.UNIT_BPM),
            ActivitySummaryEntries.STEPS to (4900.0 to ActivitySummaryEntries.UNIT_STEPS)
        )

        val point = workoutPoint(summary, zone)!!

        assertEquals(ts(2026, 9, 2, 7, 30) * 1000L, point.timestamp)
        assertEquals("running", point.fields["activity"])
        assertEquals("Morning run", point.fields["name"])
        assertEquals("2026-09-02T08:05:00+08:00", point.fields["end_time"])
        assertEquals(2100L, point.fields["duration_seconds"])
        assertEquals(5200.0, point.fields["distance_m"] as Double, 0.001)
        assertEquals(310.0, point.fields["calories"] as Double, 0.001)
        assertEquals(148.0, point.fields["avg_heart_rate"] as Double, 0.001)
        assertEquals(4900.0, point.fields["steps"] as Double, 0.001)
    }

    /** The summary table also holds spans that are not exercise, and those are not workouts. */
    @Test
    fun `a non-exercise span is not a workout`() {
        for (kind in listOf(ActivityKind.LIGHT_SLEEP, ActivityKind.NOT_WORN, ActivityKind.NOT_MEASURED)) {
            val summary = workout(kind, null, ts(2026, 9, 2, 1, 0), ts(2026, 9, 2, 9, 0))
            assertNull("$kind should not be uploaded as a workout", workoutPoint(summary, zone))
        }
    }

    /** A metric the device never reported is absent, not reported as a measured zero. */
    @Test
    fun `a workout with no reported numbers still sends its span`() {
        val summary = workout(ActivityKind.WALKING, null, ts(2026, 9, 2, 18, 0), ts(2026, 9, 2, 18, 20))

        val point = workoutPoint(summary, zone)!!

        assertEquals(
            listOf("activity", "end_time", "duration_seconds"),
            point.fields.keys.toList()
        )
        assertEquals(1200L, point.fields["duration_seconds"])
    }

    /** The zones and the figures the watch derived are what makes a workout readable. */
    @Test
    fun `the zones and the watch's own figures go on the wire`() {
        val data = ActivitySummaryData()
        data.add(ActivitySummaryEntries.HR_MAX, 153, ActivitySummaryEntries.UNIT_BPM)
        data.add(ActivitySummaryEntries.HR_MIN, 91, ActivitySummaryEntries.UNIT_BPM)
        data.add(ActivitySummaryEntries.WORKOUT_LOAD, 19, ActivitySummaryEntries.UNIT_NONE)
        data.add(ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC, 1.3, ActivitySummaryEntries.UNIT_NONE)
        data.add(ActivitySummaryEntries.RECOVERY_TIME, 8.0, ActivitySummaryEntries.UNIT_HOURS)
        // The parsers write every zone, as a progress entry, the same shape the workout screen draws.
        for ((key, seconds) in listOf(
            ActivitySummaryEntries.HR_ZONE_WARM_UP to 25.0,
            ActivitySummaryEntries.HR_ZONE_FAT_BURN to 110.0,
            ActivitySummaryEntries.HR_ZONE_AEROBIC to 1005.0,
            ActivitySummaryEntries.HR_ZONE_ANAEROBIC to 20.0,
            ActivitySummaryEntries.HR_ZONE_EXTREME to 0.0
        )) {
            data.add(key, ActivitySummaryProgressEntry(seconds, ActivitySummaryEntries.UNIT_SECONDS, 0, 0x123456))
        }
        val summary = workout(
            ActivityKind.INDOOR_WALKING, null, ts(2026, 9, 2, 14, 35), ts(2026, 9, 2, 14, 55), data
        )

        val point = workoutPoint(summary, zone)!!

        assertEquals(153.0, point.fields["max_heart_rate"] as Double, 0.001)
        assertEquals(91.0, point.fields["min_heart_rate"] as Double, 0.001)
        assertEquals(19.0, point.fields["workout_load"] as Double, 0.001)
        assertEquals(1.3, point.fields["aerobic_training_effect"] as Double, 0.001)
        assertEquals(8.0, point.fields["recovery_time_hours"] as Double, 0.001)
        // The watch splits every workout across all five zones, so a zero is a reading: it measured
        // no time there. It goes out, and reads as none rather than as unknown.
        assertEquals(0.0, point.fields["hr_zone_extreme_seconds"] as Double, 0.001)
        assertEquals(1005.0, point.fields["hr_zone_aerobic_seconds"] as Double, 0.001)
    }

    /** The running form, swim and elevation entries belong to watches this band is not. */
    @Test
    fun `a metric the watch never reports stays off the wire`() {
        val data = ActivitySummaryData()
        data.add(ActivitySummaryEntries.ALTITUDE_AVG, 320.0, ActivitySummaryEntries.UNIT_METERS)
        data.add(
            ActivitySummaryEntries.GROUND_CONTACT_TIME_AVG,
            248.0,
            ActivitySummaryEntries.UNIT_MILLISECONDS
        )
        data.add(ActivitySummaryEntries.SWOLF_AVG, 42.0, ActivitySummaryEntries.UNIT_NONE)
        data.add(ActivitySummaryEntries.JUMPS, 300, ActivitySummaryEntries.UNIT_JUMPS)
        data.add(ActivitySummaryEntries.HR_ZONE_EASY, 60.0, ActivitySummaryEntries.UNIT_SECONDS)

        val point = workoutPoint(
            workout(ActivityKind.RUNNING, null, ts(2026, 9, 2, 7, 30), ts(2026, 9, 2, 8, 5), data),
            zone
        )!!

        assertEquals(listOf("activity", "end_time", "duration_seconds"), point.fields.keys.toList())
    }

    /** A zone the watch did not split at all is absent, not reported as no time spent in it. */
    @Test
    fun `a zone the workout has no heart rate for stays off the wire`() {
        val data = ActivitySummaryData()
        data.add(ActivitySummaryEntries.HR_ZONE_WARM_UP, 90.0, ActivitySummaryEntries.UNIT_SECONDS)

        val point = workoutPoint(
            workout(ActivityKind.STRENGTH_TRAINING, null, ts(2026, 9, 2, 13, 44), ts(2026, 9, 2, 14, 34), data),
            zone
        )!!

        assertEquals(90.0, point.fields["hr_zone_warm_up_seconds"] as Double, 0.001)
        assertFalse(point.fields.containsKey("hr_zone_aerobic_seconds"))
    }

    /** Pace is one key holding different units, so the name has to say which one was stored. */
    @Test
    fun `pace and step rate go out under the unit the watch stored`() {
        val walking = ActivitySummaryData().apply {
            add(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, 906.8, ActivitySummaryEntries.UNIT_SECONDS_PER_KM)
            add(ActivitySummaryEntries.STEP_RATE_AVG, 74.0, ActivitySummaryEntries.UNIT_SPM)
        }
        val swimming = ActivitySummaryData().apply {
            add(
                ActivitySummaryEntries.PACE_AVG_SECONDS_KM,
                110.0,
                ActivitySummaryEntries.UNIT_SECONDS_PER_100_METERS
            )
        }

        val walk = workoutPoint(
            workout(ActivityKind.INDOOR_WALKING, null, ts(2026, 9, 2, 14, 35), ts(2026, 9, 2, 14, 55), walking),
            zone
        )!!
        val swim = workoutPoint(
            workout(ActivityKind.SWIMMING, null, ts(2026, 9, 2, 18, 0), ts(2026, 9, 2, 18, 40), swimming),
            zone
        )!!

        assertEquals(906.8, walk.fields["avg_pace_seconds_km"] as Double, 0.001)
        assertEquals(74.0, walk.fields["avg_step_rate_spm"] as Double, 0.001)
        // Seconds per 100 m and seconds per km are 10× apart; the stored unit is what separates them.
        assertEquals(110.0, swim.fields["avg_pace_seconds_100m"] as Double, 0.001)
    }

    private fun workout(
        kind: ActivityKind,
        name: String?,
        start: Long,
        end: Long,
        vararg entries: Pair<String, Pair<Double, String>>
    ): BaseActivitySummary {
        val data = ActivitySummaryData()
        for ((key, value) in entries) {
            data.add(key, value.first, value.second)
        }
        return workout(kind, name, start, end, data)
    }

    private fun workout(
        kind: ActivityKind,
        name: String?,
        start: Long,
        end: Long,
        data: ActivitySummaryData
    ): BaseActivitySummary {
        return BaseActivitySummary().apply {
            this.name = name
            this.startTime = Date(start * 1000L)
            this.endTime = Date(end * 1000L)
            this.activityKind = kind.code
            this.summaryData = data.toJson()
        }
    }

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond()
}
