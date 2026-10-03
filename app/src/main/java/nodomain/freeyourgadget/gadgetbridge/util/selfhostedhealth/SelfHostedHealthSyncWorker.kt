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
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.HuaweiSleepStageSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiEmotionsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSleepApneaSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSleepStatsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.FitnessData
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiActivitySample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiActivitySampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiStressSample
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutDataSampleDao
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutSummarySampleDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextStore
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextSyncWorker
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
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
        val result = runSync()

        // Only the scheduled run owns the schedule: it arms the next one once it is done, so the
        // grid point is computed from the run that actually happened rather than from a period that
        // started whenever the last one began. A run WorkManager is going to repeat (a retry) is
        // still the pending run, so arming another here would just cancel that retry.
        if (inputData.getBoolean(INPUT_SCHEDULED, false) && result !is Result.Retry) {
            rescheduleNextRun(applicationContext)
        }

        return result
    }

    /** The upload itself. [doWork] is only the scheduling around it. */
    private fun runSync(): Result {
        val prefs = GBApplication.getPrefs()

        // A cycle deletion that never reached the server has no other way back: the local values
        // are already gone, so nothing but this pass would ever retry it. Checked before the enabled
        // guard because the leftover copy outlives the sync being switched off.
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
            val cursors = uploadCursors(
                prefs.getString(cursorTargetKey(address), null),
                url,
                prefs.getLong(cursorKey(address), 0L),
                prefs.getLong(sleepCursorKey(address), 0L)
            )
            val sleepCursor = cursors.sleepCursor
            val windowStart = uploadWindowStart(
                cursors.cursor,
                prefs.getLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, 0L),
                now,
                zone
            )

            // Reading and packaging share one guard: neither should be able to throw past doWork,
            // or WorkManager records a bare failure and this screen keeps showing the stale status.
            val data: SelfHostedHealthData
            val payload: SelfHostedHealthPayloadSet
            try {
                val profile = readProfile(prefs, zone)
                data = readHealthData(device, windowStart, now, zone)
                payload = SelfHostedHealthPayload.build(
                    data.samples, zone, sleepCursor, now, data.extras, data.napMinutes, profile
                )
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
                editor.putLong(
                    cursorKey(address),
                    nextUploadCursor(cursors.cursor, payload.dataUploadedThrough, now)
                )
                // A night the other server's cursor held back is not one this server has, so when
                // the target changed this run's result replaces that number outright — zero
                // included, which is the honest count of what this server has been sent so far.
                if (cursors.otherServer || payload.sleepUploadedThrough > sleepCursor) {
                    editor.putLong(sleepCursorKey(address), payload.sleepUploadedThrough)
                }
                editor.putString(cursorTargetKey(address), url)
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
            // The upload has no visible moment of its own: it runs on an unlock or on the schedule,
            // with the app closed as often as not, and both of those look exactly like nothing
            // happening. The toast is the only thing that says it went through.
            GB.toast(
                applicationContext,
                applicationContext.getString(R.string.selfhosted_health_upload_done, uploadedDays),
                Toast.LENGTH_SHORT,
                GB.INFO
            )
            return Result.success()
        }

        storeStatus(
            applicationContext.getString(R.string.selfhosted_health_status_failed, timestamp, failure)
        )
        return if (retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    /**
     * Everything one device contributes to an upload: the activity samples (steps, heart rate,
     * sleep stages), plus the readings that live in their own tables — the extra series, the
     * recorded workouts, and the Huawei-only sleep tables.
     */
    private class SelfHostedHealthData(
        val samples: List<ActivitySample>,
        val extras: SelfHostedHealthExtras,
        /** Epoch seconds the watch tagged as a nap; empty when it reported none. */
        val napMinutes: Set<Long>
    )

    /**
     * Reads the whole health record of one device in a single DB session.
     *
     * Activity samples are epoch-second; every [nodomain.freeyourgadget.gadgetbridge.model.TimeSample]
     * provider is epoch-millisecond, which is what the Health Connect syncers use too.
     *
     * The SpO2, stress, HRV and temperature providers and the resting heart rate cover any device
     * that exposes them; the recorded workouts come from the summary table the device parsers fill,
     * and the sleep statistics, emotions and sleep apnea tables have no coordinator accessor, so they
     * are read directly and only for Huawei/Honor devices, which are the only ones that ever fill
     * them.
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
                ?: return@use SelfHostedHealthData(emptyList(), SelfHostedHealthExtras(), emptySet())
            @Suppress("UNCHECKED_CAST")
            val samples = provider
                .getAllActivitySamples(fromTs.toInt(), toTs.toInt()) as List<ActivitySample>

            val calories = mutableListOf<SelfHostedHealthPoint>()
            val distance = mutableListOf<SelfHostedHealthPoint>()
            val restingHeartRate = mutableListOf<SelfHostedHealthPoint>()
            for (sample in samples) {
                val timestamp = sample.timestamp.toLong() * 1000L
                // ActivitySample.activeCalories is in calories; DashboardUtils and the Health
                // Connect syncer both divide by 1000 for kcal, which is what the server stores.
                val kcal = sample.activeCalories / 1000.0
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

            // Only Huawei/Honor devices ever fill these three tables, and they have no coordinator
            // accessor, so they are read directly here; everyone else skips the queries entirely.
            val isHuawei = coordinator is HuaweiCoordinator
            val sleepStats = if (isHuawei) {
                HuaweiSleepStatsSampleProvider(device, session)
                    .getSleepSamples(fromMs, toMs)
                    .map { sleepStatsPoint(it, zone) }
            } else {
                emptyList()
            }
            // A nap survives only in the watch's own stage rows: the sample provider folds stage 5
            // into light sleep, so the payload cannot tell a daytime nap from a night otherwise.
            val napMinutes = if (isHuawei) {
                HuaweiSleepStageSampleProvider(device, session)
                    .getAllSamples(fromMs, toMs)
                    .filter { it.stage == HuaweiSampleProvider.SLEEP_STAGE_NAP }
                    .map { it.timestamp / 1000L }
                    .toSet()
            } else {
                emptySet()
            }
            val emotions = if (isHuawei) {
                HuaweiEmotionsSampleProvider(device, session)
                    .getAllSamples(fromMs, toMs)
                    .map {
                        SelfHostedHealthPoint(
                            it.timestamp, linkedMapOf(
                                "last_timestamp" to iso(it.lastTimestamp, zone),
                                "status" to it.status,
                                "origin_status" to it.originStatus,
                                "valence" to it.valenceCharacter,
                                "arousal" to it.arousalCharacter
                            )
                        )
                    }
            } else {
                emptyList()
            }
            val sleepApnea = if (isHuawei) {
                HuaweiSleepApneaSampleProvider(device, session)
                    .getAllSamples(fromMs, toMs)
                    .map {
                        SelfHostedHealthPoint(
                            it.timestamp, linkedMapOf(
                                "last_timestamp" to iso(it.lastTimestamp, zone),
                                "level" to it.level
                            )
                        )
                    }
            } else {
                emptyList()
            }

            SelfHostedHealthData(
                samples = samples,
                extras = SelfHostedHealthExtras(
                    workouts = readWorkouts(device, session, fromMs, toMs, zone),
                    spo2 = coordinator.getSpo2SampleProvider(device, session)
                        ?.getAllSamples(fromMs, toMs)
                        // SpO2Sample reports 0 for "not measured", the same rule the charts read it
                        // with; the interface promises no reading above 100, so nothing else is
                        // second-guessed here.
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
                        // RMSSD in ms: 0 is the parsers' "no measurement", and a trained user really
                        // does reach values above 200 ms, so no ceiling is imposed here.
                        ?.filter { it.value > 0 }
                        ?.map { SelfHostedHealthPoint(it.timestamp, mapOf("value" to it.value)) }
                        .orEmpty(),
                    temperature = coordinator.getTemperatureSampleProvider(device, session)
                        ?.getAllSamples(fromMs, toMs)
                        // Celsius, and not always body temperature: the provider may hold skin,
                        // body or ambient readings and this reader does not look at the type, so
                        // every stored reading goes out rather than a guessed human span.
                        ?.map {
                            SelfHostedHealthPoint(
                                it.timestamp, mapOf("value" to it.temperature.toDouble())
                            )
                        }
                        .orEmpty(),
                    restingHeartRate = restingHeartRate,
                    activeCalories = calories,
                    distance = distance,
                    sleepStats = sleepStats,
                    emotions = emotions,
                    sleepApnea = sleepApnea,
                    heartRateCoverage = if (isHuawei) {
                        readHeartRateCoverage(device, session, fromTs, toTs, zone)
                    } else {
                        null
                    }
                ),
                napMinutes = napMinutes
            )
        }
    }

    /**
     * The workouts the band recorded inside the window, from the table the device parsers fill.
     *
     * Keyed on the start time, so a workout belongs to the day it began. Whether that table is ever
     * written is the device's own call, through the coordinator's supportsRecordedActivities; for a
     * device that never records workouts the query is simply empty.
     */
    private fun readWorkouts(
        device: GBDevice,
        session: DaoSession,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId
    ): List<SelfHostedHealthPoint> {
        // findDevice, not getDevice: the latter writes a missing device row, and this whole read runs
        // inside a read-only session.
        val deviceId = DBHelper.findDevice(device, session)?.id ?: return emptyList()
        return session.baseActivitySummaryDao.queryBuilder()
            .where(
                BaseActivitySummaryDao.Properties.DeviceId.eq(deviceId),
                BaseActivitySummaryDao.Properties.StartTime.ge(Date(fromMs)),
                BaseActivitySummaryDao.Properties.StartTime.lt(Date(toMs))
            )
            .list()
            .mapNotNull { summary ->
                val huaweiSummary = if (device.deviceCoordinator is HuaweiCoordinator) {
                    // Both ends belong in the key. The totals row is written with (workout number,
                    // start, end) as its identity, and the parser that filled this summary in took
                    // its endTime from the row it had matched — so the pair names that row exactly.
                    // On the start alone, two rows for one start (a re-sync whose end moved) tie,
                    // and greenDAO's unique() takes the first without saying so, attaching the
                    // heart-rate stream of the other, older recording.
                    session.huaweiWorkoutSummarySampleDao.queryBuilder()
                        .where(
                            HuaweiWorkoutSummarySampleDao.Properties.DeviceId.eq(deviceId),
                            HuaweiWorkoutSummarySampleDao.Properties.UserId.eq(summary.userId),
                            HuaweiWorkoutSummarySampleDao.Properties.StartTimestamp.eq(summary.startTime.time / 1000L),
                            HuaweiWorkoutSummarySampleDao.Properties.EndTimestamp.eq(summary.endTime.time / 1000L)
                        )
                        .unique()
                } else {
                    null
                }
                val heartRate = huaweiSummary?.let {
                    val readings = session.huaweiWorkoutDataSampleDao.queryBuilder()
                        .where(HuaweiWorkoutDataSampleDao.Properties.WorkoutId.eq(it.workoutId))
                        .orderAsc(HuaweiWorkoutDataSampleDao.Properties.Timestamp)
                        .list()
                    huaweiWorkoutHeartRate(it, readings, zone)
                }
                workoutPoint(summary, zone, heartRate)
            }
    }

    private fun readHeartRateCoverage(
        device: GBDevice,
        session: DaoSession,
        fromTs: Long,
        toTs: Long,
        zone: ZoneId
    ): List<SelfHostedHealthPoint> {
        val deviceId = DBHelper.findDevice(device, session)?.id ?: return emptyList()
        val samples = session.huaweiActivitySampleDao.queryBuilder()
            .where(
                HuaweiActivitySampleDao.Properties.DeviceId.eq(deviceId),
                HuaweiActivitySampleDao.Properties.Source.eq(FitnessData.MessageData.stepId),
                HuaweiActivitySampleDao.Properties.Timestamp.lt(toTs),
                HuaweiActivitySampleDao.Properties.OtherTimestamp.gt(fromTs)
            )
            .list()
        return huaweiHeartRateCoverage(samples, fromTs, toTs, zone)
    }

    /**
     * The user's own body profile, read from the preferences the "About you" screen writes.
     *
     * Only the body itself: the band's display name and the step/sleep/weight goals live on that
     * same screen but describe the device or the user's targets, not the person reading them.
     */
    private fun readProfile(prefs: GBPrefs, zone: ZoneId): JSONObject? = SelfHostedHealthProfile.build(
        gender = prefs.getString(ActivityUser.PREF_USER_GENDER, null),
        birthday = prefs.getString(ActivityUser.PREF_USER_DATE_OF_BIRTH, null),
        heightCm = prefs.getString(ActivityUser.PREF_USER_HEIGHT_CM, null),
        weightKg = prefs.getString(ActivityUser.PREF_USER_WEIGHT_KG, null),
        today = LocalDate.now(zone)
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
        records = SelfHostedHealthLog.countRecords(day.body),
        httpCode = result.httpCode,
        responseTimeMs = result.responseTimeMs,
        success = result is SelfHostedHealthUploadResult.Success,
        message = (result as? SelfHostedHealthUploadResult.Failure)?.message,
        payload = prettyPayload(day.body)
    )

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
        /** True on the run the schedule armed, which is the only one that arms its successor. */
        const val INPUT_SCHEDULED = "scheduled"
        const val WORK_TAG = "SelfHostedHealthSyncWorker"

        /** Unique name of the one armed run, so arming it twice moves that run instead of adding one. */
        private const val NEXT_RUN_WORK_NAME = "SelfHostedHealthSyncWorker_Next"

        /**
         * Queues one upload for [deviceAddress], [delaySeconds] from now.
         *
         * Both things that name a moment to upload — data landing and an unlock — come through here,
         * so they cannot drift apart on the work name, the constraints, or what counts as a device
         * this sync covers. REPLACE is what keeps a burst to one run: a second trigger moves the
         * pending upload instead of adding another.
         */
        @JvmStatic
        fun enqueueUpload(context: Context, deviceAddress: String, delaySeconds: Long) {
            val prefs = GBApplication.getPrefs()
            if (!prefs.getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false)) {
                return
            }
            val selected = prefs.getStringSet(GBPrefs.SELF_HOSTED_HEALTH_DEVICE_SELECTION, emptySet())
                .orEmpty()
                .map { it.uppercase(Locale.ROOT) }
                .toSet()
            if (deviceAddress.uppercase(Locale.ROOT) !in selected) {
                LOG.debug("Ignoring upload for {} - not configured for self-hosted health sync", deviceAddress)
                return
            }
            val request = OneTimeWorkRequest.Builder(SelfHostedHealthSyncWorker::class.java)
                .addTag(WORK_TAG)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(Data.Builder().putString(INPUT_DEVICE_ADDRESS, deviceAddress).build())
                .build()
            LOG.debug("Self-hosted health upload for {} scheduled in {}s", deviceAddress, delaySeconds)
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_TAG + "_" + deviceAddress, ExistingWorkPolicy.REPLACE, request
            )
        }

        /**
         * The periodic run this replaced. An install that predates the change still has it armed in
         * WorkManager, where it would keep uploading on its drifted time next to the chain, so every
         * re-arm cancels that name too. Nothing enqueues it any more, which is what makes this a
         * one-time cleanup rather than part of the schedule.
         */
        private const val LEGACY_PERIODIC_WORK_NAME = "SelfHostedHealthSyncWorker_Periodic"

        /**
         * Brings the armed upload in line with the current settings, and is safe to call any number
         * of times: it cancels the armed run when the feature is off or the interval is 0, and
         * otherwise arms one for the next grid point. Called on every settings change, once when the
         * service starts, and at the end of every scheduled run — so an armed run lost to a reinstall
         * comes back, and the run that follows a late one is back on the grid.
         *
         * A one-time run arming its successor is what makes [startTime] and [minutes] mean what the
         * settings say. WorkManager restarts a periodic run's period from the run that actually
         * happened, so one run delayed by Doze pushes every later one and the anchor is gone for
         * good; a delay computed to a clock time from the run that just finished instead puts the
         * next run back where it belongs. Re-arming is therefore idempotent: whatever time it is
         * called at, the point it aims at is the same one.
         *
         * [enabled], [minutes], [startTime] and [onUnlock] default to the stored values but can be
         * passed in from a settings listener, which fires before the new value is persisted.
         *
         * Sync on unlock takes the cadence over rather than adding to it: while it is on, the two
         * interval settings describe a grid nothing is armed on any more, so the run being armed here
         * is [UNLOCK_INTERVAL_MINUTES] from [UNLOCK_ANCHOR] — every hour on the hour — whatever they
         * hold, "0" included.
         */
        @JvmStatic
        @JvmOverloads
        fun rescheduleNextRun(
            context: Context,
            enabled: Boolean = GBApplication.getPrefs().getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false),
            minutes: Int = intervalMinutes(GBApplication.getPrefs()),
            startTime: LocalTime = GBApplication.getPrefs()
                .getLocalTime(GBPrefs.SELF_HOSTED_HEALTH_SYNC_TIME, DEFAULT_START_TIME),
            onUnlock: Boolean = GBApplication.getPrefs()
                .getBoolean(GBPrefs.SELF_HOSTED_HEALTH_SYNC_ON_UNLOCK, false)
        ) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
            if (!enabled || (!onUnlock && minutes <= 0)) {
                workManager.cancelUniqueWork(NEXT_RUN_WORK_NAME)
                LOG.info(
                    "Self-hosted health upload cancelled (enabled={}, minutes={}, onUnlock={})",
                    enabled, minutes, onUnlock
                )
                return
            }
            val interval = if (onUnlock) UNLOCK_INTERVAL_MINUTES else minutes
            val anchor = if (onUnlock) UNLOCK_ANCHOR else startTime
            val initialDelay = nextRunDelaySeconds(
                anchor, interval, ZonedDateTime.now(ZoneId.systemDefault())
            )
            val request = OneTimeWorkRequest.Builder(SelfHostedHealthSyncWorker::class.java)
                .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(Data.Builder().putBoolean(INPUT_SCHEDULED, true).build())
                .build()
            // REPLACE, not KEEP: an armed run for some other point was computed from older settings
            // or from an older run, and this call is the newer truth about where the next one goes.
            workManager.enqueueUniqueWork(NEXT_RUN_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            LOG.info(
                "Self-hosted health upload scheduled every {} minute(s) from {}, next run in {}s",
                interval, anchor, initialDelay
            )
        }

        /**
         * True when the upload wants the device pulled on unlock: the feature is on and the unlock
         * trigger is on. Both are required — an unlock sync for an upload that is switched off would
         * be a fetch nobody asked for.
         */
        @JvmStatic
        fun syncOnUnlock(): Boolean {
            val prefs = GBApplication.getPrefs()
            return prefs.getBoolean(GBPrefs.SELF_HOSTED_HEALTH_ENABLED, false) &&
                prefs.getBoolean(GBPrefs.SELF_HOSTED_HEALTH_SYNC_ON_UNLOCK, false)
        }

        /** Stored cadence in minutes; 30 by default, so a missed on-event upload still gets a retry.
         *  0 means the user turned the periodic safety net off. */
        private fun intervalMinutes(prefs: GBPrefs): Int =
            prefs.getString(GBPrefs.SELF_HOSTED_HEALTH_SYNC_INTERVAL, "30").orEmpty().toIntOrNull() ?: 0

        /** Midnight keeps the pre-existing cadence shape: a sub-daily interval is unchanged by the
         *  grid, and "once a day" uploads at the start of the day until a time is picked. */
        const val DEFAULT_START_TIME = "00:00"

        /** Cadence sync-on-unlock runs on: the clock, once an hour. */
        private const val UNLOCK_INTERVAL_MINUTES = 60
        private val UNLOCK_ANCHOR: LocalTime = LocalTime.MIDNIGHT

        private const val MAX_ATTEMPTS = 5

        fun cursorKey(address: String): String = "selfhosted_health_cursor_" + address.uppercase(Locale.ROOT)

        fun sleepCursorKey(address: String): String = "selfhosted_health_sleep_cursor_" + address.uppercase(Locale.ROOT)

        /**
         * The server the two cursors above were written for, normalized the same way the upload URL
         * is: an equivalent spelling of the same address must not read as a different server.
         */
        fun cursorTargetKey(address: String): String = "selfhosted_health_cursor_target_" + address.uppercase(Locale.ROOT)
    }
}

