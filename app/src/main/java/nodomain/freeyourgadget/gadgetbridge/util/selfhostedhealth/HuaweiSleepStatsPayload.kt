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
            // The row's own timestamp is the watch's falling-asleep time; it is only the day anchor
            // when neither end-of-night time was reported, so the field itself carries it here.
            "fall_asleep_time" to time(sample.timestamp, zone),
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
            // The watch's own baseline for the same three metrics, plus how far tonight sat from it.
            "min_heart_rate_baseline" to known(sample.minHeartRateBaseline),
            "max_heart_rate_baseline" to known(sample.maxHeartRateBaseline),
            "heart_rate_day_to_baseline" to known(sample.heartRateDayToBaseline),
            "min_oxygen_saturation" to known(sample.minOxygenSaturation),
            "max_oxygen_saturation" to known(sample.maxOxygenSaturation),
            "avg_oxygen_saturation" to known(sample.avgOxygenSaturation),
            "min_oxygen_saturation_baseline" to known(sample.minOxygenSaturationBaseline),
            "max_oxygen_saturation_baseline" to known(sample.maxOxygenSaturationBaseline),
            "oxygen_saturation_day_to_baseline" to known(sample.oxygenSaturationDayToBaseline),
            "min_breath_rate" to known(sample.minBreathRate),
            "max_breath_rate" to known(sample.maxBreathRate),
            "avg_breath_rate" to known(sample.avgBreathRate),
            "min_breath_rate_baseline" to known(sample.minBreathRateBaseline),
            "max_breath_rate_baseline" to known(sample.maxBreathRateBaseline),
            "breath_rate_day_to_baseline" to known(sample.breathRateDayToBaseline),
            "avg_hrv" to known(sample.avgHrv),
            "min_hrv_baseline" to known(sample.minHrvBaseline),
            "max_hrv_baseline" to known(sample.maxHrvBaseline),
            "hrv_day_to_baseline" to known(sample.hrvDayToBaseline),
            "rdi" to known(sample.rdi),
            "wake_count" to known(sample.wakeCount),
            "turn_over_count" to known(sample.turnOverCount),
            "wake_up_feeling" to known(sample.wakeUpFeeling),
            // prepareSleepTime is left out on purpose: nothing in the app or the server says what
            // its unit is, and the watch's bare number would only invite a guess. Send it once a
            // real value can be checked against the row's bed and falling-asleep times.
            "sleep_version" to known(sample.sleepVersion)
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
