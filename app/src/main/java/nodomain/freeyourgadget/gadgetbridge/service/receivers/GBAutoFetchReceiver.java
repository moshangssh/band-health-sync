/*  Copyright (C) 2018-2025 Daniele Gobbetti, José Rebelo, Martin

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
package nodomain.freeyourgadget.gadgetbridge.service.receivers;

import static nodomain.freeyourgadget.gadgetbridge.GBApplication.ACTION_NEW_DATA;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceCommunicationService;
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthSyncWorker;
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;


public class GBAutoFetchReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(GBAutoFetchReceiver.class);

    private static final String PREF_AUTO_FETCH_LAST_TIME = "auto_fetch_last_time";

    private final DeviceCommunicationService service;

    /**
     * Devices an unlock wanted data from while they were not up yet. Connecting takes a moment, and
     * nothing in Gadgetbridge fetches on connect, so the address waits here until the device
     * announces that it is initialized.
     */
    private final Set<String> pendingFetch = new HashSet<>();

    /**
     * Devices an unlock wants uploaded from once the data that unlock fetched has landed. Separate
     * from [pendingFetch], which is the stage before this one: that one waits for the device to come
     * up, this one waits for the fetch to finish writing. Both can hold the same address at once.
     */
    private final Set<String> pendingUpload = new HashSet<>();

    public GBAutoFetchReceiver(final DeviceCommunicationService service) {
        this.service = service;
        final IntentFilter intentFilter = new IntentFilter(GBDevice.ACTION_DEVICE_CHANGED);
        intentFilter.addAction(ACTION_NEW_DATA);
        LocalBroadcastManager.getInstance(service).registerReceiver(this, intentFilter);
    }

    public void destroy() {
        LocalBroadcastManager.getInstance(service).unregisterReceiver(this);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (GBDevice.ACTION_DEVICE_CHANGED.equals(intent.getAction())) {
            final GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            if (device != null) {
                onDeviceChanged(device);
            }
            return;
        }

        // The fetch an unlock started announcing that it is done. Handled before the unlock fallback
        // below, which would otherwise read this signal as a new unlock.
        if (ACTION_NEW_DATA.equals(intent.getAction())) {
            final GBDevice device = intent.getParcelableExtra(GBDevice.EXTRA_DEVICE);
            if (device != null) {
                onNewData(device);
            }
            return;
        }

        LOG.info("Trigger auto fetch by {}", intent.getAction());
        onUnlock();
    }

    /**
     * The unlock fetch reached the database — Gadgetbridge says so once the whole sync has wrapped up
     * and unset the busy task. Uploading now sends what that fetch brought, without a delay guessed
     * to be long enough for it.
     */
    private synchronized void onNewData(final GBDevice device) {
        if (!pendingUpload.remove(device.getAddress())) {
            return;
        }
        if (!SelfHostedHealthSyncWorker.syncOnUnlock()) {
            return;
        }
        SelfHostedHealthSyncWorker.enqueueUpload(GBApplication.getContext(), device.getAddress(), 0);
    }

    /**
     * An unlock is the reason to go get the data. A device that is not up yet is brought up and
     * fetched from once it is: connecting alone never fetches, so without the second half the unlock
     * would produce a connected band holding last night's data and nothing would ask it for more.
     *
     * Which state the device is in when it is down is not asked. Gadgetbridge has several of them
     * for that — the timed reconnect and the BLE scan path name different ones — and the fetch is
     * owed in all of them, so the only thing checked is the user's own per-device auto-reconnect,
     * which is what says this device is supposed to be up. A connection is started only while
     * nothing is connecting yet: the waiting states come before CONNECTING, and a device the scan or
     * an earlier attempt is already fetching back does not want a second one.
     */
    private synchronized void onUnlock() {
        if (!fetchOnUnlockEnabled()) {
            return;
        }

        final List<GBDevice> devices = GBApplication.app().getDeviceManager().getDevices();
        for (final GBDevice device : devices) {
            if (device.isInitialized()) {
                fetchIfDue(device);
            } else if (reconnectWanted(device)) {
                LOG.debug("Waiting for {} to come up so the unlock can fetch from it", device);
                pendingFetch.add(device.getAddress());
                if (device.getState().ordinal() < GBDevice.State.CONNECTING.ordinal()) {
                    GBApplication.deviceService(device).connect();
                }
            }
        }
    }

    /** A device the user left on auto-reconnect is one that is meant to be up. */
    private boolean reconnectWanted(final GBDevice device) {
        return device.getDeviceCoordinator().supportsDataFetching(device)
                && GBApplication.getPrefs().getAutoReconnect(device);
    }

    /** The fetch an unlock asked for, once the device it was meant for is up. */
    private synchronized void onDeviceChanged(final GBDevice device) {
        if (!device.isInitialized() || !pendingFetch.remove(device.getAddress())) {
            return;
        }
        if (!fetchOnUnlockEnabled()) {
            return;
        }
        fetchIfDue(device);
    }

    /**
     * Two independent reasons to pull data on unlock: Gadgetbridge's own auto fetch, and this fork's
     * self-hosted upload, which wants the night's data on the server without waiting for the next
     * scheduled run. Either is enough, and both at once still produce a single fetch.
     */
    private boolean fetchOnUnlockEnabled() {
        return GBApplication.getPrefs().getBoolean(GBPrefs.PREF_AUTO_FETCH_ENABLED, false)
                || SelfHostedHealthSyncWorker.syncOnUnlock();
    }

    private void fetchIfDue(final GBDevice device) {
        final long now = new Date().getTime();

        final DevicePrefs devicePrefs = GBApplication.getDevicePrefs(device);
        final long lastSync = devicePrefs.getLong(PREF_AUTO_FETCH_LAST_TIME, 0);

        final long timeSinceLast = now - lastSync;
        // #4165 - prevent multiple syncs in very quick succession. The minimum the user set is for
        // Gadgetbridge's own auto fetch, which fires on every unlock and needs the cap. The fork's
        // unlock upload is defined as every unlock, so it does not consult it — capping it here would
        // quietly turn the setting into "one unlock per interval" instead of what it says.
        if (!SelfHostedHealthSyncWorker.syncOnUnlock() && timeSinceLast < minimumIntervalMillis()) {
            LOG.warn("Not auto-fetching from {}, last fetch was {}ms ago", device, timeSinceLast);
            return;
        }

        LOG.debug("Auto-fetching from {}", device);
        GBApplication.deviceService(device).onFetchRecordedData(RecordedDataTypes.TYPE_SYNC);

        // The upload the unlock asked for waits on the signal that this fetch is done, not on a delay
        // guessed to outlast it: the band answers in its own time, and an upload fired too early
        // would send what the database held before the fetch rather than what it brought.
        if (SelfHostedHealthSyncWorker.syncOnUnlock()) {
            pendingUpload.add(device.getAddress());
        }

        devicePrefs.getPreferences().edit()
                .putLong(PREF_AUTO_FETCH_LAST_TIME, now)
                .apply();
    }

    /** "Minimum time between fetches" as the settings screen stores it: minutes, 0 meaning none. */
    private long minimumIntervalMillis() {
        return GBApplication.getPrefs().getInt(GBPrefs.PREF_AUTO_FETCH_INTERVAL_LIMIT, 0) * 60 * 1000L;
    }
}