/** The cursors one device's upload starts from, plus whether the stored pair belongs elsewhere. */
internal data class UploadCursors(
    val cursor: Long,
    val sleepCursor: Long,
    val otherServer: Boolean
)

/**
 * Cursors belong to the server they were written for: a device pointed at a different one starts
 * from nothing, so the whole window goes out to it again. That includes the run right after this
 * rule ships, where the stored pair carries no server at all — an old pair is a number this server
 * was never sent, and using it would strand the sleep sessions it covers. The window's lower bound
 * is unchanged either way: the initial-sync timestamp, floored at the 31-day look-back.
 */
internal fun uploadCursors(
    storedTarget: String?,
    url: String,
    cursor: Long,
    sleepCursor: Long
): UploadCursors = if (storedTarget == url) {
    UploadCursors(cursor, sleepCursor, otherServer = false)
} else {
    UploadCursors(0L, 0L, otherServer = true)
}

/**
 * Seconds from [now] to the next [startTime] on a grid spaced [minutes] apart.
 *
 * Anchoring the whole grid, not just delaying the first run by one period, is what makes "once a day
 * at 08:00" mean that, instead of 24 hours after whenever the app was last opened. For a sub-daily
 * interval it reads as "every N, on the hour of the start time": a 6-hour interval anchored at 08:00
 * runs at 08:00, 14:00, 20:00, 02:00.
 */
