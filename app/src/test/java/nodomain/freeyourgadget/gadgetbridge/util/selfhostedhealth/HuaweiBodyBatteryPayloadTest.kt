package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.FitnessData
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutDataSample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutSummarySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

class HuaweiBodyBatteryPayloadTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = ZonedDateTime.of(2026, 9, 30, 13, 0, 0, 0, zone).toEpochSecond()

    @Test
    fun `运动心率保持五秒时间戳并由恢复片段覆盖重复点`() {
        val summary = HuaweiWorkoutSummarySample().apply {
            endTimestamp = (start + 15).toInt()
            recoveryHeartRates = "968c8200ff".toByteArray(Charsets.US_ASCII)
        }
        val readings = huaweiWorkoutHeartRate(
            summary,
            listOf(
                workoutSample(start + 10, 160),
                workoutSample(start, 130),
                workoutSample(start + 5, 140),
                workoutSample(start + 5, 145),
                workoutSample(start + 30, 0),
                workoutSample(start + 35, 255)
            ),
            zone
        )
        assertEquals(5, readings.length())
        assertEquals(listOf(130, 145, 150, 140, 130), (0 until readings.length()).map {
            readings.getJSONObject(it).getInt("value")
        })
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L), (0 until readings.length()).map {
            ZonedDateTime.parse(readings.getJSONObject(it).getString("timestamp")).toEpochSecond() - start
        })

        val base = BaseActivitySummary().apply {
            startTime = Date(start * 1000L)
            endTime = Date((start + 15) * 1000L)
            activityKind = ActivityKind.INDOOR_CYCLING.code
            summaryData = "{}"
        }
        val point = workoutPoint(base, zone, readings)!!
        assertEquals(15L, point.fields["duration_seconds"])
        assertEquals("2026-09-30T13:00:15+08:00", point.fields["end_time"])
        val body = SelfHostedHealthPayload.build(
            emptyList(), zone, 0L, start + 100,
            SelfHostedHealthExtras(workouts = listOf(point))
        ).days.single().body
        assertEquals(5, body.getJSONArray("workouts").getJSONObject(0).getJSONArray("heart_rate").length())
        assertFalse(body.has("steps_bucket_seconds"))
    }

    @Test
    fun `只合并真实历史同状态相邻区间并保留未知缺口`() {
        val samples = listOf(
            activity(start, start + 60, -1),
            activity(start + 60, start + 120, -1),
            activity(start + 180, start + 240, 150.toByte().toInt()),
            activity(start + 240, start + 300, 70),
            activity(start + 60, start, -1), // provider 存储的人造结束标记
            activity(start + 120, start + 180, -1).apply { source = 0 }, // 非步数历史
            activity(start + 150, start + 150, -1) // 不具有原始时间跨度的补齐行
        )
        val spans = huaweiHeartRateCoverage(samples.reversed(), start, start + 300, zone)
        assertEquals(2, spans.size)
        assertEquals(start * 1000L, spans[0].timestamp)
        assertEquals("missing", spans[0].fields["status"])
        assertEquals(iso((start + 120) * 1000L, zone), spans[0].fields["end_time"])
        assertEquals((start + 180) * 1000L, spans[1].timestamp)
        assertEquals("observed", spans[1].fields["status"])
        assertEquals(iso((start + 300) * 1000L, zone), spans[1].fields["end_time"])
    }

    @Test
    fun `覆盖截取查询窗口并按当地午夜分日`() {
        val midnight = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, zone).toEpochSecond()
        val spans = huaweiHeartRateCoverage(
            listOf(activity(midnight - 60, midnight + 60, -1)),
            midnight - 30, midnight + 30, zone
        )
        assertEquals(2, spans.size)
        assertEquals((midnight - 30) * 1000L, spans[0].timestamp)
        assertEquals(iso(midnight * 1000L, zone), spans[0].fields["end_time"])
        assertEquals(midnight * 1000L, spans[1].timestamp)
        assertEquals(iso((midnight + 30) * 1000L, zone), spans[1].fields["end_time"])

        val payload = SelfHostedHealthPayload.build(
            emptyList(), zone, 0L, midnight + 60,
            SelfHostedHealthExtras(heartRateCoverage = spans)
        )
        assertEquals(listOf("2026-09-30", "2026-10-01"), payload.days.map { it.date })
        assertEquals(1, payload.days[0].body.getJSONArray("heart_rate_coverage").length())
        assertEquals(1, payload.days[1].body.getJSONArray("heart_rate_coverage").length())
    }

    @Test
    fun `没有原始记录时不生成覆盖或离腕事件`() {
        assertEquals(emptyList<SelfHostedHealthPoint>(), huaweiHeartRateCoverage(emptyList(), start, start + 60, zone))
    }

    private fun workoutSample(timestamp: Long, bpm: Int) = HuaweiWorkoutDataSample().apply {
        this.timestamp = timestamp.toInt()
        heartRate = bpm.toByte()
    }

    private fun activity(start: Long, end: Long, bpm: Int) = HuaweiActivitySample().apply {
        timestamp = start.toInt()
        otherTimestamp = end.toInt()
        source = FitnessData.MessageData.stepId.toByte()
        heartRate = bpm
    }
}
