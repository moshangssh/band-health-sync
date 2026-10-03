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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The scheduled upload's start-time grid, which decides when the timed upload actually fires. */
class SelfHostedHealthSyncWorkerTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private val server = "http://192.168.31.245:47831/api/health"

    @Test
    fun `cursors recorded for this server are used as they are`() {
        val cursors = uploadCursors(server, server, 1790600556L, 1790557741L)

        assertEquals(1790600556L, cursors.cursor)
        assertEquals(1790557741L, cursors.sleepCursor)
        assertEquals(false, cursors.otherServer)
    }

    @Test
    fun `a device moved to another server starts from nothing, so the window goes out again`() {
        val cursors = uploadCursors("https://health.example.com/api/health", server, 1790600556L, 1790557741L)

        assertEquals(0L, cursors.cursor)
        assertEquals(0L, cursors.sleepCursor)
        assertEquals(true, cursors.otherServer)
    }

    /** The pair stored before this rule ships carries no server, so the first run re-sends. */
    @Test
    fun `cursors with no server recorded start from nothing`() {
        val cursors = uploadCursors(null, server, 1790600556L, 1790557741L)

        assertEquals(0L, cursors.cursor)
        assertEquals(0L, cursors.sleepCursor)
        assertEquals(true, cursors.otherServer)
    }

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

    /**
     * The whole point of arming the next run from the run that just finished: a run that came in
     * late is followed by the next grid point, not by "as late as this one", so a schedule that
     * slipped once is back on its clock time tomorrow instead of staying slipped.
     */
    @Test
    fun `a run that came late arms the next one at the start time`() {
        val now = ZonedDateTime.of(2026, 10, 1, 9, 32, 47, 0, zone)

        assertEquals(
            Duration.ofHours(21).plusMinutes(27).plusSeconds(13).seconds,
            nextRunDelaySeconds(LocalTime.of(7, 0), 1440, now)
        )
    }

    /** Sync on unlock runs the schedule on the clock rather than on the user's interval: midnight as
     *  the anchor with an hour between runs is every hour on the hour. */
    @Test
    fun `the unlock cadence is every hour on the hour`() {
        val now = ZonedDateTime.of(2026, 10, 1, 7, 23, 47, 0, zone)

        assertEquals(
            Duration.ofMinutes(36).plusSeconds(13).seconds,
            nextRunDelaySeconds(LocalTime.MIDNIGHT, 60, now)
        )
    }

    /** On the hour, the one armed is the following hour: the run that just finished is not re-armed. */
    @Test
    fun `the unlock cadence never arms a run for right now`() {
        val now = ZonedDateTime.of(2026, 10, 1, 8, 0, 0, 0, zone)

        assertEquals(
            Duration.ofHours(1).seconds,
            nextRunDelaySeconds(LocalTime.MIDNIGHT, 60, now)
        )
    }

    /** The last hour of the day rolls into the next day instead of stopping at midnight. */
    @Test
    fun `the unlock cadence crosses midnight`() {
        val now = ZonedDateTime.of(2026, 10, 1, 23, 30, 0, 0, zone)

        assertEquals(
            Duration.ofMinutes(30).seconds,
            nextRunDelaySeconds(LocalTime.MIDNIGHT, 60, now)
        )
    }

    // --- the upload cursor: what the next run reads from ---

    /**
     * A run that read nothing must leave the cursor alone. The old cursor was the wall clock, so
     * every empty run walked it forward while the band was away, and the backlog that arrived later
     * fell behind the window and was never sent.
     */
    @Test
    fun `a run that read nothing leaves the cursor where it was`() {
        assertEquals(1790601000L, nextUploadCursor(1790601000L, 0L, 1790700000L))
    }

    @Test
    fun `a cursor that was never set stays unset when there is nothing to send`() {
        assertEquals(0L, nextUploadCursor(0L, 0L, 1790700000L))
    }

    /** The band caught up: the cursor moves to the newest thing actually delivered. */
    @Test
    fun `the cursor advances to the newest data delivered`() {
        val now = 1790700000L

        assertEquals(now - 60, nextUploadCursor(now - 5 * 86400, now - 60, now))
    }

    /** Nothing a run delivers may move the window behind data the server already has. */
    @Test
    fun `the cursor does not go backwards`() {
        val now = 1790700000L

        assertEquals(now - 3600, nextUploadCursor(now - 3600, now - 90000, now))
    }

    /** A device pointed at a new server has a zero cursor, and the run that follows sets it again. */
    @Test
    fun `an unset cursor takes the delivered data as its starting point`() {
        assertEquals(1790600000L, nextUploadCursor(0L, 1790600000L, 1790700000L))
    }

    /** A band whose clock ran ahead must not park the cursor in the future, where it reads nothing. */
    @Test
    fun `delivered data dated in the future is clamped to now`() {
        val now = 1790700000L

        assertEquals(now, nextUploadCursor(0L, now + 100000, now))
    }

    // --- the read window those cursors open ---

    /** Five days the band was away sit behind the cursor, and the window has to still reach them. */
    @Test
    fun `the window covers the days behind a cursor that stopped advancing`() {
        val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone)
        val cursor = now.toEpochSecond() - 5 * 86400

        assertTrue(uploadWindowStart(cursor, 0L, now.toEpochSecond(), zone) <= cursor)
    }

    /**
     * The same moment, with the cursor written as the wall clock. This is the bug: the window starts
     * a day back from now, and the five days of backlog falls outside it.
     */
    @Test
    fun `a wall-clock cursor would have excluded that backlog`() {
        val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone)
        val backlog = now.toEpochSecond() - 5 * 86400

        assertTrue(uploadWindowStart(now.toEpochSecond(), 0L, now.toEpochSecond(), zone) > backlog)
    }

    /** A fresh install reads from its own timestamp, not from its whole history. */
    @Test
    fun `a fresh install starts at the initial sync timestamp`() {
        val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone)
        val initial = ZonedDateTime.of(2026, 9, 20, 15, 30, 0, 0, zone).toEpochSecond()

        assertEquals(
            ZonedDateTime.of(2026, 9, 20, 0, 0, 0, 0, zone).toEpochSecond(),
            uploadWindowStart(0L, initial, now.toEpochSecond(), zone)
        )
    }

    /** A cursor that stopped moving cannot drag the window back further than the cap. */
    @Test
    fun `a stale cursor is capped at the maximum window`() {
        val now = ZonedDateTime.of(2026, 9, 27, 10, 0, 0, 0, zone)
        val nowSeconds = now.toEpochSecond()

        assertEquals(
            ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowSeconds - MAX_WINDOW_SECONDS), zone)
                .toLocalDate()
                .atStartOfDay(zone)
                .toEpochSecond(),
            uploadWindowStart(nowSeconds - 400 * 86400, 0L, nowSeconds, zone)
        )
    }
}