internal fun nextRunDelaySeconds(startTime: LocalTime, minutes: Int, now: ZonedDateTime): Long {
    val anchor = now.with(startTime)
    val next = if (anchor.isAfter(now)) {
        anchor
    } else {
        val steps = Duration.between(anchor, now).toMinutes() / minutes + 1
        anchor.plusMinutes(steps * minutes)
    }
    return Duration.between(now, next).seconds
}

/** 根目录 gradle.properties：游标之前额外重读的时长，用来接住迟到补拉的数据。 */
internal const val LOOK_BACK_SECONDS = BuildConfig.SELF_HOSTED_HEALTH_LOOK_BACK_SECONDS

/** 根目录 gradle.properties：单次运行最多往前读的时长，给停止前进的游标封顶。 */
internal const val MAX_WINDOW_SECONDS = BuildConfig.SELF_HOSTED_HEALTH_MAX_WINDOW_SECONDS

/**
 * Where the cursor stands after a run that delivered everything through [dataUploadedThrough].
 *
 * The cursor is the newest instant the device has actually handed this server, not the wall clock.
 * A run that read nothing — the band is away, or the fetch it was racing has not landed yet — must
 * leave it where it is: the old wall-clock cursor walked forward on those empty runs, and the data
 * that arrived afterwards fell behind `cursor - lookback` and was never sent again. It never moves
 * backwards, and never past [now], the same horizon the payload builder clamps its sleep settle to.
 */
internal fun nextUploadCursor(storedCursor: Long, dataUploadedThrough: Long, now: Long): Long =
    maxOf(storedCursor, minOf(now, dataUploadedThrough))

/**
 * Lower bound of the samples to read.
 *
 * Always a local midnight: the server derives a day's step total from the buckets it holds, so a
 * window that starts mid-day would report a total missing the morning. The look-back re-covers
 * recent days because a band delivers data late and out of order, [initialSyncTs] keeps a fresh
 * install from reading its whole history at once, and [MAX_WINDOW_SECONDS] caps how far a cursor
 * that stopped moving can reach back.
 */
internal fun uploadWindowStart(cursor: Long, initialSyncTs: Long, now: Long, zone: ZoneId): Long {
    val fromCursor = if (cursor > 0L) cursor - LOOK_BACK_SECONDS else initialSyncTs
    val start = maxOf(fromCursor, initialSyncTs, now - MAX_WINDOW_SECONDS)
    return ZonedDateTime.ofInstant(Instant.ofEpochSecond(start), zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toEpochSecond()
}
