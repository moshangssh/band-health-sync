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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pins how the readings outside the activity stream are filed: one series per local day, calories
 * and distance summed per day, sleep statistics anchored on the wakeup time.
 */
class SelfHostedHealthExtrasTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `extra series are filed under their own local day`() {
        val extras = SelfHostedHealthExtras(
            spo2 = listOf(
                point(ts(2026, 9, 1, 23, 0), "value" to 96),
                point(ts(2026, 9, 2, 8, 0), "value" to 97)
            ),
            stress = listOf(point(ts(2026, 9, 2, 9, 0), "value" to 40, "level" to 2)),
            hrv = listOf(point(ts(2026, 9, 2, 9, 0), "value" to 42)),
            temperature = listOf(point(ts(2026, 9, 2, 9, 0), "value" to 36.5)),
            restingHeartRate = listOf(point(ts(2026, 9, 2, 9, 0), "value" to 55))
        )

        val payload = SelfHostedHealthPayload.build(emptyList(), zone, 0L, ts(2026, 9, 3, 0, 0), extras)

        val first = bodyFor(payload, "2026-09-01").getJSONArray("spo2")
        assertEquals(1, first.length())
        assertEquals(96, first.getJSONObject(0).getInt("value"))

        val second = bodyFor(payload, "2026-09-02")
        assertEquals(97, second.getJSONArray("spo2").getJSONObject(0).getInt("value"))
        assertEquals(2, second.getJSONArray("stress").getJSONObject(0).getInt("level"))
        assertEquals(36.5, second.getJSONArray("temperature").getJSONObject(0).getDouble("value"), 0.001)
        assertEquals(55, second.getJSONArray("resting_heart_rate").getJSONObject(0).getInt("value"))
        assertEquals(
            "2026-09-02T09:00:00+08:00",
            second.getJSONArray("hrv").getJSONObject(0).getString("timestamp")
        )
    }

    @Test
    fun `calories and distance are summed per day`() {
        val extras = SelfHostedHealthExtras(
            activeCalories = listOf(
                point(ts(2026, 9, 2, 8, 0), "value" to 120),
                point(ts(2026, 9, 2, 20, 0), "value" to 200)
            ),
            distance = listOf(
                point(ts(2026, 9, 2, 8, 0), "value" to 1500.0),
                point(ts(2026, 9, 2, 20, 0), "value" to 2700.0)
            )
        )

        val payload = SelfHostedHealthPayload.build(emptyList(), zone, 0L, ts(2026, 9, 2, 21, 0), extras)

        val body = bodyFor(payload, "2026-09-02")
        assertEquals(320.0, body.getJSONObject("active_calories").getDouble("total"), 0.001)
        assertEquals(4200.0, body.getJSONObject("distance").getDouble("total"), 0.001)
    }

    @Test
    fun `sleep statistics are filed under the wakeup day`() {
        val extras = SelfHostedHealthExtras(
            sleepStats = listOf(
                SelfHostedHealthPoint(
                    ts(2026, 9, 2, 7, 0) * 1000L,
                    linkedMapOf("sleep_score" to 88, "avg_breath_rate" to 14)
                )
            ),
            emotions = listOf(point(ts(2026, 9, 2, 10, 0), "status" to 2)),
            sleepApnea = listOf(point(ts(2026, 9, 2, 3, 0), "level" to 1))
        )

        val payload = SelfHostedHealthPayload.build(emptyList(), zone, 0L, ts(2026, 9, 2, 12, 0), extras)

        val body = bodyFor(payload, "2026-09-02")
        val stats = body.getJSONArray("sleep_stats").getJSONObject(0)
        assertEquals(88, stats.getInt("sleep_score"))
        assertEquals("2026-09-02T07:00:00+08:00", stats.getString("timestamp"))
        assertEquals(1, body.getJSONArray("emotions").length())
        assertEquals(1, body.getJSONArray("sleep_apnea").length())
    }

    @Test
    fun `extras alone still produce a day payload`() {
        val extras = SelfHostedHealthExtras(hrv = listOf(point(ts(2026, 9, 2, 9, 0), "value" to 42)))

        val payload = SelfHostedHealthPayload.build(emptyList(), zone, 0L, ts(2026, 9, 2, 10, 0), extras)

        assertEquals(1, payload.days.size)
        assertTrue(bodyFor(payload, "2026-09-02").has("hrv"))
    }

    /** [timestamp] is epoch seconds; the payload's points carry milliseconds. */
    private fun point(timestamp: Long, vararg fields: Pair<String, Any>): SelfHostedHealthPoint =
        SelfHostedHealthPoint(timestamp * 1000L, linkedMapOf(*fields))

    private fun bodyFor(payload: SelfHostedHealthPayloadSet, date: String): JSONObject =
        payload.days.first { it.date == date }.body

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond()
}
