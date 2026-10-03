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

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummarySimpleEntry
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.json.JSONArray
import java.time.ZoneId

/**
 * One recorded workout as the self-hosted sync sends it, anchored on its start time.
 *
 * The start is also the server's key: the device parser states the start never changes, so a workout
 * re-sent after the watch corrected its end time replaces the stored copy instead of landing beside
 * it under a second key. A day the watch recorded two separate workouts on therefore keeps both.
 *
 * 汇总字段来自已保存的 summaryData。可选 heartRate 直接读取运动与恢复心率，保留原始时间戳；
 * 恢复片段可延伸到 end_time 之后，但不会延长运动时长。GPS 轨迹仍不上传。
 *
 * Pure (no Android, no database) so the field mapping can be unit tested.
 *
 * @param zone zone the ISO times are written in, matching every other timestamp in the payload.
 * @return the point, or null when the row is not a workout.
 */
internal fun workoutPoint(
    summary: BaseActivitySummary,
    zone: ZoneId,
    heartRate: JSONArray? = null
): SelfHostedHealthPoint? {
    val start = summary.startTime?.time ?: return null
    val end = summary.endTime?.time ?: return null
    val kind = ActivityKind.fromCode(summary.activityKind)
    // The same rule the Health Connect path applies: the summary table also holds spans that are not
    // exercise, such as a sleep record or a stretch the watch was off the wrist.
    if (kind == ActivityKind.NOT_MEASURED || kind == ActivityKind.NOT_WORN || ActivityKind.isSleep(kind)) {
        return null
    }

    val data = ActivitySummaryData.fromJson(summary.summaryData)
    val fields = linkedMapOf<String, Any?>()
    summary.name?.let { fields["name"] = it }
    fields["activity"] = kind.name.lowercase()
    fields["end_time"] = iso(end, zone)
    fields["duration_seconds"] = (end - start) / 1000L
    // A zero is never stored: the parsers add only non-zero values, so a missing entry reads back as
    // 0 here and stays off the wire rather than being reported as a measured zero.
    for ((field, key) in WORKOUT_AGGREGATES) {
        val value = data.getNumber(key, 0).toDouble()
        if (value != 0.0) {
            fields[field] = value
        }
    }
    // The zones are the one group where a stored zero is itself a reading: the watch splits every
    // workout it has a heart rate for across all five zones, so a zero says it recorded no time
    // there. A zone the row does not hold at all is the workout it could not split.
    for ((field, key) in HR_ZONE_AGGREGATES) {
        if (data.has(key)) {
            fields[field] = data.getNumber(key, 0).toDouble()
        }
    }
    // Pace and step rate are stored under one key each with the unit the sport gives them, so the
    // name carries the unit the row stores rather than one assumed here.
    for ((field, key) in UNIT_NAMED_AGGREGATES) {
        val entry = data.get(key) as? ActivitySummarySimpleEntry ?: continue
        val value = entry.value as? Number ?: continue
        if (value.toDouble() != 0.0) {
            fields["${field}_${entry.unit}"] = value.toDouble()
        }
    }
    if (heartRate != null) fields["heart_rate"] = heartRate
    return SelfHostedHealthPoint(start, fields)
}

/**
 * The aggregates written under a fixed name, because the row always measures them in the same unit.
 *
 * The name says what the number is: the assistant reading the payload never sees the row or the app's
 * UI, so `recovery_time_hours` has to stand on its own.
 *
 * This is not every key `ActivitySummaryEntries` defines — it is the ones the watch that fills this
 * row actually reports. A band fills heart rate, calories, distance, steps, pace, step rate, its
 * training effect and the zones it split the workout into, and nothing else; the running form, swim,
 * jump rope, power and elevation entries in that class belong to watches with sensors this one has
 * not got, and listing them here would only promise the server fields that never arrive.
 */
private val WORKOUT_AGGREGATES = listOf(
    // Totals
    "distance_m" to ActivitySummaryEntries.DISTANCE_METERS,
    "calories" to ActivitySummaryEntries.CALORIES_BURNT,
    "steps" to ActivitySummaryEntries.STEPS,
    "active_seconds" to ActivitySummaryEntries.ACTIVE_SECONDS,
    // Heart rate: the average, the span it moved in
    "avg_heart_rate" to ActivitySummaryEntries.HR_AVG,
    "max_heart_rate" to ActivitySummaryEntries.HR_MAX,
    "min_heart_rate" to ActivitySummaryEntries.HR_MIN,
    // What the watch derived from the session
    "workout_load" to ActivitySummaryEntries.WORKOUT_LOAD,
    "aerobic_training_effect" to ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC,
    "recovery_time_hours" to ActivitySummaryEntries.RECOVERY_TIME
)

/**
 * The watch's own split of the workout across its five heart rate zones, in seconds.
 *
 * Only these five: they are the ones this watch reports, and it reports all five on every workout it
 * has a heart rate for. `HR_ZONE_EASY`, `HR_ZONE_THRESHOLD` and `HR_ZONE_MAXIMUM` are other vendors'
 * names for the same ladder and never appear on the row.
 */
private val HR_ZONE_AGGREGATES = listOf(
    "hr_zone_warm_up_seconds" to ActivitySummaryEntries.HR_ZONE_WARM_UP,
    "hr_zone_fat_burn_seconds" to ActivitySummaryEntries.HR_ZONE_FAT_BURN,
    "hr_zone_aerobic_seconds" to ActivitySummaryEntries.HR_ZONE_AEROBIC,
    "hr_zone_anaerobic_seconds" to ActivitySummaryEntries.HR_ZONE_ANAEROBIC,
    "hr_zone_extreme_seconds" to ActivitySummaryEntries.HR_ZONE_EXTREME
)

/**
 * The aggregates whose unit the row decides, written under a name ending in that unit.
 *
 * Pace is the same key whether the sport is walked, run or swum, but seconds per km and seconds per
 * 100 m are 10× apart, and the row is what says which one it holds. Step rate the watch stores in
 * steps per minute, and a row that gets it from somewhere else is no worse off for saying so.
 */
private val UNIT_NAMED_AGGREGATES = listOf(
    "avg_pace" to ActivitySummaryEntries.PACE_AVG_SECONDS_KM,
    "max_pace" to ActivitySummaryEntries.PACE_MAX,
    "avg_step_rate" to ActivitySummaryEntries.STEP_RATE_AVG
)
