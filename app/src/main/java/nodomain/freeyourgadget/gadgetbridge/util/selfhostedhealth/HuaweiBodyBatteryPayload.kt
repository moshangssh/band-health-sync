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

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.FitnessData
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutDataSample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutSummarySample
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiWorkoutGbParser.RECOVERY_HEART_RATE_INTERVAL_MS
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** 只使用实际运动记录，恢复片段同时间戳的读数优先；不改运动结束时间。 */
internal fun huaweiWorkoutHeartRate(
    summary: HuaweiWorkoutSummarySample,
    samples: List<HuaweiWorkoutDataSample>,
    zone: ZoneId
): JSONArray {
    val readings = sortedMapOf<Long, Int>()
    for (sample in samples) {
        val bpm = sample.heartRate.toInt() and 0xff
        if (bpm in 1..254) {
            readings[sample.timestamp.toLong() * 1000L] = bpm
        }
    }
    summary.recoveryHeartRates?.let { encoded ->
        val recovery = StringUtils.hexToBytes(String(encoded, Charsets.US_ASCII))
        for ((index, value) in recovery.withIndex()) {
            val bpm = value.toInt() and 0xff
            if (bpm in 1..254) {
                val timestamp = summary.endTimestamp.toLong() * 1000L +
                    (index - 1L) * RECOVERY_HEART_RATE_INTERVAL_MS
                readings[timestamp] = bpm
            }
        }
    }
    return JSONArray().apply {
        for ((timestamp, bpm) in readings) {
            put(JSONObject().put("timestamp", iso(timestamp, zone)).put("value", bpm))
        }
    }
}

/** 原始活动历史的覆盖，不读取 provider 的补齐分钟，也不把无心率解释成离腕。 */
internal fun huaweiHeartRateCoverage(
    samples: List<HuaweiActivitySample>,
    fromTs: Long,
    toTs: Long,
    zone: ZoneId
): List<SelfHostedHealthPoint> {
    data class Span(val start: Long, var end: Long, val status: String)

    val spans = mutableListOf<Span>()
    for (sample in samples.sortedBy { it.timestamp }) {
        if (sample.source.toInt() != FitnessData.MessageData.stepId.toInt() ||
            sample.timestamp >= sample.otherTimestamp
        ) {
            continue
        }
        var start = maxOf(sample.timestamp.toLong(), fromTs)
        val end = minOf(sample.otherTimestamp.toLong(), toTs)
        val bpm = sample.heartRate and 0xff
        val status = if (bpm in 1..254) "observed" else "missing"
        while (start < end) {
            val day = Instant.ofEpochSecond(start).atZone(zone).toLocalDate()
            val midnight = day.atStartOfDay(zone).toEpochSecond()
            val nextMidnight = day.plusDays(1).atStartOfDay(zone).toEpochSecond()
            val segmentEnd = minOf(end, nextMidnight)
            val previous = spans.lastOrNull()
            if (previous != null && previous.end == start && previous.start >= midnight &&
                previous.status == status
            ) {
                previous.end = segmentEnd
            } else {
                spans.add(Span(start, segmentEnd, status))
            }
            start = segmentEnd
        }
    }
    return spans.map {
        SelfHostedHealthPoint(
            it.start * 1000L,
            linkedMapOf("end_time" to iso(it.end * 1000L, zone), "status" to it.status)
        )
    }
}
