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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The Huawei per-night sleep report, anchored so it lands on the day the night ended: the server
 * files sleep statistics by the wake day ([docs/PATCHES.md](docs/PATCHES.md)).
 *
 * A watch does not report every field of every night. The parser leaves the missing ones at a
 * sentinel: -1 for numbers, and a negative or zero millisecond time for the time fields. Those
 * sentinels are dropped rather than sent, so a missing reading never reaches the server as -1 or as
 * a fabricated 1970 date.
 *
 * Pure (no Android, no database) so the anchor and the wire fields can be unit tested.
 */
internal fun sleepStatsPoint(sample: HuaweiSleepStatsSample, zone: ZoneId): SelfHostedHealthPoint =
    SelfHostedHealthPoint(
        timestamp = anchor(sample),
        fields = fieldsOf(
            "sleep_score" to known(sample.sleepScore),
            "bed_time" to time(sample.bedTime, zone),
            "rising_time" to time(sample.risingTime, zone),
            "wakeup_time" to time(sample.wakeupTime, zone),
            "sleep_efficiency" to known(sample.sleepEfficiency),
            "sleep_latency" to known(sample.sleepLatency),
            "deep_part" to known(sample.deepPart),
            "snore_freq" to known(sample.snoreFreq),
            "sleep_data_quality" to known(sample.sleepDataQuality),
            "min_heart_rate" to known(sample.minHeartRate),
            "max_heart_rate" to known(sample.maxHeartRate),
            "avg_heart_rate" to known(sample.avgHeartRate),
            "min_oxygen_saturation" to known(sample.minOxygenSaturation),
            "max_oxygen_saturation" to known(sample.maxOxygenSaturation),
            "avg_oxygen_saturation" to known(sample.avgOxygenSaturation),
            "min_breath_rate" to known(sample.minBreathRate),
            "max_breath_rate" to known(sample.maxBreathRate),
            "avg_breath_rate" to known(sample.avgBreathRate),
            "avg_hrv" to known(sample.avgHrv),
            "hrv_day_to_baseline" to known(sample.hrvDayToBaseline),
            "rdi" to known(sample.rdi),
            "wake_count" to known(sample.wakeCount),
            "turn_over_count" to known(sample.turnOverCount),
            "wake_up_feeling" to known(sample.wakeUpFeeling),
            "prepare_sleep_time" to known(sample.prepareSleepTime)
        )
    )

/**
 * The day the night is filed under: the wakeup time when the watch reports it, else the rising time,
 * else the row's own falling-asleep timestamp. All three are real times, so a night is never filed
 * under the 1970 sentinel day; the first two keep it on the wake day, the fallback only applies when
 * the watch reported neither end-of-night time.
 */
private fun anchor(sample: HuaweiSleepStatsSample): Long = when {
    sample.wakeupTime > 0 -> sample.wakeupTime
    sample.risingTime > 0 -> sample.risingTime
    else -> sample.timestamp
}

/** A number below its validity floor is the parser's "not reported" sentinel, kept out of the payload. */
private fun known(value: Int): Int? = value.takeIf { it >= 0 }

private fun known(value: Long): Long? = value.takeIf { it >= 0 }

private fun known(value: Double): Double? = value.takeIf { it >= 0.0 }

/** An ISO time for a reported time, or null when the sentinel says the watch did not report it. */
private fun time(epochMillis: Long, zone: ZoneId): String? =
    epochMillis.takeIf { it > 0 }?.let { iso(it, zone) }

private fun fieldsOf(vararg entries: Pair<String, Any?>): LinkedHashMap<String, Any?> {
    val fields = LinkedHashMap<String, Any?>()
    for ((name, value) in entries) {
        if (value != null) {
            fields[name] = value
        }
    }
    return fields
}

/** ISO 8601 with an explicit offset, for the payload fields that carry a time rather than a number. */
internal fun iso(epochMillis: Long, zone: ZoneId): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
    )
