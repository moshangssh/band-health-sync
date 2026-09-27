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

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiEmotionsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSleepApneaSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSleepStatsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiSleepStatsSample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiStressSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextStore
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextSyncWorker
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads the samples Gadgetbridge already stores and posts them to the user's own health server.
 *
 * Replaces the Health Connect -> third party webhook chain for that one destination: same data, one
 * process, no Google component in the path. The Health Connect sync is untouched and stays useful
 * for anyone feeding other apps from it.
 */
class SelfHostedHealthSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = GBApplication.getPrefs()

        // A cycle deletion that never reached the server has no other way back: the local values
        // are already gone, so nothing but this periodic pass would ever retry it. Checked before
        // the enabled guard because the leftover copy outlives the sync being switched off.
        if (CycleContextStore.pendingClear()) {
            CycleContextSyncWorker.enqueue(applicationContext, clear = true)
        }

        if (!prefs.getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false)) {
            LOG.info("Self-hosted health sync is disabled, skipping")
            return Result.success()
        }

        val url = SelfHostedHealthEndpoint.normalize(prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_URL, ""))
        // Sanitize here too, not only on save: a token stored before this code shipped may still
        // carry the pasted-in newline, and this lets it work on the next run without re-entry.
        val token = SelfHostedHealthUploader.sanitizeToken(prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_TOKEN, ""))
        if (url.isNullOrEmpty() || token.isEmpty()) {
            LOG.warn("Self-hosted health sync is enabled but the server address or token is missing")
            storeStatus(applicationContext.getString(R.string.selfhosted_health_status_not_configured))
            return Result.success()
        }

        val requestedAddress = inputData.getString(INPUT_DEVICE_ADDRESS)
        val devices = selectedDevices(prefs, requestedAddress)
        if (devices.isEmpty()) {
            LOG.info("No devices selected for self-hosted health sync")
            storeStatus(applicationContext.getString(R.string.selfhosted_health_status_no_devices))
            return Result.success()
        }

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis() / 1000L
        val uploader = SelfHostedHealthUploader()
        // "Upload now" sets this; event-driven and periodic runs leave it false.
        val manual = inputData.getBoolean(INPUT_MANUAL, false)
        // One entry per posted day, written to the sync log in a single batch at the end.
        val logEntries = mutableListOf<SelfHostedHealthLogEntry>()

        var uploadedDays = 0
        var retryable = false
        var failure: String? = null

        for (device in devices) {
            val address = device.address
            val deviceName = device.aliasOrName
            val cursor = prefs.getLong(cursorKey(address), 0L)
            val sleepCursor = prefs.getLong(sleepCursorKey(address), 0L)
            val windowStart = windowStart(prefs, cursor, now, zone)

            // Reading and packaging share one guard: neither should be able to throw past doWork,
            // or WorkManager records a bare failure and this screen keeps showing the stale status.
            val data: SelfHostedHealthData
            val payload: SelfHostedHealthPayloadSet
            try {
                data = readHealthData(device, windowStart, now, zone)
                payload = SelfHostedHealthPayload.build(data.samples, zone, sleepCursor, now, data.extras)
            } catch (e: Exception) {
                LOG.error("Could not prepare self-hosted health payload for {}", address, e)
                failure = e.message ?: e.javaClass.simpleName
                retryable = true
                continue
            }

            LOG.info(
                "Self-hosted health sync for {}: {} sample(s) from {} produced {} day payload(s)",
                address, data.samples.size, Instant.ofEpochSecond(windowStart), payload.days.size
            )

            var allSucceeded = true
            for (day in payload.days) {
                val result = uploader.upload(url, token, day.body.toString())
                // Logged whether it succeeded or failed: the point of the log is to see the failures.
                logEntries.add(logEntry(day, deviceName, url, manual, result))
                when (result) {
                    is SelfHostedHealthUploadResult.Success -> uploadedDays++
                    is SelfHostedHealthUploadResult.Failure -> {
                        LOG.warn("Upload of {} failed: {}", day.date, result.message)
                        allSucceeded = false
                        failure = result.message
                        retryable = retryable || result.retryable
                        // Days are independent on the server, but stopping keeps the failure
                        // report about the first thing that actually broke.
                        break
                    }
                }
            }

            // Cursors move only on a clean run for this device. Steps and heart rate are safe to
            // re-send, and a sleep session that was not confirmed must stay eligible.
            if (allSucceeded) {
                val editor = GBApplication.getPrefs().preferences.edit()
                editor.putLong(cursorKey(address), now)
                if (payload.sleepUploadedThrough > sleepCursor) {
                    editor.putLong(sleepCursorKey(address), payload.sleepUploadedThrough)
                }
                editor.apply()
            }
        }

        SelfHostedHealthLog.append(applicationContext, logEntries)

        val timestamp = TIME_FORMAT.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(now), zone))
        if (failure == null) {
            storeStatus(
                applicationContext.getString(
                    R.string.selfhosted_health_status_success, timestamp, uploadedDays
                )
            )
            return Result.success()
        }

        storeStatus(
            applicationContext.getString(R.string.selfhosted_health_status_failed, timestamp, failure)
        )
        return if (retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    /**
     * Lower bound of the samples to read.
     *
     * Always a local midnight: the server keeps the larger step total per day, so a window that
     * starts mid-day would report a partial total for that day and, on a first run, leave it there.
     * The look-back re-covers recent days because a band delivers data late and out of order.
     */
    private fun windowStart(prefs: GBPrefs, cursor: Long, now: Long, zone: ZoneId): Long {
        val initial = prefs.getLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, 0L)
        val fromCursor = if (cursor > 0L) cursor - LOOK_BACK_SECONDS else initial
        val start = maxOf(fromCursor, initial, now - MAX_WINDOW_SECONDS)
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(start), zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toEpochSecond()
    }

    /**
     * Everything one device contributes to an upload: the activity samples (steps, heart rate,
     * sleep stages), plus the readings that live in their own tables.
     */
    private class SelfHostedHealthData(
        val samples: List<ActivitySample>,
        val extras: SelfHostedHealthExtras
    )

    /**
     * Reads the whole health record of one device in a single DB session.
     *
     * Activity samples are epoch-second; every [nodomain.freeyourgadget.gadgetbridge.model.TimeSample]
     * provider is epoch-millisecond, which is what the Health Connect syncers use too.
     *
     * The SpO2, stress, HRV and temperature providers and the resting heart rate cover any device
     * that exposes them; the sleep statistics, emotions and sleep apnea tables have no coordinator
     * accessor, so they are read directly and simply come back empty on devices that never fill them.
     */
    private fun readHealthData(
        device: GBDevice,
        fromTs: Long,
        toTs: Long,
        zone: ZoneId
    ): SelfHostedHealthData {
        return GBApplication.acquireDbReadOnly().use { db ->
            val session = db.daoSession
            val coordinator = device.deviceCoordinator
            val fromMs = fromTs * 1000L
            val toMs = toTs * 1000L

            val provider = coordinator.getSampleProvider(device, session)
                ?: return@use SelfHostedHealthData(emptyList(), SelfHostedHealthExtras())
            @Suppress("UNCHECKED_CAST")
            val samples = provider
                .getAllActivitySamples(fromTs.toInt(), toTs.toInt()) as List<ActivitySample>

            val calories = mutableListOf<SelfHostedHealthPoint>()
            val distance = mutableListOf<SelfHostedHealthPoint>()
            val restingHeartRate = mutableListOf<SelfHostedHealthPoint>()
            for (sample in samples) {
                val timestamp = sample.timestamp.toLong() * 1000L
                val kcal = sample.activeCalories
                if (kcal > 0) {
                    calories.add(SelfHostedHealthPoint(timestamp, mapOf("value" to kcal)))
                }
                val distanceCm = sample.distanceCm
                if (distanceCm > 0) {
                    distance.add(SelfHostedHealthPoint(timestamp, mapOf("value" to distanceCm / 100.0)))
                }
                if (sample is HuaweiActivitySample && sample.restingHeartRate > 0) {
                    restingHeartRate.add(
                        SelfHostedHealthPoint(timestamp, mapOf("value" to sample.restingHeartRate))
                    )
                }
            }

            SelfHostedHealthData(
                samples = samples,
                extras = SelfHostedHealthExtras(
                    spo2 = coordinator.getSpo2SampleProvider(device, session)
                        ?.getAllSamples(fromMs, toMs)
                        ?.filter { it.spo2 > 0 }
                        ?.map { SelfHostedHealthPoint(it.timestamp, mapOf("value" to it.spo2)) }
                        .orEmpty(),
                    stress = coordinator.getStressSampleProvider(device, session)
                        ?.getAllSamples(fromMs, toMs)
                        ?.filter { it.stress > 0 }
                        ?.map { sample ->
                            val fields = linkedMapOf<String, Any?>("value" to sample.stress)
                            (sample as? HuaweiStressSample)?.let { fields["level"] = it.level }
                            SelfHostedHealthPoint(sample.timestamp, fields)
                        }
                        .orEmpty(),
                    hrv = coordinator.getHrvValueSampleProvider(device, session)
                        ?.getAllSamples(fromMs, toMs)
                        ?.filter { it.value > 0 }
                        ?.map { SelfHostedHealthPoint(it.timestamp, mapOf("value" to it.value)) }
                        .orEmpty(),
                    temperature = coordinator.getTemperatureSampleProvider(device, session)
                        ?.getAllSamples(fromMs, toMs)
                        ?.map {
                            SelfHostedHealthPoint(
                                it.timestamp, mapOf("value" to it.temperature.toDouble())
                            )
                        }
                        .orEmpty(),
                    restingHeartRate = restingHeartRate,
                    activeCalories = calories,
                    distance = distance,
                    sleepStats = HuaweiSleepStatsSampleProvider(device, session)
                        .getSleepSamples(fromMs, toMs)
                        .map { sleepStatsPoint(it, zone) },
                    emotions = HuaweiEmotionsSampleProvider(device, session)
                        .getAllSamples(fromMs, toMs)
                        .map {
                            SelfHostedHealthPoint(it.timestamp, linkedMapOf(
                                "last_timestamp" to iso(it.lastTimestamp, zone),
                                "status" to it.status,
                                "origin_status" to it.originStatus,
                                "valence" to it.valenceCharacter,
                                "arousal" to it.arousalCharacter
                            ))
                        },
                    sleepApnea = HuaweiSleepApneaSampleProvider(device, session)
                        .getAllSamples(fromMs, toMs)
                        .map {
                            SelfHostedHealthPoint(it.timestamp, linkedMapOf(
                                "last_timestamp" to iso(it.lastTimestamp, zone),
                                "level" to it.level
                            ))
                        }
                )
            )
        }
    }

    /** The Huawei per-night sleep report, anchored on the wakeup time so it lands on the wake day. */
    private fun sleepStatsPoint(sample: HuaweiSleepStatsSample, zone: ZoneId): SelfHostedHealthPoint =
        SelfHostedHealthPoint(
            timestamp = sample.wakeupTime,
            fields = linkedMapOf(
                "sleep_score" to sample.sleepScore,
                "bed_time" to iso(sample.bedTime, zone),
                "rising_time" to iso(sample.risingTime, zone),
                "wakeup_time" to iso(sample.wakeupTime, zone),
                "sleep_efficiency" to sample.sleepEfficiency,
                "sleep_latency" to sample.sleepLatency,
                "deep_part" to sample.deepPart,
                "snore_freq" to sample.snoreFreq,
                "sleep_data_quality" to sample.sleepDataQuality,
                "min_heart_rate" to sample.minHeartRate,
                "max_heart_rate" to sample.maxHeartRate,
                "avg_heart_rate" to sample.avgHeartRate,
                "min_oxygen_saturation" to sample.minOxygenSaturation,
                "max_oxygen_saturation" to sample.maxOxygenSaturation,
                "avg_oxygen_saturation" to sample.avgOxygenSaturation,
                "min_breath_rate" to sample.minBreathRate,
                "max_breath_rate" to sample.maxBreathRate,
                "avg_breath_rate" to sample.avgBreathRate,
                "avg_hrv" to sample.avgHrv,
                "hrv_day_to_baseline" to sample.hrvDayToBaseline,
                "rdi" to sample.rdi,
                "wake_count" to sample.wakeCount,
                "turn_over_count" to sample.turnOverCount,
                "wake_up_feeling" to sample.wakeUpFeeling,
                "prepare_sleep_time" to sample.prepareSleepTime
            )
        )

    private fun iso(epochMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        )

    private fun selectedDevices(prefs: GBPrefs, requestedAddress: String?): List<GBDevice> {
        val selected = prefs.getStringSet(GBPrefs.SELF_HOSTED_HEALTH_DEVICE_SELECTION, emptySet())
            .orEmpty()
            .map { it.uppercase(Locale.ROOT) }
            .toSet()
        if (selected.isEmpty()) {
            return emptyList()
        }
        return GBApplication.app().deviceManager.devices.filter { device ->
            val address = device.address?.uppercase(Locale.ROOT) ?: return@filter false
            address in selected &&
                (requestedAddress == null || address == requestedAddress.uppercase(Locale.ROOT))
        }
    }

    private fun storeStatus(status: String) {
        GBApplication.getPrefs().preferences.edit()
            .putString(GBPrefs.SELF_HOSTED_HEALTH_STATUS, status)
            .apply()
    }

    private fun logEntry(
        day: SelfHostedHealthDay,
        deviceName: String,
        url: String,
        manual: Boolean,
        result: SelfHostedHealthUploadResult
    ): SelfHostedHealthLogEntry = SelfHostedHealthLogEntry(
        timestampMs = System.currentTimeMillis(),
        deviceName = deviceName,
        url = url,
        date = day.date,
        manual = manual,
        records = countRecords(day.body),
        httpCode = result.httpCode,
        responseTimeMs = result.responseTimeMs,
        success = result is SelfHostedHealthUploadResult.Success,
        message = (result as? SelfHostedHealthUploadResult.Failure)?.message,
        payload = prettyPayload(day.body)
    )

    /** Steps, calories and distance count as one record each; every series entry counts on its own. */
    private fun countRecords(body: JSONObject): Int {
        var count = 0
        for (key in TOTALS) {
            if (body.has(key)) {
                count++
            }
        }
        for (key in SERIES) {
            count += body.optJSONArray(key)?.length() ?: 0
        }
        return count
    }

    /** Pretty-printed for the detail screen; falls back to compact if indentation ever throws. */
    private fun prettyPayload(body: JSONObject): String = try {
        body.toString(2)
    } catch (e: JSONException) {
        body.toString()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(SelfHostedHealthSyncWorker::class.java)
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        const val INPUT_DEVICE_ADDRESS = "device_address"
        /** True when the run was started by the "Upload now" button, so the log can label it manual. */
        const val INPUT_MANUAL = "manual"
        const val WORK_TAG = "SelfHostedHealthSyncWorker"

        /** Unique name for the optional periodic upload, so scheduling it twice just updates it. */
        private const val PERIODIC_WORK_NAME = "SelfHostedHealthSyncWorker_Periodic"

        /**
         * Brings the periodic upload in line with the current settings, and is safe to call any
         * number of times: it cancels the schedule when the feature is off or the interval is 0, and
         * otherwise (re)installs one unique periodic work. Called on every settings change and once
         * when the service starts, so a schedule lost to a reinstall re-arms itself.
         *
         * [enabled] and [minutes] default to the stored values but can be passed in from a settings
         * listener, which fires before the new value is persisted.
         */
        @JvmStatic
        @JvmOverloads
        fun reschedulePeriodic(
            context: Context,
            enabled: Boolean = GBApplication.getPrefs().getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false),
            minutes: Int = intervalMinutes(GBApplication.getPrefs())
        ) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled || minutes <= 0) {
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                LOG.info("Self-hosted health periodic upload cancelled (enabled={}, minutes={})", enabled, minutes)
                return
            }
            val request = PeriodicWorkRequest.Builder(
                SelfHostedHealthSyncWorker::class.java, minutes.toLong(), TimeUnit.MINUTES
            )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            // UPDATE keeps the running schedule when nothing changed and only reshuffles when the
            // interval actually moved, so reopening the screen does not restart the timer.
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
            LOG.info("Self-hosted health periodic upload scheduled every {} minute(s)", minutes)
        }

        /** Stored cadence in minutes; 30 by default, so a missed on-event upload still gets a retry.
         *  0 means the user turned the periodic safety net off. */
        private fun intervalMinutes(prefs: GBPrefs): Int =
            prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_SYNC_INTERVAL, "30").orEmpty().toIntOrNull() ?: 0

        /** Re-cover this much before the cursor, so data the band delivers late still gets sent. */
        private const val LOOK_BACK_SECONDS = 24L * 60L * 60L

        /** Ceiling on a single run, so a stale cursor or a far-back start cannot read months at once. */
        private const val MAX_WINDOW_SECONDS = 31L * 24L * 60L * 60L

        /** Payload keys that hold a single per-day total rather than a list of readings. */
        private val TOTALS = listOf("steps", "active_calories", "distance")

        /** Payload keys that hold a list of readings. */
        private val SERIES = listOf(
            "heart_rate", "sleep", "spo2", "stress", "hrv", "temperature",
            "resting_heart_rate", "sleep_stats", "emotions", "sleep_apnea"
        )

        private const val MAX_ATTEMPTS = 5

        fun cursorKey(address: String): String = "selfhosted_health_cursor_" + address.uppercase(Locale.ROOT)

        fun sleepCursorKey(address: String): String = "selfhosted_health_sleep_cursor_" + address.uppercase(Locale.ROOT)
    }
}
